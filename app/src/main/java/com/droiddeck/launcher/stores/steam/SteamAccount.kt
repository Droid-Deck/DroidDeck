package com.droiddeck.launcher.stores.steam

import android.content.Context
import com.droiddeck.launcher.session.WebCookie
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
    fun fromWebCookies(cookies: List<WebCookie>): SteamWebSession? {
        val wanted = setOf("steamLoginSecure", "sessionid")
        val values = LinkedHashMap<String, String>()
        cookies.sortedByDescending { it.host.contains("steampowered.com", ignoreCase = true) }.forEach { cookie ->
            val pair = cookie.header.substringBefore(';').split('=', limit = 2)
            if (pair.size == 2 && pair[0] in wanted) values.putIfAbsent(pair[0], pair[1])
        }
        return fromCookieHeader(values.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

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
