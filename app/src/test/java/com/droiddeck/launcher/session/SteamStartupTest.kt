package com.droiddeck.launcher.session

import android.content.Context
import android.content.Intent
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SteamStartupTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Before fun setUp() {
        prefs.edit().clear().commit()
        SessionState.running = false
        SessionState.launcherVisible = true
        SessionState.phase = SessionPhase.IDLE
    }

    @After fun tearDown() {
        SessionState.running = false
        SessionState.launcherVisible = false
        SessionState.phase = SessionPhase.IDLE
        SessionState.mode = SessionService.MODE_STEAM
    }

    @Test fun startupIsOptInAndEachChoiceIsRemembered() {
        assertEquals(SteamStartup.OFF, SessionPrefs.steamStartup(app))
        for (mode in SteamStartup.entries) {
            SessionPrefs.setSteamStartup(app, mode)
            assertEquals(mode, SessionPrefs.steamStartup(app))
        }
    }

    @Test fun existingOpenSteamChoiceIsMovedOnceAndTheOldKeyIsRemoved() {
        prefs.edit().putBoolean("runSteamAtStartup", true).commit()
        assertEquals(SteamStartup.OPEN, SessionPrefs.steamStartup(app))
        assertFalse(prefs.contains("runSteamAtStartup"))
        SessionPrefs.setSteamStartup(app, SteamStartup.BACKGROUND)
        assertEquals(SteamStartup.BACKGROUND, SessionPrefs.steamStartup(app))
    }

    @Test fun incompleteSetupDoesNotStartAServiceOrInstallAnything() {
        SessionPrefs.setSteamStartup(app, SteamStartup.BACKGROUND)
        SessionService.prepareSteam(app)
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(SessionState.running)
        assertEquals(SessionPhase.IDLE, SessionState.phase)
    }

    @Test fun configuredBackgroundStartupRequestsTheExistingServiceOnce() {
        installRuntimeFiles()
        SessionPrefs.setSteamStartup(app, SteamStartup.BACKGROUND)
        SessionService.prepareSteam(app)
        assertEquals("com.droiddeck.launcher.PREPARE_STEAM", shadowOf(app).nextStartedService.action)
        SessionState.running = true
        SessionService.prepareSteam(app)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun startupAndTeardownExcludeAnotherBackgroundRequest() {
        installRuntimeFiles()
        SessionPrefs.setSteamStartup(app, SteamStartup.BACKGROUND)
        for (phase in SessionPhase.entries.filter { it !in setOf(SessionPhase.IDLE, SessionPhase.FAILED) }) {
            SessionState.phase = phase
            SessionService.prepareSteam(app)
            assertNull(phase.name, shadowOf(app).nextStartedService)
        }
    }

    @Test fun missingProtonSeedLeavesSetupToPlay() {
        installRuntimeFiles()
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/steamapps/appmanifest_4427310.acf").delete()
        SessionPrefs.setSteamStartup(app, SteamStartup.BACKGROUND)
        SessionService.prepareSteam(app)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun backgroundStartupWaitsForProtonInstallsAndEnoughFreeSpace() {
        installRuntimeFiles()
        SessionPrefs.setSteamStartup(app, SteamStartup.BACKGROUND)
        ReflectionHelpers.setStaticField(ProtonExtras::class.java, "installInProgress", true)
        try {
            SessionService.prepareSteam(app)
            assertNull(shadowOf(app).nextStartedService)
        } finally { ReflectionHelpers.setStaticField(ProtonExtras::class.java, "installInProgress", false) }
        org.robolectric.shadows.ShadowStatFs.registerStats(app.filesDir.path, 10_000_000, 1, 1)
        SessionService.prepareSteam(app)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun leavingTheLauncherBeforeTheServiceReceivesItsRequestCancelsPreparation() {
        installRuntimeFiles()
        SessionPrefs.setSteamStartup(app, SteamStartup.BACKGROUND)
        SessionService.prepareSteam(app)
        val request = requireNotNull(shadowOf(app).nextStartedService)
        SessionService.setLauncherVisible(app, false)
        val service = Robolectric.buildService(SessionService::class.java).get()
        service.onStartCommand(request, 0, 1)
        assertFalse(SessionState.running)
        assertEquals(SessionPhase.IDLE, SessionState.phase)
    }

    @Test fun launcherVisibilityDoesNotAutomaticallyRestartAStoppedSession() {
        SessionPrefs.setSteamStartup(app, SteamStartup.BACKGROUND)
        SessionService.setLauncherVisible(app, true)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun preparationStaysAwakeOnlyWhileTheLauncherAndScreenAreVisible() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        ReflectionHelpers.setField(service, "activityVisible", false)
        ReflectionHelpers.setField(service, "preparingSteam", true)
        assertTrue(inUse(service))
        SessionState.launcherVisible = false
        assertFalse(inUse(service))
        SessionState.launcherVisible = true
        ReflectionHelpers.setField(service, "screenOn", false)
        assertFalse(inUse(service))
    }

    @Test fun attachingTheSessionScreenEndsTheLauncherPreparationHold() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        SessionState.running = true
        ReflectionHelpers.setField(service, "preparingSteam", true)
        service.onStartCommand(Intent().setAction("com.droiddeck.launcher.ACTIVITY_VISIBLE"), 0, 1)
        assertFalse(ReflectionHelpers.getField(service, "preparingSteam"))
        service.onStartCommand(Intent().setAction("com.droiddeck.launcher.ACTIVITY_HIDDEN"), 0, 2)
        assertFalse(inUse(service))
    }

    @Test fun anExistingDesktopSessionIsNotReplacedByALatePreparationRequest() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        SessionPrefs.setSteamStartup(app, SteamStartup.BACKGROUND)
        SessionState.mode = SessionService.MODE_DESKTOP
        SessionState.running = true
        SessionState.phase = SessionPhase.READY
        service.onStartCommand(Intent().setAction("com.droiddeck.launcher.PREPARE_STEAM"), 0, 1)
        assertTrue(SessionState.running)
        assertEquals(SessionService.MODE_DESKTOP, SessionState.mode)
        assertEquals(SessionPhase.READY, SessionState.phase)
    }

    private fun inUse(service: SessionService): Boolean = ReflectionHelpers.callInstanceMethod(service, "getSessionInUse")

    private fun installRuntimeFiles() {
        val root = LinuxRuntime.rootDir(app)
        for (file in listOf(File(root, "usr/bin/gamescope"), File(root, LinuxRuntime.SESSION_SCRIPT.drop(1)),
            LinuxRuntime.prootBinary(app), LinuxRuntime.prootLoader(app),
            File(root, "root/.local/share/Steam/steamapps/appmanifest_4427310.acf"))) {
            file.parentFile!!.mkdirs()
            file.writeText("")
        }
        org.robolectric.shadows.ShadowStatFs.registerStats(app.filesDir.path, 10_000_000, 8_000_000, 8_000_000)
    }
}
