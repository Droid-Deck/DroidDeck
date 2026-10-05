package com.droiddeck.launcher.session

import android.content.Context
import android.view.WindowManager

/** The virtual display advertised to the guest, before it is fitted onto the Android panel. */
object SessionDisplay {
    const val MATCH_SCREEN = "screen"
    const val DEFAULT_RESOLUTION = "1280x720"

    fun screenSize(panel: Pair<Int, Int>): Pair<Int, Int> =
        (maxOf(panel.first, panel.second) and 1.inv()) to (minOf(panel.first, panel.second) and 1.inv())

    fun resolveChoice(panel: Pair<Int, Int>, choice: String): Pair<Int, Int> =
        if (choice == MATCH_SCREEN) screenSize(panel) else {
            // Legacy displays may exceed the custom dialog's size limit. Keep their exact size.
            val parts = choice.split('x').map { it.toIntOrNull() }
            if (parts.size == 2 && parts.all { it != null && it > 0 }) parts[0]!! to parts[1]!!
            else 1280 to 720
        }

    /** Fixed dimensions and the current panel, with no duplicate panel-size preset. */
    fun resolutionOptions(panel: Pair<Int, Int>): List<String> =
        listOf(DEFAULT_RESOLUTION, "1600x900", "1920x1080")
            .filter { resolveChoice(panel, it) != screenSize(panel) } + MATCH_SCREEN

    fun panelSize(context: Context): Pair<Int, Int> {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val mode = manager.defaultDisplay.mode
        return maxOf(mode.physicalWidth, mode.physicalHeight) to minOf(mode.physicalWidth, mode.physicalHeight)
    }

    /** Interpret an older cap/aspect combination without altering the saved picture size. */
    fun resolve(panel: Pair<Int, Int>, cap: Int, shape: String, custom: Pair<Int, Int>? = null): Pair<Int, Int> {
        custom?.let { return it }
        val panelW = maxOf(panel.first, panel.second).toFloat()
        val panelH = minOf(panel.first, panel.second).toFloat().coerceAtLeast(1f)
        // Auto retains wide panels but floors squarer ones at 16:9 for game compatibility.
        // Match screen removes that floor; fixed 16:9 also keeps foldable sessions stable.
        val aspect = when (shape) {
            SessionPrefs.SHAPE_WIDE -> 16f / 9f
            SessionPrefs.SHAPE_EXACT -> panelW / panelH
            else -> maxOf(panelW / panelH, 16f / 9f)
        }
        val height = (if (cap <= 0) panelH else minOf(panelH, cap.toFloat())).toInt()
        return ((height * aspect).toInt() and 1.inv()) to (height and 1.inv())
    }

    /** Mirrors gamescope's even-width 16:9 calculation, including near-16:9 rounding. */
    fun canStretch16x9(size: Pair<Int, Int>): Boolean =
        (size.second * 16 + 4) / 9 / 2 * 2 > size.first
}
