package center.mycrm.driver.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import center.mycrm.driver.core.Rules
import center.mycrm.driver.data.Order
import center.mycrm.driver.update.UpdateInfo

/** Opens a URL / tel: / geo: link in whatever app handles it; tells the user when nothing does. */
fun openLink(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun dial(context: Context, phoneRaw: String?) {
    val digits = Rules.phoneForLinks(phoneRaw)
    if (digits.isEmpty()) {
        Toast.makeText(context, "No phone number on this order", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$digits")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "No phone app", Toast.LENGTH_SHORT).show()
    }
}

fun priorityColor(priority: Int): Color = when (priority) {
    1 -> Palette.Red
    2 -> Palette.Yellow
    else -> Palette.Green
}

@Composable
fun PriorityDot(priority: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(12.dp)
            .background(priorityColor(priority), CircleShape)
    )
}

@Composable
fun Chip(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(color = container, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** "CASH 1,250 AED" / "LINK 1,250 AED" / "PAID" / "? 1,250 AED" for list cards. */
@Composable
fun PaymentChip(order: Order) {
    val scheme = MaterialTheme.colorScheme
    when (order.paymentMode) {
        Rules.PaymentMode.CASH -> Chip("CASH " + Rules.fmtAed(order.cashToCollect), Palette.Orange, Color.White)
        Rules.PaymentMode.LINK -> Chip("LINK " + Rules.fmtAed(order.amountDue), scheme.secondary, scheme.onSecondary)
        Rules.PaymentMode.NONE -> Chip("NO CASH", scheme.surfaceVariant, scheme.onSurfaceVariant)
        Rules.PaymentMode.UNKNOWN -> Chip("? " + Rules.fmtAed(order.amountDue), Palette.Red, Color.White)
    }
}

@Composable
fun InfoRow(label: String, value: String, valueWeight: FontWeight = FontWeight.Normal) {
    if (value.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = valueWeight)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
fun UpdateBanner(info: UpdateInfo, busy: Boolean, onInstall: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Update ${info.version} available",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    if (busy) "Downloading…" else "Tap Update, then confirm the install",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Button(onClick = onInstall, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Text("Update")
                }
            }
        }
    }
}

@Composable
fun StatusLine(offline: Boolean, lastUpdateMs: Long?) {
    val text = when {
        offline && lastUpdateMs != null -> "Offline — showing data from " + Rules.fmtTimeDubai(java.time.Instant.ofEpochMilli(lastUpdateMs).toString())
        offline -> "Offline — no data yet"
        else -> return
    }
    Surface(color = Palette.Red.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Red,
            fontWeight = FontWeight.Bold
        )
    }
}
