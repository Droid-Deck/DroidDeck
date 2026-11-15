package com.droiddeck.launcher.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SteamStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun manifest(id: Int, dir: String, flags: Int = 4) = SteamStorage.parseManifest(
        File("appmanifest_$id.acf"),
        """
        "AppState"
        {
            "appid"		"$id"
            "name"		"App $id"
            "StateFlags"		"$flags"
            "installdir"		"$dir"
            "LastPlayed"		"1792072812"
        }
        """.trimIndent(),
    )!!

    @Test fun readsManifestFields() {
        val m = manifest(1794680, "Vampire Survivors")
        assertEquals(1794680, m.appId)
        assertEquals("Vampire Survivors", m.installDir)
        assertTrue(m.installed)
        assertEquals(1792072812L, m.lastPlayed)
        assertFalse(manifest(1, "x", flags = 1026).installed)
    }

    @Test fun rejectsInstallDirsThatEscapeCommon() {
        assertNull(manifest(5, "../steamapps").installDir)
        assertNull(manifest(5, "a/b").installDir)
    }

    @Test fun classifiesTools() {
        assertEquals(SteamStorage.Kind.OLD_RUNTIME, SteamStorage.kind(manifest(1070560, "SteamLinuxRuntime")))
        assertEquals(SteamStorage.Kind.OLD_RUNTIME, SteamStorage.kind(manifest(1391110, "SteamLinuxRuntime_soldier")))
        assertEquals(SteamStorage.Kind.REQUIRED, SteamStorage.kind(manifest(1628350, "SteamLinuxRuntime_sniper")))
        assertEquals(SteamStorage.Kind.REQUIRED, SteamStorage.kind(manifest(4185400, "SteamLinuxRuntime_4-arm64")))
        assertEquals(SteamStorage.Kind.ARM_PROTON, SteamStorage.kind(manifest(4427310, "Proton Experimental (ARM64)")))
        assertEquals(SteamStorage.Kind.ARM_PROTON, SteamStorage.kind(manifest(4628740, "Proton 11.0 (ARM64)")))
        assertEquals(SteamStorage.Kind.X86_PROTON, SteamStorage.kind(manifest(1493710, "Proton - Experimental")))
        assertEquals(SteamStorage.Kind.X86_PROTON, SteamStorage.kind(manifest(2805730, "Proton 9.0")))
        assertEquals(SteamStorage.Kind.REQUIRED, SteamStorage.kind(manifest(228980, "Steamworks Shared")))
        assertEquals(SteamStorage.Kind.REQUIRED, SteamStorage.kind(manifest(1826330, "Proton EasyAntiCheat Runtime")))
        assertEquals(SteamStorage.Kind.REQUIRED, SteamStorage.kind(manifest(1161040, "Proton BattlEye Runtime")))
        assertTrue(SteamStorage.isTool(manifest(2805730, "Proton 9.0")))
        assertFalse(SteamStorage.isTool(manifest(204360, "CastleCrashers")))
    }

    @Test fun readsOnlyTheCompatToolMappingBlock() {
        val vdf = """
            "InstallConfigStore"
            {
                "Software"
                {
                    "Valve"
                    {
                        "Steam"
                        {
                            "CompatToolMapping"
                            {
                                "0"
                                {
                                    "name"		"droiddeck-proton-arm64"
                                    "config"		""
                                }
                                "207140"
                                {
                                    "name"		"droiddeck-proton-11-arm64"
                                }
                            }
                            "Other"
                            {
                                "999"
                                {
                                    "name"		"not-a-mapping"
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()
        assertEquals(mapOf("0" to "droiddeck-proton-arm64", "207140" to "droiddeck-proton-11-arm64"), SteamStorage.compatMappings(vdf))
        assertEquals(emptyMap<String, String>(), SteamStorage.compatMappings("\"x\" {}"))
    }

    @Test fun readsShaderPrecacheFromConfig() {
        val vdf = """
            "InstallConfigStore"
            {
                "Software" { "Valve" { "Steam" { "ShaderCacheManager"
                {
                    "HasCurrentBucket"		"1"
                    "DisableShaderCache"		"1"
                } } } }
            }
        """.trimIndent()
        assertEquals(false, SteamStorage.shaderPrecache(vdf))
        assertEquals(true, SteamStorage.shaderPrecache(vdf.replace("\"DisableShaderCache\"\t\t\"1\"", "\"DisableShaderCache\"\t\t\"0\"")))
        assertEquals(true, SteamStorage.shaderPrecache("\"InstallConfigStore\" {}"))
        assertEquals(true, SteamStorage.shaderPrecache("""
            "InstallConfigStore"
            {
                // "DisableShaderCache" "1"
                "Other" { "DisableShaderCache" "1" }
                "Software" { "Valve" { "Steam" { "ShaderCacheManager" { "DisableShaderCache" "0" } } } }
            }
        """.trimIndent()))
    }

    @Test fun readsCustomToolName() {
        val vdf = "\"compatibilitytools\"\n{\n  \"compat_tools\"\n  {\n    \"GE-Proton11-9-aarch64\"\n    {\n      \"install_path\" \".\"\n    }\n  }\n}"
        assertEquals("GE-Proton11-9-aarch64", SteamStorage.compatToolName(vdf))
    }

    @Test fun countsToolUseInBothClients() {
        assertEquals("GE-Proton11-9-a64", SteamStorage.x64ToolName("GE-Proton11-9-aarch64"))
        assertEquals("droiddeck-proton-a64", SteamStorage.x64ToolName("droiddeck-proton-arm64"))
        assertEquals("GE-Proton10-1", SteamStorage.x64ToolName("GE-Proton10-1"))
        val arm = SteamStorage.ToolUse(mapOf("0" to "droiddeck-proton-arm64", "10" to "GE-Proton11-9-aarch64", "20" to "GE-Proton11-9-aarch64"), setOf("10"))
        val x64 = SteamStorage.ToolUse(
            mapOf("0" to "GE-Proton11-9-a64", "30" to "GE-Proton11-9-a64", "40" to "droiddeck-direct", "50" to "GE-Proton11-9-aarch64"),
            setOf("30", "40", "50"), x64 = true,
        )
        assertEquals(1, arm.games("GE-Proton11-9-aarch64"))
        assertEquals(1, x64.games("GE-Proton11-9-aarch64"))
        assertFalse(arm.isDefault("GE-Proton11-9-aarch64"))
        assertTrue(x64.isDefault("GE-Proton11-9-aarch64"))
        assertTrue(arm.isDefault("droiddeck-proton-arm64"))
        assertFalse(x64.isDefault("droiddeck-proton-arm64"))
    }

    @Test fun listingCountsPendingDownloadsAndUnreadableManifests() {
        val library = tmp.newFolder("lib")
        val apps = File(library, "steamapps").apply { mkdirs() }
        File(apps, "appmanifest_10.acf").writeText("\"appid\" \"10\"\n\"StateFlags\" \"4\"")
        File(apps, "appmanifest_379720.acf.1131809557.tmp").writeText("")
        File(apps, "downloading/3061810").mkdirs()
        File(apps, "appmanifest_20.acf").mkdirs()
        val listing = SteamStorage.manifests(library)!!
        assertTrue(listing.complete)
        assertEquals(setOf(10, 379720, 3061810), listing.ids)
        val named = File(apps, "appmanifest_30.acf").apply { writeText("x"); setReadable(false) }
        val unnamed = File(apps, "appmanifest_.acf").apply { writeText("x"); setReadable(false) }
        if (!named.canRead() && !unnamed.canRead()) {
            val after = SteamStorage.manifests(library)!!
            assertTrue(30 in after.ids)
            assertFalse(after.complete)
        }
        assertNull(SteamStorage.manifests(File(library, "missing")))
    }

    @Test fun anEmptyX64FolderIsNoClient() {
        val root = tmp.newFolder("x64")
        File(root, ".cef-enable-remote-debugging").createNewFile()
        assertFalse(SteamStorage.x64ClientPresent(root))
        assertFalse(SteamStorage.x64ClientPresent(File(root, "missing")))
        File(root, "package").mkdirs()
        File(root, "package/droiddeck-installed").writeText("1\n")
        assertTrue(SteamStorage.x64ClientPresent(root))
        val bare = tmp.newFolder("x64-steamapps")
        File(bare, "steamapps").mkdirs()
        assertTrue(SteamStorage.x64ClientPresent(bare))
    }

    @Test fun leftoverIdsSkipTheSharedPrefixAndShortcuts() {
        assertNull(SteamStorage.leftoverId("0"))
        assertNull(SteamStorage.leftoverId("3207321049"))
        assertNull(SteamStorage.leftoverId("-1768546955"))
        assertNull(SteamStorage.leftoverId("pfx"))
        assertEquals(452280, SteamStorage.leftoverId("452280"))
    }

    @Test fun shortcutIdsReadBothSignedAndUnsignedFolderNames() {
        assertEquals(3207321049L, SteamStorage.shortcutId("3207321049"))
        assertEquals(2526420341L, SteamStorage.shortcutId("-1768546955"))
        assertNull(SteamStorage.shortcutId("0"))
        assertNull(SteamStorage.shortcutId("452280"))
        assertNull(SteamStorage.shortcutId("pfx"))
        assertNull(SteamStorage.shortcutId("4294967296"))
    }

    @Test fun sharedPrefixComesFromTheCompatDataPathInLaunchOptions() {
        assertEquals(2929927072L, SteamStorage.sharedPrefixId("STEAM_COMPAT_DATA_PATH=/root/.local/share/Steam/steamapps/compatdata/2929927072 %command%"))
        assertEquals(2596113170L, SteamStorage.sharedPrefixId("STEAM_COMPAT_DATA_PATH=/x/compatdata/2596113170/ %command% --exec=\"launch WoW\""))
        assertNull(SteamStorage.sharedPrefixId("%command% -windowed"))
        assertNull(SteamStorage.sharedPrefixId("STEAM_COMPAT_DATA_PATH=/x/compatdata/0 %command%"))
        assertEquals(3000000002L, SteamStorage.sharedPrefixId("STEAM_COMPAT_DATA_PATH=\"/root/My Games/steamapps/compatdata/3000000002\" %command%"))
        assertEquals(3000000002L, SteamStorage.sharedPrefixId("STEAM_COMPAT_DATA_PATH='/x/compatdata/3000000002/' %command%"))
    }

    @Test fun gameFolderHintReadsTheFolderWineRecorded() {
        val prefix = tmp.newFolder("compatdata", "3000000002")
        File(prefix, "pfx").mkdirs()
        File(prefix, "pfx/system.reg").writeText(
            "\"Path\"=\"Z:\\\\usr\\\\local\\\\share\\\\fonts\\\\a.ttf\"\n" +
                "\"A\"=\"Z:\\\\root\\\\Games\\\\Stores\\\\Ubisoft\\\\Tom Clancy's Splinter Cell\\\\\"\n" +
                "\"B\"=\"Z:\\\\root\\\\Games\\\\Stores\\\\Ubisoft\\\\Tom Clancy's Splinter Cell\\\\system\\\\x.exe\"\n",
        )
        File(prefix, "pfx/user.reg").writeText("\"C\"=\"Z:\\\\mnt\\\\droiddeck-lib\\\\1\\\\steamapps\\\\common\\\\198X\\\\198X.exe\"\n")
        assertEquals("Tom Clancy's Splinter Cell", SteamStorage.gameFolderHint(prefix))
        assertNull(SteamStorage.gameFolderHint(tmp.newFolder("compatdata", "3000000003")))
    }

    @Test fun stubPrefixes() {
        val stub = tmp.newFolder("compatdata", "10")
        assertTrue(SteamStorage.isStubPrefix(stub))
        File(stub, "pfx/drive_c/users/steamuser/AppData/LocalLow/Studio/Game").mkdirs()
        File(stub, "pfx/drive_c/users/steamuser/AppData/LocalLow/Studio/Game/steam_autocloud.vdf").writeText("x")
        assertTrue(SteamStorage.isStubPrefix(stub))
        val used = tmp.newFolder("compatdata", "20")
        File(used, "pfx/drive_c/users/steamuser/Saved Games").mkdirs()
        File(used, "pfx/drive_c/users/steamuser/Saved Games/slot1.sav").writeText("x")
        assertFalse(SteamStorage.isStubPrefix(used))
    }
}
