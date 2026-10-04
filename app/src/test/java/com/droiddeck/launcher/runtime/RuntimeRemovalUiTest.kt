package com.droiddeck.launcher.runtime

import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RuntimeRemovalUiTest {
    private lateinit var dir: File
    @Before fun setUp() {
        dir = Files.createTempDirectory("runtime-removal-ui").toFile()
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
    }
    @After fun tearDown() {
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        RuntimeFileTree.delete(dir, null)
    }

    @Test fun directSessionLaunchDuringRemovalFinishesWithoutStartingTheGuest() {
        val removal = LinuxRuntimeInstaller.beginUninstall(File(dir, "linuxfs"))!!
        try {
            val activity = Robolectric.buildActivity(SessionActivity::class.java).create()
            assertTrue(activity.get().isFinishing)
            // Even lifecycle callbacks delivered after finish must not touch uninitialised UI.
            activity.start().resume().pause().stop().destroy()
            assertFalse(SessionState.running)
            assertEquals(SessionPhase.IDLE, SessionState.phase)
        } finally { assertTrue(removal.run(null)) }
    }

    @Test fun removalIsRefusedWhileASessionIsRunningOrStarting() {
        val context = RuntimeEnvironment.getApplication()
        SessionState.running = true
        assertNull(LinuxRuntimeInstaller.beginUninstall(context))
        SessionState.running = false
        SessionState.phase = SessionPhase.STARTING_COMPOSITOR
        assertNull(LinuxRuntimeInstaller.beginUninstall(context))
        assertFalse(LinuxRuntimeInstaller.isBusy())
    }
}
