package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.AppUiPrefs
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.input.ControllerPrefs
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
        listOf("session", "controller", "app-ui", "frame_gen", "steam_touch", "agent").forEach {
            prefs(it).edit().clear().commit()
        }
    }

    @Test fun settingsGoBackToTheirDefaults() {
        SessionPrefs.setTheme(app, "ocean")
        SessionPrefs.setNoXalia(app, false)
        SessionPrefs.setFpsLimit(app, SessionService.MODE_STEAM, 30)
        SessionPrefs.setHdr(app, SessionService.MODE_DESKTOP, true)
        SessionPrefs.setResolutionChoice(app, SessionService.MODE_STEAM, "1280x800")
        SessionPrefs.setGuestHostname(app, "MyDeck")
        SessionPrefs.setGameDebugLogsEnabled(app, true)
        ControllerPrefs.setOpacity(app, 40)
        AppUiPrefs.setScale(app, 150)
        FrameGen.set(app, FrameGen.Mode(FrameGen.ENGINE_WINFG, 3))
        prefs("steam_touch").edit().putBoolean("gyro", false).commit()

        assertTrue(SettingsReset.resetAll(app, gameProfiles = false))

        assertEquals("graphite", SessionPrefs.theme(app))
        assertTrue(SessionPrefs.noXalia(app))
        assertEquals(0, SessionPrefs.fpsLimit(app, SessionService.MODE_STEAM))
        assertFalse(SessionPrefs.hdr(app, SessionService.MODE_DESKTOP))
        assertFalse(SessionPrefs.resolutionChosen(app, SessionService.MODE_STEAM))
        assertEquals(SessionPrefs.DEFAULT_GUEST_HOSTNAME, SessionPrefs.guestHostname(app))
        assertFalse(SessionPrefs.gameDebugLogsEnabled(app))
        val guest = java.io.File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(app),
            "root/.config/droiddeck/game-environment.json")
        assertFalse(org.json.JSONObject(guest.readText()).getBoolean(GameEnvironmentStore.GAME_DEBUG_LOGS))
        assertEquals(100, ControllerPrefs.read(app).opacity)
        assertEquals(AppUiPrefs.DEFAULT_SCALE, AppUiPrefs.scale(app))
        assertEquals(FrameGen.Mode.OFF.engine, FrameGen.engine(app))
        assertFalse(prefs("steam_touch").contains("gyro"))
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
