package com.droiddeck.launcher.glasses

class XrealModel private constructor(
    override val name: String,
    val productId: Int,
    override val diagonalFovDeg: Float,
    private val native60Hz: Int,
    override val brightnessMax: Int,
    override val hasSunblock: Boolean,
) : GlassesModel {
    companion object {
        const val VENDOR_ID = 0x3318
        const val PRODUCT_ONE = 0x0438
        const val PRODUCT_1S = 0x043e
        const val NATIVE_1S_60HZ = 29
        val ONE = XrealModel("XREAL One", PRODUCT_ONE, 50f, 2, 0, false)
        val ONE_S = XrealModel("XREAL 1S", PRODUCT_1S, 52f, NATIVE_1S_60HZ, 9, true)

        // Recovery and Air products must never receive One-family control commands.
        fun model(vid: Int, pid: Int): XrealModel? = if (vid != VENDOR_ID) null else when (pid) {
            PRODUCT_ONE -> ONE
            PRODUCT_1S -> ONE_S
            else -> null
        }
        fun isDisplay(name: String) = name.contains("XREAL", ignoreCase = true)
    }

    override val controllable = true
    override val hardwareControls get() = brightnessMax > 0 || volumeMax > 0 || hasSunblock
    override val volumeMax = 0
    override val sbs90 = false

    fun supportsDisplayMode(mode: Int) = mode in 2..5 || mode == native60Hz

    // One-family EDID configuration values, not VITURE's display mode IDs.
    override fun mode(hz: Int, sbs: Boolean) = when {
        sbs -> 5
        hz >= 120 -> 4
        hz >= 90 -> 3
        else -> native60Hz
    }
    override fun modeForStep(step: Int): Int? = null
}
