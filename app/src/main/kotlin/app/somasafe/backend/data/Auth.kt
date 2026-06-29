package app.somasafe.backend.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * Token storage for the stateful backend session. The backend issues opaque
 * access + refresh tokens; we keep them (and the username) in an encrypted
 * preferences file. Access tokens are short-lived — [refreshAccess] mints a new
 * one from the refresh token, and the request helpers in ModelDownloader retry
 * once on a 401.
 */
object AuthStore {
    private const val FILE = "somasafe_auth"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_USERNAME = "username"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(context: Context, access: String, refresh: String, username: String) {
        prefs(context).edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun accessToken(context: Context): String? = prefs(context).getString(KEY_ACCESS, null)
    fun refreshToken(context: Context): String? = prefs(context).getString(KEY_REFRESH, null)
    fun username(context: Context): String? = prefs(context).getString(KEY_USERNAME, null)
    fun isLoggedIn(context: Context): Boolean = accessToken(context) != null
}

private fun form(vararg pairs: Pair<String, String>): ByteArray =
    pairs.joinToString("&") { (k, v) ->
        "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
    }.toByteArray()

/** Exchange credentials for a token pair and persist it. */
suspend fun login(context: Context, username: String, password: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("$BACKEND_URL/auth/token").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(form("username" to username, "password" to password)) }

                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED) error("Incorrect username or password")
                if (code != HttpURLConnection.HTTP_OK) error("HTTP $code")
                val body = JSONObject(connection.inputStream.bufferedReader().readText())
                AuthStore.save(
                    context,
                    body.getString("access_token"),
                    body.getString("refresh_token"),
                    username,
                )
            } finally {
                connection.disconnect()
            }
        }
    }

/** Revoke the session server-side (best effort) and drop the local tokens. */
suspend fun logout(context: Context): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val token = AuthStore.accessToken(context)
            if (token != null) {
                val connection = URL("$BACKEND_URL/auth/logout").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.responseCode  // fire the request; ignore the result
                } finally {
                    connection.disconnect()
                }
            }
        }.also { AuthStore.clear(context) }
    }

/**
 * Mint a fresh access token from the stored refresh token, persisting the
 * rotated pair. On failure the session is cleared (the user must sign in again).
 */
suspend fun refreshAccess(context: Context): Result<String> =
    withContext(Dispatchers.IO) {
        runCatching {
            val refresh = AuthStore.refreshToken(context) ?: error("Not signed in")
            val connection = URL("$BACKEND_URL/auth/refresh").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(JSONObject().put("refresh_token", refresh).toString().toByteArray())
                }
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) error("session expired")
                val body = JSONObject(connection.inputStream.bufferedReader().readText())
                val access = body.getString("access_token")
                AuthStore.save(context, access, body.getString("refresh_token"),
                    AuthStore.username(context) ?: "")
                access
            } finally {
                connection.disconnect()
            }
        }.onFailure { AuthStore.clear(context) }
    }
