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
    const val BFI_MIN_REFRESH_HZ = 100f

    fun apply(a: Activity, fpsLimit: Int = 0, refreshLimit: Int = 0, requestedRefresh: Int = 0, held: Boolean = false): String {
        val parts = ArrayList<String>(2)

        parts += try {
            val display = if (Build.VERSION.SDK_INT >= 30) a.display else @Suppress("DEPRECATION") a.windowManager.defaultDisplay
            if (display == null || Build.VERSION.SDK_INT < 23) "display mode unchanged" else if (held) {
                if (a.window.attributes.preferredDisplayModeId != 0) {
                    a.window.attributes = a.window.attributes.apply { preferredDisplayModeId = 0 }
                }
                "display ${display.mode.refreshRate.toInt()} Hz (set by the system)"
            } else {
                val cur = display.mode
                val best = selectMode(display.supportedModes, cur, fpsLimit, refreshCap(a, refreshLimit), requestedRefresh)
                if (best != null && needsChange(display.supportedModes, a.window.attributes.preferredDisplayModeId, best)) {
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

    private fun refreshCap(context: Context, refreshLimit: Int): Float =
        minOf(peakRefreshCap(context), refreshLimit.takeIf { it > 0 }?.toFloat() ?: Float.POSITIVE_INFINITY)

    fun panelRates(a: Activity, refreshLimit: Int = 0, held: Boolean = false): IntArray = try {
        val display = if (Build.VERSION.SDK_INT >= 30) a.display else @Suppress("DEPRECATION") a.windowManager.defaultDisplay
        if (display == null || Build.VERSION.SDK_INT < 23) IntArray(0)
        else panelRates(display.supportedModes, display.mode, refreshCap(a, refreshLimit), held)
    } catch (t: Throwable) { Log.w(TAG, "panel rates", t); IntArray(0) }

    internal fun panelRates(modes: Array<Display.Mode>, current: Display.Mode, cap: Float, held: Boolean = false): IntArray =
        if (held) intArrayOf(Math.round(current.refreshRate))
        else allowedModes(modes, current, cap).map { Math.round(it.refreshRate) }.distinct().sorted().toIntArray()

    fun panelHeld(a: Activity): Boolean = try {
        val display = if (Build.VERSION.SDK_INT >= 30) a.display else @Suppress("DEPRECATION") a.windowManager.defaultDisplay
        val wanted = a.window.attributes.preferredDisplayModeId
        val requested = display?.supportedModes?.firstOrNull { wanted != 0 && it.modeId == wanted }
        display != null && requested != null && panelHeld(requested.refreshRate, display.mode.refreshRate)
    } catch (t: Throwable) { Log.w(TAG, "panel held", t); false }

    internal fun panelHeld(requestedHz: Float, activeHz: Float): Boolean =
        requestedHz > 1f && activeHz > 1f && Math.abs(requestedHz - activeHz) > 0.5f

    fun pacedRate(ownLimit: Int, limiterRate: Int, limiterRefresh: Int, steadyRate: Int): Int = when {
        ownLimit > 0 -> ownLimit
        limiterRate <= 0 -> steadyRate
        steadyRate <= 0 || limiterRate < limiterRefresh || limiterRefresh <= 0 -> limiterRate
        cadenceFits(limiterRate.toFloat(), steadyRate) -> limiterRate
        else -> steadyRate
    }

    fun contentRate(fps: Int, frameGenFactor: Int, frameGenTarget: Int): Int = when {
        frameGenFactor <= 1 -> fps
        frameGenTarget > 0 -> frameGenTarget
        else -> fps * frameGenFactor
    }

    private fun allowedModes(modes: Array<Display.Mode>, current: Display.Mode, cap: Float): List<Display.Mode> =
        modes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight &&
                it.refreshRate <= cap + 0.5f
        }

    internal fun selectMode(
        modes: Array<Display.Mode>,
        current: Display.Mode,
        fpsLimit: Int,
        cap: Float,
        requestedRefresh: Int = 0,
    ): Display.Mode? {
        val sameSize = modes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val allowed = allowedModes(modes, current, cap)
        val requested = if (fpsLimit > 0 || requestedRefresh <= 0) null else allowed
            .filter { it.refreshRate >= requestedRefresh - 0.5f }.minByOrNull { it.refreshRate }
        return requested
            ?: allowed.filter { cadenceFits(it.refreshRate, fpsLimit) }.maxByOrNull { it.refreshRate }
            ?: allowed.maxByOrNull { it.refreshRate }
            ?: sameSize.minByOrNull { it.refreshRate }
    }

    internal fun needsChange(modes: Array<Display.Mode>, preferredId: Int, best: Display.Mode): Boolean {
        val preferred = modes.firstOrNull { preferredId != 0 && it.modeId == preferredId } ?: return true
        return Math.abs(preferred.refreshRate - best.refreshRate) > 0.5f
    }

    fun cadenceFits(hz: Float, fpsLimit: Int): Boolean {
        if (fpsLimit <= 0) return true
        val ratio = hz / fpsLimit
        return ratio >= 0.98f && Math.abs(ratio - Math.round(ratio)) < 0.02f
    }
}
