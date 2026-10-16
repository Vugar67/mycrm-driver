package center.mycrm.driver.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import center.mycrm.driver.BuildConfig
import center.mycrm.driver.core.Rules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val notes: String?,
    val publishedAt: String?
)

/**
 * Self-update without Google Play: the latest GitHub Release of the app repo is the source of truth.
 * The APK asset must be named mycrm-driver.apk (the CI workflow uploads it under that name).
 */
class UpdateManager(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val currentVersion: String = BuildConfig.VERSION_NAME

    /** Returns the newer release, or null when up to date / no release / network error. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "mycrm-driver/$currentVersion")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val j = JSONObject(resp.body?.string() ?: return@withContext null)
                val tag = j.optString("tag_name", "")
                if (tag.isEmpty() || !Rules.isNewerVersion(tag, currentVersion)) return@withContext null
                val assets = j.optJSONArray("assets")
                var url: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name", "")
                        if (name == ASSET_NAME || (url == null && name.endsWith(".apk"))) {
                            url = a.optString("browser_download_url", "")
                            if (name == ASSET_NAME) break
                        }
                    }
                }
                if (url.isNullOrEmpty()) return@withContext null
                UpdateInfo(
                    version = tag.removePrefix("v"),
                    apkUrl = url,
                    notes = j.optString("body", "").takeIf { it.isNotBlank() },
                    publishedAt = j.optString("published_at", "").takeIf { it.isNotBlank() }
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Android ≥ 8 asks the user once to allow this app to install APKs. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Downloads the APK with the system DownloadManager and returns the download id. */
    fun startDownload(info: UpdateInfo): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "mycrm-driver-${info.version}.apk"
        // remove a stale copy with the same name so the installer does not pick an old file
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { dir ->
            val f = java.io.File(dir, fileName)
            if (f.exists()) f.delete()
        }
        val req = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("MyCRM Driver ${info.version}")
            .setDescription("Downloading update")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        return dm.enqueue(req)
    }

    /**
     * Waits for the download to finish (polling the DownloadManager) and launches the installer.
     * Returns null on success or a short error message.
     */
    suspend fun awaitAndInstall(downloadId: Long, timeoutMs: Long = 10 * 60 * 1000L): String? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val status = withContext(Dispatchers.IO) { queryStatus(dm, downloadId) }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val uri = dm.getUriForDownloadedFile(downloadId) ?: return "Download finished but the file is missing"
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, APK_MIME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    return try {
                        context.startActivity(intent)
                        null
                    } catch (e: Exception) {
                        "Could not open the installer: ${e.message}"
                    }
                }
                DownloadManager.STATUS_FAILED -> return "Download failed"
                -1 -> return "Download was cancelled"
            }
            delay(1000)
        }
        return "Download timed out"
    }

    private fun queryStatus(dm: DownloadManager, id: Long): Int {
        val c = dm.query(DownloadManager.Query().setFilterById(id)) ?: return -1
        c.use { cur ->
            if (!cur.moveToFirst()) return -1
            val idx = cur.getColumnIndex(DownloadManager.COLUMN_STATUS)
            return if (idx >= 0) cur.getInt(idx) else -1
        }
    }

    companion object {
        const val ASSET_NAME = "mycrm-driver.apk"
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}
