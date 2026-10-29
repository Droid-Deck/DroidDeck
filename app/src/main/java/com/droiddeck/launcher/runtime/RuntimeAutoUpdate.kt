package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.BuildConfig

object RuntimeAutoUpdate {
    private const val PREFS = "runtime-auto-update"
    const val MAX_ATTEMPTS = 3
    const val RETRY_MS = 10 * 60_000L

    fun due(installed: String?, release: String?, attempts: Int, lastAt: Long = 0L, now: Long = lastAt + RETRY_MS): Boolean =
        installed != null && !release.isNullOrEmpty() && release != installed && attempts < MAX_ATTEMPTS &&
            (attempts == 0 || now - lastAt !in 0 until RETRY_MS)

    fun key(appVersion: Int, release: String) = "$appVersion:$release"

    fun claim(context: Context, installed: String?, release: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (release.isNullOrEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(BuildConfig.VERSION_CODE, release)
        synchronized(this) {
            val attempts = prefs.getInt(key, 0)
            if (!due(installed, release, attempts, prefs.getLong("$key@at", 0L), now)) return false
            val edit = prefs.edit()
            prefs.all.keys.filterNot { it.startsWith("${BuildConfig.VERSION_CODE}:") }.forEach { edit.remove(it) }
            edit.putInt(key, attempts + 1).putLong("$key@at", now).apply()
            return true
        }
    }
}
