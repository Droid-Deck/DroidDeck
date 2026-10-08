package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GameProfileManagerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "game-environment.json").delete()
        File(context.filesDir, "wincomponents.json").delete()
        File(context.filesDir, "components").deleteRecursively()
        File(LinuxRuntime.rootDir(context), "root/.local/share/droiddeck-compat").deleteRecursively()
    }

    @Test
    fun resetClearsOnlyTheSelectedGameProfile() {
        val game = "42"
        val other = "43"
        GameEnvironmentStore.save(
            context,
            GameEnvironment.Config(
                games = mapOf(
                    game to mapOf("DXVK_HUD" to "fps"),
                    other to mapOf("DXVK_HUD" to "compiler"),
                ),
            ),
        )
        ProtonDefault.requestGame(context, game, proton("game"))
        ProtonDefault.requestGame(context, other, proton("other"))
        seedComponentProfiles(game, other)
        SessionPrefs.setGameTextureAnisotropy(context, game, 4)
        SessionPrefs.setGameTextureAnisotropy(context, other, 8)
        WinComponents.setPicks(context, game, listOf("vcrun2022"))
        WinComponents.setPicks(context, other, listOf("dotnet48"))

        GameProfileManager.reset(context, game)

        val environment = GameEnvironmentStore.read(context)
        assertFalse(environment.games.containsKey(game))
        assertEquals("compiler", environment.entries(other)["DXVK_HUD"])
        assertNull(ProtonDefault.gameChoice(context, game))
        assertEquals("other", ProtonDefault.gameChoice(context, other)?.dir)
        assertEquals(emptyMap<String, String>(), ComponentsManager.gameComponents(context, game))
        assertEquals("other.wcp", ComponentsManager.gameComponents(context, other)["dxvk"])
        assertEquals(SessionPrefs.GameTextureFiltering(), SessionPrefs.gameTextureFiltering(context, game))
        assertEquals(8, SessionPrefs.gameTextureFiltering(context, other).anisotropy)
        assertEquals(emptyList<String>(), WinComponents.picks(context, game))
        assertEquals(listOf("dotnet48"), WinComponents.picks(context, other))
    }

    private fun proton(name: String) = ComponentsManager.Proton(
        id = name,
        name = name,
        dir = File(context.filesDir, name),
        guestPath = "/proton/$name",
        version = "1",
        valve = false,
    )

    private fun seedComponentProfiles(game: String, other: String) {
        val components = File(context.filesDir, "components").apply { mkdirs() }
        File(components, "state.json").writeText(
            JSONObject().put(
                "profiles",
                JSONObject()
                    .put(game, JSONObject().put("dxvk", "game.wcp"))
                    .put(other, JSONObject().put("dxvk", "other.wcp")),
            ).toString(),
        )
    }
}
