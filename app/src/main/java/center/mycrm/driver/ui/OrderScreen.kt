@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package center.mycrm.driver.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import center.mycrm.driver.pod.Pod
import java.io.File
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
import center.mycrm.driver.core.MessageTemplate
import center.mycrm.driver.core.Rules
import center.mycrm.driver.data.Order
import center.mycrm.driver.data.OrderItem

@Composable
fun OrderScreen(
    order: Order,
    items: List<OrderItem>,
    busy: Boolean,
    isEnRoute: Boolean,
    pod: PodState?,
    messages: List<MessageTemplate>,
    driverName: String,
    onBack: () -> Unit,
    onPrefetchFix: () -> Unit,
    onPhotoTaken: (PodKind, File, String?) -> Unit,
    onConfirmPod: () -> Unit,
    onCancelPod: () -> Unit,
    onDeliveredNoPhoto: (String) -> Unit,
    onFailed: (String) -> Unit
) {
    val context = LocalContext.current
    var askFail by rememberSaveable { mutableStateOf(false) }
    var waPick by rememberSaveable { mutableStateOf(false) }
    // v1.2: WhatsApp texts written by the admin (Directory → Driver messages); seller's own replace the general ones
    val waMessages = remember(messages, order.sellerName) { Rules.pickMessages(messages, order.sellerName) }
    val waVars = remember(order, driverName) {
        mapOf(
            "client" to (order.clientName ?: ""),
            "order" to order.orderNum,
            "driver" to driverName,
            "seller" to (order.sellerName?.takeIf { it.isNotBlank() } ?: "Pure Peptides"),
            "amount" to Rules.fmtAed(order.amountDue),
            "window" to order.scheduleText,
            "address" to order.addressText
        )
    }
    var noPhotoAsk by rememberSaveable { mutableStateOf(false) }   // camera closed without a photo (Delivered)
    var skipAsk by rememberSaveable { mutableStateOf(false) }      // "Can't take photo" → reason
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var captureKind by rememberSaveable { mutableStateOf(PodKind.DELIVERED.name) }
    var captureReason by rememberSaveable { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = capturePath
        val kind = PodKind.valueOf(captureKind)
        if (ok && path != null && File(path).length() > 0) {
            onPhotoTaken(kind, File(path), captureReason)
        } else {
            path?.let { File(it).delete() }
            if (pod == null) {
                if (kind == PodKind.DELIVERED) noPhotoAsk = true else askFail = true
            }
        }
    }

    fun launchCamera(kind: PodKind, reason: String?) {
        val (file, uri) = Pod.newCaptureTarget(context)
        capturePath = file.path
        captureKind = kind.name
        captureReason = reason
        onPrefetchFix()
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No camera app on this phone", Toast.LENGTH_LONG).show()
            if (kind == PodKind.DELIVERED) noPhotoAsk = true
        }
    }

    var pendingKind by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingReason by rememberSaveable { mutableStateOf<String?>(null) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        // granted or not — the photo is taken anyway; without permission the stamp says "GPS: off"
        val k = pendingKind
        pendingKind = null
        if (k != null) launchCamera(PodKind.valueOf(k), pendingReason)
    }

    fun startCapture(kind: PodKind, reason: String?) {
        if (!Pod.hasLocationPermission(context)) {
            pendingKind = kind.name
            pendingReason = reason
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            launchCamera(kind, reason)
        }
    }

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
                        if (waMessages.size == 1) {
                            openLink(context, Rules.whatsappLink(order.clientNumber, Rules.fillMessage(waMessages[0].body, waVars)))
                        } else {
                            waPick = true
                        }
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
                        onClick = { startCapture(PodKind.DELIVERED, null) },
                        enabled = !busy && pod == null,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Green, contentColor = Color.White),
                        modifier = Modifier.weight(1.2f).height(60.dp)
                    ) { Text(if (busy) "Saving…" else "📷  Delivered", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    if (waPick) {
        AlertDialog(
            onDismissRequest = { waPick = false },
            title = { Text("WhatsApp to ${order.clientName ?: "customer"}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    waMessages.forEach { m ->
                        val text = Rules.fillMessage(m.body, waVars)
                        Surface(
                            onClick = { waPick = false; openLink(context, Rules.whatsappLink(order.clientNumber, text)) },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(m.title, fontWeight = FontWeight.Bold)
                                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { waPick = false; openLink(context, Rules.whatsappLink(order.clientNumber, null)) }) { Text("Open chat without text") }
            },
            dismissButton = { TextButton(onClick = { waPick = false }) { Text("Cancel") } }
        )
    }
    if (noPhotoAsk) {
        AlertDialog(
            onDismissRequest = { noPhotoAsk = false },
            title = { Text("No photo taken") },
            text = { Text("A photo of the package at the door (villa / flat number visible) is required to close ${order.orderNum}.") },
            confirmButton = {
                Button(onClick = { noPhotoAsk = false; startCapture(PodKind.DELIVERED, null) }) { Text("📷 Take photo") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { noPhotoAsk = false; skipAsk = true }) { Text("Can't take photo") }
                    TextButton(onClick = { noPhotoAsk = false }) { Text("Cancel") }
                }
            }
        )
    }
    if (skipAsk) {
        SkipPhotoDialog(
            order = order,
            busy = busy,
            onConfirm = { reason -> skipAsk = false; onDeliveredNoPhoto(reason) },
            onDismiss = { skipAsk = false }
        )
    }
    if (askFail) {
        FailDialog(
            orderNum = order.orderNum,
            onConfirm = { reason -> askFail = false; onFailed(reason) },
            onConfirmWithPhoto = { reason -> askFail = false; startCapture(PodKind.FAILED, reason) },
            onDismiss = { askFail = false }
        )
    }
    if (pod != null && pod.orderId == order.id) {
        PodDialog(
            order = order,
            pod = pod,
            onConfirm = onConfirmPod,
            onRetake = {
                val k = pod.kind
                val r = pod.failReason
                onCancelPod()
                startCapture(k, r)
            },
            onCancel = onCancelPod
        )
    }
}

@Composable
private fun MoneyCard(order: Order) {
    val (title, amount, color, hint) = when (order.paymentMode) {
        Rules.PaymentMode.CASH -> Quad(
            "CASH TO COLLECT", Rules.fmtAed(order.cashToCollect), Palette.Orange,
            "Payment: " + (order.paymentStatus ?: "") + (if (order.cashToCollect <= 0.0) " — amount is 0, check with the dispatcher" else "")
        )
        Rules.PaymentMode.LINK -> Quad("PAYMENT LINK AT THE DOOR", Rules.fmtAed(order.amountDue), MaterialTheme.colorScheme.secondary, "Payment: " + (order.paymentStatus ?: "") + " — customer pays by link at the door")
        Rules.PaymentMode.NONE -> Quad("NOTHING TO COLLECT", "", Palette.Green, "Payment: " + (order.paymentStatus ?: "") + " · order total " + Rules.fmtAed(order.amountDue))
        Rules.PaymentMode.UNKNOWN -> Quad(
            if (order.paymentStatus.isNullOrBlank()) "PAYMENT NOT SET" else "CHECK PAYMENT",
            Rules.fmtAed(order.amountDue),
            Palette.Red,
            (if (order.paymentStatus.isNullOrBlank()) "" else "Payment: \"" + order.paymentStatus + "\" — ") + "ask the dispatcher before you take money"
        )
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
private fun SkipPhotoDialog(order: Order, busy: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var other by rememberSaveable { mutableStateOf("") }
    val isOther = selected == Pod.SKIP_REASONS.last()
    val reason = when {
        selected == null -> null
        isOther -> other.trim().takeIf { it.isNotEmpty() }
        else -> selected
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delivered ${order.orderNum} without photo") },
        text = {
            Column {
                Text("Why is there no photo? The dispatcher sees the reason.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Pod.SKIP_REASONS.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = selected == r, onClick = { selected = r }, role = Role.RadioButton).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == r, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(r, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (isOther) {
                    OutlinedTextField(value = other, onValueChange = { other = it.take(200) }, label = { Text("What happened?") }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                MoneyLine(order)
            }
        },
        confirmButton = {
            Button(
                onClick = { reason?.let(onConfirm) },
                enabled = reason != null && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Green, contentColor = Color.White)
            ) { Text("✓ Delivered") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** What the driver collected — one line, used in the confirm dialogs. */
@Composable
private fun MoneyLine(order: Order) {
    when (order.paymentMode) {
        Rules.PaymentMode.CASH -> Text("Cash collected: " + Rules.fmtAed(order.cashToCollect), fontWeight = FontWeight.Bold, color = Palette.Orange)
        Rules.PaymentMode.LINK -> Text("Paid by link: " + Rules.fmtAed(order.amountDue), fontWeight = FontWeight.Bold)
        Rules.PaymentMode.UNKNOWN -> Text("Check payment with the dispatcher — amount " + Rules.fmtAed(order.amountDue), fontWeight = FontWeight.Bold, color = Palette.Red)
        Rules.PaymentMode.NONE -> Text("Nothing to collect", fontWeight = FontWeight.Bold)
    }
}

/** The photo with its stamp, then Confirm (upload + close) / Retake / Cancel. */
@Composable
private fun PodDialog(order: Order, pod: PodState, onConfirm: () -> Unit, onRetake: () -> Unit, onCancel: () -> Unit) {
    val uploading = pod.stage == PodStage.UPLOADING
    Dialog(
        onDismissRequest = { if (!uploading) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(0.94f)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (pod.kind == PodKind.DELIVERED) "Delivered ${order.orderNum}?" else "Could not deliver ${order.orderNum}",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                val file = pod.file
                val bitmap = remember(file?.path, pod.stage) {
                    if (file != null && file.exists()) runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() else null
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Photo at the door",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        if (pod.stage == PodStage.ERROR && file == null) {
                            Text(pod.error ?: "No photo", color = Palette.Red)
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(8.dp))
                                Text("Preparing photo…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (pod.fix == null && pod.stage != PodStage.PROCESSING) {
                    Text("GPS was not available — the photo is still accepted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Check that the villa / flat number or the door is visible.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (pod.kind == PodKind.DELIVERED) MoneyLine(order) else Text("Reason: " + (pod.failReason ?: ""), fontWeight = FontWeight.Bold)
                if (pod.stage == PodStage.ERROR && pod.error != null && file != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(pod.error, color = Palette.Red, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onConfirm,
                    enabled = file != null && (pod.stage == PodStage.READY || pod.stage == PodStage.ERROR),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (pod.kind == PodKind.DELIVERED) Palette.Green else Palette.Red,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    when {
                        uploading -> {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(10.dp))
                            Text("Sending photo…")
                        }
                        pod.stage == PodStage.ERROR && file != null -> Text("Try again", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        pod.kind == PodKind.DELIVERED -> Text("✓ Confirm delivered", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        else -> Text("Return to dispatcher", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onRetake, enabled = !uploading) { Text("📷 Retake") }
                    TextButton(onClick = onCancel, enabled = !uploading) { Text("Cancel") }
                }
                if (pod.kind == PodKind.DELIVERED) {
                    Text("You can undo for 5 minutes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FailDialog(orderNum: String, onConfirm: (String) -> Unit, onConfirmWithPhoto: (String) -> Unit, onDismiss: () -> Unit) {
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
                    "The order goes back to the dispatcher's queue. A photo (closed door, wrong address) helps — optional.",
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
            Column(horizontalAlignment = Alignment.End) {
                Button(
                    onClick = { reason?.let(onConfirmWithPhoto) },
                    enabled = reason != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Red, contentColor = Color.White)
                ) { Text("📷 Photo + return") }
                TextButton(onClick = { reason?.let(onConfirm) }, enabled = reason != null) { Text("Return without photo") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
