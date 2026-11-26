package com.droiddeck.launcher.stores.steam

import android.content.Context
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject

internal data class SteamWebSession(
    val steamId: String,
    val loginSecure: String,
    val sessionId: String,
) {
    val cookieHeader: String
        get() = "steamLoginSecure=$loginSecure; sessionid=$sessionId"
}

internal object SteamAccount {
    fun fromCookieHeader(header: String?): SteamWebSession? {
        val cookies = header.orEmpty().split(';').mapNotNull { field ->
            val name = field.substringBefore('=').trim()
            val value = field.substringAfter('=', "").trim()
            name.takeIf { it.isNotEmpty() }?.let { it to value }
        }.toMap()
        val loginSecure = cookies["steamLoginSecure"]?.takeIf { it.isNotBlank() } ?: return null
        val steamId = Regex("^(\\d{15,20})(?:\\|\\||%7[Cc]%7[Cc])").find(loginSecure)?.groupValues?.get(1) ?: return null
        return SteamWebSession(steamId, loginSecure, cookies["sessionid"].orEmpty())
    }

    fun read(context: Context): SteamWebSession? {
        val record = StoreAccounts.read(context.applicationContext, Store.STEAM) ?: return null
        val id = record.optString("steam_id").takeIf { it.isNotBlank() } ?: return null
        val loginSecure = record.optString("steam_login_secure").takeIf { it.isNotBlank() } ?: return null
        return SteamWebSession(id, loginSecure, record.optString("session_id"))
    }

    fun save(context: Context, session: SteamWebSession) {
        StoreAccounts.write(
            context.applicationContext,
            Store.STEAM,
            JSONObject()
                .put("steam_id", session.steamId)
                .put("display_name", "Steam · ${session.steamId.takeLast(4)}")
                .put("steam_login_secure", session.loginSecure)
                .put("session_id", session.sessionId),
        )
    }

    fun clear(context: Context) = StoreAccounts.clear(context.applicationContext, Store.STEAM)
}
