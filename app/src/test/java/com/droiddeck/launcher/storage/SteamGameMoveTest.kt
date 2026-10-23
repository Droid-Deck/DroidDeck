package com.droiddeck.launcher.storage

import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.SecondaryLibrary
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SteamLibraryRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SteamGameMoveTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun gameDataAndManifestMoveAndTheFolderBecomesASteamLibrary() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val source = tmp.newFolder("source")
        val gameDir = File(source, "steamapps/common/Example").apply { mkdirs() }
        File(gameDir, "game.exe").writeText("game")
        val manifest = File(source, "steamapps/appmanifest_220.acf").apply {
            writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"220\"\n\t\"name\"\t\t\"Example\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Example\"\n}\n")
        }
        val sourceData = tmp.newFolder("source-data")
        val prefix = File(sourceData, "steamapps/compatdata/220/pfx").apply { mkdirs() }
        File(prefix, "save").writeText("save")
        val shader = File(sourceData, "steamapps/shadercache/220").apply { mkdirs() }
        File(shader, "cache").writeText("shader")
        val workshop = File(source, "steamapps/workshop/content/220").apply { mkdirs() }
        File(workshop, "item").writeText("workshop")
        val workshopManifest = File(source, "steamapps/workshop/appworkshop_220.acf").apply {
            parentFile?.mkdirs()
            writeText("\"AppWorkshop\"\n{\n\t\"appid\"\t\t\"220\"\n}\n")
        }
        val target = tmp.newFolder("target")
        val game = GameUsage(
            220, "Example", null, "internal", true, 0,
            0, 0, 0, 0, null, false,
            source, sourceData, gameDir, manifest,
        )

        val error = SteamGameMove.move(app, game, target)

        assertNull(error)
        assertTrue(File(target, "steamapps/common/Example/game.exe").isFile)
        assertTrue(File(target, "steamapps/appmanifest_220.acf").isFile)
        assertFalse(gameDir.exists())
        assertFalse(manifest.exists())
        val privateData = SecondaryLibrary.privateRoot(app.filesDir, target)
        assertTrue(File(privateData, "steamapps/compatdata/220/pfx/save").isFile)
        assertTrue(File(privateData, "steamapps/shadercache/220/cache").isFile)
        assertFalse(File(sourceData, "steamapps/compatdata/220").exists())
        assertTrue(File(target, "steamapps/workshop/content/220/item").isFile)
        assertTrue(File(target, "steamapps/workshop/appworkshop_220.acf").isFile)
        assertFalse(workshopManifest.exists())
        assertTrue(File(target, "libraryfolder.vdf").isFile)
        assertTrue(SteamLibraryRegistry.available(app, "arm64").any { it.host.canonicalPath == target.canonicalPath })
        assertTrue(Library.steamLibraries(app, "arm64").any { it.first.canonicalPath == target.canonicalPath })
    }

    @Test fun legacySecondaryLibraryDataMovesEvenWhenPrivateDataIsMissing() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val source = tmp.newFolder("secondary")
        val gameDir = File(source, "steamapps/common/Example").apply { mkdirs() }
        File(gameDir, "game.exe").writeText("game")
        val manifest = File(source, "steamapps/appmanifest_220.acf").apply {
            writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"220\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Example\"\n}\n")
        }
        val legacyPrefix = File(source, "steamapps/compatdata/220/pfx").apply { mkdirs() }
        File(legacyPrefix, "save").writeText("save")
        val privateData = SecondaryLibrary.privateRoot(app.filesDir, source)
        val target = tmp.newFolder("target")
        val game = GameUsage(
            220, "Example", null, "SD Card", false, 0,
            0, 0, 0, 0, null, false,
            source, privateData, gameDir, manifest,
        )

        val error = SteamGameMove.move(app, game, target)

        assertNull(error)
        val targetData = SecondaryLibrary.privateRoot(app.filesDir, target)
        assertTrue(File(targetData, "steamapps/compatdata/220/pfx/save").isFile)
        assertFalse(File(source, "steamapps/compatdata/220").exists())
    }

    @Test fun anX64GameRegistersOnlyInTheX64Client() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val source = tmp.newFolder("source-x64")
        val gameDir = File(source, "steamapps/common/Example").apply { mkdirs() }
        File(gameDir, "game.exe").writeText("game")
        val manifest = File(source, "steamapps/appmanifest_220.acf").apply {
            writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"220\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Example\"\n}\n")
        }
        val target = tmp.newFolder("target-x64")
        val game = GameUsage(
            220, "Example", null, "internal", true, 0,
            0, 0, 0, 0, null, true,
            source, source, gameDir, manifest,
        )

        val error = SteamGameMove.move(app, game, target)

        assertNull(error)
        assertTrue(SteamLibraryRegistry.available(app, "x86_64").any { it.host.canonicalPath == target.canonicalPath })
        assertTrue(Library.steamLibraries(app, "x86_64").any { it.first.canonicalPath == target.canonicalPath })
        assertTrue(Library.steamLibraries(app, "arm64").none { it.first.canonicalPath == target.canonicalPath })
    }

    @Test fun anX64GameCannotUseAnAddedGamesFolder() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val source = tmp.newFolder("source-added-x64")
        val gameDir = File(source, "steamapps/common/Example").apply { mkdirs() }
        File(gameDir, "game.exe").writeText("game")
        val manifest = File(source, "steamapps/appmanifest_220.acf").apply {
            writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"220\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Example\"\n}\n")
        }
        val target = tmp.newFolder("target-added")
        SessionPrefs.setGameFolderBinds(app, listOf(target.path to "/root/Games/Added"))
        val game = GameUsage(
            220, "Example", null, "internal", true, 0,
            0, 0, 0, 0, null, true,
            source, source, gameDir, manifest,
        )

        val error = SteamGameMove.move(app, game, target)

        assertEquals(app.getString(com.droiddeck.launcher.R.string.storage_move_other_client), error)
        assertTrue(File(gameDir, "game.exe").isFile)
        assertTrue(manifest.isFile)
        assertFalse(File(target, "steamapps/common/Example").exists())
        assertTrue(SteamLibraryRegistry.all(app).isEmpty())
    }

    @Test fun aTargetRegisteredForBothClientsIsRejected() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val source = tmp.newFolder("source-dual")
        val gameDir = File(source, "steamapps/common/Example").apply { mkdirs() }
        File(gameDir, "game.exe").writeText("game")
        val manifest = File(source, "steamapps/appmanifest_220.acf").apply {
            writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"220\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Example\"\n}\n")
        }
        val target = tmp.newFolder("target-dual")
        SteamLibraryRegistry.register(app, "arm64", target, "Arm")
        SteamLibraryRegistry.register(app, "x86_64", target, "X64")
        val game = GameUsage(
            220, "Example", null, "internal", true, 0,
            0, 0, 0, 0, null, false,
            source, source, gameDir, manifest,
        )

        val error = SteamGameMove.move(app, game, target)

        assertEquals(app.getString(com.droiddeck.launcher.R.string.storage_move_other_client), error)
        assertTrue(File(gameDir, "game.exe").isFile)
        assertTrue(manifest.isFile)
        assertFalse(File(target, "steamapps/common/Example").exists())
    }

    @Test fun movingBackToInternalPutsThePrefixInTheInternalLibrary() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val source = tmp.newFolder("source-card")
        val gameDir = File(source, "steamapps/common/Example").apply { mkdirs() }
        File(gameDir, "game.exe").writeText("game")
        val manifest = File(source, "steamapps/appmanifest_220.acf").apply {
            writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"220\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Example\"\n}\n")
        }
        val sourceData = tmp.newFolder("source-card-data")
        File(sourceData, "steamapps/compatdata/220/pfx").mkdirs()
        File(sourceData, "steamapps/compatdata/220/pfx/save").writeText("save")
        val target = SessionPrefs.steamRoot(app, "arm64").apply { mkdirs() }
        val game = GameUsage(
            220, "Example", null, "SD Card", false, 0,
            0, 0, 0, 0, null, false,
            source, sourceData, gameDir, manifest,
        )

        val error = SteamGameMove.move(app, game, target)

        assertNull(error)
        assertTrue(File(target, "steamapps/common/Example/game.exe").isFile)
        assertTrue(File(target, "steamapps/compatdata/220/pfx/save").isFile)
        assertFalse(File(target, "libraryfolder.vdf").exists())
        assertTrue(SteamLibraryRegistry.all(app).isEmpty())
    }

    @Test fun aFailedDataMoveRollsTheGameBackAndUnregistersTheLibrary() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val source = tmp.newFolder("source-fail")
        val gameDir = File(source, "steamapps/common/Example").apply { mkdirs() }
        File(gameDir, "game.exe").writeText("game")
        val manifest = File(source, "steamapps/appmanifest_220.acf").apply {
            writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"220\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Example\"\n}\n")
        }
        val sourceData = tmp.newFolder("source-fail-data")
        val prefix = File(sourceData, "steamapps/compatdata/220/pfx").apply { mkdirs() }
        File(prefix, "save").writeText("save")
        val target = tmp.newFolder("target-fail")
        val targetData = SecondaryLibrary.privateRoot(app.filesDir, target)
        File(targetData, "steamapps").apply { parentFile?.mkdirs(); writeText("not a directory") }
        val game = GameUsage(
            220, "Example", null, "internal", true, 0,
            0, 0, 0, 0, null, false,
            source, sourceData, gameDir, manifest,
        )

        val error = SteamGameMove.move(app, game, target)

        assertTrue(error != null)
        assertTrue(File(gameDir, "game.exe").isFile)
        assertTrue(manifest.isFile)
        assertFalse(File(target, "steamapps/common/Example").exists())
        assertFalse(File(target, "steamapps/appmanifest_220.acf").exists())
        assertFalse(File(target, "libraryfolder.vdf").exists())
        assertTrue(SteamLibraryRegistry.all(app).isEmpty())
    }

    @Test fun anExistingTargetPrefixIsRejected() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val source = tmp.newFolder("source-prefix")
        val gameDir = File(source, "steamapps/common/Example").apply { mkdirs() }
        File(gameDir, "game.exe").writeText("game")
        val manifest = File(source, "steamapps/appmanifest_220.acf").apply {
            writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"220\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Example\"\n}\n")
        }
        val sourceData = tmp.newFolder("source-prefix-data")
        val target = tmp.newFolder("target-prefix")
        val targetData = SecondaryLibrary.privateRoot(app.filesDir, target)
        File(targetData, "steamapps/compatdata/220/pfx").mkdirs()
        val game = GameUsage(
            220, "Example", null, "internal", true, 0,
            0, 0, 0, 0, null, false,
            source, sourceData, gameDir, manifest,
        )

        val error = SteamGameMove.move(app, game, target)

        assertEquals(app.getString(com.droiddeck.launcher.R.string.storage_move_exists), error)
        assertTrue(File(gameDir, "game.exe").isFile)
        assertTrue(manifest.isFile)
        assertFalse(File(target, "steamapps/common/Example").exists())
        assertTrue(SteamLibraryRegistry.all(app).isEmpty())
    }

    @Test fun staleSecondaryLibraryDataIsRemovedAfterAMove() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val source = tmp.newFolder("secondary-stale")
        val gameDir = File(source, "steamapps/common/Example").apply { mkdirs() }
        File(gameDir, "game.exe").writeText("game")
        val manifest = File(source, "steamapps/appmanifest_220.acf").apply {
            writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"220\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Example\"\n}\n")
        }
        val sourceData = SecondaryLibrary.privateRoot(app.filesDir, source)
        File(sourceData, "steamapps/compatdata/220/pfx").mkdirs()
        File(sourceData, "steamapps/compatdata/220/pfx/save").writeText("private")
        File(sourceData, "steamapps/shadercache/220").mkdirs()
        val legacyPrefix = File(source, "steamapps/compatdata/220/pfx").apply { mkdirs() }
        File(legacyPrefix, "save").writeText("legacy")
        File(source, "steamapps/shadercache/220").mkdirs()
        val target = tmp.newFolder("target-stale")
        val game = GameUsage(
            220, "Example", null, "SD Card", false, 0,
            0, 0, 0, 0, null, false,
            source, sourceData, gameDir, manifest,
        )

        val error = SteamGameMove.move(app, game, target)

        assertNull(error)
        val targetData = SecondaryLibrary.privateRoot(app.filesDir, target)
        assertEquals("private", File(targetData, "steamapps/compatdata/220/pfx/save").readText())
        assertFalse(File(source, "steamapps/compatdata/220").exists())
        assertFalse(File(source, "steamapps/shadercache/220").exists())
    }

    @Test fun aLibraryKeepsOneGuestPathForBothClients() {
        val root = tmp.newFolder("library")
        val first = SteamLibraryRegistry.register(app, "arm64", root, "First")
        val renamed = SteamLibraryRegistry.register(app, "arm64", root, "Renamed")
        val x64 = SteamLibraryRegistry.register(app, "x86_64", root, "Renamed")

        assertEquals(first.guest, renamed.guest)
        assertEquals("Renamed", renamed.label)
        assertEquals(first.guest, x64.guest)
        assertEquals(2, SteamLibraryRegistry.all(app).size)
    }

    @Test fun theSameLibraryIsRejectedBeforeAnythingMoves() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val source = tmp.newFolder("source")
        val gameDir = File(source, "steamapps/common/Example").apply { mkdirs() }
        File(gameDir, "game.exe").writeText("game")
        val manifest = File(source, "steamapps/appmanifest_220.acf").apply {
            writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"220\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Example\"\n}\n")
        }
        val game = GameUsage(
            220, "Example", null, "internal", true, 0,
            0, 0, 0, 0, null, false,
            source, source, gameDir, manifest,
        )

        val error = SteamGameMove.move(app, game, source)

        assertEquals(app.getString(com.droiddeck.launcher.R.string.storage_move_same), error)
        assertTrue(File(gameDir, "game.exe").isFile)
        assertTrue(manifest.isFile)
    }

    @Test fun aFreshDriveWithoutASteamFolderIsCreatedAndRegistered() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val source = tmp.newFolder("source-fresh")
        val gameDir = File(source, "steamapps/common/Example").apply { mkdirs() }
        File(gameDir, "game.exe").writeText("game")
        val manifest = File(source, "steamapps/appmanifest_220.acf").apply {
            writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"220\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Example\"\n}\n")
        }
        val game = GameUsage(
            220, "Example", null, "internal", true, 0,
            0, 0, 0, 0, null, false,
            source, source, gameDir, manifest,
        )
        // A freshly plugged SSD has no steam folder yet, like a new GameStorage volume.
        val target = File(tmp.root, "fresh-ssd-${System.nanoTime()}/steam")
        assertFalse(target.exists())

        val error = SteamGameMove.move(app, game, target)

        assertNull(error)
        assertTrue(File(target, "steamapps/common/Example/game.exe").isFile)
        assertTrue(File(target, "steamapps/appmanifest_220.acf").isFile)
        assertFalse(gameDir.exists())
        assertFalse(manifest.exists())
        assertTrue(SteamLibraryRegistry.available(app, "arm64").any { it.host.canonicalPath == target.canonicalPath })
    }

    @Test fun aTargetThatExistsAsAFileIsStillRejected() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val source = tmp.newFolder("source-file-target")
        val gameDir = File(source, "steamapps/common/Example").apply { mkdirs() }
        File(gameDir, "game.exe").writeText("game")
        val manifest = File(source, "steamapps/appmanifest_220.acf").apply {
            writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"220\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Example\"\n}\n")
        }
        val game = GameUsage(
            220, "Example", null, "internal", true, 0,
            0, 0, 0, 0, null, false,
            source, source, gameDir, manifest,
        )
        val target = tmp.newFile("blocked")

        val error = SteamGameMove.move(app, game, target)

        assertEquals(app.getString(com.droiddeck.launcher.R.string.storage_move_missing), error)
        assertTrue(File(gameDir, "game.exe").isFile)
        assertTrue(manifest.isFile)
    }
}
