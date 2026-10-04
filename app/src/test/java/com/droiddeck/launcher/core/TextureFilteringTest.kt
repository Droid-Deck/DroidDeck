package com.droiddeck.launcher.core

import org.junit.Assert.*
import org.junit.Test

class TextureFilteringTest {
    @Test fun autoBiasFollowsFsrGuidanceAndStopsAtNoEnlargement() {
        assertEquals(0f, TextureFiltering.autoLodBias(1f), 0f)
        assertEquals(0f, TextureFiltering.autoLodBias(0f), 0f)
        assertEquals(0f, TextureFiltering.autoLodBias(Float.NaN), 0f)
        assertEquals(-0.58f, TextureFiltering.autoLodBias(1.5f), 0.01f)
        assertEquals(-1f, TextureFiltering.autoLodBias(2f), 0.001f)
        assertEquals(-2f, TextureFiltering.autoLodBias(16f), 0f)
    }

    @Test fun optionsNameBothApisAndUseDotDecimals() {
        assertEquals("", TextureFiltering.dxvkOptions(0, TextureFiltering.LOD_BIAS_OFF, -0.58f))
        assertEquals("d3d9.samplerAnisotropy = 16; d3d11.samplerAnisotropy = 16", TextureFiltering.dxvkOptions(16, "0", 0f))
        assertEquals(
            "d3d9.samplerLodBias = -0.58; d3d11.samplerLodBias = -0.58",
            TextureFiltering.dxvkOptions(0, TextureFiltering.LOD_BIAS_AUTO, -0.58f),
        )
        assertEquals(
            "d3d9.samplerAnisotropy = 8; d3d11.samplerAnisotropy = 8; d3d9.samplerLodBias = -0.50; d3d11.samplerLodBias = -0.50",
            TextureFiltering.dxvkOptions(8, "-0.5", 0f),
        )
    }

    @Test fun unknownValuesLeaveTheGameAlone() {
        assertEquals("", TextureFiltering.dxvkOptions(3, "junk", 0f))
        assertEquals("", TextureFiltering.dxvkOptions(0, TextureFiltering.LOD_BIAS_AUTO, 0f))
        assertEquals("", TextureFiltering.dxvkOptions(0, "0.5", 0f))
        assertEquals(-2f, TextureFiltering.lodBias("-9", 0f), 0f)
    }
}
