package com.droiddeck.launcher.glasses

object RayNeoModel {
    private val modelName = Regex("(?i)(ray\\s*neo|tcl).*\\b(air|gt)(?=\\b|[0-9])")

    fun isVendor(vid: Int) = vid == RayNeoProtocol.VENDOR_ID || vid == 0x22d9

    fun model(vid: Int, pid: Int, name: String?): GlassesModel? {
        if (!isVendor(vid)) return null
        // Only this pair is confirmed by the scaffold. Other bridge IDs require a model name.
        if (!(vid == RayNeoProtocol.VENDOR_ID && pid == RayNeoProtocol.PRODUCT_ID) &&
            !modelName.containsMatchIn(name.orEmpty())) return null
        return Model(name?.trim()?.takeIf { modelName.containsMatchIn(it) } ?: "RayNeo Air")
    }

    fun isDisplay(name: String): Boolean =
        name.contains("rayneo", ignoreCase = true) || name.contains("ray neo", ignoreCase = true) ||
            modelName.containsMatchIn(name)

    private class Model(override val name: String) : GlassesModel {
        override val controllable = true
        override val brightnessMax = 0
        override val volumeMax = 0
        override val diagonalFovDeg = 46f // Conservative existing default, not a calibrated model profile.
        override val sbs90 = false
        override fun mode(hz: Int, sbs: Boolean) =
            if (sbs) RayNeoProtocol.CMD_SWITCH_TO_3D else RayNeoProtocol.CMD_SWITCH_TO_2D
        override fun modeForStep(step: Int): Int? = null
    }
}
