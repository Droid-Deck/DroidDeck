package com.droiddeck.launcher.core

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Setup > Diagnostics' background-app limit: while a session runs, Android keeps at most a few
 * apps cached - ActivityManager's max_cached_processes, set over the Wireless debugging pairing,
 * what Developer options' Background process limit sets - and closes the rest at once, so the game
 * has their memory (1.2 GB on a Galaxy Z Fold7 at four). A device_config value outlives a reboot,
 * where that switch does not: what was there is kept before the limit goes on and put back when
 * the session ends, or, after a session Android killed, when the app next starts without one.
 */
object BackgroundAppLimit {
    private const val TAG = "BackgroundAppLimit"
    const val OFF = -1
    /** The choices Setup offers: off, then Developer options' own at most 4, at most 1 and none. */
    val CHOICES = listOf(OFF, 4, 1, 0)
    private const val KEY = "max_cached_processes"
    private const val STATE = "background-app-limit"
    private const val APPLIED = "applied"
    private const val PREVIOUS = "previous"

    /** One at a time, in order: a session's end puts the value back before the next one sets it. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "background-app-limit").apply { isDaemon = true } }

    internal fun readCommand() = "device_config get activity_manager $KEY"
    internal fun setCommand(limit: Int): String {
        require(limit in 0..1024)
        return "device_config put activity_manager $KEY $limit"
    }
    internal fun restoreCommand(previous: String?) =
        if (previous == null) "device_config delete activity_manager $KEY" else "device_config put activity_manager $KEY $previous"

    /** device_config get's answer: the value there was, or null for none. */
    internal fun parse(output: String): String? = output.trim().takeIf { it.toIntOrNull() != null }

    private fun state(context: Context) = context.applicationContext.getSharedPreferences(STATE, Context.MODE_PRIVATE)

    /** Whether a limit of the app's is on and not yet put back. */
    fun applied(context: Context): Boolean = state(context).getBoolean(APPLIED, false)

    /** Sets [limit], keeping what was there first unless a limit of the app's is on already. */
    fun applyAsync(context: Context, limit: Int) {
        val app = context.applicationContext
        worker.execute {
            runCatching {
                if (!applied(app)) {
                    val previous = parse(WirelessAdbFix.shell(app, readCommand()))
                    state(app).edit().putBoolean(APPLIED, true).putString(PREVIOUS, previous).commit()
                }
                WirelessAdbFix.shell(app, setCommand(limit))
            }.onSuccess { Log.i(TAG, "at most $limit cached app(s) while the session runs") }
                .onFailure { Log.w(TAG, "limit not set: ${it.message}") }
        }
    }

    /** Puts back what was there before the app's limit, when one is on; nothing otherwise. */
    fun restoreAsync(context: Context) {
        val app = context.applicationContext
        worker.execute {
            if (!applied(app)) return@execute
            val previous = state(app).getString(PREVIOUS, null)
            runCatching { WirelessAdbFix.shell(app, restoreCommand(previous)) }
                .onSuccess {
                    state(app).edit().putBoolean(APPLIED, false).remove(PREVIOUS).commit()
                    Log.i(TAG, "cached-app limit put back (${previous ?: "Android's own"})")
                }
                .onFailure { Log.w(TAG, "limit not put back yet: ${it.message}") }
        }
    }
}
