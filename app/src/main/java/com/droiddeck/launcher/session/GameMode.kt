package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.WirelessAdbFix
import java.util.concurrent.atomic.AtomicBoolean

/** Applies the chosen Android game mode to DroidDeck over the saved Wireless debugging pairing. */
object GameMode {
    private const val TAG = "GameMode"

    private val applying = AtomicBoolean(false)

    internal fun command(mode: String, packageName: String): String = "cmd game mode $mode $packageName"

    fun apply(context: Context) {
        val mode = SessionPrefs.gameMode(context)
        if (mode == SessionPrefs.GAME_MODE_SYSTEM) return
        if (!WirelessAdbFix.hasSavedPairing(context)) {
            Log.w(TAG, "no saved Wireless debugging pairing; game mode $mode not applied")
            return
        }
        if (!applying.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            try {
                WirelessAdbFix.shell(app, command(mode, app.packageName))
            } catch (t: Throwable) {
                Log.w(TAG, "game mode $mode", t)
            } finally {
                applying.set(false)
            }
        }, "game-mode").start()
    }
}
