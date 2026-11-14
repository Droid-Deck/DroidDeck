package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ShaderPrecachePrefsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        SessionPrefs.steamRoot(context, SessionPrefs.steamClientArch(context)).deleteRecursively()
    }

    @Test fun clientWithoutConfigStartsWithPrecachingOff() {
        assertEquals(false, SessionPrefs.pendingShaderPrecache(context))
        assertEquals(false, SessionPrefs.shaderPrecache(context))
    }

    @Test fun existingConfigKeepsTheClientsChoice() {
        val config = SessionPrefs.steamRoot(context, SessionPrefs.steamClientArch(context)).resolve("config/config.vdf")
        config.parentFile!!.mkdirs()
        config.writeText("\"InstallConfigStore\" {}")
        assertNull(SessionPrefs.pendingShaderPrecache(context))
        assertEquals(true, SessionPrefs.shaderPrecache(context))
    }
}
