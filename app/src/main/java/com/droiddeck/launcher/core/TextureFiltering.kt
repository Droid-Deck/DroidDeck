package com.droiddeck.launcher.core

import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Texture filtering for DirectX 9-11 games, as DXVK configuration options
 * (d3d9/d3d11.samplerAnisotropy and samplerLodBias). VKD3D-Proton and WineD3D do not read them.
 * Published into the game environment and read by DXVK when a game starts, so a change lands on
 * the next launch, not the running game.
 */
object TextureFiltering {
    /** 0 = the game's own filtering; otherwise that anisotropy on every linearly filtered sampler. */
    val ANISOTROPY = listOf(0, 2, 4, 8, 16)

    /** Negative mip LOD bias added to the game's own: sharper textures at the cost of some shimmer. */
    const val LOD_BIAS_OFF = "0"
    /** Derived from how far the session is enlarged to the panel (see [autoLodBias]). */
    const val LOD_BIAS_AUTO = "auto"
    val LOD_BIAS = listOf(LOD_BIAS_OFF, LOD_BIAS_AUTO, "-0.25", "-0.5", "-0.75", "-1.0")

    private const val LOD_BIAS_MIN = -2f

    /**
     * The mip LOD bias for a picture enlarged by [scale] (panel size over session size, long side
     * to long side): -log2(scale), AMD's FSR 1 guidance (a 1.5x enlargement - 720p on a 1080p
     * panel - gives -0.58). 0 when the session is not enlarged or the scale is unknown.
     */
    fun autoLodBias(scale: Float): Float {
        if (scale.isNaN() || scale <= 1f) return 0f
        return max((-ln(scale.toDouble()) / ln(2.0)).toFloat(), LOD_BIAS_MIN)
    }

    fun lodBias(choice: String, autoBias: Float): Float = when (choice) {
        LOD_BIAS_OFF, "" -> 0f
        LOD_BIAS_AUTO -> autoBias
        else -> choice.toFloatOrNull()?.let { max(min(it, 0f), LOD_BIAS_MIN) } ?: 0f
    }

    /** The DXVK_CONFIG options for the two settings, "" when both are left to the game. */
    fun dxvkOptions(anisotropy: Int, lodBiasChoice: String, autoBias: Float): String {
        val parts = ArrayList<String>(4)
        val aniso = if (anisotropy in ANISOTROPY) anisotropy else 0
        if (aniso > 0) {
            parts += "d3d9.samplerAnisotropy = $aniso"
            parts += "d3d11.samplerAnisotropy = $aniso"
        }
        val bias = lodBias(lodBiasChoice, autoBias)
        if (bias < 0f) {
            // Locale.US: DXVK parses "-0.58", never "-0,58".
            val b = String.format(Locale.US, "%.2f", bias)
            parts += "d3d9.samplerLodBias = $b"
            parts += "d3d11.samplerLodBias = $b"
        }
        return parts.joinToString("; ")
    }
}
