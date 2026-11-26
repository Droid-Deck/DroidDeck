package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.core.AddonPack
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
        listOf("usr/bin/gamescope", LinuxRuntime.SESSION_SCRIPT.removePrefix("/")).forEach { path ->
            File(root, path).apply { parentFile!!.mkdirs(); writeText("") }
        }
        AddonPack.PROOT.file(context, AddonPack.COMPLETE).apply { parentFile!!.mkdirs(); writeText("") }
        SessionPrefs.setGameStorage(context, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(context, emptyList())
        val steamapps = File(root, "root/.local/share/Steam/steamapps").apply { mkdirs() }
        val manifest = File(steamapps, "appmanifest_8400.acf").apply {
            writeText("\"AppState\"\n{\n\"name\" \"Geometry Wars\"\n\"StateFlags\" \"4\"\n}")
        }
        val folder = File(context.filesDir, "exports").apply { mkdirs() }
        GameFileSync.enable(context, folder)
        assertEquals("8400", File(folder, "Geometry Wars.steam").readText())
        assertEquals(folder.path, GameFileSync.folder(context))
        assertEquals(1, folder.listFiles()!!.count { it.extension == GameFiles.EXTENSION })
        val unavailable = File(steamapps.parentFile, "steamapps-unavailable")
        assertTrue(steamapps.renameTo(unavailable))
        assertThrows(IllegalStateException::class.java) { GameFileSync.syncConfigured(context) }
        assertEquals(1, folder.listFiles()!!.count { it.extension == GameFiles.EXTENSION })
        assertTrue(unavailable.renameTo(steamapps))
        GameFileSync.syncConfigured(context)
        assertTrue(File(folder, "Geometry Wars.steam").exists())
        assertTrue(manifest.delete())
        GameFileSync.syncConfigured(context)
        assertEquals(0, folder.listFiles()!!.count { it.extension == GameFiles.EXTENSION })
        GameFileSync.disable(context)
        manifest.writeText("\"name\" \"Geometry Wars\"\n\"StateFlags\" \"4\"")
        GameFileSync.syncConfigured(context)
        assertNull(GameFileSync.folder(context))
        assertFalse(File(folder, "Geometry Wars.steam").exists())
        assertEquals(0, folder.listFiles()!!.count { it.extension == GameFiles.EXTENSION })
    }

    @Test fun libraryNotCreatedYetOnPresentStorageIsEmptyNotUnavailable() {
        val context = RuntimeEnvironment.getApplication()
        val root = LinuxRuntime.rootDir(context)
        listOf("usr/bin/gamescope", LinuxRuntime.SESSION_SCRIPT.removePrefix("/")).forEach { path ->
            File(root, path).apply { parentFile!!.mkdirs(); writeText("") }
        }
        AddonPack.PROOT.file(context, AddonPack.COMPLETE).apply { parentFile!!.mkdirs(); writeText("") }
        SessionPrefs.setAddedGamesDirs(context, emptyList())
        File(root, "root/.local/share/Steam/steamapps").mkdirs()
        val volume = File(context.filesDir, "card").apply { mkdirs() }
        val library = File(volume, "steam")
        SessionPrefs.setGameStorage(context, library.path, "Card")
        val folder = File(context.filesDir, "exports-card").apply { mkdirs() }
        GameFileSync.enable(context, folder)
        assertEquals(folder.path, GameFileSync.folder(context))
        assertFalse(library.exists())
        library.writeText("")
        assertThrows(IllegalStateException::class.java) { GameFileSync.syncConfigured(context) }
        assertTrue(library.delete())
        GameFileSync.disable(context)
    }

    @Test fun unreadableAddedGamesFolderKeepsShortcutFilesWithoutStoppingTheSync() {
        val context = RuntimeEnvironment.getApplication()
        val root = LinuxRuntime.rootDir(context)
        listOf("usr/bin/gamescope", LinuxRuntime.SESSION_SCRIPT.removePrefix("/")).forEach { path ->
            File(root, path).apply { parentFile!!.mkdirs(); writeText("") }
        }
        AddonPack.PROOT.file(context, AddonPack.COMPLETE).apply { parentFile!!.mkdirs(); writeText("") }
        SessionPrefs.setGameStorage(context, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(context, emptyList())
        val steamapps = File(root, "root/.local/share/Steam/steamapps").apply { mkdirs() }
        val manifest = File(steamapps, "appmanifest_8400.acf").apply {
            writeText("\"AppState\"\n{\n\"name\" \"Geometry Wars\"\n\"StateFlags\" \"4\"\n}")
        }
        val card = File(context.filesDir, "old-card/Games")
        SessionPrefs.setGameFolderBinds(context, listOf(card.path to "${AddedGames.GUEST_DIR}/Games"))
        val folder = File(context.filesDir, "exports-added").apply { mkdirs() }
        GameFileSync.enable(context, folder)
        assertEquals(folder.path, GameFileSync.folder(context))
        assertEquals("8400", File(folder, "Geometry Wars.steam").readText())
        val shortcut = Library.SteamGame(0, "Card Game", null, Library.ADDED, (0xC0FFEE01L shl 32) or 0x02000000L)
        GameFiles.sync(context, folder, Library.launchableGames(context, strictRead = true) + shortcut)
        assertEquals(shortcut.gameIdString, File(folder, "Card Game.steam").readText())
        assertTrue(manifest.delete())
        File(steamapps, "appmanifest_620.acf").writeText("\"AppState\"\n{\n\"name\" \"Portal 2\"\n\"StateFlags\" \"4\"\n}")
        GameFileSync.syncConfigured(context)
        assertEquals("620", File(folder, "Portal 2.steam").readText())
        assertFalse(File(folder, "Geometry Wars.steam").exists())
        assertEquals(shortcut.gameIdString, File(folder, "Card Game.steam").readText())
        assertTrue(card.mkdirs())
        GameFileSync.syncConfigured(context)
        assertFalse(File(folder, "Card Game.steam").exists())
        assertTrue(File(folder, "Portal 2.steam").isFile)
        GameFileSync.disable(context)
        SessionPrefs.setGameFolderBinds(context, emptyList())
    }

    @Test fun libraryReadableOnlyWhenListableOrNotCreatedOnPresentParent() {
        val base = File(RuntimeEnvironment.getApplication().filesDir, "readable").apply { mkdirs() }
        assertTrue(GameFileSync.libraryReadable(File(base, "dir").apply { mkdirs() }))
        assertTrue(GameFileSync.libraryReadable(File(base, "missing")))
        assertFalse(GameFileSync.libraryReadable(File(base, "gone/steam")))
        assertFalse(GameFileSync.libraryReadable(File(base, "file").apply { writeText("") }))
    }

    @Test fun previousAutomaticLibraryNotCreatedYetKeepsSyncingButRemovedDriveStops() {
        val context = RuntimeEnvironment.getApplication()
        val root = LinuxRuntime.rootDir(context)
        listOf("usr/bin/gamescope", LinuxRuntime.SESSION_SCRIPT.removePrefix("/")).forEach { path ->
            File(root, path).apply { parentFile!!.mkdirs(); writeText("") }
        }
        AddonPack.PROOT.file(context, AddonPack.COMPLETE).apply { parentFile!!.mkdirs(); writeText("") }
        SessionPrefs.setAddedGamesDirs(context, emptyList())
        SessionPrefs.setGameStorage(context, "", "")
        File(root, "root/.local/share/Steam/steamapps").mkdirs()
        val folder = File(context.filesDir, "exports-auto").apply { mkdirs() }
        val prefs = context.getSharedPreferences("game_files", android.content.Context.MODE_PRIVATE)
        val volume = File(context.filesDir, "auto-card").apply { mkdirs() }
        prefs.edit().putString("storageSetting", "").putString("storagePath", File(volume, "steam").path).apply()
        GameFileSync.enable(context, folder)
        prefs.edit().putString("storageSetting", "").putString("storagePath", File(context.filesDir, "removed-card/steam").path).apply()
        assertThrows(IllegalStateException::class.java) { GameFileSync.syncConfigured(context) }
        GameFileSync.disable(context)
    }
}
