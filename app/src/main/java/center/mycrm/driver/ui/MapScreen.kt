package center.mycrm.driver.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import center.mycrm.driver.core.Rules
import center.mycrm.driver.data.Order
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

/**
 * v1.10: the driver's deliveries on a map (OpenStreetMap tiles through osmdroid — pure Java, works on phones without
 * Google services). Every order with a stored point (orders.geo_*, add_order_geo.sql / core v13.113) is a numbered
 * marker: the number is its place in the driver's route, "•" when the order is not placed, "≈" + grey when only the
 * district centre is known. The NEXT order is green. The driver's own position is the blue dot (GPS, only while this
 * screen is open). Tap a marker → a card with the order and Open / Navigate. Orders without a point are listed under
 * the map. Sequence on the map and the "Auto" order by distance come in a later version.
 */
@Composable
fun MapScreen(
    state: UiState,
    onBack: () -> Unit,
    onOpen: (Order) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    BackHandler(onBack = onBack)

    // the same numbers as the list: position 1..N among the placed orders, the first placed one is NEXT
    val position: Map<String, Int> = state.routedOrders.withIndex().associate { it.value.id to it.index + 1 }
    val nextId = state.nextOrderId
    val (pins, missingIds) = Rules.mapPins(state.orders, { it.id }, { it.geoLat }, { it.geoLng }, { it.geoSrc }, position, nextId)
    val missing = missingIds.mapNotNull { id -> state.orders.firstOrNull { it.id == id } }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = selectedId?.let { id -> state.orders.firstOrNull { it.id == id } }

    // osmdroid wants an identifying user agent (OSM tile policy) and a cache folder of its own
    remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
        true
    }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 5.0
            maxZoomLevel = 19.5
            controller.setZoom(11.0)
            controller.setCenter(GeoPoint(25.2048, 55.2708))   // Dubai until the pins arrive
        }
    }
    val myLocation = remember {
        MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
            val dot = blueDot(context)
            setPersonIcon(dot); setDirectionIcon(dot)
            setPersonAnchor(0.5f, 0.5f); setDirectionAnchor(0.5f, 0.5f)
            isDrawAccuracyEnabled = true
        }
    }
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        locationGranted = result.values.any { it }
    }
    LaunchedEffect(Unit) { if (!locationGranted) askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    LaunchedEffect(locationGranted) {
        if (locationGranted) {
            if (!mapView.overlays.contains(myLocation)) mapView.overlays.add(myLocation)
            myLocation.enableMyLocation()
            mapView.invalidate()
        }
    }

    // markers follow the pins (the list refreshes every 10 s; a delivered order disappears, a new one appears)
    val markers = remember { ArrayList<Pair<Marker, Rules.MapPin>>() }
    var fitted by remember { mutableStateOf(false) }
    val fitAll: () -> Unit = {
        val pts = pins.map { it.lat to it.lng } + (myLocation.myLocation?.let { listOf(it.latitude to it.longitude) } ?: emptyList())
        Rules.mapBounds(pts)?.let { b ->
            if (pts.size == 1) { mapView.controller.setZoom(16.0); mapView.controller.setCenter(GeoPoint(b.centerLat, b.centerLng)) }
            else mapView.zoomToBoundingBox(BoundingBox(b.north, b.east, b.south, b.west), false, dp(context, 24f))
        }
    }
    LaunchedEffect(pins) {
        markers.forEach { mapView.overlays.remove(it.first) }
        markers.clear()
        // the NEXT marker is added last, so it is drawn on top of its neighbours
        for (p in pins.sortedBy { if (it.isNext) 1 else 0 }) {
            val m = Marker(mapView)
            m.position = GeoPoint(p.lat, p.lng)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            m.icon = pinDrawable(context, Rules.pinLabel(p.number, p.approx), p.approx, p.isNext, p.orderId == selectedId)
            m.infoWindow = null
            m.setOnMarkerClickListener { _, _ -> selectedId = p.orderId; true }
            markers.add(m to p)
            mapView.overlays.add(m)
        }
        if (selectedId != null && pins.none { it.orderId == selectedId }) selectedId = null   // the selected order left the list
        if (!fitted && pins.isNotEmpty()) {
            fitted = true
            if (mapView.width > 0) fitAll() else mapView.addOnFirstLayoutListener { _, _, _, _, _ -> fitAll() }
        }
        mapView.invalidate()
    }
    // the selected marker gets a ring; redraw icons when the selection changes
    LaunchedEffect(selectedId) {
        for ((m, p) in markers) m.icon = pinDrawable(context, Rules.pinLabel(p.number, p.approx), p.approx, p.isNext, p.orderId == selectedId)
        mapView.invalidate()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            myLocation.disableMyLocation()
            mapView.onDetach()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            // legend + controls
            Column(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalAlignment = Alignment.End) {
                MapButton("⊞") { fitAll() }
                Spacer(Modifier.height(8.dp))
                MapButton("⌖") {
                    val here = myLocation.myLocation
                    when {
                        !locationGranted -> askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        here == null -> Toast.makeText(context, "Waiting for GPS…", Toast.LENGTH_SHORT).show()
                        else -> { mapView.controller.animateTo(here); if (mapView.zoomLevelDouble < 15.0) mapView.controller.setZoom(15.0) }
                    }
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
            ) {
                Text(
                    "${pins.size} on the map" + (if (pins.any { it.approx }) " · ≈ district only" else "") + (if (nextId != null) " · green = NEXT" else ""),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        if (missing.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("No map point:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(missing, key = { it.id }) { o ->
                        OutlinedButton(onClick = { onOpen(o) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(30.dp)) {
                            Text(o.orderNum, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        if (selected != null) {
            val o = selected
            val n = position[o.id]
            val nav = o.navigateLink
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (n != null) {
                            Surface(color = if (o.id == nextId) Palette.Green else MaterialTheme.colorScheme.primary, shape = CircleShape) {
                                Text("$n", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(o.orderNum, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (o.id == nextId) { Chip("NEXT", Palette.Green, Color.White); Spacer(Modifier.width(8.dp)) }
                        PaymentChip(o)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { selectedId = null }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp)) { Text("✕") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(o.clientName ?: "", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        Rules.placeLine(o.emirate, o.apt) + (if (o.geoSrc == "district") "  ·  ≈ approx. — district centre, no exact pin" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (o.geoSrc == "district") Palette.Yellow else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { onOpen(o) }, modifier = Modifier.weight(1f)) { Text("Open") }
                        Button(
                            onClick = { nav?.let { openLink(context, it) } },
                            enabled = nav != null,
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Green, contentColor = Color.White),
                            modifier = Modifier.weight(1f)
                        ) { Text("🧭  Navigate") }
                    }
                }
            }
        } else {
            Text(
                if (pins.isEmpty()) "No orders with a map point yet" else "Tap a marker to see the order",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
        }
    }
}

@Composable
private fun MapButton(glyph: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), shape = CircleShape, shadowElevation = 2.dp, modifier = Modifier.size(44.dp).tap(onClick)) {
        Box(contentAlignment = Alignment.Center) { Text(glyph, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

/** A round numbered marker with a small tail; orange = exact pin, grey = district only, green = NEXT; a dark ring when selected. */
private fun pinDrawable(context: Context, label: String, approx: Boolean, isNext: Boolean, selected: Boolean): Drawable {
    val d = context.resources.displayMetrics.density
    val r = 15f * d                     // circle radius
    val tail = 9f * d
    val ring = if (selected) 3.5f * d else 2f * d
    val w = ((r + ring) * 2).toInt() + 2
    val h = (r * 2 + ring + tail).toInt() + 2
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f; val cy = r + ring
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when { isNext -> 0xFF2E9E5B.toInt(); approx -> 0xFF8A8F98.toInt(); else -> 0xFFE8872B.toInt() }
        style = Paint.Style.FILL
    }
    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (selected) 0xFF1B1B1B.toInt() else 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = ring }
    // tail
    val path = Path().apply {
        moveTo(cx - r * 0.45f, cy + r * 0.75f); lineTo(cx, cy + r + tail); lineTo(cx + r * 0.45f, cy + r * 0.75f); close()
    }
    c.drawPath(path, fill)
    c.drawCircle(cx, cy, r, fill)
    c.drawCircle(cx, cy, r, edge)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = (if (label.length >= 3) 11f else 13f) * d
    }
    val ty = cy - (text.descent() + text.ascent()) / 2
    c.drawText(label, cx, ty, text)
    return BitmapDrawable(context.resources, bmp)
}

/** The driver's position: a blue dot with a white edge. */
private fun blueDot(context: Context): Bitmap {
    val d = context.resources.displayMetrics.density
    val r = 8f * d; val s = (r * 2 + 4 * d).toInt()
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawCircle(s / 2f, s / 2f, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
    c.drawCircle(s / 2f, s / 2f, r - 2 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F6FB2.toInt() })
    return bmp
}
