package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject

object EpicCredentialStore {
    private const val TAG = "EpicCredentials"
    private const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
    private val refreshLock = Any()

    data class Credentials(
        val accessToken: String,
        val refreshToken: String?,
        val accountId: String,
        val displayName: String,
        val expiresAt: Long,
    )

    class TokenResult(
        val accessToken: String,
        val refreshToken: String?,
        val accountId: String?,
        val displayName: String?,
        val expiresAt: Long,
    ) {
        companion object {
            fun from(json: JSONObject): TokenResult? {
                if (json.has("error")) return null
                val access = json.optString("access_token", "").ifEmpty { return null }
                return TokenResult(
                    access,
                    json.optString("refresh_token", "").ifEmpty { null },
                    json.optString("account_id", "").ifEmpty { null },
                    json.optString("display_name", "").ifEmpty { null },
                    json.optLong("expires_at", 0L),
                )
            }
        }
    }

    internal fun fromJson(o: JSONObject): Credentials? {
        val access = o.optString("access_token", "").ifEmpty { return null }
        return Credentials(
            accessToken = access,
            refreshToken = o.optString("refresh_token", "").ifEmpty { null },
            accountId = o.optString("account_id", ""),
            displayName = o.optString("display_name", ""),
            expiresAt = o.optLong("expires_at", 0L),
        )
    }

    internal fun toJson(c: Credentials): JSONObject = JSONObject().apply {
        put("access_token", c.accessToken)
        c.refreshToken?.takeIf { it.isNotEmpty() }?.let { put("refresh_token", it) }
        put("account_id", c.accountId)
        put("display_name", c.displayName)
        put("expires_at", c.expiresAt)
    }

    @JvmStatic
    fun load(context: Context): Credentials? = StoreAccounts.read(context.applicationContext, Store.EPIC)?.let(::fromJson)

    fun save(context: Context, result: TokenResult, merge: Boolean = true) {
        val before = if (merge) load(context) else null
        val next = Credentials(
            accessToken = result.accessToken,
            refreshToken = result.refreshToken ?: before?.refreshToken,
            accountId = result.accountId ?: before?.accountId.orEmpty(),
            displayName = result.displayName ?: before?.displayName.orEmpty(),
            expiresAt = result.expiresAt,
        )
        StoreAccounts.write(context.applicationContext, Store.EPIC, toJson(next))
    }

    fun isLoggedIn(context: Context): Boolean = load(context) != null

    fun expired(context: Context): Boolean {
        val c = load(context) ?: return false
        return c.expiresAt > 0 && c.expiresAt < System.currentTimeMillis()
    }

    private fun fresh(c: Credentials): Boolean =
        c.expiresAt - System.currentTimeMillis() > REFRESH_MARGIN_MS || c.refreshToken.isNullOrEmpty()

    private fun unexpired(c: Credentials): String? =
        c.accessToken.takeIf { c.expiresAt <= 0 || c.expiresAt > System.currentTimeMillis() }

    @JvmStatic
    fun getValidAccessToken(context: Context): String? {
        val current = load(context) ?: return null
        if (fresh(current)) return current.accessToken
        synchronized(refreshLock) {
            val again = load(context) ?: return null
            if (fresh(again)) return again.accessToken
            val answer = EpicNative.call("auth.refresh", JSONObject().put("refreshToken", again.refreshToken))
            val result = answer?.let(TokenResult::from)
            if (result == null) {
                Log.w(TAG, "token refresh failed: ${answer?.optString("error").orEmpty().ifEmpty { "no answer" }}")
                return unexpired(again)
            }
            save(context, result)
            return result.accessToken
        }
    }
}
