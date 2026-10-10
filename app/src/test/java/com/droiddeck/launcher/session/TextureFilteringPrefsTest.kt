package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.TextureFiltering
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TextureFilteringPrefsTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "game-environment.json").delete()
        File(LinuxRuntime.rootDir(context), "root/.config/droiddeck/game-environment.json").delete()
    }

    @Test fun gameChoicesRemainSeparateFromDefaultAndCanInheritAgain() {
        SessionPrefs.setTextureAnisotropy(context, 16)
        SessionPrefs.setTextureLodBias(context, TextureFiltering.LOD_BIAS_AUTO)
        SessionPrefs.setGameTextureAnisotropy(context, "42", 4)
        SessionPrefs.setGameTextureLodBias(context, "42", "-0.5")

        assertEquals(16, SessionPrefs.textureAnisotropy(context))
        assertEquals(TextureFiltering.LOD_BIAS_AUTO, SessionPrefs.textureLodBias(context))
        assertEquals(SessionPrefs.GameTextureFiltering(4, "-0.5"), SessionPrefs.gameTextureFiltering(context, "42"))
        assertEquals(SessionPrefs.GameTextureFiltering(), SessionPrefs.gameTextureFiltering(context, "43"))

        SessionPrefs.setGameTextureAnisotropy(context, "42", null)
        SessionPrefs.setGameTextureLodBias(context, "42", null)

        assertEquals(SessionPrefs.GameTextureFiltering(), SessionPrefs.gameTextureFiltering(context, "42"))
        assertFalse(SessionPrefs.gameTextureFiltering(context).containsKey("42"))
    }

    @Test fun publishedDxvkOptionsUseGameOverridesWithoutChangingDefault() {
        SessionPrefs.setTextureAnisotropy(context, 16)
        SessionPrefs.setGameTextureAnisotropy(context, "42", 0)
        SessionPrefs.setGameTextureLodBias(context, "42", TextureFiltering.LOD_BIAS_OFF)

        val published = JSONObject(
            File(LinuxRuntime.rootDir(context), "root/.config/droiddeck/game-environment.json").readText(),
        )
        assertTrue(published.getString(GameEnvironmentStore.DXVK_CONFIG).contains("samplerAnisotropy = 16"))
        assertEquals("", published.getJSONObject(GameEnvironmentStore.DXVK_CONFIG_GAMES).getString("42"))
        assertNull(SessionPrefs.gameTextureFiltering(context, "43").anisotropy)
    }

    @Test fun clearingGameChoicesPreservesOtherProfiles() {
        SessionPrefs.setGameTextureAnisotropy(context, "42", 4)
        SessionPrefs.setGameTextureLodBias(context, "42", "-0.5")
        SessionPrefs.setGameTextureAnisotropy(context, "43", 8)

        SessionPrefs.clearGameTextureFiltering(context, "42")

        assertEquals(SessionPrefs.GameTextureFiltering(), SessionPrefs.gameTextureFiltering(context, "42"))
        assertEquals(8, SessionPrefs.gameTextureFiltering(context, "43").anisotropy)
    }
}
