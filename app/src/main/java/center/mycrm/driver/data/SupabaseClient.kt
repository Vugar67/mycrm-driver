package center.mycrm.driver.data

import android.content.Context
import android.content.SharedPreferences
import center.mycrm.driver.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

/** PostgREST / GoTrue error with the server's message (shown to the driver as is). */
class SupabaseException(val status: Int, message: String) : Exception(message)

/** The refresh token is gone or rejected — the app must show the login screen. */
class AuthRequiredException : Exception("Session expired — please sign in again")

/**
 * Minimal Supabase client over plain HTTP (Auth password grant + refresh, PostgREST select / rpc / insert).
 * Mirrors the hand-written client in the MyCRM core instead of pulling supabase-kt.
 */
class SupabaseClient(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mycrm_driver_auth", Context.MODE_PRIVATE)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private val refreshMutex = Mutex()

    val baseUrl: String = BuildConfig.SUPABASE_URL
    private val apiKey: String = BuildConfig.SUPABASE_KEY

    val isSignedIn: Boolean get() = !prefs.getString(K_REFRESH, null).isNullOrEmpty()
    val userId: String? get() = prefs.getString(K_UID, null)
    val userEmail: String? get() = prefs.getString(K_EMAIL, null)

    // ---- Auth -------------------------------------------------------------------------------

    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val req = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=password")
            .post(body.toString().toRequestBody(JSON))
            .header("apikey", apiKey)
            .header("Content-Type", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw SupabaseException(resp.code, authError(text, resp.code))
            saveSession(JSONObject(text))
        }
    }

    fun signOut() {
        prefs.edit().clear().apply()
    }

    private fun saveSession(j: JSONObject) {
        val access = j.optString("access_token", "")
        val refresh = j.optString("refresh_token", "")
        val expiresIn = j.optLong("expires_in", 3600L)
        val user = j.optJSONObject("user")
        val e = prefs.edit()
            .putString(K_ACCESS, access)
            .putString(K_REFRESH, refresh)
            .putLong(K_EXPIRES, System.currentTimeMillis() / 1000 + expiresIn - 30)
        if (user != null) {
            e.putString(K_UID, user.optString("id", prefs.getString(K_UID, "") ?: ""))
            e.putString(K_EMAIL, user.optString("email", prefs.getString(K_EMAIL, "") ?: ""))
        }
        e.apply()
    }

    /** A valid access token, refreshed when it is about to expire. */
    private suspend fun ensureToken(force: Boolean = false): String {
        val now = System.currentTimeMillis() / 1000
        val access = prefs.getString(K_ACCESS, null)
        val exp = prefs.getLong(K_EXPIRES, 0L)
        if (!force && !access.isNullOrEmpty() && exp > now) return access
        return refreshToken(staleToken = access)
    }

    private suspend fun refreshToken(staleToken: String?): String = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            // Another call may have refreshed while we waited for the lock.
            val current = prefs.getString(K_ACCESS, null)
            val exp = prefs.getLong(K_EXPIRES, 0L)
            if (!current.isNullOrEmpty() && current != staleToken && exp > System.currentTimeMillis() / 1000) {
                return@withLock current
            }
            val refresh = prefs.getString(K_REFRESH, null)
            if (refresh.isNullOrEmpty()) throw AuthRequiredException()
            val body = JSONObject().put("refresh_token", refresh)
            val req = Request.Builder()
                .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
                .post(body.toString().toRequestBody(JSON))
                .header("apikey", apiKey)
                .header("Content-Type", "application/json")
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.code in 400..499) {
                    signOut()
                    throw AuthRequiredException()
                }
                if (!resp.isSuccessful) throw SupabaseException(resp.code, "Auth server error ${resp.code}")
                saveSession(JSONObject(text))
                prefs.getString(K_ACCESS, null) ?: throw AuthRequiredException()
            }
        }
    }

    // ---- PostgREST --------------------------------------------------------------------------

    fun restUrl(path: String): HttpUrl.Builder =
        "$baseUrl/rest/v1/".toHttpUrl().newBuilder().addPathSegments(path)

    /** GET /rest/v1/{table}?... → JSON array. */
    suspend fun select(table: String, params: Map<String, String>): JSONArray {
        val b = restUrl(table)
        for ((k, v) in params) b.addQueryParameter(k, v)
        val url = b.build()
        val text = exec { token ->
            Request.Builder().url(url).get().apply { authHeaders(token) }.build()
        }
        return parseArray(text)
    }

    /** POST /rest/v1/rpc/{fn} → whatever the function returns (JSONObject, JSONArray, primitive or null). */
    suspend fun rpc(fn: String, args: JSONObject): Any? {
        val url = restUrl("rpc/$fn").build()
        val text = exec { token ->
            Request.Builder().url(url)
                .post(args.toString().toRequestBody(JSON))
                .apply { authHeaders(token) }
                .header("Content-Type", "application/json")
                .build()
        }
        if (text.isBlank()) return null
        return try {
            JSONTokener(text).nextValue()
        } catch (_: Exception) {
            text
        }
    }

    /** POST /rest/v1/{table} with one row; no representation requested. */
    suspend fun insert(table: String, row: JSONObject) {
        val url = restUrl(table).build()
        exec { token ->
            Request.Builder().url(url)
                .post(row.toString().toRequestBody(JSON))
                .apply { authHeaders(token) }
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .build()
        }
    }

    private fun Request.Builder.authHeaders(token: String) {
        header("apikey", apiKey)
        header("Authorization", "Bearer $token")
        header("Accept", "application/json")
    }

    /** Executes with the current token; on 401 refreshes once and retries. */
    private suspend fun exec(build: (String) -> Request): String = withContext(Dispatchers.IO) {
        var token = ensureToken()
        var result: String? = null
        var attempt = 0
        while (result == null) {
            attempt++
            http.newCall(build(token)).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.code == 401 && attempt == 1) {
                    token = ensureToken(force = true)
                } else if (!resp.isSuccessful) {
                    throw SupabaseException(resp.code, postgrestMessage(text, resp.code))
                } else {
                    result = text
                }
            }
        }
        result ?: ""
    }

    private fun parseArray(text: String): JSONArray {
        if (text.isBlank()) return JSONArray()
        return try {
            JSONArray(text)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun postgrestMessage(text: String, code: Int): String {
        return try {
            val j = JSONObject(text)
            val msg = j.optString("message", "")
            val details = j.optString("details", "")
            val hint = j.optString("hint", "")
            when {
                msg.isNotEmpty() -> msg
                details.isNotEmpty() -> details
                hint.isNotEmpty() -> hint
                else -> "Server error $code"
            }
        } catch (_: Exception) {
            if (text.isNotBlank()) text.take(200) else "Server error $code"
        }
    }

    private fun authError(text: String, code: Int): String {
        return try {
            val j = JSONObject(text)
            val m = listOf("msg", "error_description", "message", "error")
                .map { j.optString(it, "") }
                .firstOrNull { it.isNotEmpty() }
            m ?: "Sign-in failed ($code)"
        } catch (_: Exception) {
            "Sign-in failed ($code)"
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val K_ACCESS = "access_token"
        private const val K_REFRESH = "refresh_token"
        private const val K_EXPIRES = "expires_at"
        private const val K_UID = "user_id"
        private const val K_EMAIL = "user_email"
    }
}
