package com.droiddeck.launcher.session

import android.content.Context
import android.view.WindowManager

object SessionDisplay {
    const val MATCH_SCREEN = "screen"
    const val FOLLOW_SCREEN = "follow"
    const val FOLLOW_MIN_SIDE = 480

    const val DEFAULT_RESOLUTION = "720p"
    private val PRESET_HEIGHTS = listOf(720, 900, 1080)

    fun presetHeight(choice: String): Int? =
        if (choice.endsWith("p")) choice.dropLast(1).toIntOrNull()?.takeIf { it > 0 } else null

    fun screenSize(panel: Pair<Int, Int>): Pair<Int, Int> =
        (maxOf(panel.first, panel.second) and 1.inv()) to (minOf(panel.first, panel.second) and 1.inv())

    fun resolveChoice(panel: Pair<Int, Int>, choice: String): Pair<Int, Int> {
        if (choice == MATCH_SCREEN || choice == FOLLOW_SCREEN) return screenSize(panel)
        presetHeight(choice)?.let { return resolve(panel, it, SessionPrefs.SHAPE_AUTO) }
        val parts = choice.split('x').map { it.toIntOrNull() }
        return if (parts.size == 2 && parts.all { it != null && it > 0 }) parts[0]!! to parts[1]!!
        else resolve(panel, 720, SessionPrefs.SHAPE_AUTO)
    }

    fun resolutionOptions(panel: Pair<Int, Int>): List<String> {
        val panelHeight = minOf(panel.first, panel.second)
        return PRESET_HEIGHTS.filter { it <= panelHeight }.map { "${it}p" }
            .filter { resolveChoice(panel, it) != screenSize(panel) } + MATCH_SCREEN + FOLLOW_SCREEN
    }

    fun followSize(width: Int, height: Int): Pair<Int, Int>? =
        if (minOf(width, height) < FOLLOW_MIN_SIDE) null else (width and 1.inv()) to (height and 1.inv())

    const val UNFOLDED_MAX_GAME_SHARE = 0.8f

    fun foldable(context: Context): Boolean =
        context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")

    const val UNFOLDED_MIN_DP = 600

    fun unfoldedGameHeight(width: Int, height: Int): Int =
        minOf(width * 9 / 16, (height * UNFOLDED_MAX_GAME_SHARE).toInt()) and 1.inv()

    fun unfoldedControlsWanted(
        enabled: Boolean,
        foldable: Boolean,
        controllerConnected: Boolean,
        keepWithController: Boolean,
        steamDeckPad: Boolean,
        pip: Boolean,
        width: Int,
        height: Int,
        smallestWidthDp: Int,
    ): Boolean = enabled && foldable && (keepWithController || !controllerConnected) && steamDeckPad &&
        !pip && width > 0 && height > 0 && smallestWidthDp >= UNFOLDED_MIN_DP


    fun panelSize(context: Context): Pair<Int, Int> {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val mode = manager.defaultDisplay.mode
        return maxOf(mode.physicalWidth, mode.physicalHeight) to minOf(mode.physicalWidth, mode.physicalHeight)
    }

    fun resolve(panel: Pair<Int, Int>, cap: Int, shape: String, custom: Pair<Int, Int>? = null): Pair<Int, Int> {
        custom?.let { return it }
        val panelW = maxOf(panel.first, panel.second).toFloat()
        val panelH = minOf(panel.first, panel.second).toFloat().coerceAtLeast(1f)
        val aspect = when (shape) {
            SessionPrefs.SHAPE_WIDE -> 16f / 9f
            SessionPrefs.SHAPE_EXACT -> panelW / panelH
            else -> maxOf(panelW / panelH, 16f / 9f)
        }
        val height = (if (cap <= 0) panelH else minOf(panelH, cap.toFloat())).toInt()
        return ((height * aspect).toInt() and 1.inv()) to (height and 1.inv())
    }

    fun canStretch16x9(size: Pair<Int, Int>): Boolean =
        (size.second * 16 + 4) / 9 / 2 * 2 > size.first || (size.first * 9 + 8) / 16 / 2 * 2 > size.second
}
