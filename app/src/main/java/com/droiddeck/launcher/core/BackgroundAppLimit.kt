package com.droiddeck.launcher.core

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

object BackgroundAppLimit {
    private const val TAG = "BackgroundAppLimit"
    const val OFF = -1
    val CHOICES = listOf(OFF, 4, 1, 0)
    private const val KEY = "max_cached_processes"
    private const val STATE = "background-app-limit"
    private const val APPLIED = "applied"
    private const val PREVIOUS = "previous"

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "background-app-limit").apply { isDaemon = true } }

    internal fun readCommand() = "device_config get activity_manager $KEY"
    internal fun setCommand(limit: Int): String {
        require(limit in 0..1024)
        return "device_config put activity_manager $KEY $limit"
    }
    internal fun restoreCommand(previous: String?) =
        if (previous == null) "device_config delete activity_manager $KEY" else "device_config put activity_manager $KEY $previous"

    internal fun parse(output: String): String? = output.trim().takeIf { it.toIntOrNull() != null }

    private fun state(context: Context) = context.applicationContext.getSharedPreferences(STATE, Context.MODE_PRIVATE)

    fun applied(context: Context): Boolean = state(context).getBoolean(APPLIED, false)

    fun applyAsync(context: Context, limit: Int) {
        val app = context.applicationContext
        worker.execute {
            runCatching {
                if (!applied(app)) {
                    val previous = parse(WirelessAdbFix.shell(app, readCommand()))
                    check(state(app).edit().putBoolean(APPLIED, true).putString(PREVIOUS, previous).commit()) { "previous value not saved" }
                }
                WirelessAdbFix.shell(app, setCommand(limit))
            }.onSuccess { Log.i(TAG, "at most $limit cached app(s) while the session runs") }
                .onFailure { Log.w(TAG, "limit not set: ${it.message}") }
        }
    }

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
