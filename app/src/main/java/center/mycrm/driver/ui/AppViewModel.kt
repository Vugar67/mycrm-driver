package center.mycrm.driver.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import center.mycrm.driver.core.Rules
import center.mycrm.driver.data.AuthRequiredException
import center.mycrm.driver.data.DriverIdentity
import center.mycrm.driver.data.NotADriverException
import center.mycrm.driver.data.Order
import center.mycrm.driver.data.OrderItem
import center.mycrm.driver.data.Repository
import center.mycrm.driver.data.SupabaseClient
import center.mycrm.driver.data.SupabaseException
import center.mycrm.driver.update.UpdateInfo
import center.mycrm.driver.update.UpdateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class Screen {
    data object Loading : Screen()
    data object Login : Screen()
    data object Home : Screen()
    data class OrderDetail(val orderId: String) : Screen()
}

enum class Tab { Deliveries, Done, Settings }

data class FailRecord(val orderNum: String, val clientName: String?, val reason: String, val atMs: Long)

enum class PodKind { DELIVERED, FAILED }
enum class PodStage { PROCESSING, READY, UPLOADING, ERROR }

/** Photo at the door (v1.1): taken → stamped → confirmed → uploaded → order closed. */
data class PodState(
    val orderId: String,
    val orderNum: String,
    val kind: PodKind,
    val stage: PodStage,
    val failReason: String? = null,
    val file: java.io.File? = null,
    val takenAtMs: Long = 0L,
    val fix: center.mycrm.driver.pod.Fix? = null,
    val lines: List<String> = emptyList(),
    val uploadedPath: String? = null,
    val error: String? = null
)

data class UiState(
    val screen: Screen = Screen.Loading,
    val tab: Tab = Tab.Deliveries,
    val identity: DriverIdentity? = null,
    val orders: List<Order> = emptyList(),
    val items: Map<String, List<OrderItem>> = emptyMap(),
    val doneToday: List<Order> = emptyList(),
    val lastUpdateMs: Long? = null,
    val offline: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val loginError: String? = null,
    val bootError: String? = null,
    val update: UpdateInfo? = null,
    val updateBusy: Boolean = false,
    val updateChecked: Boolean = false,
    /** orderId → deadline (epoch ms) until which Undo is offered. */
    val undoDeadlines: Map<String, Long> = emptyMap(),
    /** Failed deliveries this session (the order leaves the driver's list, so we remember it locally). */
    val recentFails: List<FailRecord> = emptyList(),
    val pod: PodState? = null
) {
    val selectedOrder: Order?
        get() = (screen as? Screen.OrderDetail)?.let { s -> orders.firstOrNull { it.id == s.orderId } ?: doneToday.firstOrNull { it.id == s.orderId } }

    val cashCollectedToday: Double get() = doneToday.sumOf { it.cashToCollect }
}

