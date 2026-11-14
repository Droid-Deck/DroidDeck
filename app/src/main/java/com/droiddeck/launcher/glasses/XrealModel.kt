package com.droiddeck.launcher.glasses

class XrealModel private constructor(
    override val name: String,
    override val diagonalFovDeg: Float,
    private val native60Hz: Int,
) : GlassesModel {
    override val controllable = true
    override val brightnessMax = 9
    override val volumeMax = 0
    override val hasSunblock = true
    override val sbs90 = false

    internal fun supportsDisplayMode(mode: Int) = mode in 3..5 || mode == native60Hz ||
        (this === ONE_S && mode == 2)

    override fun mode(hz: Int, sbs: Boolean) = when {
        sbs -> 5
        hz >= 120 -> 4
        hz >= 90 -> 3
        else -> native60Hz
    }
    override fun modeForStep(step: Int): Int? = null

    companion object {
        const val VENDOR_ID = 0x3318
        const val PRODUCT_1S = 0x043e
        const val PRODUCT_ONE_PRO_PLUS = 0x0444
        val ONE_S = XrealModel("XREAL 1S", 52f, 29)
        val ONE_PRO_PLUS = XrealModel("XREAL 1 Pro+", 57f, 9)

        fun model(vid: Int, pid: Int): XrealModel? = if (vid != VENDOR_ID) null else when (pid) {
            PRODUCT_1S -> ONE_S
            PRODUCT_ONE_PRO_PLUS -> ONE_PRO_PLUS
            else -> null
        }

        fun isDisplay(name: String) = name.contains("XREAL", ignoreCase = true)
    }
}
