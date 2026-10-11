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

/**
 * The GOG sign-in: the token the OAuth page hands back, kept in StoreAccounts' private file,
 * refreshed through auth.gog.com when it has expired (the client id and secret are GOG Galaxy's
 * own, public ones, as every third-party GOG client uses). Nothing here is ever logged: the token
 * endpoint's URL itself carries the secret and the refresh token.
 */
object GogAuth {
    private const val TAG = "GogAuth"
    private const val CLIENT_ID = "46899977096215655"
    private const val CLIENT_SECRET = "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9"
    private const val TOKEN_URL = "https://auth.gog.com/token?client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&grant_type=refresh_token&refresh_token="

    /** The login page, as GOG Galaxy opens it: an implicit grant that returns the token in the redirect's fragment. */
    const val AUTH_URL = "https://auth.gog.com/auth?client_id=$CLIENT_ID" +
        "&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient&response_type=token&layout=client2"
    const val REDIRECT_PREFIX = "https://embed.gog.com/on_login_success"
    const val GALAXY_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) GOG Galaxy/2.0"

    fun authUrl(state: String): String = "$AUTH_URL&state=${Uri.encode(state)}"

    fun isSignedIn(context: Context): Boolean = StoreAccounts.read(context, Store.GOG)?.optString("access_token", "")?.isNotEmpty() == true

    /** Saves a fresh sign-in and looks the account's name up for the chip. Blocking. */
    fun saveLogin(context: Context, accessToken: String, refreshToken: String?, userId: String?) {
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

    /** The raw stored token, expired or not; null when signed out. */
    fun storedToken(context: Context): String? = StoreAccounts.read(context, Store.GOG)?.optString("access_token", "")?.ifEmpty { null }

    /** A usable access token, refreshed when the recorded login time says it has expired; null when signed out or the refresh failed. */
    @JvmStatic
    fun validToken(context: Context): String? {
        val json = StoreAccounts.read(context, Store.GOG) ?: return null
        val token = json.optString("access_token", "").ifEmpty { return null }
        val loginTime = json.optLong("login_time", 0L)
        val expiresIn = json.optLong("expires_in", 3600L)
        val now = System.currentTimeMillis() / 1000L
        return if (loginTime == 0L || now >= loginTime + expiresIn - 60) refresh(context) else token
    }

    /** Refreshes the access token; the new one, or null on any failure. Blocking. */
    @JvmStatic
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
            // Not the throwable: HttpURLConnection's messages echo the URL, which embeds the secrets.
            Log.w(TAG, "refresh failed: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** Hosts the GOG login page hands off to for a social sign-in; they refuse an embedded browser's identity. */
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
