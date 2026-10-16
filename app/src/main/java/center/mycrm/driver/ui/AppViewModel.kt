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
import kotlinx.coroutines.delay
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
    val recentFails: List<FailRecord> = emptyList()
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

    fun markDelivered(order: Order) = action {
        repo.markDelivered(order.id)
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

    fun markFailed(order: Order, reason: String) = action {
        repo.markFailed(order.id, reason)
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
