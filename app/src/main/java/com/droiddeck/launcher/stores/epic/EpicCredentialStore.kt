package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject

/**
 * The Epic sign-in in StoreAccounts' private file: access and refresh tokens, the account id and
 * display name (the launch arguments carry both), the expiry. Refreshed when within five minutes
 * of expiring.
 */
object EpicCredentialStore {
    private const val TAG = "EpicAuth"

    class Credentials(val accessToken: String, val refreshToken: String?, val accountId: String, val displayName: String, val expiresAt: Long)

    @JvmStatic
    fun save(context: Context, result: EpicAuthClient.TokenResult) {
        val json = JSONObject().put("access_token", result.accessToken).put("account_id", result.accountId ?: "")
            .put("display_name", result.displayName ?: "").put("expires_at", result.expiresAt)
        if (!result.refreshToken.isNullOrEmpty()) json.put("refresh_token", result.refreshToken)
        StoreAccounts.write(context, Store.EPIC, json)
    }

    @JvmStatic
    fun load(context: Context): Credentials? {
        val json = StoreAccounts.read(context, Store.EPIC) ?: return null
        val token = json.optString("access_token", "").ifEmpty { return null }
        return Credentials(token, json.optString("refresh_token", "").ifEmpty { null }, json.optString("account_id", ""), json.optString("display_name", ""), json.optLong("expires_at", 0L))
    }

    @JvmStatic
    fun isLoggedIn(context: Context): Boolean = load(context) != null

    /** A valid access token, refreshed when within five minutes of expiry; null when signed out or the refresh failed. */
    @JvmStatic
    fun getValidAccessToken(context: Context): String? {
        val creds = load(context) ?: return null
        if (creds.expiresAt - System.currentTimeMillis() < 5L * 60L * 1000L && creds.refreshToken != null) {
            val result = EpicAuthClient.refreshToken(creds.refreshToken)
            if (result != null) {
                if (result.refreshToken.isNullOrEmpty()) result.refreshToken = creds.refreshToken
                if (result.accountId.isNullOrEmpty()) result.accountId = creds.accountId
                if (result.displayName.isNullOrEmpty()) result.displayName = creds.displayName
                save(context, result)
                Log.i(TAG, "token refreshed")
                return result.accessToken
            }
            Log.w(TAG, "token refresh failed; using the stored token")
        }
        return creds.accessToken
    }
}