sealed class UiEvent {
    data class Toast(val text: String) : UiEvent()
    data class Undoable(val orderId: String, val text: String) : UiEvent()
    data object NeedInstallPermission : UiEvent()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val sb = SupabaseClient(app)
    private val repo = Repository(sb)
    val updates = UpdateManager(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var pollJob: Job? = null
    private var foreground = false
    private var pendingOrderNum: String? = null

    val appVersion: String get() = repo.appVersion

    init {
        center.mycrm.driver.pod.Pod.cleanup(app)
        bootstrap()
    }

    // ---- Session -----------------------------------------------------------------------------

    fun bootstrap() {
        viewModelScope.launch {
            _state.update { it.copy(screen = Screen.Loading, bootError = null) }
            if (!sb.isSignedIn) {
                _state.update { it.copy(screen = Screen.Login) }
                checkUpdate()
                return@launch
            }
            try {
                val id = repo.loadIdentity()
                _state.update { it.copy(identity = id, screen = Screen.Home, loading = true) }
                refresh()
                if (foreground) startPolling()
            } catch (e: AuthRequiredException) {
                sb.signOut()
                _state.update { it.copy(screen = Screen.Login, loginError = "Session expired — please sign in again") }
            } catch (e: NotADriverException) {
                sb.signOut()
                _state.update { it.copy(screen = Screen.Login, loginError = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(screen = Screen.Loading, bootError = "No connection. Check the internet and retry.") }
            }
            checkUpdate()
        }
    }

    fun signIn(email: String, password: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, loginError = null) }
            try {
                sb.signIn(email, password)
                val id = repo.loadIdentity()
                repo.logLogin(id)
                _state.update { it.copy(identity = id, screen = Screen.Home, loading = true, busy = false) }
                refresh()
                if (foreground) startPolling()
            } catch (e: NotADriverException) {
                sb.signOut()
                _state.update { it.copy(busy = false, loginError = e.message) }
            } catch (e: SupabaseException) {
                _state.update { it.copy(busy = false, loginError = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, loginError = "No connection. Check the internet and try again.") }
            }
        }
    }

    fun signOut() {
        stopPolling()
        sb.signOut()
        val keepUpdate = _state.value.update
        _state.value = UiState(screen = Screen.Login, update = keepUpdate, updateChecked = true)
    }

    // ---- Polling -----------------------------------------------------------------------------

    fun onForeground() {
        foreground = true
        startPolling()
    }

    fun onBackground() {
        foreground = false
        stopPolling()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        if (_state.value.identity == null) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refreshNow() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val id = _state.value.identity ?: return
        try {
            val snap = repo.loadSnapshot(id.driverName)
            _state.update {
                it.copy(
                    orders = snap.orders,
                    items = snap.items,
                    doneToday = snap.doneToday,
                    lastUpdateMs = System.currentTimeMillis(),
                    offline = false,
                    loading = false
                )
            }
            resolvePendingOrder()
        } catch (e: AuthRequiredException) {
            stopPolling()
            sb.signOut()
            _state.update { UiState(screen = Screen.Login, loginError = "Session expired — please sign in again", update = it.update, updateChecked = true) }
        } catch (e: Exception) {
            _state.update { it.copy(offline = true, loading = false) }
        }
    }

    // ---- Navigation --------------------------------------------------------------------------

    fun selectTab(tab: Tab) {
        _state.update { it.copy(tab = tab) }
    }

    fun openOrder(orderId: String) {
        _state.update { it.copy(screen = Screen.OrderDetail(orderId)) }
    }

    fun closeOrder() {
        _state.update { if (it.screen is Screen.OrderDetail) it.copy(screen = Screen.Home) else it }
    }

    /** From an App Link https://mycrm.center/driver/o/ORD-123456. */
    fun openOrderByNum(orderNum: String) {
        pendingOrderNum = orderNum.trim().uppercase()
        resolvePendingOrder()
    }

    private fun resolvePendingOrder() {
        val num = pendingOrderNum ?: return
        val st = _state.value
        if (st.identity == null) return
        val o = st.orders.firstOrNull { it.orderNum.equals(num, ignoreCase = true) }
        if (o != null) {
            pendingOrderNum = null
            _state.update { it.copy(screen = Screen.OrderDetail(o.id), tab = Tab.Deliveries) }
        } else if (st.lastUpdateMs != null) {
            pendingOrderNum = null
            toast("$num is not in your list")
        }
    }

    // ---- Actions (all through DB functions) --------------------------------------------------

    // ---- Photo at the door ------------------------------------------------------------------

    private var fixJob: kotlinx.coroutines.Deferred<center.mycrm.driver.pod.Fix?>? = null
    private var fixJobAt = 0L

    /** Start a GPS fix as the camera opens, so it is ready when the photo comes back. */
    fun prefetchFix() {
        val now = System.currentTimeMillis()
        if (fixJob?.isActive == true || (fixJob != null && now - fixJobAt < 60_000)) return
        fixJobAt = now
        fixJob = viewModelScope.async { center.mycrm.driver.pod.Pod.currentFix(getApplication(), 10_000L) }
    }

    private suspend fun awaitFix(maxWaitMs: Long): center.mycrm.driver.pod.Fix? {
        val job = fixJob ?: return null
        return withTimeoutOrNull(maxWaitMs) { runCatching { job.await() }.getOrNull() }
    }

    fun onPhotoTaken(order: Order, kind: PodKind, captured: java.io.File, failReason: String?) {
        val driver = _state.value.identity?.driverName ?: ""
        val takenAt = System.currentTimeMillis()
        _state.update { it.copy(pod = PodState(order.id, order.orderNum, kind, PodStage.PROCESSING, failReason = failReason, takenAtMs = takenAt)) }
        viewModelScope.launch {
            try {
                val fix = awaitFix(10_000L)
                val lines = center.mycrm.driver.pod.Pod.stampLines(order.orderNum, driver, takenAt, fix)
                val out = center.mycrm.driver.pod.Pod.newProcessedFile(getApplication())
                val file = center.mycrm.driver.pod.Pod.process(captured, out, lines)
                _state.update { st ->
                    val cur = st.pod
                    if (cur == null || cur.orderId != order.id) st
                    else st.copy(pod = cur.copy(stage = PodStage.READY, file = file, fix = fix, lines = lines, error = null, uploadedPath = null))
                }
            } catch (e: Exception) {
                _state.update { st -> st.copy(pod = st.pod?.copy(stage = PodStage.ERROR, error = e.message ?: "Could not prepare the photo")) }
            }
        }
    }

    fun cancelPod() {
        val p = _state.value.pod ?: return
        if (p.stage == PodStage.UPLOADING) return
        p.file?.delete()
        _state.update { it.copy(pod = null) }
    }

    /** Upload (3 tries, keeps the file between tries) → close the order with the photo. */
    fun confirmPod(order: Order) {
        val p = _state.value.pod ?: return
        if (p.orderId != order.id || p.file == null || p.stage == PodStage.UPLOADING || p.stage == PodStage.PROCESSING) return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, pod = it.pod?.copy(stage = PodStage.UPLOADING, error = null)) }
            try {
                var path = p.uploadedPath
                if (path == null) {
                    var last: Exception? = null
                    for (attempt in 0 until 3) {
                        try {
                            path = repo.uploadPhoto(order.id, p.file, p.takenAtMs)
                            break
                        } catch (e: SupabaseException) {
                            throw e   // refused by the server (policy / size) — retrying will not help
                        } catch (e: AuthRequiredException) {
                            throw e
                        } catch (e: Exception) {
                            last = e
                            kotlinx.coroutines.delay(if (attempt == 0) 2_000L else 5_000L)
                        }
                    }
                    if (path == null) throw last ?: IllegalStateException("upload failed")
                    _state.update { it.copy(pod = it.pod?.copy(uploadedPath = path)) }
                }
                if (p.kind == PodKind.DELIVERED) {
                    repo.markDelivered(order.id, path, null, p.fix)
                    afterDelivered(order)
                } else {
                    val reason = p.failReason ?: "Other"
                    repo.markFailed(order.id, reason, path, p.fix)
                    afterFailed(order, reason)
                }
                p.file.delete()
                _state.update { it.copy(pod = null) }
            } catch (e: AuthRequiredException) {
                signOut()
                _state.update { it.copy(loginError = "Session expired — please sign in again") }
            } catch (e: SupabaseException) {
                // the server refused (order no longer yours / not En Route / photo refused) — nothing to retry
                _state.update { it.copy(pod = null) }
                p.file.delete()
                toast(e.message ?: "Server refused the change")
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(pod = it.pod?.copy(stage = PodStage.ERROR, error = "No connection — the photo was NOT sent. Tap Try again (no need to retake).")) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Delivered without a photo — only with a reason (logged, the dispatcher sees it). */
    fun markDeliveredNoPhoto(order: Order, reason: String) = action {
        repo.markDelivered(order.id, null, reason, awaitFix(3_000L))
        afterDelivered(order)
    }

    /** Could not deliver without a photo (the photo is optional here). */
    fun markFailed(order: Order, reason: String) = action {
        repo.markFailed(order.id, reason, null, null)
        afterFailed(order, reason)
    }

    private suspend fun afterDelivered(order: Order) {
        val deadline = System.currentTimeMillis() + Rules.UNDO_WINDOW_MS
        _state.update {
            it.copy(
                screen = Screen.Home,
                undoDeadlines = it.undoDeadlines + (order.id to deadline),
                // optimistic: drop it from the list right away, the next refresh confirms
                orders = it.orders.filterNot { o -> o.id == order.id }
            )
        }
        _events.tryEmit(UiEvent.Undoable(order.id, "${order.orderNum} delivered"))
        refresh()
    }

    private suspend fun afterFailed(order: Order, reason: String) {
        _state.update {
            it.copy(
                screen = Screen.Home,
                orders = it.orders.filterNot { o -> o.id == order.id },
                recentFails = listOf(FailRecord(order.orderNum, order.clientName, reason, System.currentTimeMillis())) + it.recentFails
            )
        }
        toast("${order.orderNum} returned to the dispatcher: $reason")
        refresh()
    }

    fun undoDelivered(orderId: String) = action {
        repo.undoDelivered(orderId)
        _state.update { it.copy(undoDeadlines = it.undoDeadlines - orderId, screen = Screen.Home, tab = Tab.Deliveries) }
        toast("Back to En Route")
        refresh()
    }

    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
            } catch (e: AuthRequiredException) {
                signOut()
                _state.update { it.copy(loginError = "Session expired — please sign in again") }
            } catch (e: SupabaseException) {
                toast(e.message ?: "Server refused the change")
                refresh()
            } catch (e: Exception) {
                toast("No connection — the change was NOT saved. Try again.")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun toast(text: String) {
        _events.tryEmit(UiEvent.Toast(text))
    }

    // ---- Self-update -------------------------------------------------------------------------

    fun checkUpdate() {
        viewModelScope.launch {
            val u = updates.check()
            _state.update { it.copy(update = u, updateChecked = true) }
        }
    }

    fun installUpdate() {
        val info = _state.value.update ?: return
        if (_state.value.updateBusy) return
        if (!updates.canInstall()) {
            _events.tryEmit(UiEvent.NeedInstallPermission)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(updateBusy = true) }
            try {
                val id = updates.startDownload(info)
                val err = updates.awaitAndInstall(id)
                if (err != null) toast(err)
            } catch (e: Exception) {
                toast("Update failed: ${e.message}")
            } finally {
                _state.update { it.copy(updateBusy = false) }
            }
        }
    }

    companion object {
        const val POLL_MS = 10_000L
    }
}
