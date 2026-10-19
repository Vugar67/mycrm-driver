@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package center.mycrm.driver

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import center.mycrm.driver.ui.AppViewModel
import center.mycrm.driver.ui.DeliveriesTab
import center.mycrm.driver.ui.DoneTab
import center.mycrm.driver.ui.DriverTheme
import center.mycrm.driver.ui.LoginScreen
import center.mycrm.driver.ui.OrderScreen
import center.mycrm.driver.ui.Screen
import center.mycrm.driver.ui.SettingsTab
import center.mycrm.driver.ui.StatusLine
import center.mycrm.driver.ui.Tab
import center.mycrm.driver.ui.UiEvent
import center.mycrm.driver.ui.UpdateBanner
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                vm.onForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                vm.onBackground()
            }
        })

        handleLink(intent)

        setContent {
            DriverTheme {
                App(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** https://mycrm.center/driver/o/ORD-123456 or mycrmdriver://o/ORD-123456 → open that order. */
    private fun handleLink(intent: Intent?) {
        val data = intent?.data ?: return
        val segs = data.pathSegments ?: emptyList()
        val num: String? = if (data.scheme == "mycrmdriver") {
            segs.firstOrNull()
        } else {
            val i = segs.indexOf("o")
            if (i >= 0 && i + 1 < segs.size) segs[i + 1] else null
        }
        if (!num.isNullOrBlank() && num.uppercase().startsWith("ORD-")) vm.openOrderByNum(num)
    }
}

@Composable
fun App(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // one clock for the Undo countdowns
    LaunchedEffect(state.undoDeadlines.isNotEmpty()) {
        while (state.undoDeadlines.isNotEmpty()) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
        nowMs = System.currentTimeMillis()
    }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.Toast -> snackbar.showSnackbar(e.text, withDismissAction = true, duration = SnackbarDuration.Long)
                is UiEvent.Undoable -> {
                    val r = snackbar.showSnackbar(
                        message = e.text,
                        actionLabel = "UNDO",
                        withDismissAction = true,
                        duration = SnackbarDuration.Long
                    )
                    if (r == SnackbarResult.ActionPerformed) vm.undoDelivered(e.orderId)
                }
                UiEvent.NeedInstallPermission -> {
                    val r = snackbar.showSnackbar(
                        message = "Allow MyCRM Driver to install updates, then tap Update again",
                        actionLabel = "SETTINGS",
                        duration = SnackbarDuration.Long
                    )
                    if (r == SnackbarResult.ActionPerformed) {
                        try {
                            vm.getApplication<android.app.Application>().startActivity(vm.updates.installPermissionIntent())
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    when (val screen = state.screen) {
        Screen.Loading -> LoadingScreen(state.bootError, onRetry = vm::bootstrap)
        Screen.Login -> LoginScreen(
            busy = state.busy,
            error = state.loginError,
            appVersion = vm.appVersion,
            update = state.update,
            updateBusy = state.updateBusy,
            onSignIn = vm::signIn,
            onInstallUpdate = vm::installUpdate
        )
        Screen.Home, is Screen.OrderDetail -> {
            val order = state.selectedOrder
            val inDetail = screen is Screen.OrderDetail && order != null
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        title = {
                            if (inDetail) {
                                Text(order!!.orderNum, fontWeight = FontWeight.Bold)
                            } else {
                                Column {
                                    Text(
                                        when (state.tab) {
                                            Tab.Deliveries -> "My deliveries (${state.orders.size})"
                                            Tab.Done -> "Done today (${state.doneToday.size})"
                                            Tab.Settings -> "Settings"
                                        },
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        state.identity?.driverName ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            if (inDetail) TextButton(onClick = vm::closeOrder) { Text("‹ Back") }
                        },
                        actions = {
                            if (!inDetail && state.tab != Tab.Settings) {
                                TextButton(onClick = vm::refreshNow) { Text("↻") }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                    )
                },
                bottomBar = {
                    if (!inDetail) {
                        NavigationBar {
                            NavigationBarItem(
                                selected = state.tab == Tab.Deliveries,
                                onClick = { vm.selectTab(Tab.Deliveries) },
                                icon = { Text("🚚") },
                                label = { Text("Deliveries") }
                            )
                            NavigationBarItem(
                                selected = state.tab == Tab.Done,
                                onClick = { vm.selectTab(Tab.Done) },
                                icon = { Text("✓") },
                                label = { Text("Done today") }
                            )
                            NavigationBarItem(
                                selected = state.tab == Tab.Settings,
                                onClick = { vm.selectTab(Tab.Settings) },
                                icon = { Text("⚙") },
                                label = { Text("Settings") }
                            )
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.background
            ) { padding ->
                Column(Modifier.padding(padding).fillMaxSize()) {
                    state.update?.let { if (!inDetail) UpdateBanner(it, state.updateBusy, vm::installUpdate) }
                    StatusLine(state.offline, state.lastUpdateMs)
                    Box(Modifier.weight(1f)) {
                        if (inDetail) {
                            OrderScreen(
                                order = order!!,
                                items = state.items[order.id] ?: emptyList(),
                                busy = state.busy,
                                isEnRoute = state.orders.any { it.id == order.id },
                                pod = state.pod,
                                messages = state.messages,
                                driverName = state.identity?.driverName ?: "",
                                cashChecking = state.cashChecking,
                                onBack = vm::closeOrder,
                                onCheckCash = vm::checkCash,
                                onPrefetchFix = vm::prefetchFix,
                                onPhotoTaken = { kind, file, reason -> vm.onPhotoTaken(order, kind, file, reason) },
                                onConfirmPod = { cash -> vm.confirmPod(order, cash) },
                                onCancelPod = vm::cancelPod,
                                onDeliveredNoPhoto = { reason, cash -> vm.markDeliveredNoPhoto(order, reason, cash) },
                                onFailed = { reason -> vm.markFailed(order, reason) }
                            )
                        } else {
                            when (state.tab) {
                                Tab.Deliveries -> DeliveriesTab(state, onOpen = { vm.openOrder(it.id) }, onRefresh = vm::refreshNow)
                                Tab.Done -> DoneTab(state, onUndo = vm::undoDelivered, nowMs = nowMs)
                                Tab.Settings -> SettingsTab(
                                    state = state,
                                    appVersion = vm.appVersion,
                                    onCheckUpdate = vm::checkUpdate,
                                    onInstallUpdate = vm::installUpdate,
                                    onSignOut = vm::signOut
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen(error: String?, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (error == null) {
                CircularProgressIndicator()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text(error, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}
