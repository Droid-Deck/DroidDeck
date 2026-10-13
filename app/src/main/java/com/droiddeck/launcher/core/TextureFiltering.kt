package com.droiddeck.launcher.core

import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

object TextureFiltering {
    val ANISOTROPY = listOf(0, 2, 4, 8, 16)

    const val LOD_BIAS_OFF = "0"
    const val LOD_BIAS_AUTO = "auto"
    val LOD_BIAS = listOf(LOD_BIAS_OFF, LOD_BIAS_AUTO, "-0.25", "-0.5", "-0.75", "-1.0")

    private const val LOD_BIAS_MIN = -2f

    fun autoLodBias(scale: Float): Float {
        if (scale.isNaN() || scale <= 1f) return 0f
        return max((-ln(scale.toDouble()) / ln(2.0)).toFloat(), LOD_BIAS_MIN)
    }

    fun lodBias(choice: String, autoBias: Float): Float = when (choice) {
        LOD_BIAS_OFF, "" -> 0f
        LOD_BIAS_AUTO -> autoBias
        else -> choice.toFloatOrNull()?.let { max(min(it, 0f), LOD_BIAS_MIN) } ?: 0f
    }

    fun dxvkOptions(anisotropy: Int, lodBiasChoice: String, autoBias: Float): String {
        val parts = ArrayList<String>(4)
        val aniso = if (anisotropy in ANISOTROPY) anisotropy else 0
        if (aniso > 0) {
            parts += "d3d9.samplerAnisotropy = $aniso"
            parts += "d3d11.samplerAnisotropy = $aniso"
        }
        val bias = lodBias(lodBiasChoice, autoBias)
        if (bias < 0f) {
            val b = String.format(Locale.US, "%.2f", bias)
            parts += "d3d9.samplerLodBias = $b"
            parts += "d3d11.samplerLodBias = $b"
        }
        return parts.joinToString("; ")
    }
}
