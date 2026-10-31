package com.droiddeck.launcher.stores.gamejolt

import android.content.Context
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject

internal object GameJoltAuth {
    fun cookies(context: Context): String = StoreAccounts.read(context.applicationContext, Store.GAMEJOLT)?.optString("access_token").orEmpty()

    fun save(context: Context, cookies: String, user: JSONObject) {
        val name = user.optString("display_name").ifBlank { user.optString("username") }
        StoreAccounts.write(
            context.applicationContext, Store.GAMEJOLT,
            JSONObject().put("access_token", cookies).put("user_id", user.optLong("id").toString()).put("username", user.optString("username")).put("display_name", name),
        )
    }

    fun userId(context: Context): String = StoreAccounts.read(context.applicationContext, Store.GAMEJOLT)?.optString("user_id").orEmpty()

    fun clear(context: Context) = StoreAccounts.clear(context.applicationContext, Store.GAMEJOLT)
}
