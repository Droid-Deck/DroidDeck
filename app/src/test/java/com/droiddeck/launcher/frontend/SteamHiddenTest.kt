package com.droiddeck.launcher.frontend

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SteamHiddenTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun steamRoot(): File = tmp.newFolder("Steam").also { root ->
        File(root, "config").mkdirs()
        File(root, "config/loginusers.vdf").writeText(
            "\"users\"\n{\n\t\"76561198352563669\"\n\t{\n\t\t\"AccountName\"\t\t\"a\"\n\t\t\"MostRecent\"\t\t\"1\"\n\t}\n}\n")
    }

    private fun store(root: File, json: String?): File {
        val dir = File(root, "userdata/392297941/config/cloudstorage").apply { mkdirs() }
        return File(dir, "cloud-storage-namespace-1.json").also { f -> json?.let { f.writeText(it) } }
    }

    private fun hiddenEntry(file: File): JSONObject {
        val entries = JSONArray(file.readText())
        return (0 until entries.length()).map { entries.getJSONArray(it) }.first { it.getString(0) == "user-collections.hidden" }.getJSONObject(1)
    }

    @Test fun hidingAddsTheGameAndKeepsOtherEntries() {
        val root = steamRoot()
        val file = store(root, """[["user-collections.favorite",{"key":"user-collections.favorite","value":"{}"}],["user-collections.hidden",{"key":"user-collections.hidden","timestamp":5,"version":"7","value":"{\"id\":\"hidden\",\"name\":\"Hidden\",\"added\":[10],\"removed\":[]}"}]]""")
        assertNotNull(SteamHidden.write(root, 440, true, now = 99))
        assertEquals(setOf(10L, 440L), SteamHidden.read(root))
        val entry = hiddenEntry(file)
        assertEquals(99L, entry.getLong("timestamp"))
        assertEquals("8", entry.getString("version"))
        assertEquals("Hidden", JSONObject(entry.getString("value")).getString("name"))
        assertEquals(2, JSONArray(file.readText()).length())
    }

    @Test fun unhidingTakesTheGameOutAgain() {
        val root = steamRoot()
        store(root, """[["user-collections.hidden",{"key":"user-collections.hidden","value":"{\"id\":\"hidden\",\"added\":[10,440],\"removed\":[]}"}]]""")
        assertNotNull(SteamHidden.write(root, 440, false))
        assertEquals(setOf(10L), SteamHidden.read(root))
    }

    @Test fun aMissingCollectionIsCreatedWhenHiding() {
        val root = steamRoot()
        val file = store(root, "[]")
        assertNotNull(SteamHidden.write(root, 2931908220L, true))
        assertEquals(setOf(2931908220L), SteamHidden.read(root))
        assertFalse(hiddenEntry(file).getBoolean("is_deleted"))
    }

    @Test fun aDeletedCollectionIsRevivedWhenHiding() {
        val root = steamRoot()
        store(root, """[["user-collections.hidden",{"key":"user-collections.hidden","is_deleted":true}]]""")
        assertEquals(emptySet<Long>(), SteamHidden.read(root))
        assertNotNull(SteamHidden.write(root, 7, true))
        assertEquals(setOf(7L), SteamHidden.read(root))
    }

    @Test fun withoutASteamStoreNothingIsWritten() {
        val root = steamRoot()
        assertNull(SteamHidden.write(root, 1, true))
        assertEquals(emptySet<Long>(), SteamHidden.read(root))
    }

    @Test fun theCollectionIsMarkedAsPendingUploadOnce() {
        val root = steamRoot()
        val file = store(root, "[]")
        SteamHidden.write(root, 1, true)
        SteamHidden.write(root, 2, true)
        assertEquals("""["user-collections.hidden"]""", File(file.parentFile, "cloud-storage-namespace-1.modified.json").readText())
    }

    @Test fun aMalformedMemberIsRejectedAndTheFileIsLeftAlone() {
        val root = steamRoot()
        val json = """[["user-collections.hidden",{"key":"user-collections.hidden","value":"{\"id\":\"hidden\",\"added\":[\"x\"],\"removed\":[]}"}]]"""
        val file = store(root, json)
        assertEquals(emptySet<Long>(), SteamHidden.readOrNull(root))
        assertNull(SteamHidden.write(root, 1, true))
        assertEquals(json, file.readText())
    }

    @Test fun numericStringsAreReadAndBadMembersAreSkipped() {
        val root = steamRoot()
        store(root, """[["user-collections.hidden",{"key":"user-collections.hidden","value":"{\"id\":\"hidden\",\"added\":[\"440\",\"x\",4.5,730],\"removed\":[]}"}]]""")
        assertEquals(setOf(440L, 730L), SteamHidden.read(root))
    }
}
