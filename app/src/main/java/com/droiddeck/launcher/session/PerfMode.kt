package com.droiddeck.launcher.session

import android.app.Activity
import android.app.GameManager
import android.app.GameState
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * What a session tells Android about itself so the SoC is run for a game, not for an app.
 *
 * The manifest already makes the app a game (appCategory + game_mode_config); this is the
 * per-window half, applied when the session activity is created:
 *
 *  - NOT sustained performance mode: on Pixel and Qualcomm power HALs it caps the CPU and GPU at
 *    a level the device can hold indefinitely rather than holding a floor, which cost games and
 *    Big Picture their peak clocks. WinNative never asks for it; the thermal budget is left to
 *    the game;
 *  - the panel's fastest mode at its current size (Android 6+), up to the user's peak refresh rate
 *    ([peakRefreshCap]): a 120 Hz phone is switched to 120 Hz for the menu, not only when a game
 *    votes for it. With a frame cap, the fastest mode
 *    the cap divides evenly, so every capped frame is shown for the same number of refreshes;
 *  - GameManager's game state (Android 13+): "in gameplay", so an OEM framework that releases
 *    its boost on loading screens or menus keeps it up.
 *
 * Every step is version-guarded and skipped silently below its API; the returned line is what
 * the session log shows, which is the first thing to read on a device that feels slow.
 */
object PerfMode {
    private const val TAG = "PerfMode"

    fun apply(a: Activity, fpsLimit: Int = 0): String {
        val parts = ArrayList<String>(2)

        // The fastest display mode at the panel's current size.
        parts += try {
            val display = if (Build.VERSION.SDK_INT >= 30) a.display else @Suppress("DEPRECATION") a.windowManager.defaultDisplay
            if (display == null || Build.VERSION.SDK_INT < 23) "display mode unchanged" else {
                val cur = display.mode
                val cap = peakRefreshCap(a)
                val sameSize = display.supportedModes
                    .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                    .filter { it.refreshRate <= cap + 0.5f }
                    .ifEmpty { display.supportedModes.filter { it.modeId == cur.modeId } }
                val best = sameSize.filter { cadenceFits(it.refreshRate, fpsLimit) }.maxByOrNull { it.refreshRate }
                    ?: sameSize.maxByOrNull { it.refreshRate }
                if (best != null && best.modeId != cur.modeId &&
                    (best.refreshRate > cur.refreshRate + 0.5f || !cadenceFits(cur.refreshRate, fpsLimit))) {
                    a.window.attributes = a.window.attributes.apply { preferredDisplayModeId = best.modeId }
                    "display ${cur.refreshRate.toInt()} → ${best.refreshRate.toInt()} Hz (mode ${best.modeId})"
                } else "display ${cur.refreshRate.toInt()} Hz"
            }
        } catch (t: Throwable) { Log.w(TAG, "display mode", t); "display mode failed" }

        // GameManager: what mode the OS put us in, and that we are playing.
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

    /**
     * The highest rate Android will run the panel at: the user's "peak refresh rate" (Smooth display,
     * or the AYN/Odin quick-settings tile). Above it a mode can be requested and is even reported back
     * as active, while SurfaceFlinger keeps composing at the cap. The Thor set to 60 Hz ran 120 Hz
     * sessions at 60: gamescope and Steam were told 120, every game topped out at 60, and the QAM's
     * Frame Limit read "60 FPS (120 Hz)" and changed nothing. Unset (most phones) is no cap.
     */
    fun peakRefreshCap(context: Context): Float {
        val peak = try {
            Settings.System.getFloat(context.contentResolver, "peak_refresh_rate")
        } catch (_: Throwable) { 0f }
        return if (peak >= 1f) peak else Float.POSITIVE_INFINITY
    }

    /** A panel rate that shows every capped frame for a whole number of refreshes (no cap: any). */
    fun cadenceFits(hz: Float, fpsLimit: Int): Boolean {
        if (fpsLimit <= 0) return true
        val ratio = hz / fpsLimit
        return ratio >= 0.98f && Math.abs(ratio - Math.round(ratio)) < 0.02f
    }
}
