package com.droiddeck.launcher.session

import android.content.Context
import android.content.Intent
import android.view.Display
import com.droiddeck.launcher.glasses.Glasses
import com.droiddeck.launcher.glasses.GlassesState
import com.droiddeck.launcher.glasses.GlassesSettings
import kotlinx.coroutines.flow.MutableStateFlow
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.AddonPack
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
    private val glassesState get() = ReflectionHelpers.getStaticField<MutableStateFlow<GlassesState>>(Glasses::class.java, "_state")
    private val glassesSettings get() = ReflectionHelpers.getStaticField<MutableStateFlow<GlassesSettings>>(Glasses::class.java, "_settings")
    private lateinit var savedGlasses: GlassesState
    private lateinit var savedGlassesSettings: GlassesSettings

    @Before fun setUp() {
        savedGlasses = glassesState.value
        savedGlassesSettings = glassesSettings.value
        glassesState.value = GlassesState()
        glassesSettings.value = GlassesSettings()
        prefs.edit().clear().commit()
        SessionState.running = false
        SessionState.stopRequested = false
        SessionState.hdr = false
        SessionState.launcherVisible = true
        SessionState.phase = SessionPhase.IDLE
        SessionPrefs.setSteamStartup(app, SteamStartup.BACKGROUND)
        installRuntimeFiles()
    }

    @After fun tearDown() {
        glassesState.value = savedGlasses
        glassesSettings.value = savedGlassesSettings
        SessionState.running = false
        SessionState.launcherVisible = false
        SessionState.phase = SessionPhase.IDLE
        SessionState.mode = SessionService.MODE_STEAM
    }

    @Test fun startupIsOptInAndOnlyBackgroundPrepares() {
        prefs.edit().clear().commit()
        assertEquals(SteamStartup.OFF, SessionPrefs.steamStartup(app))
        for (mode in SteamStartup.entries) {
            SessionPrefs.setSteamStartup(app, mode)
            assertEquals(mode, SessionPrefs.steamStartup(app))
            assertEquals(mode == SteamStartup.BACKGROUND, request() != null)
        }
    }

    @Test fun legacySettingsMigrateOnce() {
        for (enabled in listOf(false, true)) {
            prefs.edit().putBoolean("runSteamAtStartup", enabled).commit()
            assertEquals(if (enabled) SteamStartup.OPEN else SteamStartup.OFF, SessionPrefs.steamStartup(app))
            assertFalse(prefs.contains("runSteamAtStartup"))
            SessionPrefs.setSteamStartup(app, SteamStartup.BACKGROUND)
            assertEquals(SteamStartup.BACKGROUND, SessionPrefs.steamStartup(app))
        }
    }

    @Test fun preparationRequiresCompleteIdleSetupAndEnoughSpace() {
        assertNotNull(request())
        val root = LinuxRuntime.rootDir(app)
        for (path in listOf(LinuxRuntime.SESSION_SCRIPT.drop(1), "root/.local/share/Steam/steamapps/appmanifest_4427310.acf")) {
            val file = File(root, path)
            file.delete()
            assertNull(path, request())
            file.writeText("")
        }
        for (phase in SessionPhase.entries.filter { it !in setOf(SessionPhase.IDLE, SessionPhase.FAILED) }) {
            SessionState.phase = phase
            assertNull(phase.name, request())
        }
        SessionState.phase = SessionPhase.IDLE
        ReflectionHelpers.setStaticField(ProtonExtras::class.java, "installInProgress", true)
        try { assertNull(request()) }
        finally { ReflectionHelpers.setStaticField(ProtonExtras::class.java, "installInProgress", false) }
        org.robolectric.shadows.ShadowStatFs.registerStats(app.filesDir.path, 10_000_000, 1, 1)
        assertNull(request())
        assertFalse(SessionState.running)
    }

    @Test fun cancellationAndExistingModesCannotBeReplacedByPreparation() {
        SessionState.stopRequested = true
        assertNull(request())
        SessionState.stopRequested = false
        val service = Robolectric.buildService(SessionService::class.java).get()
        for (mode in listOf(SessionService.MODE_STEAM, SessionService.MODE_DESKTOP, SessionService.MODE_RUN)) {
            SessionState.mode = mode
            SessionState.running = true
            SessionState.phase = SessionPhase.READY
            assertNull(request())
            service.onStartCommand(Intent().setAction("com.droiddeck.launcher.PREPARE_STEAM"), 0, 1)
            assertTrue(SessionState.running)
            assertEquals(mode, SessionState.mode)
            assertEquals(SessionPhase.READY, SessionState.phase)
        }
    }

    @Test fun hdrUsesTheSelectedOutputAndDefersWhenItIsUnknown() {
        SessionPrefs.setHdr(app, SessionService.MODE_STEAM, true)
        assertEquals(Display.DEFAULT_DISPLAY, requireNotNull(request()).getIntExtra("displayId", -1))
        val id = org.robolectric.shadows.ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        glassesState.value = GlassesState(present = true, displayId = id)
        val request = requireNotNull(request())
        assertEquals(id, request.getIntExtra("displayId", -1))
        assertEquals(Display.DEFAULT_DISPLAY, request.getIntExtra("activityDisplayId", -1))
        glassesState.value = GlassesState(present = true)
        assertNull(request())
        glassesSettings.value = GlassesSettings(gameOnGlasses = false)
        assertEquals(Display.DEFAULT_DISPLAY, requireNotNull(request()).getIntExtra("displayId", -1))
    }

    @Test fun queuedPreparationRechecksLauncherAndDisplayAvailability() {
        for (hideLauncher in listOf(true, false)) {
            SessionState.launcherVisible = true
            glassesState.value = GlassesState()
            val request = requireNotNull(request())
            if (hideLauncher) SessionService.setLauncherVisible(app, false)
            else glassesState.value = GlassesState(present = true)
            Robolectric.buildService(SessionService::class.java).get().onStartCommand(request, 0, 1)
            assertFalse(SessionState.running)
            assertEquals(SessionPhase.IDLE, SessionState.phase)
        }
    }

    @Test fun launcherVisibilityNeverRestartsAStoppedSession() {
        SessionService.setLauncherVisible(app, true)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun launcherHoldHonorsVisibilityManualPauseAndTheSessionHandoff() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        ReflectionHelpers.setField(service, "activityVisible", false)
        ReflectionHelpers.setField(service, "preparingSteam", true)
        assertTrue(inUse(service))
        SessionState.launcherVisible = false
        assertFalse(inUse(service))
        SessionState.launcherVisible = true
        ReflectionHelpers.setField(service, "screenOn", false)
        assertFalse(inUse(service))
        ReflectionHelpers.setField(service, "screenOn", true)
        ReflectionHelpers.setField(service, "manualHold", true)
        ReflectionHelpers.setField(service, "nativeSleepToken", "manual-pause")
        for (policy in listOf(SessionPrefs.SUSPEND_AUTO, SessionPrefs.SUSPEND_NATIVE)) {
            ReflectionHelpers.setField(service, "suspendPolicy", policy)
            ReflectionHelpers.callInstanceMethod<Unit>(service, "resumeSteamSleepOnReturn")
            assertEquals("manual-pause", ReflectionHelpers.getField<String>(service, "nativeSleepToken"))
        }
        service.onStartCommand(Intent().setAction("com.droiddeck.launcher.ACTIVITY_VISIBLE"), 0, 1)
        assertFalse(ReflectionHelpers.getField(service, "preparingSteam"))
        service.onStartCommand(Intent().setAction("com.droiddeck.launcher.ACTIVITY_HIDDEN"), 0, 2)
        assertFalse(inUse(service))
    }

    private fun request(): Intent? {
        SessionService.prepareSteam(app)
        return shadowOf(app).nextStartedService
    }

    private fun inUse(service: SessionService): Boolean = ReflectionHelpers.callInstanceMethod(service, "getSessionInUse")

    private fun installRuntimeFiles() {
        val root = LinuxRuntime.rootDir(app)
        val version = BuildConfig.PACK_PROOT_SHA256.ifEmpty { "test-pack" }
        val pack = File(app.filesDir, "addons/proot/$version").apply { mkdirs() }
        File(pack, "libproot.so").writeText("")
        File(pack, "libproot-loader.so").writeText("")
        File(pack, AddonPack.COMPLETE).writeText(version)
        for (file in listOf(File(root, "usr/bin/gamescope"), File(root, LinuxRuntime.SESSION_SCRIPT.drop(1)),
            LinuxRuntime.prootBinary(app), LinuxRuntime.prootLoader(app),
            File(root, "root/.local/share/Steam/steamapps/appmanifest_4427310.acf"))) {
            file.parentFile!!.mkdirs()
            file.writeText("")
        }
        org.robolectric.shadows.ShadowStatFs.registerStats(app.filesDir.path, 10_000_000, 8_000_000, 8_000_000)
    }
}
