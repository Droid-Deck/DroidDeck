package com.droiddeck.launcher.core

import org.junit.Assert.*
import org.junit.Test

class GameEnvironmentTest {
    @Test fun defaultsIncludeRequestedGraphicsLevelsAndKeepCaching() {
        val values = GameEnvironment.defaults("")
        assertEquals("false", values["MESA_SHADER_CACHE_DISABLE"])
        assertEquals("12_2", values["VKD3D_FEATURE_LEVEL"])
        assertEquals("6_9", values["VKD3D_SHADER_MODEL"])
    }

    @Test fun forceSsbsIsADefaultAGameCanTurnOff() {
        assertFalse(GameEnvironment.defaults("").containsKey(GameEnvironment.FORCE_SSBS))
        assertEquals("1", GameEnvironment.defaults("", forceSsbs = true)[GameEnvironment.FORCE_SSBS])
        val config = GameEnvironment.Config().withEntries("42", mapOf(GameEnvironment.FORCE_SSBS to "0"))
        assertEquals("0", GameEnvironment.effective(config, "", "42", forceSsbs = true)[GameEnvironment.FORCE_SSBS])
        assertEquals("1", GameEnvironment.effective(config, "", "43", forceSsbs = true)[GameEnvironment.FORCE_SSBS])
    }

    @Test fun gameOverridesSharedAndPresetWithoutLeakingToOtherGames() {
        val config = GameEnvironment.Config(
            shared = mapOf("FEX_MULTIBLOCK" to "0", "VKD3D_FEATURE_LEVEL" to "12_2"),
            games = mapOf("42" to mapOf("FEX_MULTIBLOCK" to "1", "VKD3D_FEATURE_LEVEL" to null)),
        )
        val game = GameEnvironment.effective(config, "COMPATIBILITY", "42")
        assertEquals("1", game["FEX_MULTIBLOCK"])
        assertTrue(game.containsKey("VKD3D_FEATURE_LEVEL"))
        assertNull(game["VKD3D_FEATURE_LEVEL"])
        val other = GameEnvironment.effective(config, "COMPATIBILITY", "43")
        assertEquals("0", other["FEX_MULTIBLOCK"])
        assertEquals("12_2", other["VKD3D_FEATURE_LEVEL"])
    }

    @Test fun graphicsDefaultsRespectExistingEditsAndRemovals() {
        val config = GameEnvironment.Config(shared = mapOf("VKD3D_FEATURE_LEVEL" to "12_0"))
            .withEntries("42", mapOf("VKD3D_SHADER_MODEL" to null))
        val game = GameEnvironment.effective(config, "", "42")
        assertEquals("12_0", game["VKD3D_FEATURE_LEVEL"])
        assertTrue(game.containsKey("VKD3D_SHADER_MODEL"))
        assertNull(game["VKD3D_SHADER_MODEL"])
        val restored = GameEnvironment.effective(config.withEntries("42", emptyMap()), "", "42")
        assertEquals("6_9", restored["VKD3D_SHADER_MODEL"])
    }

    @Test fun dotnetRuntimeFitsAndroidAddressSpaceForEveryGame() {
        val values = GameEnvironment.effective(GameEnvironment.Config(), "PERFORMANCE", "43")
        assertEquals("800000000", values["DOTNET_GCRegionRange"])
        assertEquals("0", values["DOTNET_EnableWriteXorExecute"])
    }

    @Test fun dotnetHeapRangeIsBareHexForNativeAot() {
        for (preset in FexPreset.all) {
            val values = GameEnvironment.effective(GameEnvironment.Config(), preset.id, "3478050")
            assertEquals(32L * 1024 * 1024 * 1024, values.getValue("DOTNET_GCRegionRange")!!.toLong(16))
        }
    }

    @Test fun dotnetHeapRangeRespectsSharedAndGameOverrides() {
        val config = GameEnvironment.Config(
            shared = mapOf("DOTNET_GCRegionRange" to "400000000"),
            games = mapOf("3478050" to mapOf("DOTNET_GCRegionRange" to "800000000")),
        )
        assertEquals("800000000", GameEnvironment.effective(config, "", "3478050")["DOTNET_GCRegionRange"])
        assertEquals("400000000", GameEnvironment.effective(config, "", "43")["DOTNET_GCRegionRange"])
    }

    @Test fun multiSelectionPreservesCustomTokensAndRemovesToggledValues() {
        assertEquals("fps,custom_token,frametimes", GameEnvironmentOptions.toggle("fps,custom_token", "frametimes"))
        assertEquals("custom_token", GameEnvironmentOptions.toggle("fps,custom_token", "fps"))
        assertEquals("", GameEnvironmentOptions.toggle("fps", "fps"))
        assertEquals("fps", GameEnvironmentOptions.toggle("", "fps"))
    }

    @Test fun switchingPresetDropsOldPresetOnlyVariables() {
        val config = GameEnvironment.Config()
        assertEquals("none", GameEnvironment.effective(config, "EXTREME", "")["FEX_SMCCHECKS"])
        assertFalse(GameEnvironment.effective(config, "COMPATIBILITY", "").containsKey("FEX_SMCCHECKS"))
        assertFalse(GameEnvironment.effective(config, "", "").containsKey("FEX_MULTIBLOCK"))
    }

