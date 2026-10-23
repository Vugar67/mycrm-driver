package center.mycrm.driver.pod

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.ExifInterface
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import center.mycrm.driver.core.Rules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.max

/** GPS fix at the moment of the photo. */
data class Fix(val lat: Double, val lng: Double, val accuracyM: Float?, val atMs: Long)

/** Proof-of-delivery helpers: camera file, stamping/compression, one-shot location. */
object Pod {

    const val MAX_SIDE = 1280
    const val JPEG_QUALITY = 75

    /** Reasons offered when the driver cannot take the photo at Delivered. */
    val SKIP_REASONS: List<String> = listOf(
        "Customer refused photo",
        "Handed over in person",
        "Camera not working",
        "Other…"
    )

    private fun podDir(context: Context): File = File(context.cacheDir, "pod").apply { mkdirs() }

    /** A fresh file for the camera app to write into, and its content:// uri. */
    fun newCaptureTarget(context: Context): Pair<File, android.net.Uri> {
        val f = File(podDir(context), "capture-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        return f to uri
    }

    fun newProcessedFile(context: Context): File = File(podDir(context), "pod-${System.currentTimeMillis()}.jpg")

    /** Drop leftovers older than 2 days (failed uploads are retried within the session). */
    fun cleanup(context: Context) {
        val cutoff = System.currentTimeMillis() - 2L * 24 * 3600 * 1000
        podDir(context).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /** Lines printed on the photo (pure logic in Rules, tested there). */
    fun stampLines(orderNum: String, driver: String, takenAtMs: Long, fix: Fix?): List<String> =
        Rules.podStampLines(orderNum, driver, takenAtMs, fix?.lat, fix?.lng, fix?.accuracyM)

    /**
     * Camera file → upright, max 1280 px, stamped, JPEG q75. Runs off the main thread.
     * Returns the processed file or throws with a short message.
     */
    suspend fun process(src: File, out: File, lines: List<String>): File = withContext(Dispatchers.IO) {
        if (!src.exists() || src.length() == 0L) throw IllegalStateException("The camera returned no photo")
        // 1. bounds → sample size
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) throw IllegalStateException("Could not read the photo")
        var sample = 1
        while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("Could not read the photo")
        // 2. EXIF rotation
        val rotation = try {
            when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }
        // 3. scale to MAX_SIDE + rotate in one matrix
        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height).toFloat()
        val m = Matrix()
        if (scale < 1f) m.postScale(scale, scale)
        if (rotation != 0f) m.postRotate(rotation)
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (upright !== decoded) decoded.recycle()
        val bmp = if (upright.isMutable) upright else upright.copy(Bitmap.Config.ARGB_8888, true).also { upright.recycle() }
        // 4. stamp: dark band at the bottom, white text
        val canvas = Canvas(bmp)
        val textSize = max(18f, bmp.width * 0.034f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val lineH = textSize * 1.3f
        val pad = textSize * 0.6f
        val bandH = pad * 2 + lineH * lines.size
        val band = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        canvas.drawRect(0f, bmp.height - bandH, bmp.width.toFloat(), bmp.height.toFloat(), band)
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, pad, bmp.height - bandH + pad + lineH * (i + 1) - lineH * 0.25f, paint)
        }
        // 5. JPEG
        FileOutputStream(out).use { os -> bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os) }
        bmp.recycle()
        src.delete()
        out
    }

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** One fix within [timeoutMs]; falls back to a last known fix younger than 2 minutes; null without permission. */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(context: Context, timeoutMs: Long = 10_000L): Fix? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val live: Location? = if (providers.isEmpty()) null else withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        // first NETWORK fix may be coarse; accept it only if it is reasonably accurate
                        if (location.provider == LocationManager.GPS_PROVIDER || (location.hasAccuracy() && location.accuracy <= 100f)) {
                            runCatching { lm.removeUpdates(this) }
                            if (cont.isActive) cont.resume(location)
                        }
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("required on Android < 10")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                providers.forEach { p ->
                    runCatching { lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper()) }
                }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            }
        }
        val loc = live ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 2 * 60 * 1000 }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
        return loc?.let { Fix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null, it.time) }
    }

    /** Storage object name: <order uuid>/[prefix]<yyyyMMdd-HHmmss>-<4 hex>.jpg (the bucket policy checks this shape). */
    fun objectName(orderId: String, takenAtMs: Long, prefix: String = ""): String =
        Rules.podObjectName(orderId, takenAtMs, (Math.random() * 0xFFFF).toInt(), prefix)

    /** v1.7: Link on delivery — why there is no photo of the client's payment confirmation. */
    val PAY_SKIP_REASONS: List<String> = listOf(
        "Client paid earlier",
        "Client refused to show the screen",
        "Camera not working",
        "Other…"
    )

    /** Stamp of the payment screen photo: a title line, then the usual order / time / GPS lines. */
    fun paymentStampLines(orderNum: String, driver: String, takenAtMs: Long, fix: Fix?): List<String> =
        listOf("PAYMENT CONFIRMATION") + stampLines(orderNum, driver, takenAtMs, fix)
}
