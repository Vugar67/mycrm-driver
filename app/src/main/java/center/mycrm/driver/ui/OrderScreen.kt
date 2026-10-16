@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package center.mycrm.driver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import center.mycrm.driver.core.Rules
import center.mycrm.driver.data.Order
import center.mycrm.driver.data.OrderItem

@Composable
fun OrderScreen(
    order: Order,
    items: List<OrderItem>,
    busy: Boolean,
    isEnRoute: Boolean,
    onBack: () -> Unit,
    onDelivered: () -> Unit,
    onFailed: (String) -> Unit
) {
    val context = LocalContext.current
    var confirmDelivered by rememberSaveable { mutableStateOf(false) }
    var askFail by rememberSaveable { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriorityDot(order.priority)
                Spacer(Modifier.width(8.dp))
                Text(order.orderNum, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (order.priority == 1) Chip("URGENT", Palette.Red, Color.White)
                else if (order.priority == 2) Chip("P2", Palette.Yellow, Color.Black)
            }
            Spacer(Modifier.height(8.dp))

            // Money first — the thing a driver must not get wrong
            MoneyCard(order)
            Spacer(Modifier.height(12.dp))

            // Customer
            SectionTitle("CUSTOMER")
            Text(order.clientName ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (order.phoneText.isNotEmpty()) {
                Text(order.phoneText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = { dial(context, order.clientNumber) },
                    enabled = order.phoneText.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("📞  Call", fontSize = 16.sp) }
                FilledTonalButton(
                    onClick = {
                        openLink(
                            context,
                            Rules.whatsappLink(
                                order.clientNumber,
                                "Hello, this is the Pure Peptides driver. I'm on my way with your order ${order.orderNum}."
                            )
                        )
                    },
                    enabled = order.phoneText.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("💬  WhatsApp", fontSize = 16.sp) }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // Address
            SectionTitle("ADDRESS")
            InfoRow("Emirate", order.emirate?.uppercase() ?: "", FontWeight.Bold)
            InfoRow("City / area", order.city ?: "")
            InfoRow("Villa / bldg", order.villa ?: "")
            InfoRow("Apt", order.apt ?: "")
            if (order.scheduleText.isNotEmpty()) InfoRow("When", order.scheduleText, FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val nav = order.navigateLink
            val waze = order.wazeLink
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { nav?.let { openLink(context, it) } },
                    enabled = nav != null,
                    modifier = Modifier.weight(1f).height(56.dp)
                ) { Text("🧭  Navigate", fontSize = 17.sp) }
                if (waze != null) {
                    OutlinedButton(
                        onClick = { openLink(context, waze) },
                        modifier = Modifier.weight(0.6f).height(56.dp)
                    ) { Text("Waze", fontSize = 16.sp) }
                }
            }
            if (order.locationUrl.isNullOrBlank()) {
                Text(
                    if (nav == null) "No location and no address on this order — call the customer."
                    else "No location pin on this order — Navigate searches by the address text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Red,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Driver note
            if (!order.driverNote.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("NOTE FOR DRIVER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(order.driverNote, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // Items
            SectionTitle("ITEMS (${items.size})")
            if (items.isEmpty()) {
                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items.forEach { line ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(line.productName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(Rules.pcs(line.quantity), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
            }
            if (!order.sellerName.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Seller: ${order.sellerName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }

        // Action bar
        if (isEnRoute) {
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { askFail = true },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).height(60.dp)
                    ) { Text("✕  Could not deliver", color = Palette.Red, fontSize = 15.sp) }
                    Button(
                        onClick = { confirmDelivered = true },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Green, contentColor = Color.White),
                        modifier = Modifier.weight(1.2f).height(60.dp)
                    ) { Text(if (busy) "Saving…" else "✓  Delivered", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    if (confirmDelivered) {
        DeliveredDialog(
            order = order,
            onConfirm = { confirmDelivered = false; onDelivered() },
            onDismiss = { confirmDelivered = false }
        )
    }
    if (askFail) {
        FailDialog(
            orderNum = order.orderNum,
            onConfirm = { reason -> askFail = false; onFailed(reason) },
            onDismiss = { askFail = false }
        )
    }
}

@Composable
private fun MoneyCard(order: Order) {
    val (title, amount, color, hint) = when (order.paymentMode) {
        Rules.PaymentMode.CASH -> Quad("CASH TO COLLECT", Rules.fmtAed(order.cashToCollect), Palette.Orange, "Cash on delivery")
        Rules.PaymentMode.LINK -> Quad("PAYMENT LINK AT THE DOOR", Rules.fmtAed(order.amountDue), MaterialTheme.colorScheme.secondary, "Customer pays by payment link at the door")
        Rules.PaymentMode.NONE -> Quad("NOTHING TO COLLECT", "", Palette.Green, order.paymentStatus ?: "")
        Rules.PaymentMode.UNKNOWN -> Quad("PAYMENT NOT SET", Rules.fmtAed(order.amountDue), Palette.Red, "Ask the dispatcher before you take money")
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            if (amount.isNotEmpty()) {
                Text(amount, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            }
            if (hint.isNotEmpty()) {
                Text(hint, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private data class Quad(val a: String, val b: String, val c: Color, val d: String)

@Composable
private fun DeliveredDialog(order: Order, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delivered ${order.orderNum}?") },
        text = {
            Column {
                Text(order.clientName ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(order.addressText, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                when (order.paymentMode) {
                    Rules.PaymentMode.CASH -> Text("Cash collected: " + Rules.fmtAed(order.cashToCollect), fontWeight = FontWeight.Bold, color = Palette.Orange)
                    Rules.PaymentMode.LINK -> Text("Paid by link: " + Rules.fmtAed(order.amountDue), fontWeight = FontWeight.Bold)
                    Rules.PaymentMode.UNKNOWN -> Text("Payment not set — amount " + Rules.fmtAed(order.amountDue), fontWeight = FontWeight.Bold, color = Palette.Red)
                    Rules.PaymentMode.NONE -> Text("Nothing to collect", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text("You can undo for 5 minutes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Green, contentColor = Color.White)
            ) { Text("✓ Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FailDialog(orderNum: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var other by rememberSaveable { mutableStateOf("") }
    val isOther = selected == Rules.FAIL_REASONS.last()
    val reason = remember(selected, other) {
        when {
            selected == null -> null
            isOther -> other.trim().takeIf { it.isNotEmpty() }
            else -> selected
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Could not deliver $orderNum") },
        text = {
            Column {
                Text(
                    "The order goes back to the dispatcher's queue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Rules.FAIL_REASONS.forEach { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == r, onClick = { selected = r }, role = Role.RadioButton)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == r, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(r, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (isOther) {
                    OutlinedTextField(
                        value = other,
                        onValueChange = { other = it.take(200) },
                        label = { Text("What happened?") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { reason?.let(onConfirm) },
                enabled = reason != null,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Red, contentColor = Color.White)
            ) { Text("Return to dispatcher") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
