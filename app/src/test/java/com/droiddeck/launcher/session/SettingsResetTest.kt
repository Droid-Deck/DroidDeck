package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.AppUiPrefs
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.InputAction
import com.droiddeck.launcher.input.InputControl
import com.droiddeck.launcher.input.InputProfile
import com.droiddeck.launcher.input.InputProfiles
import com.droiddeck.launcher.input.InputRole
import com.droiddeck.launcher.input.InputSurfaceLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsResetTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val session get() = prefs("session")

    @Before fun clear() {
        listOf("session", "controller", "input-profiles", "input-surfaces", "app-ui", "frame_gen", "steam_touch", "agent").forEach {
            prefs(it).edit().clear().commit()
        }
    }

    @Test fun settingsGoBackToTheirDefaults() {
        SessionPrefs.setTheme(app, "ocean")
        SessionPrefs.setBackDoubleTapMs(app, 100)
        SessionPrefs.setUnfoldedControlsWithController(app, true)
        SessionPrefs.setNoXalia(app, false)
        session.edit().putInt("fpsLimit.steam", 30).commit()
        SessionPrefs.setHdr(app, SessionService.MODE_DESKTOP, true)
        SessionPrefs.setResolutionChoice(app, SessionService.MODE_STEAM, "1280x800")
        SessionPrefs.setGuestHostname(app, "MyDeck")
        assertTrue(SessionPrefs.setGameDebugLogsEnabled(app, true))
        SessionPrefs.setHudShowFps(app, false)
        SessionPrefs.setHudShowBattery(app, false)
        SessionPrefs.setHudShowGraph(app, false)
        SessionPrefs.setHudColor(app, "amber")
        SessionPrefs.setHudOpacity(app, 40)
        SessionPrefs.setDroidDeckHudConfig(app, "hudSize=mega,showFPSGraph=0")
        SessionPrefs.setDroidDeckHudPosition(app, 120f, 40f)
        ControllerPrefs.setOpacity(app, 40)
        AppUiPrefs.setScale(app, 150)
        FrameGen.set(app, FrameGen.Mode(FrameGen.ENGINE_DDFG, 3))
        prefs("steam_touch").edit().putBoolean("gyro", false).commit()

        assertTrue(SettingsReset.resetAll(app, gameProfiles = false))

        assertEquals("graphite", SessionPrefs.theme(app))
        assertEquals(500, SessionPrefs.backDoubleTapMs(app))
        assertFalse(SessionPrefs.unfoldedControlsWithController(app))
        assertTrue(SessionPrefs.noXalia(app))
        assertFalse(session.contains("fpsLimit.steam"))
        assertFalse(SessionPrefs.hdr(app, SessionService.MODE_DESKTOP))
        assertFalse(SessionPrefs.resolutionChosen(app, SessionService.MODE_STEAM))
        assertEquals(SessionPrefs.DEFAULT_GUEST_HOSTNAME, SessionPrefs.guestHostname(app))
        assertFalse(SessionPrefs.gameDebugLogsEnabled(app))
        val guest = java.io.File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(app),
            "root/.config/droiddeck/game-environment.json")
        assertFalse(org.json.JSONObject(guest.readText()).getBoolean(GameEnvironmentStore.GAME_DEBUG_LOGS))
        assertTrue(SessionPrefs.hudShowFps(app))
        assertTrue(SessionPrefs.hudShowBattery(app))
        assertTrue(SessionPrefs.hudShowGraph(app))
        assertEquals(SessionPrefs.HUD_COLOR_MINT, SessionPrefs.hudColor(app))
        assertEquals(SessionPrefs.HUD_OPACITY_DEFAULT, SessionPrefs.hudOpacity(app))
        assertNull(SessionPrefs.droidDeckHudConfig(app))
        assertNull(SessionPrefs.droidDeckHudPosition(app))
        assertEquals(100, ControllerPrefs.read(app).opacity)
        assertEquals(AppUiPrefs.DEFAULT_SCALE, AppUiPrefs.scale(app))
        assertEquals(FrameGen.Mode.OFF.engine, FrameGen.engine(app))
        assertFalse(prefs("steam_touch").contains("gyro"))
    }

    @Test fun aFailedDebugOptOutDoesNotHideTheEnabledPreference() {
        assertTrue(SessionPrefs.setGameDebugLogsEnabled(app, true))
        val directory = java.io.File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(app),
            "root/.config/droiddeck")
        directory.deleteRecursively()
        directory.writeText("blocks configuration publication")
        try {
            assertFalse(SettingsReset.resetAll(app, gameProfiles = false))
            assertTrue(SessionPrefs.gameDebugLogsEnabled(app))
        } finally {
            directory.delete()
            directory.mkdirs()
            GameEnvironmentStore.publish(app)
        }
    }

    @Test fun libraryAndRecordsAreKept() {
        val folder = "/storage/emulated/0/Games/Foo"
        SessionPrefs.setAddedGameName(app, folder, "Foo")
        SessionPrefs.setAddedGameExe(app, folder, "$folder/foo.exe")
        SessionPrefs.addedGameAppId(app, folder, 1234L)
        SessionPrefs.setAddedGameRemoved(app, "/storage/emulated/0/Games/Bar", true)
        SessionPrefs.setGamesFoldersMigrated(app)
        SessionPrefs.setGpuAutoInstalled(app, setOf("turnip-a"))
        SessionPrefs.setRomsDir(app, "/storage/emulated/0/ROMs")
        SessionPrefs.setGameStorage(app, "/storage/ABCD-1234", "SD Card")
        SessionPrefs.setMicAsked(app)
        prefs("steam_touch").edit().putString("copiedLayout", "a:1,2").commit()

        SettingsReset.resetAll(app, gameProfiles = false)

        assertEquals("Foo", SessionPrefs.addedGameName(app, folder))
        assertEquals("$folder/foo.exe", SessionPrefs.addedGameExe(app, folder))
        assertEquals(1234L, SessionPrefs.addedGameStoredAppId(app, folder))
        assertEquals(listOf("/storage/emulated/0/Games/Bar"), SessionPrefs.removedAddedGames(app))
        assertTrue(SessionPrefs.gamesFoldersMigrated(app))
        assertEquals(setOf("turnip-a"), SessionPrefs.gpuAutoInstalled(app))
        assertEquals("/storage/emulated/0/ROMs", SessionPrefs.romsDir(app))
        assertEquals("/storage/ABCD-1234", SessionPrefs.gameStorage(app))
        assertTrue(SessionPrefs.micAsked(app))
        assertEquals("a:1,2", prefs("steam_touch").getString("copiedLayout", null))
    }

    @Test fun inputProfilesAndSurfacesResetWithoutRestoringLegacyMappings() {
        ControllerPrefs.setTarget(app, "a", "b")
        InputProfiles.select(app, InputProfile.DESKTOP)
        InputProfiles.save(app, InputProfile.defaults(InputProfile.GAME)
            .withBinding(InputRole.PHYSICAL, InputControl.A, InputAction.Disabled))
        InputSurfaceLayout.save(app, InputProfile.DESKTOP, emptyList())

        assertTrue(SettingsReset.resetAll(app, gameProfiles = false))

        assertEquals(InputProfiles.AUTO, InputProfiles.selection(app))
        assertEquals(InputProfile.defaults(InputProfile.GAME), InputProfiles.read(app, InputProfile.GAME))
        assertEquals(InputProfile.defaults(InputProfile.DESKTOP), InputProfiles.read(app, InputProfile.DESKTOP))
        assertEquals(InputSurfaceLayout.defaults(), InputSurfaceLayout.read(app, InputProfile.DESKTOP))
    }

    @Test fun gameTextureFilteringIsKeptUnlessGameProfilesAreReset() {
        session.edit().putString("gameTextureFiltering", """{"440":{"anisotropy":16}}""").commit()
        SettingsReset.resetAll(app, gameProfiles = false)
        assertEquals(16, SessionPrefs.gameTextureFiltering(app, "440").anisotropy)

        SettingsReset.resetAll(app, gameProfiles = true)
        assertTrue(SessionPrefs.gameTextureFiltering(app).isEmpty())
    }

    @Test fun perModeKeysAreSettings() {
        listOf("resolutionCap.steam", "displayResolution.desktop", "customRes.steam", "hdr.steam", "fpsLimit.desktop", "suspendPolicy.steam")
            .forEach { assertTrue(it, SettingsReset.isSessionSetting(it)) }
        listOf("addedName:/x", "addedAppId:/x", "gpuAutoInstalled", "romsDir", "gameStorage", "micAsked", "gameTextureFiltering")
            .forEach { assertFalse(it, SettingsReset.isSessionSetting(it)) }
    }
}
