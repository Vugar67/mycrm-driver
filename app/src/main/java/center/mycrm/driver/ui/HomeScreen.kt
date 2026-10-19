@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package center.mycrm.driver.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import center.mycrm.driver.core.Rules
import center.mycrm.driver.data.Order

// ---- Deliveries -------------------------------------------------------------------------------

@Composable
fun DeliveriesTab(
    state: UiState,
    onOpen: (Order) -> Unit,
    onRefresh: () -> Unit
) {
    if (state.loading && state.orders.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state.orders.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("No deliveries right now", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "When the dispatcher sends you an order it appears here within 10 seconds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
        }
        return
    }
    val totalCash = state.orders.sumOf { it.cashToCollect }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                "${state.orders.size} to deliver · cash on route " + Rules.fmtAed(totalCash),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        items(state.orders, key = { it.id }) { o ->
            OrderCard(o, itemCount = state.items[o.id]?.size ?: 0, onClick = { onOpen(o) })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun OrderCard(order: Order, itemCount: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriorityDot(order.priority)
                Spacer(Modifier.width(8.dp))
                Text(order.orderNum, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                PaymentChip(order)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                order.clientName ?: "—",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val place = listOfNotNull(order.emirate?.uppercase(), order.city).filter { it.isNotBlank() }.joinToString(" · ")
            if (place.isNotEmpty()) {
                Text(place, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            val schedule = order.scheduleText
            val meta = listOfNotNull(
                schedule.takeIf { it.isNotEmpty() }?.let { "🕒 $it" },
                if (itemCount > 0) "📦 $itemCount line" + (if (itemCount == 1) "" else "s") else null
            ).joinToString("   ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val note = order.driverNote?.lineSequence()?.firstOrNull { it.isNotBlank() }
            if (!note.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "📝 $note",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---- Done today -------------------------------------------------------------------------------

@Composable
fun DoneTab(state: UiState, onUndo: (String) -> Unit, nowMs: Long) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "${state.doneToday.size} delivered",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        (if (state.cashTodayExact) "Cash taken: " else "Cash taken ≈ ") + Rules.fmtAed(state.cashCollectedToday),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        if (state.cashTodayExact) "The amounts you entered — hand over all of it."
                        else "Some orders were closed without an amount (≈ = the due amount). Hand over all the cash you took.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        if (state.doneToday.isEmpty()) {
            item {
                Text(
                    "Nothing delivered yet today.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
        items(state.doneToday, key = { "d" + it.id }) { o ->
            val deadline = state.undoDeadlines[o.id]
            val canUndo = deadline != null && deadline > nowMs
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("✓ ${o.orderNum}  ·  ${Rules.fmtTimeDubai(o.deliveredAt)}", fontWeight = FontWeight.Bold)
                        Text(
                            listOfNotNull(o.clientName, o.emirate?.uppercase()).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (o.paymentMode == Rules.PaymentMode.CASH && o.cashTaken > 0) {
                            val over = if (o.cashReceived != null && o.cashDue != null) Rules.round2(o.cashReceived - o.cashDue) else 0.0
                            Text(
                                (if (o.cashReceived != null) "cash " else "cash ≈ ") + Rules.fmtAed(o.cashTaken) +
                                    (if (over > 0.004) "  (+" + Rules.fmtAed(over) + ")" else ""),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    if (canUndo) {
                        val left = ((deadline!! - nowMs) / 1000).coerceAtLeast(0)
                        OutlinedButton(onClick = { onUndo(o.id) }, enabled = !state.busy) {
                            Text("↩ Undo " + String.format("%d:%02d", left / 60, left % 60))
                        }
                    }
                }
            }
        }
        if (state.recentFails.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                SectionTitle("RETURNED TO DISPATCHER (THIS SESSION)")
            }
            items(state.recentFails, key = { "f" + it.orderNum + it.atMs }) { f ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "↩ ${f.orderNum}  ·  " + Rules.fmtTimeDubai(java.time.Instant.ofEpochMilli(f.atMs).toString()),
                            fontWeight = FontWeight.Bold
                        )
                        Text(listOfNotNull(f.clientName, f.reason).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---- Settings ---------------------------------------------------------------------------------

@Composable
fun SettingsTab(
    state: UiState,
    appVersion: String,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        val id = state.identity
        SectionTitle("SIGNED IN AS")
        Text(id?.driverName ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(id?.email ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!id?.driverPhone.isNullOrBlank()) {
            Text(Rules.formatPhone(id?.driverPhone), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        SectionTitle("APP")
        Text(appVersion, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        when {
            state.update != null -> {
                Text("Version ${state.update.version} is available.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onInstallUpdate, enabled = !state.updateBusy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (state.updateBusy) "Downloading…" else "Update now")
                }
            }
            state.updateChecked -> Text("Up to date", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        state.lastUpdateMs?.let {
            Text(
                "List refreshed " + Rules.fmtTimeDubai(java.time.Instant.ofEpochMilli(it).toString()) + " · every 10 s while the app is open",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Log out", color = MaterialTheme.colorScheme.error, fontSize = 16.sp)
        }
    }
}

/** Small helper so a whole row is tappable without ripple boilerplate at call sites. */
fun Modifier.tap(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
