package com.droiddeck.launcher.stores.ea

import android.content.Context
import android.net.Uri
import android.util.Log
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object EaAuth {
    private const val TAG = "EaAuth"
    const val CLIENT_ID = "JUNO_PC_CLIENT"
    private const val CLIENT_SECRET = "4mRLtYMb6vq9qglomWEaT4ChxsXWcyqbQpuBNfMPOYOiDmYYQmjuaBsF2Zp0RyVeWkfqhE9TuGgAw7te"
    const val AUTH_URL = "https://accounts.ea.com/connect/auth"
    private const val TOKEN_URL = "https://accounts.ea.com/connect/token"
    const val REDIRECT = "qrc:///html/login_successful.html"
    private const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
    private val refreshLock = Any()
    private val random = SecureRandom()

    private val PC_SIGN_KEYS = mapOf(
        "v1" to "ISa3dpGOc8wW7Adn4auACSQmaccrOyR2".toByteArray(),
        "v2" to "nt5FfJbdPzNcl2pkC3zgjO43Knvscxft".toByteArray(),
    )

    val OPAQUE_SCOPES = listOf(
        "basic.commerce.cartv2", "service.atom", "dp.client.default", "signin", "social_recommendation_user",
        "basic.optin.write", "basic.commerce.cartv2.write", "basic.billing", "external.social_information_ups_admin",
    )

    class Account(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val userId: String,
        val personaId: String,
        val displayName: String,
        val eaId: String,
    )

    class Pending(val url: String, val verifier: String)

    internal fun fromJson(o: JSONObject): Account? {
        val access = o.optString("access_token").ifEmpty { return null }
        return Account(
            accessToken = access,
            refreshToken = o.optString("refresh_token"),
            expiresAt = o.optLong("expires_at"),
            userId = o.optString("user_id"),
            personaId = o.optString("persona_id"),
            displayName = o.optString("display_name"),
            eaId = o.optString("ea_id"),
        )
    }

    fun load(context: Context): Account? = StoreAccounts.read(context.applicationContext, Store.EA)?.let(::fromJson)

    private fun deviceId(context: Context): String {
        val prefs = EaPrefs.get(context)
        prefs.getString("device", null)?.let { return it }
        val made = EaCrypto.hex(ByteArray(16).also(random::nextBytes))
        prefs.edit().putString("device", made).apply()
        return made
    }

    internal fun pcSign(device: String, now: Date = Date(), version: String = if (random.nextBoolean()) "v1" else "v2"): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val mid = EaCrypto.fnv1a(("DroidDeck" + device.uppercase() + "DroidDeck").toByteArray()).toString()
        val json = "{\"av\":\"v1\",\"bsn\":\"None\",\"gid\":0,\"hsn\":\"None\",\"mid\":\"$mid\",\"msn\":\"${device.uppercase()}\",\"sv\":\"$version\",\"ts\":\"$stamp\"}"
        val payload = EaCrypto.base64Url(json.toByteArray())
        val signature = EaCrypto.base64Url(EaCrypto.hmacSha256(PC_SIGN_KEYS.getValue(version), payload.toByteArray()))
        return "$payload.$signature"
    }

    internal fun authUrl(
        device: String,
        responseType: String,
        challenge: String? = null,
        accessToken: String? = null,
        clientId: String = CLIENT_ID,
        scopes: List<String> = emptyList(),
        tokenFormat: String? = null,
        expiresIn: Int? = null,
    ): String {
        val b = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("sbiod_enabled", "false")
            .appendQueryParameter("response_type", responseType)
            .appendQueryParameter("locale", "en_US")
            .appendQueryParameter("pc_sign", pcSign(device))
            .appendQueryParameter("nonce", random.nextInt().toString())
        if (scopes.isNotEmpty()) b.appendQueryParameter("scope", scopes.joinToString(" "))
        if (challenge != null) {
            b.appendQueryParameter("code_challenge_method", "S256")
            b.appendQueryParameter("code_challenge", challenge)
        }
        if (accessToken != null) b.appendQueryParameter("access_token", accessToken)
        if (tokenFormat != null) b.appendQueryParameter("token_format", tokenFormat)
        if (expiresIn != null) b.appendQueryParameter("expires_in", expiresIn.toString())
        return b.build().toString()
    }

    fun begin(context: Context): Pending {
        val verifier = EaCrypto.base64Url(ByteArray(32).also(random::nextBytes))
        val challenge = EaCrypto.base64Url(EaCrypto.sha256(verifier.toByteArray()))
        return Pending(authUrl(deviceId(context), "code", challenge = challenge), verifier)
    }

    /** Pulls one parameter out of the redirect EA sends to its desktop client's local page. */
    internal fun redirectValue(location: String, name: String): String? {
        val normal = location.replace("qrc:///html", "http://127.0.0.1").replace("qrc:/html", "http://127.0.0.1")
        val uri = Uri.parse(normal)
        uri.getQueryParameter(name)?.let { return it }
        val fragment = uri.encodedFragment ?: return null
        return Uri.parse("http://x/?$fragment").getQueryParameter(name)
    }

    private fun tokenCall(values: List<Pair<String, String>>): JSONObject {
        val reply = EaHttp.request(
            TOKEN_URL, "POST",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            EaHttp.form(values + listOf("client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET)).toByteArray(),
            follow = false,
        )
        val json = runCatching { JSONObject(reply.text) }.getOrNull()
        if (!reply.ok || json == null || json.optString("access_token").isEmpty()) {
            throw IllegalStateException("EA token request failed (${reply.status}): ${json?.optString("error_description")?.ifEmpty { json.optString("error") } ?: ""}")
        }
        return json
    }

    fun exchange(context: Context, code: String, verifier: String) {
        val token = tokenCall(
            listOf(
                "grant_type" to "authorization_code", "code" to code, "code_verifier" to verifier,
                "redirect_uri" to REDIRECT, "token_format" to "JWS",
            ),
        )
        val access = token.getString("access_token")
        val player = runCatching { EaApi.player(access) }.onFailure { Log.w(TAG, "player: ${it.message}") }.getOrNull()
        val record = JSONObject()
            .put("access_token", access)
            .put("refresh_token", token.optString("refresh_token"))
            .put("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000)
            .put("user_id", player?.userId.orEmpty())
            .put("persona_id", player?.personaId.orEmpty())
            .put("display_name", player?.displayName.orEmpty())
            .put("ea_id", player?.eaId.orEmpty())
        StoreAccounts.write(context.applicationContext, Store.EA, record)
    }

    private fun fresh(a: Account) = a.expiresAt - System.currentTimeMillis() > REFRESH_MARGIN_MS || a.refreshToken.isEmpty()

    private fun refreshLocked(context: Context, a: Account): String? {
        if (a.refreshToken.isEmpty()) return null
        val token = try {
            tokenCall(listOf("grant_type" to "refresh_token", "refresh_token" to a.refreshToken))
        } catch (e: Exception) {
            Log.w(TAG, "refresh: ${e.message}")
            return null
        }
        val app = context.applicationContext
        val record = StoreAccounts.read(app, Store.EA) ?: return null
        val access = token.getString("access_token")
        record.put("access_token", access)
            .put("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000)
        token.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { record.put("refresh_token", it) }
        if (record.optString("user_id").isEmpty()) runCatching { EaApi.player(access) }.getOrNull()?.let {
            record.put("user_id", it.userId).put("persona_id", it.personaId).put("display_name", it.displayName).put("ea_id", it.eaId)
        }
        StoreAccounts.write(app, Store.EA, record)
        return access
    }

    fun accessToken(context: Context): String? {
        val current = load(context) ?: return null
        if (fresh(current)) return current.accessToken
        synchronized(refreshLock) {
            val again = load(context) ?: return null
            if (fresh(again)) return again.accessToken
            return refreshLocked(context, again) ?: again.accessToken.takeIf { again.expiresAt > System.currentTimeMillis() }
        }
    }

    fun refreshNow(context: Context, stale: String?): String? = synchronized(refreshLock) {
        val current = load(context) ?: return null
        if (stale != null && current.accessToken != stale) return current.accessToken
        refreshLocked(context, current)
    }

    /** Asks EA to mint a code or token for another client from the signed-in session, as the EA app does for games. */
    fun mint(
        context: Context,
        clientId: String,
        responseType: String,
        scopes: List<String> = emptyList(),
        tokenFormat: String? = null,
        expiresIn: Int? = null,
    ): String? {
        val access = accessToken(context) ?: return null
        val url = authUrl(deviceId(context), responseType, accessToken = access, clientId = clientId, scopes = scopes, tokenFormat = tokenFormat, expiresIn = expiresIn)
        val reply = runCatching { EaHttp.request(url, follow = false) }.getOrElse {
            Log.w(TAG, "mint $clientId: ${it.javaClass.simpleName}")
            return null
        }
        val location = reply.header("Location") ?: run {
            Log.w(TAG, "mint $clientId: no redirect (${reply.status})")
            return null
        }
        if (location.startsWith("https://signin.ea.com")) {
            Log.w(TAG, "mint $clientId: the session needs a new sign-in")
            return null
        }
        return redirectValue(location, if (responseType == "token") "access_token" else responseType)
    }

    fun signOut(context: Context) {
        StoreAccounts.clear(context.applicationContext, Store.EA)
    }
}
