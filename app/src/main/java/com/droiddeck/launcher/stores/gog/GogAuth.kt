package com.droiddeck.launcher.stores.gog

import android.content.Context
import android.net.Uri
import android.util.Log
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.stores.StoreNet
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GogAuth {
    private const val TAG = "GogAuth"
    private const val CLIENT_ID = "46899977096215655"
    private const val CLIENT_SECRET = "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9"
    private const val TOKEN_URL = "https://auth.gog.com/token?client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&grant_type=refresh_token&refresh_token="

    const val AUTH_URL = "https://auth.gog.com/auth?client_id=$CLIENT_ID" +
        "&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient&response_type=token&layout=client2"
    const val REDIRECT_PREFIX = "https://embed.gog.com/on_login_success"
    const val GALAXY_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) GOG Galaxy/2.0"
    const val COOKIE_DOMAIN = ".gog.com"
    val SIGN_IN_URLS = listOf("https://login.gog.com/", "https://auth.gog.com/")

    fun authUrl(state: String): String = "$AUTH_URL&state=${Uri.encode(state)}"

    fun isSignedIn(context: Context): Boolean = StoreAccounts.read(context, Store.GOG)?.optString("access_token", "")?.isNotEmpty() == true

    fun saveLogin(context: Context, accessToken: String, refreshToken: String?, userId: String?) = GogComet.replaceAccount {
        saveAccount(context, accessToken, refreshToken, userId)
    }

    @Synchronized
    private fun saveAccount(context: Context, accessToken: String, refreshToken: String?, userId: String?) {
        GogMaintenance.reset()
        var username = ""
        runCatching {
            val body = StoreNet.get("https://embed.gog.com/userData.json", bearer = accessToken, userAgent = GALAXY_UA)
            if (body != null) username = JSONObject(body).optString("username", "")
        }
        val json = JSONObject().put("access_token", accessToken).put("username", username)
            .put("login_time", System.currentTimeMillis() / 1000L).put("expires_in", 3600)
        if (!refreshToken.isNullOrEmpty()) json.put("refresh_token", refreshToken)
        if (!userId.isNullOrEmpty()) json.put("user_id", userId)
        StoreAccounts.write(context, Store.GOG, json)
        Log.i(TAG, "signed in")
    }

    fun storedToken(context: Context): String? = StoreAccounts.read(context, Store.GOG)?.optString("access_token", "")?.ifEmpty { null }

    @JvmStatic
    @Synchronized
    fun validToken(context: Context): String? {
        val json = StoreAccounts.read(context, Store.GOG) ?: return null
        val token = json.optString("access_token", "").ifEmpty { return null }
        val loginTime = json.optLong("login_time", 0L)
        val expiresIn = json.optLong("expires_in", 3600L)
        val now = System.currentTimeMillis() / 1000L
        return if (loginTime == 0L || now >= loginTime + expiresIn - 60) refresh(context) else token
    }

    @JvmStatic
    @Synchronized
    fun refresh(context: Context): String? {
        val json = StoreAccounts.read(context, Store.GOG) ?: return null
        val refreshToken = json.optString("refresh_token", "").ifEmpty { return null }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(TOKEN_URL + refreshToken).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 15_000 }
            if (conn.responseCode != 200) { Log.w(TAG, "refresh: HTTP ${conn.responseCode}"); return null }
            val body = conn.inputStream.bufferedReader().readText()
            val o = JSONObject(body)
            val access = o.optString("access_token", "").ifEmpty { return null }
            json.put("access_token", access)
            o.optString("refresh_token", "").takeIf { it.isNotEmpty() }?.let { json.put("refresh_token", it) }
            json.put("login_time", System.currentTimeMillis() / 1000L).put("expires_in", o.optLong("expires_in", 3600L))
            StoreAccounts.write(context, Store.GOG, json)
            access
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    @Synchronized
    fun cometCredentials(context: Context): JSONObject? {
        val access = validToken(context) ?: return null
        val saved = StoreAccounts.read(context, Store.GOG) ?: return null
        val refresh = saved.optString("refresh_token").ifEmpty { return null }
        var user = saved.optString("user_id")
        var username = saved.optString("username")
        if (!user.matches(Regex("[0-9]+")) || username.isEmpty()) {
            val body = StoreNet.get("https://embed.gog.com/userData.json", bearer = access, userAgent = GALAXY_UA, timeoutMs = 5_000) ?: return null
            val info = runCatching { JSONObject(body) }.getOrNull() ?: return null
            user = info.optString("galaxyUserId", info.optString("userId", user))
            username = info.optString("username", username)
            if (!user.matches(Regex("[0-9]+")) || username.isEmpty()) return null
            saved.put("user_id", user).put("username", username)
            StoreAccounts.write(context, Store.GOG, saved)
        }
        return JSONObject().put("access_token", access).put("refresh_token", refresh)
            .put("user_id", user).put("username", username)
    }

    class GameToken(val accessToken: String, val userId: String)

    fun gameToken(context: Context, clientId: String, clientSecret: String): GameToken? {
        val json = StoreAccounts.read(context, Store.GOG) ?: return null
        val refresh = json.optString("refresh_token", "").ifEmpty { return null }
        var conn: HttpURLConnection? = null
        return try {
            val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
            conn = (URL("https://auth.gog.com/token?client_id=${enc(clientId)}&client_secret=${enc(clientSecret)}&grant_type=refresh_token&refresh_token=${enc(refresh)}")
                .openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 15_000 }
            conn.setRequestProperty("User-Agent", "GOG Galaxy")
            if (conn.responseCode != 200) { Log.w(TAG, "game token: HTTP ${conn.responseCode}"); return null }
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            val access = o.optString("access_token").ifEmpty { return null }
            GameToken(access, o.optString("user_id").ifEmpty { json.optString("user_id") })
        } catch (e: Exception) {
            Log.w(TAG, "game token failed: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    fun isIdentityProviderHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "google.com" || h.endsWith(".google.com") || h == "youtube.com" || h.endsWith(".youtube.com") ||
            h == "facebook.com" || h.endsWith(".facebook.com") || h == "apple.com" || h.endsWith(".apple.com")
    }

    fun isGogHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "gog.com" || h.endsWith(".gog.com")
    }
}
