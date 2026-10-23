package com.droiddeck.launcher.session

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WinFontComponentsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun pinnedInstallersHaveTheRealFontFamiliesAndNoDllOverrides() {
        val all = WinFontComponents.components().associateBy { it.name }
        assertEquals(setOf("trebuchet", "verdana"), all.keys)
        val expected = mapOf(
            "trebuchet" to listOf("trebuc.ttf" to "Trebuchet MS", "trebucbd.ttf" to "Trebuchet MS Bold",
                "trebucit.ttf" to "Trebuchet MS Italic", "trebucbi.ttf" to "Trebuchet MS Bold Italic"),
            "verdana" to listOf("verdana.ttf" to "Verdana", "verdanab.ttf" to "Verdana Bold",
                "verdanai.ttf" to "Verdana Italic", "verdanaz.ttf" to "Verdana Bold Italic"),
        )
        val hashes = mapOf(
            "trebuchet" to "5a690d9bb8510be1b8b4fe49f1f2319651fe51bbe54775ddddd8ef0bd07fdac9",
            "verdana" to "c1cb61255e363166794e47664e2f21af8e3a26cb6346eb8d2ae2fa85dd5aad96",
        )
        for ((id, c) in all) {
            assertEquals(WinComponents.Support.READY, WinComponents.support(c, all))
            assertEquals("archive_extract", c.steps.first().action)
            assertEquals(hashes[id], c.steps.first().str("file_checksum"))
            assertEquals(expected[id], c.steps.drop(1).map { it.str("file_name") to it.str("family") })
            assertTrue(c.steps.drop(1).all { it.action == "install_font" })
        }
    }

    @Test fun remoteCatalogCannotReplaceFontPinsButOtherComponentsArePreserved() {
        val unsafe = WinComponents.Component("trebuchet", "remote replacement", "", "ready", emptyList(), listOf(
            WinComponents.Step("install_font", JSONObject().put("file_name", "../outside.ttf").put("family", "Fake")),
        ))
        val other = WinComponents.Component("openal", "OpenAL", "", "ready", emptyList(), emptyList())
        val twin = WinComponents.Component("trebuchet_dll", "remote twin", "", "ready", emptyList(), emptyList())
        val merged = WinFontComponents.merge(listOf(unsafe, other, twin)).associateBy { it.name }
        assertEquals(3, merged.size)
        assertEquals(other, merged["openal"])
        assertEquals("trebuc32.exe", merged.getValue("trebuchet").steps.first().str("file_name"))
        assertFalse(merged.containsKey("trebuchet_dll"))
        assertEquals("trebuchet", WinComponents.installable("trebuchet", merged + (twin.name to twin)))
        assertEquals(WinComponents.Support.UNSUPPORTED, WinComponents.support(unsafe, mapOf(unsafe.name to unsafe)))
    }

    @Test fun aFailedRefreshNeedsACompleteCacheBeforeAddingPinnedFonts() {
        val cache = File(tmp.root, "catalog.json")
        assertNull(WinComponents.loadCatalog(null, cache))
        cache.writeText("invalid catalog")
        assertNull(WinComponents.loadCatalog(null, cache))
        val full = """{"components":[{"name":"d3dx9","status":"ready"}]}"""
        assertEquals(setOf("d3dx9", "trebuchet", "verdana"),
            WinComponents.loadCatalog(full, cache)!!.map { it.name }.toSet())
        assertEquals(full, cache.readText())
        assertEquals(setOf("d3dx9", "trebuchet", "verdana"),
            WinComponents.loadCatalog(null, cache)!!.map { it.name }.toSet())
        assertEquals(setOf("d3dx9", "trebuchet", "verdana"),
            WinComponents.loadCatalog("invalid catalog", cache)!!.map { it.name }.toSet())
        assertEquals(full, cache.readText())
    }

    @Test fun fontStagingUsesOnlyTheWindowsFontsDirectoryAndRealRegistrationKeys() {
        val source = tmp.newFolder("source")
        File(source, "Trebuc.TTF").writeBytes(byteArrayOf(0, 1, 0, 0))
        val staging = tmp.newFolder("staging")
        val values = WinFontComponents.stage("trebuchet", "trebuc.ttf", "Trebuchet MS", source, staging)
        assertTrue(File(staging, "drive_c/windows/Fonts/trebuc.ttf").isFile)
        assertFalse(File(staging, "system32").exists())
        assertEquals(setOf("Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", "Software\\Microsoft\\Windows\\CurrentVersion\\Fonts"),
            values.map { it.getString("key") }.toSet())
        for (value in values) {
            assertEquals("HKLM", value.getString("hive"))
            assertEquals("Trebuchet MS (TrueType)", value.getString("name"))
            assertEquals("trebuc.ttf", value.getString("data"))
            assertEquals("sz", value.getString("type"))
        }
    }

    @Test fun fontStagingRejectsTraversalWrongFamiliesAndMissingOrAmbiguousFiles() {
        val source = tmp.newFolder("fonts")
        val staging = tmp.newFolder("out")
        fun rejected(component: String, file: String, family: String) = assertTrue(runCatching {
            WinFontComponents.stage(component, file, family, source, staging)
        }.isFailure)
        rejected("trebuchet", "../trebuc.ttf", "Trebuchet MS")
        rejected("trebuchet", "trebuc.ttf", "Trebuchet MS\n[Other Registry Key]")
        rejected("verdana", "trebuc.ttf", "Trebuchet MS")
        rejected("trebuchet", "trebuc.otf", "Trebuchet MS")
        rejected("trebuchet", "trebuc.ttf", "Trebuchet MS")
        File(source, "one").mkdir()
        File(source, "two").mkdir()
        File(source, "one/trebuc.ttf").writeText("first")
        File(source, "two/TREBUC.TTF").writeText("second")
        rejected("trebuchet", "trebuc.ttf", "Trebuchet MS")
        assertFalse(File(staging, "drive_c").exists())
    }

    @Test fun sha256AndExistingMd5ChecksRejectChangedArchivesAndMalformedPins() {
        val archive = tmp.newFile("archive.exe").apply { writeText("abc") }
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(WinComponents.checksumMatches(archive, sha))
        assertTrue(WinComponents.checksumMatches(archive, "900150983CD24FB0D6963F7D28E17F72"))
        assertFalse(WinComponents.checksumMatches(archive, "bad hash"))
        assertFalse(WinComponents.checksumMatches(archive, "f".repeat(64)))
        archive.writeText("changed")
        assertFalse(WinComponents.checksumMatches(archive, sha))
    }
}
