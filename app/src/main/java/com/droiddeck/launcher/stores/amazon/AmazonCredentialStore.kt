package com.droiddeck.launcher.stores.amazon

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject

object AmazonCredentialStore {
    private const val TAG = "AmazonCredentials"
    private const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
    private val refreshLock = Any()

    const val ACCESS_TOKEN = "access_token"
    const val REFRESH_TOKEN = "refresh_token"
    const val DEVICE_SERIAL = "device_serial"
    const val CLIENT_ID = "client_id"
    const val EXPIRES_AT = "expires_at"
    const val DISPLAY_NAME = "display_name"

    class Account(
        val accessToken: String,
        val refreshToken: String,
        val deviceSerial: String,
        val clientId: String,
        val expiresAt: Long,
        val displayName: String,
    )

    internal fun fromJson(o: JSONObject): Account? {
        val access = o.optString(ACCESS_TOKEN, "").ifEmpty { return null }
        return Account(
            accessToken = access,
            refreshToken = o.optString(REFRESH_TOKEN, ""),
            deviceSerial = o.optString(DEVICE_SERIAL, ""),
            clientId = o.optString(CLIENT_ID, ""),
            expiresAt = o.optLong(EXPIRES_AT, 0L),
            displayName = o.optString(DISPLAY_NAME, ""),
        )
    }

    @JvmStatic
    fun load(context: Context): Account? = StoreAccounts.read(context.applicationContext, Store.AMAZON)?.let(::fromJson)

    fun isLoggedIn(context: Context): Boolean = load(context) != null

    internal fun saveSignIn(context: Context, answer: JSONObject) {
        val access = answer.optString(ACCESS_TOKEN, "")
        require(access.isNotEmpty()) { "no access token" }
        val record = JSONObject()
            .put(ACCESS_TOKEN, access)
            .put(DEVICE_SERIAL, answer.optString(DEVICE_SERIAL, ""))
            .put(CLIENT_ID, answer.optString(CLIENT_ID, ""))
            .put(EXPIRES_AT, answer.optLong(EXPIRES_AT, 0L))
        answer.optString(REFRESH_TOKEN, "").takeIf { it.isNotEmpty() }?.let { record.put(REFRESH_TOKEN, it) }
        answer.optString(DISPLAY_NAME, "").takeIf { it.isNotEmpty() }?.let { record.put(DISPLAY_NAME, it) }
        StoreAccounts.write(context.applicationContext, Store.AMAZON, record)
    }

    private fun saveRefreshed(context: Context, before: Account, access: String, expiresAt: Long) {
        val app = context.applicationContext
        val record = StoreAccounts.read(app, Store.AMAZON) ?: return
        record.put(ACCESS_TOKEN, access)
            .put(DEVICE_SERIAL, before.deviceSerial)
            .put(CLIENT_ID, before.clientId)
            .put(EXPIRES_AT, expiresAt)
        StoreAccounts.write(app, Store.AMAZON, record)
    }

    private fun fresh(a: Account): Boolean =
        a.expiresAt - System.currentTimeMillis() > REFRESH_MARGIN_MS || a.refreshToken.isEmpty()

    private fun unexpired(a: Account): String? =
        a.accessToken.takeIf { a.expiresAt <= 0 || a.expiresAt > System.currentTimeMillis() }

    private fun refreshLocked(context: Context, a: Account): String? {
        if (a.refreshToken.isEmpty()) return null
        val answer = AmazonNative.call("auth.refresh", JSONObject().put("refreshToken", a.refreshToken))
        val access = answer?.takeIf { !it.has("error") }?.optString(ACCESS_TOKEN, "").orEmpty()
        if (access.isEmpty()) {
            Log.w(TAG, "token refresh failed: ${answer?.optString("error").orEmpty().ifEmpty { "no answer" }}")
            return null
        }
        val expiresAt = answer!!.optLong(EXPIRES_AT, System.currentTimeMillis() + 3_600_000L)
        saveRefreshed(context, a, access, expiresAt)
        return access
    }

    @JvmStatic
    fun getValidAccessToken(context: Context): String? {
        val current = load(context) ?: return null
        if (fresh(current)) return current.accessToken
        synchronized(refreshLock) {
            val again = load(context) ?: return null
            if (fresh(again)) return again.accessToken
            return refreshLocked(context, again) ?: unexpired(again)
        }
    }

    fun refreshNow(context: Context, staleToken: String?): String? {
        synchronized(refreshLock) {
            val current = load(context) ?: return null
            if (staleToken != null && current.accessToken != staleToken) return current.accessToken
            return refreshLocked(context, current)
        }
    }
}
