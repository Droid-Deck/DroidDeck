package com.droiddeck.launcher.session

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecondaryLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun gamesStaySharedButPrefixesAndNativeDownloadsArePrivate() {
        val files = tmp.newFolder("files")
        val library = tmp.newFolder("card")
        File(library, "steamapps/common/Game").mkdirs()
        val binds = SecondaryLibrary.binds(files, library)
        val private = SecondaryLibrary.privateRoot(files, library)
        for (guest in listOf("/mnt/droiddeck-sd", "/mnt/bannerlator-sd")) {
            assertTrue(binds.contains("${library.path}:$guest"))
            assertTrue(binds.contains("$private/steamapps/compatdata:$guest/steamapps/compatdata"))
            assertTrue(binds.contains("$private/steamapps/downloading/4185400:$guest/steamapps/downloading/4185400"))
            assertTrue(binds.contains("$private/steamapps/common/SteamLinuxRuntime_4-arm64:$guest/steamapps/common/steamlinuxruntime_4-arm64"))
            assertFalse(binds.any { it.endsWith(":$guest/steamapps/common/Game") })
        }
    }

    @Test fun migrationPreservesSavesLinksAndOriginalAndNeverOverwritesNewSaves() {
        val files = tmp.newFolder("files")
        val library = tmp.newFolder("card")
        val save = File(library, "steamapps/compatdata/42/pfx/save.dat")
        save.parentFile!!.mkdirs()
        save.writeText("original")
        Files.createSymbolicLink(File(save.parentFile, "link").toPath(), File("save.dat").toPath())
        SecondaryLibrary.binds(files, library)
        val copied = File(SecondaryLibrary.privateRoot(files, library), "steamapps/compatdata/42/pfx/save.dat")
        assertEquals("original", copied.readText())
        assertTrue(Files.isSymbolicLink(File(copied.parentFile, "link").toPath()))
        copied.writeText("new save")
        SecondaryLibrary.binds(files, library)
        assertEquals("new save", copied.readText())
        assertEquals("original", save.readText())
    }

    @Test fun changingLibraryUsesADifferentPrivatePrefixDirectory() {
        val files = tmp.newFolder("files")
        assertNotEquals(SecondaryLibrary.privateRoot(files, tmp.newFolder("one")),
            SecondaryLibrary.privateRoot(files, tmp.newFolder("two")))
    }

    @Test fun installedNativeToolVersionsAlsoGetPrivateStaging() {
        val files = tmp.newFolder("files")
        val library = tmp.newFolder("card")
        val manifest = File(library, "steamapps/appmanifest_123.acf")
        manifest.parentFile!!.mkdirs()
        manifest.writeText("\"AppState\" { \"appid\" \"123\" \"installdir\" \"Proton 99.0 (ARM64)\" }")
        assertTrue(SecondaryLibrary.binds(files, library).any {
            it.endsWith(":/mnt/droiddeck-sd/steamapps/downloading/123")
        })
    }

    @Test fun fexAndItsRuntimeInstallWhereTheirProgramsCanRun() {
        val files = tmp.newFolder("files")
        val library = tmp.newFolder("card")
        val manifest = File(library, "steamapps/appmanifest_3127680.acf")
        manifest.parentFile!!.mkdirs()
        manifest.writeText("\"AppState\" { \"appid\" \"3127680\" \"installdir\" \"FEX\" }")
        val private = SecondaryLibrary.privateRoot(files, library)
        val binds = SecondaryLibrary.binds(files, library)
        assertTrue(binds.contains("$private/steamapps/common/FEX:/mnt/droiddeck-sd/steamapps/common/FEX"))
        assertTrue(binds.contains("$private/steamapps/downloading/3127680:/mnt/droiddeck-sd/steamapps/downloading/3127680"))
        assertTrue(binds.contains("$private/steamapps/common/SteamLinuxRuntime_sniper:/mnt/droiddeck-sd/steamapps/common/SteamLinuxRuntime_sniper"))
    }

    @Test fun deletingAStagedDepotDoesNotRestoreTheOldSharedDownload() {
        val files = tmp.newFolder("files")
        val library = tmp.newFolder("card")
        val stale = File(library, "steamapps/downloading/4185400/stale")
        stale.parentFile!!.mkdirs()
        stale.writeText("old download")
        SecondaryLibrary.binds(files, library)
        val target = File(SecondaryLibrary.privateRoot(files, library), "steamapps/downloading/4185400")
        assertTrue(File(target, "stale").isFile)
        target.deleteRecursively()
        SecondaryLibrary.binds(files, library)
        assertFalse(File(target, "stale").exists())
        assertTrue(stale.isFile)
    }
}
