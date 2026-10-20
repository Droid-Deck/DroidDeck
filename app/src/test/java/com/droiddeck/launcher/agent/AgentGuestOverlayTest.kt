package com.droiddeck.launcher.agent

import android.app.Application
import android.os.Looper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class AgentGuestOverlayTest {
    @After fun cleanUp() {
        AgentGuest.stop()
        idle()
    }

    @Test fun overlayChangesPublishWhileFocusedGameStaysTheSame() {
        publish(true)
        idle()
        assertTrue(AgentGuest.steamOverlayVisible)
        val revision = AgentGuest.focusRevision
        publish(false)
        idle()
        assertFalse(AgentGuest.steamOverlayVisible)
        assertEquals(revision + 1, AgentGuest.focusRevision)
    }

    @Test fun stoppingAgentClearsAnOpenOverlay() {
        publish(true)
        idle()
        AgentGuest.stop()
        idle()
        assertFalse(AgentGuest.steamOverlayVisible)
    }

    @Test fun queuedUpdatesFromStoppedSessionCannotRestoreOverlay() {
        val revision = AgentGuest.focusRevision
        publish(true)
        AgentGuest.stop()
        idle()
        assertFalse(AgentGuest.steamOverlayVisible)
        assertEquals(revision, AgentGuest.focusRevision)
    }

    private fun publish(visible: Boolean) {
        val snapshot = JSONObject("""{"focusedApp":769,"focusableApps":[769],"displays":{":0":[{"map":"viewable","overlay":1,"inputFocus":1}]}}""")
        if (!visible) snapshot.getJSONObject("displays").getJSONArray(":0").getJSONObject(0).put("inputFocus", 0)
        val generation: Int = ReflectionHelpers.getField(AgentGuest, "generation")
        ReflectionHelpers.callInstanceMethod<Unit>(AgentGuest, "recordFocus",
            ReflectionHelpers.ClassParameter.from(JSONObject::class.java, snapshot),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, generation))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
