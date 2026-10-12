package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GameEnvironmentStoreTest {
    private lateinit var context: Context
    private lateinit var guest: File

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        guest = File(LinuxRuntime.rootDir(context), "root/.config/droiddeck/game-environment.json")
        File(context.filesDir, "game-environment.json").delete()
    }

    @Test fun savedSettingsAndPublishedSettingsPreserveUnsetsAndLiteralValues() {
        val config = GameEnvironment.Config(
            shared = mapOf("LITERAL" to "a=b 'quoted' $(echo test)", "EMPTY" to ""),
            games = mapOf("42" to mapOf("LITERAL" to null, "VKD3D_SHADER_MODEL" to "6_9")),
        )
        GameEnvironmentStore.save(context, config)
        assertEquals(config, GameEnvironmentStore.read(context))
        val published = GameEnvironmentStore.decode(JSONObject(guest.readText()))
        assertEquals("false", published.shared["MESA_SHADER_CACHE_DISABLE"])
        assertEquals("12_2", published.shared["VKD3D_FEATURE_LEVEL"])
        assertEquals("6_9", published.shared["VKD3D_SHADER_MODEL"])
        assertEquals(config.games, published.games)
        assertEquals(config.shared["LITERAL"], published.shared["LITERAL"])
        assertEquals("", published.shared["EMPTY"])
    }

    @Test fun changingFexPresetPublishesForNextGameLaunch() {
        GameEnvironmentStore.save(context, GameEnvironment.Config())
        SessionPrefs.setFexPreset(context, "EXTREME")
        assertEquals("none", JSONObject(guest.readText()).getJSONObject("shared").getString("FEX_SMCCHECKS"))
        SessionPrefs.setFexPreset(context, "")
        assertFalse(JSONObject(guest.readText()).getJSONObject("shared").has("FEX_SMCCHECKS"))
    }

    @Test fun textureFilteringPublishesDxvkOptionsBesideTheProfiles() {
        GameEnvironmentStore.save(context, GameEnvironment.Config(shared = mapOf("DXVK_CONFIG" to "dxvk.tearFree = True")))
        assertFalse(JSONObject(guest.readText()).has(GameEnvironmentStore.DXVK_CONFIG))
        SessionState.upscaleRatio = 1.5f
        SessionPrefs.setTextureAnisotropy(context, 16)
        SessionPrefs.setTextureLodBias(context, com.droiddeck.launcher.core.TextureFiltering.LOD_BIAS_AUTO)
        val json = JSONObject(guest.readText())
        assertEquals(
            "d3d9.samplerAnisotropy = 16; d3d11.samplerAnisotropy = 16; d3d9.samplerLodBias = -0.58; d3d11.samplerLodBias = -0.58",
            json.getString(GameEnvironmentStore.DXVK_CONFIG),
        )
        // The user's own entry is untouched; the launcher appends the options at launch.
        assertEquals("dxvk.tearFree = True", json.getJSONObject("shared").getString("DXVK_CONFIG"))
        assertEquals(GameEnvironment.Config(shared = mapOf("DXVK_CONFIG" to "dxvk.tearFree = True")), GameEnvironmentStore.read(context))
        SessionPrefs.setTextureAnisotropy(context, 0)
        SessionPrefs.setTextureLodBias(context, "0")
        assertFalse(JSONObject(guest.readText()).has(GameEnvironmentStore.DXVK_CONFIG))
        SessionState.upscaleRatio = 0f
    }

    @Test fun changingGameFexPresetPreservesDefaultAndOtherGameSettings() {
        SessionPrefs.setFexPreset(context, "PERFORMANCE_TSO")
        val original = GameEnvironment.withGameFexPreset(
            GameEnvironment.Config(
                shared = mapOf("PROTON_LOG" to "1"),
                games = mapOf("42" to mapOf("DXVK_HUD" to "fps")),
            ),
            "43",
            "COMPATIBILITY",
        )
        GameEnvironmentStore.save(context, original)

        GameEnvironmentStore.setGameFexPreset(context, "42", "STABILITY")

        val saved = GameEnvironmentStore.read(context)
        assertEquals("STABILITY", GameEnvironment.gameFexPreset(saved, "42"))
        assertEquals("fps", saved.entries("42")["DXVK_HUD"])
        assertEquals(original.shared, saved.shared)
        assertEquals(original.entries("43"), saved.entries("43"))
        assertEquals("PERFORMANCE_TSO", SessionPrefs.fexPreset(context))
        val published = GameEnvironmentStore.decode(JSONObject(guest.readText()))
        assertEquals(saved.games, published.games)
        assertEquals("0", published.shared["FEX_HALFBARRIERTSOENABLED"])
        assertEquals("1", published.entries("42")["FEX_HALFBARRIERTSOENABLED"])
    }

    @Test fun gameFexDefaultsAndInheritanceRemainDistinct() {
        SessionPrefs.setFexPreset(context, "EXTREME")
        val original = GameEnvironment.Config(
            games = mapOf("42" to mapOf("DXVK_HUD" to "fps")),
        )
        GameEnvironmentStore.save(context, original)

        GameEnvironmentStore.setGameFexPreset(context, "42", "")

        val explicitDefaults = GameEnvironmentStore.read(context)
        assertEquals("", GameEnvironment.gameFexPreset(explicitDefaults, "42"))
        val published = GameEnvironmentStore.decode(JSONObject(guest.readText()))
        assertTrue(published.entries("42").containsKey("FEX_SMCCHECKS"))
        assertNull(published.entries("42")["FEX_SMCCHECKS"])

        GameEnvironmentStore.setGameFexPreset(context, "42", null)

        assertEquals(original, GameEnvironmentStore.read(context))
        val inherited = GameEnvironmentStore.decode(JSONObject(guest.readText()))
        assertFalse(inherited.entries("42").containsKey("FEX_SMCCHECKS"))
        assertEquals("none", inherited.shared["FEX_SMCCHECKS"])
        assertEquals("EXTREME", SessionPrefs.fexPreset(context))
    }

    @Test fun invalidGamePresetEditsCannotChangeSavedOrPublishedSettings() {
        val original = GameEnvironment.Config(shared = mapOf("PROTON_LOG" to "1"))
        GameEnvironmentStore.save(context, original)
        val published = guest.readText()

        assertThrows(IllegalArgumentException::class.java) {
            GameEnvironmentStore.setGameFexPreset(context, "", "STABILITY")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GameEnvironmentStore.setGameFexPreset(context, "42", GameEnvironment.CUSTOM_FEX_PRESET)
        }

        assertEquals(original, GameEnvironmentStore.read(context))
        assertEquals(published, guest.readText())
    }

    @Test fun targetedFexUpdateDoesNotRestoreEnvironmentClearedByACompletedReset() {
        val original = GameEnvironment.withGameFexPreset(
            GameEnvironment.Config(games = mapOf("42" to mapOf("DXVK_HUD" to "fps"))),
            "42",
            "COMPATIBILITY",
        )
        GameEnvironmentStore.save(context, original)
        val staleEditorSnapshot = GameEnvironmentStore.read(context)
        val resetEnvironment = staleEditorSnapshot.withEntries("42", emptyMap())
        GameEnvironmentStore.save(context, resetEnvironment)

        GameEnvironmentStore.setGameFexPreset(context, "43", "PERFORMANCE_TSO")

        val saved = GameEnvironmentStore.read(context)
        assertTrue(saved.entries("42").isEmpty())
        assertEquals("PERFORMANCE_TSO", GameEnvironment.gameFexPreset(saved, "43"))
        assertFalse(saved.games.containsKey("42"))
    }

    @Test fun unchosenFexPresetPublishesPerformanceTso() {
        GameEnvironmentStore.save(context, GameEnvironment.Config())
        val shared = JSONObject(guest.readText()).getJSONObject("shared")
        assertEquals("1", shared.getString("FEX_TSOENABLED"))
        assertEquals("0", shared.getString("FEX_HALFBARRIERTSOENABLED"))
        assertEquals("1", shared.getString("FEX_X87REDUCEDPRECISION"))
        assertEquals("1", shared.getString("FEX_MULTIBLOCK"))
    }

    @Test fun invalidDataCannotReplaceSavedConfiguration() {
        val original = GameEnvironment.Config(shared = mapOf("CUSTOM" to "ok"))
        GameEnvironmentStore.save(context, original)
        assertTrue(runCatching { GameEnvironmentStore.save(context, GameEnvironment.Config(shared = mapOf("BAD=NAME" to "x"))) }.isFailure)
        assertEquals(original, GameEnvironmentStore.read(context))
    }

    @Test fun customComponentVariablesRemainEditable() {
        val config = GameEnvironment.Config(shared = mapOf("DXVK_ASYNC" to "1"))
        GameEnvironmentStore.save(context, config)
        assertEquals(config, GameEnvironmentStore.read(context))
    }
}
