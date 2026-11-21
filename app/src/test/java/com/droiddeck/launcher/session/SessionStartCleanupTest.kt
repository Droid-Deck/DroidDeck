package com.droiddeck.launcher.session

import android.app.Application
import com.droiddeck.launcher.core.SessionPart
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SessionStartCleanupTest {
    private lateinit var service: SessionService
    private var wasRunning = false
    private val stopped = mutableListOf<String>()

    @Before fun setup() {
        wasRunning = SessionState.running
        SessionState.running = false
        service = Robolectric.buildService(SessionService::class.java).get()
        ReflectionHelpers.setField(service, "sessionGen", 3)
    }

    @After fun cleanup() { SessionState.running = wasRunning }

    @Test fun cancelledStartStopsStartedComponentsInReverseOrderDespiteStopFailure() {
        val parts = listOf(part("audio"), part("renderer", fails = true), part("network"))
        val components = ReflectionHelpers.getField<MutableList<SessionPart>>(service, "components")
        components.addAll(parts)
        abandon(3, parts)
        assertEquals(listOf("network", "renderer", "audio"), stopped)
        assertTrue(components.isEmpty())
    }

    @Test fun staleOrStillRunningStartCannotClearCurrentComponents() {
        val parts = listOf(part("current"))
        val components = ReflectionHelpers.getField<MutableList<SessionPart>>(service, "components")
        components.addAll(parts)
        abandon(2, parts)
        assertEquals(parts, components)
        assertTrue(stopped.isEmpty())
        SessionState.running = true
        abandon(3, parts)
        assertEquals(parts, components)
        assertTrue(stopped.isEmpty())
    }

    private fun part(name: String, fails: Boolean = false) = object : SessionPart() {
        override fun start() = Unit
        override fun stop() {
            stopped.add(name)
            if (fails) error("stop failed")
        }
    }

    private fun abandon(generation: Int, parts: List<SessionPart>) {
        SessionService::class.java.getDeclaredMethod("abandonStart", Int::class.javaPrimitiveType, File::class.java, List::class.java)
            .apply { isAccessible = true }.invoke(service, generation, File(service.cacheDir, "unused-session"), parts)
    }
}
