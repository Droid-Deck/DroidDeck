package com.droiddeck.launcher.stores.itch

import android.content.Context
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject

internal object ItchAuth {
    private val TOKEN = Regex("[A-Za-z0-9]{40,64}")

    fun key(context: Context): String = StoreAccounts.read(context, Store.ITCH)?.optString("access_token").orEmpty()

    fun name(context: Context): String = StoreAccounts.signedInAs(context, Store.ITCH).orEmpty()

    fun verify(key: String): JSONObject? = ItchApi.profile(key)

    fun save(context: Context, key: String, user: JSONObject) {
        StoreAccounts.write(
            context,
            Store.ITCH,
            JSONObject()
                .put("access_token", key)
                .put("username", user.optString("username"))
                .put("display_name", user.optString("display_name").ifBlank { user.optString("displayName") }.ifBlank { user.optString("username") })
                .put("user_id", user.optLong("id")),
        )
    }

    fun candidates(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        text.lineSequence().map { it.trim() }.filter { TOKEN.matches(it) }.forEach { out.add(it) }
        TOKEN.findAll(text).forEach { out.add(it.value) }
        return out.take(8)
    }
}
