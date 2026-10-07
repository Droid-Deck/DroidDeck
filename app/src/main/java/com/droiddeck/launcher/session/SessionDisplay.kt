package com.droiddeck.launcher.session

import android.content.Context
import android.view.WindowManager

/** The virtual display advertised to the guest, before it is fitted onto the Android panel. */
object SessionDisplay {
    const val MATCH_SCREEN = "screen"

    /**
     * Presets are heights, not sizes: "720p" is the panel's own shape at 720 lines, never narrower
     * than 16:9 and never taller than the panel (SHAPE_AUTO). The client treats the output as the
     * games' native resolution, so the height is what bounds a game's default size on a 1440p
     * phone; the shape is what keeps the client's interface edge to edge on a 20:9 one (1600x720)
     * and keeps games at 16:9 or wider on a foldable's near-square panel.
     */
    const val DEFAULT_RESOLUTION = "720p"
    private val PRESET_HEIGHTS = listOf(720, 900, 1080)

    /** The height of a preset choice ("900p"), or null for Match screen and fixed sizes. */
    fun presetHeight(choice: String): Int? =
        if (choice.endsWith("p")) choice.dropLast(1).toIntOrNull()?.takeIf { it > 0 } else null

    fun screenSize(panel: Pair<Int, Int>): Pair<Int, Int> =
        (maxOf(panel.first, panel.second) and 1.inv()) to (minOf(panel.first, panel.second) and 1.inv())

    fun resolveChoice(panel: Pair<Int, Int>, choice: String): Pair<Int, Int> {
        if (choice == MATCH_SCREEN) return screenSize(panel)
        presetHeight(choice)?.let { return resolve(panel, it, SessionPrefs.SHAPE_AUTO) }
        // Fixed sizes (custom, or a preset saved before presets were heights) keep their exact size,
        // including legacy ones past the custom dialog's limit.
        val parts = choice.split('x').map { it.toIntOrNull() }
        return if (parts.size == 2 && parts.all { it != null && it > 0 }) parts[0]!! to parts[1]!!
        else resolve(panel, 720, SessionPrefs.SHAPE_AUTO)
    }

    /** The height presets the panel is tall enough for, then the panel itself, with no duplicate sizes. */
    fun resolutionOptions(panel: Pair<Int, Int>): List<String> {
        val panelHeight = minOf(panel.first, panel.second)
        return PRESET_HEIGHTS.filter { it <= panelHeight }.map { "${it}p" }
            .filter { resolveChoice(panel, it) != screenSize(panel) } + MATCH_SCREEN
    }

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
