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
