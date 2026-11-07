package com.droiddeck.launcher.stores.steam

import com.droiddeck.launcher.stores.download.DownloadStage
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamLibraryFoldersTest {
    private val rootfs = File("/data/user/0/com.droiddeck.launcher/files/rootfs")
    private val card = File("/storage/1A2B-3C4D/Android/data/com.droiddeck.launcher/files/steam")
    private val lib = File("/storage/1A2B-3C4D/SteamLibrary")
    private val games = File("/storage/1A2B-3C4D/Games")
    private val armRoot = File(rootfs, "root/.local/share/Steam")
    private val x64Root = File(rootfs, "root/.droiddeck-x64/.local/share/Steam")
    private val armAliases = listOf("/root/.local/share/Steam", "/root/.steam/steam", "/root/.steam/root")
    private val mounts = listOf(
        SteamLibraryFolders.Mount("/mnt/droiddeck-sd", card),
        SteamLibraryFolders.Mount("/mnt/droiddeck-lib/1", lib),
        SteamLibraryFolders.Mount("/root/Games/Card/", games),
        SteamLibraryFolders.Mount("/root/Storage", File("/storage/emulated/0")),
    )

    private val vdf = """
        "libraryfolders"
        {
        	"contentstatsid"		"-4785"
        	"0"
        	{
        		"path"		"/root/.local/share/Steam"
        		"label"		"Internal Storage"
        		"contentid"		"123"
        		"totalsize"		"0"
        		"apps"
        		{
        			"228980"		"0"
        		}
        	}
        	"1"
        	{
        		"path"		"/mnt/droiddeck-sd"
        		"label"		"SD card"
        		"apps"
        		{
        			"291550"		"1500000000"
        		}
        	}
        	"2"
        	{
        		"path"		"/mnt/droiddeck-lib/1"
        		"label"		"Steam \"Lib\""
        	}
        	"3"
        	{
        		"path"		"/root/Games/Card"
        		"label"		""
        	}
        	"4"
        	{
        		"path"		"/root/.droiddeck-x64/shared/x86-64"
        		"label"		"x86-64 client games"
        	}
        	"5"
        	{
        		"path"		"/mnt/droiddeck-lib/9"
        		"label"		"Removed"
        	}
        	"6"
        	{
        		"path"		"/root/Games/Gone"
        		"label"		"Gone"
        	}
        }
    """.trimIndent()

    @Test fun readsEveryFolderWithItsLabel() {
        val folders = SteamLibraryFolders.parse(vdf)
        assertEquals(
            listOf("/root/.local/share/Steam", "/mnt/droiddeck-sd", "/mnt/droiddeck-lib/1", "/root/Games/Card",
                "/root/.droiddeck-x64/shared/x86-64", "/mnt/droiddeck-lib/9", "/root/Games/Gone"),
            folders.map { it.path },
        )
        assertEquals("Internal Storage", folders[0].label)
        assertEquals("Steam \"Lib\"", folders[2].label)
        assertEquals("", folders[3].label)
    }

    @Test fun readsTheOldFormatAndEscapedPaths() {
        val old = """
            "LibraryFolders"
            {
            	"TimeNextStatsReport"		"1700000000"
            	"ContentStatsID"		"-1"
            	"1"		"/mnt/droiddeck-sd"
            	"2"		"/root/odd\\name"
            }
        """.trimIndent()
        assertEquals(
            listOf(SteamLibraryFolders.Folder("/mnt/droiddeck-sd", ""), SteamLibraryFolders.Folder("/root/odd\\name", "")),
            SteamLibraryFolders.parse(old),
        )
        assertTrue(SteamLibraryFolders.parse("").isEmpty())
    }

    @Test fun arm64LibrariesMapFromGuestToHost() {
        val libraries = SteamLibraryFolders.resolve(
            SteamLibraryFolders.parse(vdf), "/root/.local/share/Steam", armAliases, armRoot, mounts, rootfs,
        )
        assertEquals(
            listOf(armRoot, card, lib, games, File(rootfs, "root/.droiddeck-x64/shared/x86-64")),
            libraries.map { it.host },
        )
        assertEquals(listOf(true, false, false, false, false), libraries.map { it.main })
        assertEquals("/mnt/droiddeck-sd", libraries[1].guest)
        assertEquals("SD card", libraries[1].label)
    }

    @Test fun x64LibrariesSitInsideTheCardAndAddedLibraries() {
        val text = """
            "libraryfolders"
            {
            	"0" { "path" "/root/.droiddeck-x64/.local/share/Steam" "label" "Internal Storage" }
            	"1" { "path" "/mnt/droiddeck-sd/x86-64" "label" "SD card" }
            	"2" { "path" "/mnt/droiddeck-lib/1/x86-64/" "label" "Lib" }
            	"3" { "path" "/root/.droiddeck-x64/shared/arm64" "label" "ARM64 client games" }
            }
        """.trimIndent()
        val libraries = SteamLibraryFolders.resolve(
            SteamLibraryFolders.parse(text), "/root/.droiddeck-x64/.local/share/Steam",
            listOf("/root/.droiddeck-x64/.local/share/Steam", "/root/.droiddeck-x64/.steam/steam"), x64Root, mounts, rootfs,
        )
        assertEquals(
            listOf(x64Root, File(card, "x86-64"), File(lib, "x86-64"), File(rootfs, "root/.droiddeck-x64/shared/arm64")),
            libraries.map { it.host },
        )
        assertEquals("/mnt/droiddeck-lib/1/x86-64", libraries[2].guest)
    }

    @Test fun aGuestPathWithoutItsBindIsNotAnInstallTarget() {
        assertNull(SteamLibraryFolders.hostFor("/mnt/droiddeck-sd", emptyList(), rootfs))
        assertNull(SteamLibraryFolders.hostFor("/root/Games/Gone", mounts, rootfs))
        assertNull(SteamLibraryFolders.hostFor("/root/Storage/Steam", emptyList(), rootfs))
        assertNull(SteamLibraryFolders.hostFor("/opt/steam", mounts, rootfs))
        assertNull(SteamLibraryFolders.hostFor("/root/../etc", mounts, rootfs))
        assertNull(SteamLibraryFolders.hostFor("relative/path", mounts, rootfs))
        assertEquals(File("/storage/emulated/0/Steam"), SteamLibraryFolders.hostFor("/root/Storage/Steam", mounts, rootfs))
        assertEquals(File(card, "x86-64"), SteamLibraryFolders.hostFor("/mnt/droiddeck-sd//x86-64/", mounts, rootfs))
    }

    @Test fun aliasesAreTheMainLibraryAndAMissingMainIsAddedFirst() {
        val aliased = SteamLibraryFolders.resolve(
            listOf(SteamLibraryFolders.Folder("/root/.steam/steam", "Internal Storage"), SteamLibraryFolders.Folder("/mnt/droiddeck-sd", "SD card")),
            "/root/.local/share/Steam", armAliases, armRoot, mounts, rootfs,
        )
        assertEquals(listOf(armRoot, card), aliased.map { it.host })
        assertEquals("/root/.steam/steam", aliased[0].guest)

        val missing = SteamLibraryFolders.resolve(
            listOf(SteamLibraryFolders.Folder("/mnt/droiddeck-sd", "SD card"), SteamLibraryFolders.Folder("/mnt/droiddeck-sd/", "Again")),
            "/root/.local/share/Steam", armAliases, armRoot, mounts, rootfs,
        )
        assertEquals(listOf(armRoot, card), missing.map { it.host })
        assertEquals("/root/.local/share/Steam", missing[0].guest)
        assertEquals("", missing[0].label)
    }

    private fun acf(library: File, appId: Int, name: String, flags: Int, dir: String = name, size: Long = 0L) {
        val apps = File(library, "steamapps").apply { mkdirs() }
        File(apps, "appmanifest_$appId.acf").writeText(
            """
            "AppState"
            {
            	"appid"		"$appId"
            	"name"		"$name"
            	"StateFlags"		"$flags"
            	"installdir"		"$dir"
            	"SizeOnDisk"		"$size"
            }
            """.trimIndent(),
        )
    }

    @Test fun onlyFullyInstalledGamesCountAsInstalled() {
        val base = Files.createTempDirectory("steam-libs").toFile()
        try {
            val first = SteamLibraryFolders.Library("/root/.local/share/Steam", File(base, "internal"), "Internal Storage", true)
            val second = SteamLibraryFolders.Library("/mnt/droiddeck-sd", File(base, "card"), "SD card", false)
            val missing = SteamLibraryFolders.Library("/mnt/droiddeck-lib/1", File(base, "gone"), "Gone", false)
            acf(first.host, 291550, "Brawlhalla", 4, size = 1_500_000_000L)
            acf(first.host, 620, "Portal 2", 1026)
            acf(first.host, 1628350, "Steam Linux Runtime 3.0 (sniper)", 4, dir = "SteamLinuxRuntime_sniper")
            acf(second.host, 291550, "Brawlhalla copy", 4)
            acf(second.host, 413150, "Stardew Valley", 6)
            File(second.host, "steamapps/appmanifest_400.acf.tmp").writeText("\"AppState\" { \"appid\" \"400\" \"StateFlags\" \"4\" }")
            File(second.host, "steamapps/libraryfolder.vdf").writeText("\"libraryfolder\" { }")

            val found = SteamLibraryFolders.installed(listOf(first, second, missing))
            assertEquals(setOf(291550, 413150), found.keys)
            assertEquals("Brawlhalla", found.getValue(291550).name)
            assertEquals(first, found.getValue(291550).library)
            assertEquals(1_500_000_000L, found.getValue(291550).sizeOnDisk)
            assertEquals(second, found.getValue(413150).library)
            assertEquals("Stardew Valley", found.getValue(413150).installDir)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun theClientsRememberedAccountIsTheSavedSignIn() {
        val users = """
            "users"
            {
            	"76561190000000001"
            	{
            		"AccountName"		"old"
            		"PersonaName"		"Old One"
            		"RememberPassword"		"1"
            		"MostRecent"		"0"
            		"Timestamp"		"1600000000"
            	}
            	"76561190000000002"
            	{
            		"AccountName"		"player"
            		"PersonaName"		"Player"
            		"RememberPassword"		"1"
            		"MostRecent"		"1"
            		"Timestamp"		"1700000000"
            	}
            }
        """.trimIndent()
        assertEquals("Player", SteamLibraryFolders.savedAccount(users))
        assertEquals("Old One", SteamLibraryFolders.savedAccount(users.replace("\"MostRecent\"\t\t\"1\"", "\"MostRecent\"\t\t\"0\"").replace("\"1700000000\"", "\"1500000000\"")))
        assertEquals("player", SteamLibraryFolders.savedAccount(users.replace("\"PersonaName\"\t\t\"Player\"", "\"PersonaName\"\t\t\"\"").replace("\"Old One\"", "\"\"").replace("\"1600000000\"", "\"1\"")))
        assertNull(SteamLibraryFolders.savedAccount(users.replace("\"RememberPassword\"\t\t\"1\"", "\"RememberPassword\"\t\t\"0\"")))
        assertNull(SteamLibraryFolders.savedAccount(""))
    }

    @Test fun theClientLanguageComesFromTheRegistry() {
        val registry = """
            "Registry"
            {
            	"HKCU"
            	{
            		"Software"
            		{
            			"Valve"
            			{
            				"Steam"
            				{
            					"AutoLoginUser"		"player"
            					"language"		"german"
            				}
            			}
            		}
            	}
            }
        """.trimIndent()
        assertEquals("german", SteamLibraryFolders.language(registry))
        assertNull(SteamLibraryFolders.language("\"Registry\" { \"HKCU\" { } }"))
    }

    @Test fun enginePhasesShowAsDownloadStages() {
        assertEquals(DownloadStage.DOWNLOAD, SteamLibraryFolders.stageOf("download"))
        assertEquals(DownloadStage.DOWNLOAD, SteamLibraryFolders.stageOf("Downloading"))
        assertEquals(DownloadStage.DOWNLOAD, SteamLibraryFolders.stageOf(""))
        assertEquals(DownloadStage.VERIFY, SteamLibraryFolders.stageOf("verify"))
        assertEquals(DownloadStage.MANIFEST, SteamLibraryFolders.stageOf("manifests"))
        assertEquals(DownloadStage.MANIFEST, SteamLibraryFolders.stageOf("cleanup"))
        assertEquals(DownloadStage.INSTALL, SteamLibraryFolders.stageOf("register"))
        assertEquals(DownloadStage.INSTALL, SteamLibraryFolders.stageOf("finalize"))
    }
}
