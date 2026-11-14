package com.droiddeck.launcher.glasses

internal class XrealModel private constructor(
    override val name: String,
    val family: Family,
    override val diagonalFovDeg: Float,
) : GlassesModel {
    enum class Family {
        ONE_S,
        ONE,
        AURA,
    }

    override val controllable = true
    override val brightnessMax = 9
    override val volumeMax = 0
    override val hasSunblock get() = family == Family.ONE_S
    override val hardwareControls get() = family == Family.ONE_S
    override val sbs90 = false
    override val rates2d get() = if (family == Family.ONE_S) super.rates2d else listOf(60)

    // Only the 1S has been exercised with SET_DISPLAY; the Aura is known to reboot on an unexpected index.
    val stereoOnlyControl get() = family != Family.ONE_S

    // The Aura reports x right, y up, z back; the rest of the One family is a half turn about x from it.
    val flipsYz get() = family != Family.AURA

    override fun mode(hz: Int, sbs: Boolean) = when {
        sbs -> 5
        stereoOnlyControl -> NATIVE_60HZ
        hz >= 120 -> 4
        hz >= 90 -> 3
        else -> NATIVE_60HZ
    }
    override fun modeForStep(step: Int): Int? = null

    override fun toString() = name

    companion object {
        const val VENDOR_ID = 0x3318
        const val PRODUCT_1S = 0x043e
        const val NATIVE_60HZ = 29

        val ONE_S = XrealModel("XREAL 1S", Family.ONE_S, 52f)
        val ONE = XrealModel("XREAL One", Family.ONE, 50f)
        val ONE_PRO = XrealModel("XREAL One Pro", Family.ONE, 57f)
        val ONE_PRO_PLUS = XrealModel("XREAL One Pro+", Family.ONE, 57f)
        val AURA = XrealModel("XREAL Aura", Family.AURA, 70f)

        // The odd product ids one below these are the glasses' recovery mode.
        private val products = mapOf(
            PRODUCT_1S to ONE_S,
            0x0438 to ONE,
            0x0436 to ONE_PRO,
            0x0444 to ONE_PRO_PLUS,
            0x043a to AURA,
        )

        fun model(vid: Int, pid: Int): XrealModel? = if (vid == VENDOR_ID) products[pid] else null
        fun matches(vid: Int, pid: Int) = model(vid, pid) != null
        fun isDisplay(name: String) = name.contains("XREAL", ignoreCase = true)
    }
}
