package com.droiddeck.launcher.stores.amazon

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject

/**
 * The Amazon sign-in in StoreAccounts' private file: the bearer tokens, the device serial and
 * client id the registration was made with (the entitlements call hashes the serial), the expiry.
 */
object AmazonCredentialStore {
    private const val TAG = "AmazonAuth"

    class Credentials(val accessToken: String, val refreshToken: String?, val deviceSerial: String, val clientId: String, val expiresAt: Long)

    @JvmStatic
    fun save(context: Context, accessToken: String, refreshToken: String?, deviceSerial: String, clientId: String, expiresAt: Long, displayName: String?) {
        val json = StoreAccounts.read(context, Store.AMAZON) ?: JSONObject()
        json.put("access_token", accessToken).put("device_serial", deviceSerial).put("client_id", clientId).put("expires_at", expiresAt)
        if (!refreshToken.isNullOrEmpty()) json.put("refresh_token", refreshToken)
        if (!displayName.isNullOrEmpty()) json.put("display_name", displayName)
        StoreAccounts.write(context, Store.AMAZON, json)
    }

    @JvmStatic
    fun load(context: Context): Credentials? {
        val json = StoreAccounts.read(context, Store.AMAZON) ?: return null
        val token = json.optString("access_token", "").ifEmpty { return null }
        return Credentials(token, json.optString("refresh_token", "").ifEmpty { null }, json.optString("device_serial", ""), json.optString("client_id", ""), json.optLong("expires_at", 0L))
    }

    @JvmStatic
    fun isLoggedIn(context: Context): Boolean = load(context) != null

    /** A fresh access token whatever the stored expiry says: for a call Amazon refused with the stored one. */
    @JvmStatic
    fun refresh(context: Context): String? {
        val creds = load(context) ?: return null
        val result = AmazonAuthClient.refreshAccessToken(creds.refreshToken ?: return null) ?: return null
        save(context, result.accessToken, creds.refreshToken, creds.deviceSerial, creds.clientId, System.currentTimeMillis() + result.expiresIn * 1000L, null)
        Log.i(TAG, "token refreshed (forced)")
        return result.accessToken
    }

    /** A valid access token, refreshed within five minutes of expiry; null when signed out or the refresh failed. */
    @JvmStatic
    fun getValidAccessToken(context: Context): String? {
        val creds = load(context) ?: return null
        if (creds.expiresAt - System.currentTimeMillis() < 5L * 60L * 1000L && creds.refreshToken != null) {
            val result = AmazonAuthClient.refreshAccessToken(creds.refreshToken)
            if (result != null) {
                save(context, result.accessToken, creds.refreshToken, creds.deviceSerial, creds.clientId, System.currentTimeMillis() + result.expiresIn * 1000L, null)
                Log.i(TAG, "token refreshed")
                return result.accessToken
            }
            Log.w(TAG, "token refresh failed; using the stored token")
        }
        return creds.accessToken
    }
}
