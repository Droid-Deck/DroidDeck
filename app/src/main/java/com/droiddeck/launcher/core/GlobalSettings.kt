package com.droiddeck.launcher.core

import android.content.Context
import android.net.Uri
import android.provider.Settings

object GlobalSettings {
    // Settings.Global caches values in-process and misses deletions made over adb; ask the provider directly.
    fun read(context: Context, name: String): String? {
        val uri = Uri.withAppendedPath(Settings.Global.CONTENT_URI, name)
        context.contentResolver.query(uri, arrayOf(Settings.NameValueTable.VALUE), null, null, null)?.use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return Settings.Global.getString(context.contentResolver, name)
    }

    fun isOn(context: Context, name: String): Boolean =
        runCatching { read(context, name)?.trim() == "1" }.getOrDefault(false)

    fun developerOptionsOn(context: Context): Boolean = isOn(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)

    // Settings.Global.ADB_WIFI_ENABLED is hidden; the key is stable since Android 11.
    fun wirelessDebuggingOn(context: Context): Boolean = isOn(context, "adb_wifi_enabled")
}
