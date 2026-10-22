package com.droiddeck.launcher.session

import android.content.Context
import android.view.WindowManager
import com.droiddeck.launcher.R

object SessionDisplay {
    const val MATCH_SCREEN = "screen"
    const val FOLLOW_SCREEN = "follow"
    const val FOLLOW_MIN_SIDE = 480

    val FOLLOW_DIVISORS = listOf(1f, 1.5f, 2f, 3f, 4f)

    fun divisorLabel(divisor: Float): Int = when (divisor) {
        1.5f -> R.string.display_follow_two_thirds
        2f -> R.string.display_follow_half
        3f -> R.string.display_follow_third
        4f -> R.string.display_follow_quarter
        else -> R.string.display_follow_full
    }

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

    fun followSize(width: Int, height: Int, divisor: Float = 1f): Pair<Int, Int>? {
        if (minOf(width, height) < FOLLOW_MIN_SIDE) return null
        val d = divisor.coerceAtLeast(1f)
        return ((width / d).toInt() and 1.inv()) to ((height / d).toInt() and 1.inv())
    }

    fun foldable(context: Context): Boolean =
        context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")

    const val FOLDED_FILL = "fill"
    const val FOLDED_16X9 = "16:9"
    const val FOLDED_16X10 = "16:10"
    const val FOLDED_SCOPE = "2.39:1"
    val FOLDED_ASPECTS = listOf(FOLDED_FILL, FOLDED_16X9, FOLDED_16X10, FOLDED_SCOPE)

    const val FOLDED_MIN_SIDE = 240

    const val CONTROLS_UNFOLDED = "unfolded"
    const val CONTROLS_HALF_FOLDED = "halfFolded"

    const val UNFOLDED_MIN_DP = 600
    const val UNFOLDED_MAX_GAME_SHARE = 0.8f

    fun unfoldedGameSize(width: Int, height: Int, aspect: String): Pair<Int, Int> =
        if (aspect == FOLDED_FILL) (width and 1.inv()) to (height / 2 and 1.inv())
        else foldedGameSize(width, (height * UNFOLDED_MAX_GAME_SHARE).toInt(), aspect)

    fun foldedGameSize(width: Int, aboveFold: Int, aspect: String): Pair<Int, Int> {
        val ratio = when (aspect) {
            FOLDED_16X9 -> 16f / 9f
            FOLDED_16X10 -> 16f / 10f
            FOLDED_SCOPE -> 2.39f
            else -> return (width and 1.inv()) to (aboveFold and 1.inv())
        }
        val gameWidth = minOf(width.toFloat(), aboveFold * ratio)
        return (gameWidth.toInt() and 1.inv()) to ((gameWidth / ratio).toInt() and 1.inv())
    }

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
        (size.second * 16 + 4) / 9 / 2 * 2 > size.first
}
