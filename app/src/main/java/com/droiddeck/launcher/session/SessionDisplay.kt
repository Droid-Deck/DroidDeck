package com.droiddeck.launcher.session

import android.content.Context
import android.view.WindowManager

object SessionDisplay {
    const val MATCH_SCREEN = "screen"
    const val FOLLOW_SCREEN = "follow"
    const val FOLLOW_MIN_SIDE = 480
    val FOLLOW_SCALES = listOf(1.5f, 2f, 2.5f, 3f, 4f)
    const val FOLLOW_CUSTOM = "follow/custom"
    const val FOLLOW_SCALE_MAX = 8f
    private const val FOLLOW_SCALED_PREFIX = "follow/"

    const val LINK_LANDSCAPE = "link/landscape"
    const val LINK_PORTRAIT = "link/portrait"

    const val DEFAULT_RESOLUTION = "720p"
    private val PRESET_HEIGHTS = listOf(720, 900, 1080)

    fun presetHeight(choice: String): Int? =
        if (choice.endsWith("p")) choice.dropLast(1).toIntOrNull()?.takeIf { it > 0 } else null

    fun followScaled(scale: Float): String = FOLLOW_SCALED_PREFIX + formatScale(scale)

    fun formatScale(scale: Float): String =
        (Math.round(scale * 10) / 10f).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }

    fun parseFollowScale(text: String): Float? =
        text.trim().replace(',', '.').toFloatOrNull()
            ?.let { Math.round(it * 10) / 10f }?.takeIf { it > 1f && it <= FOLLOW_SCALE_MAX }

    fun isFollow(choice: String): Boolean = followScale(choice) != null

    fun followScale(choice: String): Float? = when {
        choice == FOLLOW_SCREEN -> 1f
        choice.startsWith(FOLLOW_SCALED_PREFIX) -> parseFollowScale(choice.removePrefix(FOLLOW_SCALED_PREFIX))
        else -> null
    }

    fun isLink(choice: String): Boolean = choice == LINK_LANDSCAPE || choice == LINK_PORTRAIT

    fun orientCustom(choice: String, portrait: Boolean): String {
        val parts = choice.split('x').map { it.toIntOrNull() }
        if (parts.size != 2 || parts.any { it == null || it <= 0 }) return choice
        val width = parts[0]!!
        val height = parts[1]!!
        return if ((portrait && width > height) || (!portrait && height > width)) "${height}x$width" else choice
    }

    fun effectiveChoice(landscape: String, portrait: String, forPortrait: Boolean): String {
        val own = if (forPortrait) portrait else landscape
        if (!isLink(own)) return own
        val other = if (forPortrait) landscape else portrait
        return if (isLink(other)) DEFAULT_RESOLUTION else orientCustom(other, forPortrait)
    }

    fun screenSize(panel: Pair<Int, Int>): Pair<Int, Int> =
        (maxOf(panel.first, panel.second) and 1.inv()) to (minOf(panel.first, panel.second) and 1.inv())

    fun resolveChoice(panel: Pair<Int, Int>, choice: String, portrait: Boolean = false): Pair<Int, Int> {
        fun orient(size: Pair<Int, Int>) = if (portrait) size.second to size.first else size
        if (choice == MATCH_SCREEN) return orient(screenSize(panel))
        followScale(choice)?.let { return orient(scaledDown(screenSize(panel), it)) }
        presetHeight(choice)?.let { return orient(resolve(panel, it, SessionPrefs.SHAPE_AUTO)) }
        val parts = choice.split('x').map { it.toIntOrNull() }
        return if (parts.size == 2 && parts.all { it != null && it > 0 }) parts[0]!! to parts[1]!!
        else orient(resolve(panel, 720, SessionPrefs.SHAPE_AUTO))
    }

    fun resolutionOptions(panel: Pair<Int, Int>): List<String> {
        val panelHeight = minOf(panel.first, panel.second)
        return PRESET_HEIGHTS.filter { it <= panelHeight }.map { "${it}p" }
            .filter { resolveChoice(panel, it) != screenSize(panel) } + MATCH_SCREEN + FOLLOW_SCREEN + FOLLOW_SCALES.map(::followScaled)
    }

    fun followSize(width: Int, height: Int, controlsBelow: Boolean = false, scale: Float = 1f): Pair<Int, Int>? =
        if (width <= 0 || height <= 0 ||
            (if (controlsBelow) width else minOf(width, height)) < FOLLOW_MIN_SIDE) null
        else scaledDown(width to height, scale)

    private fun scaledDown(size: Pair<Int, Int>, scale: Float): Pair<Int, Int> =
        (Math.round(size.first / scale) and 1.inv()) to (Math.round(size.second / scale) and 1.inv())

    const val UNFOLDED_MAX_GAME_SHARE = 0.8f

    fun foldable(context: Context): Boolean =
        context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")

    const val UNFOLDED_MIN_DP = 600

    val UNFOLDED_CONTROLS_SHARES = listOf(0, 25, 30, 35, 40, 45, 50, 55, 60)

    fun unfoldedGameHeight(width: Int, height: Int, aspect: Float = 0f, controlsPercent: Int = 0): Int {
        val gameHeight = if (controlsPercent in UNFOLDED_CONTROLS_SHARES && controlsPercent != 0)
            height * (100 - controlsPercent) / 100
        else {
            val cap = (height * UNFOLDED_MAX_GAME_SHARE).toInt()
            val fitted = if (aspect > 0f && aspect < 16f / 9f) (width / aspect).toInt() else width * 9 / 16
            minOf(fitted, cap)
        }
        return gameHeight.coerceAtLeast(0) and 1.inv()
    }

    fun unfoldedUsesSecondScreen(
        preferSecondScreen: Boolean,
        controllerConnected: Boolean,
        keepWithController: Boolean = false,
    ): Boolean = preferSecondScreen || (controllerConnected && !keepWithController)

    fun controlsBelowSupported(mode: String): Boolean =
        mode == SessionService.MODE_STEAM || mode == SessionService.MODE_RUN

    fun unfoldedControlsWanted(
        enabled: Boolean,
        foldable: Boolean,
        gamepadAvailable: Boolean,
        pip: Boolean,
        width: Int,
        height: Int,
        smallestWidthDp: Int,
    ): Boolean = enabled && gamepadAvailable &&
        !pip && width > 0 && height > 0 &&
        (height > width || (foldable && smallestWidthDp >= UNFOLDED_MIN_DP))


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
