package com.droiddeck.launcher.session

import android.app.Activity
import android.app.GameManager
import android.app.GameState
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display

object PerfMode {
    private const val TAG = "PerfMode"

    fun apply(a: Activity, fpsLimit: Int = 0, refreshLimit: Int = 0): String {
        val parts = ArrayList<String>(2)

        parts += try {
            val display = if (Build.VERSION.SDK_INT >= 30) a.display else @Suppress("DEPRECATION") a.windowManager.defaultDisplay
            if (display == null || Build.VERSION.SDK_INT < 23) "display mode unchanged" else {
                val cur = display.mode
                val cap = minOf(peakRefreshCap(a), refreshLimit.takeIf { it > 0 }?.toFloat() ?: Float.POSITIVE_INFINITY)
                val best = selectMode(display.supportedModes, cur, fpsLimit, cap)
                if (best != null && a.window.attributes.preferredDisplayModeId != best.modeId) {
                    a.window.attributes = a.window.attributes.apply { preferredDisplayModeId = best.modeId }
                    "display ${cur.refreshRate.toInt()} → ${best.refreshRate.toInt()} Hz (mode ${best.modeId})"
                } else "display ${cur.refreshRate.toInt()} Hz"
            }
        } catch (t: Throwable) { Log.w(TAG, "display mode", t); "display mode failed" }

        parts += if (Build.VERSION.SDK_INT >= 31) {
            val gm = a.getSystemService(GameManager::class.java)
            if (gm == null) "no GameManager" else {
                val mode = when (gm.gameMode) {
                    GameManager.GAME_MODE_PERFORMANCE -> "performance"
                    GameManager.GAME_MODE_BATTERY -> "battery"
                    GameManager.GAME_MODE_STANDARD -> "standard"
                    else -> "unsupported"
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    try { gm.setGameState(GameState(false, GameState.MODE_GAMEPLAY_INTERRUPTIBLE)) } catch (t: Throwable) { Log.w(TAG, "game state", t) }
                }
                "game mode $mode"
            }
        } else "game mode needs Android 12"

        return parts.joinToString(" · ")
    }

    fun peakRefreshCap(context: Context): Float {
        val peak = try {
            Settings.System.getFloat(context.contentResolver, "peak_refresh_rate")
        } catch (_: Throwable) { 0f }
        return if (peak >= 1f) peak else Float.POSITIVE_INFINITY
    }

    internal fun selectMode(modes: Array<Display.Mode>, current: Display.Mode, fpsLimit: Int, cap: Float): Display.Mode? {
        val sameSize = modes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val allowed = sameSize.filter { it.refreshRate <= cap + 0.5f }
        return allowed.filter { cadenceFits(it.refreshRate, fpsLimit) }.maxByOrNull { it.refreshRate }
            ?: allowed.maxByOrNull { it.refreshRate }
            ?: sameSize.minByOrNull { it.refreshRate }
    }

    fun cadenceFits(hz: Float, fpsLimit: Int): Boolean {
        if (fpsLimit <= 0) return true
        val ratio = hz / fpsLimit
        return ratio >= 0.98f && Math.abs(ratio - Math.round(ratio)) < 0.02f
    }
}