    @Test fun resettingOneProfilePreservesOtherProfiles() {
        val config = GameEnvironment.Config(shared = mapOf("CUSTOM" to "shared"))
            .withEntries("42", mapOf("CUSTOM" to "game"))
            .withEntries("43", mapOf("CUSTOM" to "other"))
            .withEntries("42", emptyMap())
        assertEquals("shared", GameEnvironment.effective(config, "", "42")["CUSTOM"])
        assertEquals("other", GameEnvironment.effective(config, "", "43")["CUSTOM"])
        assertFalse(config.games.containsKey("42"))
    }

    @Test fun validationPreservesLiteralValuesAndRejectsInvalidKeys() {
        assertTrue(GameEnvironment.validName("mesa_glthread"))
        assertFalse(GameEnvironment.validName("X=Y"))
        assertFalse(GameEnvironment.validName("1VAR"))
        assertFalse(GameEnvironment.validName("VAR\n"))
        assertTrue(GameEnvironment.validValue("a=b 'quote' $(touch file) ; $"))
        assertTrue(GameEnvironment.validValue(""))
        assertFalse(GameEnvironment.validValue("bad\u0000value"))
        assertFalse(GameEnvironment.validValue("x".repeat(8193)))
        assertTrue(GameEnvironment.validScope("4294967295"))
        for (scope in listOf("0", "-1", "01", "+1", "4294967296", "../42")) assertFalse(GameEnvironment.validScope(scope))
    }

    @Test fun gameFexPresetOverridesGeneralAndCanInheritAgain() {
        val performance = GameEnvironment.withGameFexPreset(GameEnvironment.Config(), "42", "PERFORMANCE")
        assertEquals("PERFORMANCE", GameEnvironment.gameFexPreset(performance, "42"))
        assertEquals("0", GameEnvironment.effective(performance, "STABILITY", "42")["FEX_TSOENABLED"])
        assertEquals("1", GameEnvironment.effective(performance, "STABILITY", "43")["FEX_TSOENABLED"])

        val inherited = GameEnvironment.withGameFexPreset(performance, "42", null)
        assertNull(GameEnvironment.gameFexPreset(inherited, "42"))
        assertFalse(inherited.games.containsKey("42"))
    }

    @Test fun gameFexPresetPreservesOtherOverridesAndRecognizesCustomValues() {
        val original = GameEnvironment.Config(games = mapOf("42" to mapOf("CUSTOM" to "value", "FEX_MULTIBLOCK" to "7")))
        assertEquals(GameEnvironment.CUSTOM_FEX_PRESET, GameEnvironment.gameFexPreset(original, "42"))

        val compatibility = GameEnvironment.withGameFexPreset(original, "42", "COMPATIBILITY")
        assertEquals("value", compatibility.games.getValue("42")["CUSTOM"])
        assertEquals("COMPATIBILITY", GameEnvironment.gameFexPreset(compatibility, "42"))
    }

    @Test fun gameSyncChoiceMatchesTheAppWideChoiceAndCanInheritAgain() {
        val base = GameEnvironment.Config(games = mapOf("42" to mapOf("FEX_TSOENABLED" to "1", "PROTON_NO_ESYNC" to "1")))
        assertEquals(GameEnvironment.CUSTOM_SYNC, GameEnvironment.gameSync(base, "42"))
        assertNull(GameEnvironment.gameSync(base, "43"))
        for (backend in GameEnvironment.syncBackends) {
            val chosen = GameEnvironment.withGameSync(base, "42", backend)
            assertEquals(backend, GameEnvironment.gameSync(chosen, "42"))
            assertEquals("1", chosen.games["42"]!!["FEX_TSOENABLED"])
        }
        val ntsync = GameEnvironment.withGameSync(base, "42", "ntsync").games["42"]!!
        assertEquals(listOf("1", "0", "1"), listOf(ntsync["BL_SYNC"], ntsync["BL_FSYNC_FIRST"], ntsync["BL_SYNC_FALLBACK"]))
        assertFalse(ntsync.containsKey("PROTON_NO_ESYNC"))
        val fsync = GameEnvironment.withGameSync(base, "42", "fsync").games["42"]!!
        assertEquals(listOf("0", "1", "1", "1"), listOf(fsync["BL_SYNC"], fsync["BL_FSYNC_FIRST"], fsync["BL_SYNC_FALLBACK"], fsync["PROTON_NO_ESYNC"]))
        val wineserver = GameEnvironment.withGameSync(base, "42", "wineserver").games["42"]!!
        assertEquals(listOf("0", "0", "0"), listOf(wineserver["BL_SYNC"], wineserver["BL_FSYNC_FIRST"], wineserver["BL_SYNC_FALLBACK"]))
        val inherited = GameEnvironment.withGameSync(base, "42", null)
        assertNull(GameEnvironment.gameSync(inherited, "42"))
        assertEquals(mapOf("FEX_TSOENABLED" to "1"), inherited.games["42"])
        assertTrue(GameEnvironment.withGameSync(GameEnvironment.Config(), "42", null).games.isEmpty())
    }
}
