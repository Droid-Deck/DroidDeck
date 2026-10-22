package com.droiddeck.launcher.glasses

/** First One-family target. Recovery and Air USB products must not use this transport. */
object XrealModel : GlassesModel {
    const val VENDOR_ID = 0x3318
    const val PRODUCT_1S = 0x043e
    const val NATIVE_60HZ = 29 // Captured on 1S: 1920 x 1200 at 60 Hz.
    override val name = "XREAL 1S"
    override val controllable = true
    override val brightnessMax = 9
    override val volumeMax = 0
    override val hasSunblock = true
    override val diagonalFovDeg = 52f
    override val sbs90 = false

    fun matches(vid: Int, pid: Int) = vid == VENDOR_ID && pid == PRODUCT_1S
    fun isDisplay(name: String) = name.contains("XREAL", ignoreCase = true)

    // One-family EDID configuration values, not VITURE's display mode IDs.
    override fun mode(hz: Int, sbs: Boolean) = when {
        sbs -> 5
        hz >= 120 -> 4
        hz >= 90 -> 3
        else -> NATIVE_60HZ
    }
    override fun modeForStep(step: Int): Int? = null
}
