package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameFileSyncTest {
    @Test fun syncUsesFreshInstalledManifestsAndStopsWhenDisabled() {
        val context = RuntimeEnvironment.getApplication()
        val root = LinuxRuntime.rootDir(context)
        listOf("usr/bin/gamescope", LinuxRuntime.SESSION_SCRIPT.removePrefix("/"), "opt/android-host/proot", "opt/android-host/loader").forEach { path ->
            File(root, path).apply { parentFile!!.mkdirs(); writeText("") }
        }
        SessionPrefs.setGameStorage(context, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(context, emptyList())
        val steamapps = File(root, "root/.local/share/Steam/steamapps").apply { mkdirs() }
        val manifest = File(steamapps, "appmanifest_8400.acf").apply {
            writeText("\"AppState\"\n{\n\"name\" \"Geometry Wars\"\n\"StateFlags\" \"4\"\n}")
        }
        val folder = File(context.filesDir, "exports").apply { mkdirs() }
        GameFileSync.enable(context, folder)
        assertEquals(folder.path, GameFileSync.folder(context))
        assertEquals(1, folder.listFiles()!!.count { it.extension == GameFiles.EXTENSION })
        // An unavailable source must leave the existing export intact.
        val unavailable = File(steamapps.parentFile, "steamapps-unavailable")
        assertTrue(steamapps.renameTo(unavailable))
        assertThrows(IllegalStateException::class.java) { GameFileSync.syncConfigured(context) }
        assertEquals(1, folder.listFiles()!!.count { it.extension == GameFiles.EXTENSION })
        assertTrue(unavailable.renameTo(steamapps))
        // A genuine uninstall removes the owned export at the next tick.
        assertTrue(manifest.delete())
        GameFileSync.syncConfigured(context)
        assertEquals(0, folder.listFiles()!!.count { it.extension == GameFiles.EXTENSION })
        // Disabled sync does not write files for new installs.
        GameFileSync.disable(context)
        manifest.writeText("\"name\" \"Geometry Wars\"\n\"StateFlags\" \"4\"")
        GameFileSync.syncConfigured(context)
        assertNull(GameFileSync.folder(context))
        assertEquals(0, folder.listFiles()!!.count { it.extension == GameFiles.EXTENSION })
    }
}
