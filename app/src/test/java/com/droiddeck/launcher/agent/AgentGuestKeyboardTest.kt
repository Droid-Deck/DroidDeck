package com.droiddeck.launcher.agent

import android.app.Application
import android.os.FileObserver
import android.os.Looper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
class AgentGuestKeyboardTest {
    private lateinit var observer: FileObserver

    @Before fun setUp() {
        observer = newObserver()
        ReflectionHelpers.setField(AgentGuest, "observer", observer)
    }

    @After fun cleanUp() {
        AgentGuest.stop()
        idle()
    }

    @Test fun keyboardVisibilityFollowsItsOwnEvents() {
        publish(true)
        idle()
        assertTrue(AgentGuest.steamKeyboard)
        publish(false)
        idle()
        assertFalse(AgentGuest.steamKeyboard)
    }

    @Test fun stoppingAgentClearsAnOpenKeyboard() {
        publish(true)
        idle()
        AgentGuest.stop()
        idle()
        assertFalse(AgentGuest.steamKeyboard)
    }

    @Test fun queuedUpdatesFromReplacedObserverCannotRestoreKeyboard() {
        publish(true)
        ReflectionHelpers.setField(AgentGuest, "observer", newObserver())
        idle()
        assertFalse(AgentGuest.steamKeyboard)
    }

    @Test fun eventsFromPreviousObserverAreIgnored() {
        ReflectionHelpers.setField(AgentGuest, "observer", newObserver())
        publish(true)
        idle()
        assertFalse(AgentGuest.steamKeyboard)
    }

    private fun publish(visible: Boolean) {
        ReflectionHelpers.callInstanceMethod<Unit>(AgentGuest, "recordKeyboard",
            ReflectionHelpers.ClassParameter.from(FileObserver::class.java, observer),
            ReflectionHelpers.ClassParameter.from(JSONObject::class.java, JSONObject().put("steamKeyboard", visible)))
    }

    @Suppress("DEPRECATION")
    private fun newObserver() = object : FileObserver("/unused", CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {}
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
