package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.BuildConfig

object RuntimeAutoUpdate {
    private const val PREFS = "runtime-auto-update"
    const val MAX_ATTEMPTS = 3

    fun due(installed: String?, release: String?, attempts: Int): Boolean =
        installed != null && !release.isNullOrEmpty() && release != installed && attempts < MAX_ATTEMPTS

    fun key(appVersion: Int, release: String) = "$appVersion:$release"

    fun claim(context: Context, installed: String?, release: String?): Boolean {
        if (release.isNullOrEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(BuildConfig.VERSION_CODE, release)
        synchronized(this) {
            val attempts = prefs.getInt(key, 0)
            if (!due(installed, release, attempts)) return false
            val edit = prefs.edit()
            prefs.all.keys.filterNot { it.startsWith("${BuildConfig.VERSION_CODE}:") }.forEach { edit.remove(it) }
            edit.putInt(key, attempts + 1).apply()
            return true
        }
    }
}
