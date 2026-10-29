package com.droiddeck.launcher.stores.itch

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class ItchGameTest {
    private fun upload(id: String, filename: String, vararg traits: String, type: String = "default", storage: String = "hosted", demo: Boolean = false, updated: String = "2026-09-16T02:26:06Z") =
        JSONObject().put("id", id).put("filename", filename).put("size", 1000).put("type", type).put("storage", storage)
            .put("demo", demo).put("traits", JSONArray(traits.toList())).put("updated_at", updated)

    @Test fun theGameNumberComesFromThePageMeta() {
        assertEquals("1576684", ItchGame.idFromPage("""<head><meta name="itch:path" content="games/1576684"></head>"""))
        assertEquals("1576684", ItchGame.idFromPage("""<meta content="games/1576684" name="itch:path">"""))
        assertNull(ItchGame.idFromPage("<html>no meta here</html>"))
    }

    @Test fun platformsComeFromTheTraits() {
        assertEquals(setOf("windows"), ItchUploads.platforms(upload("1", "a.zip", "p_windows")))
        assertEquals(setOf("linux", "osx"), ItchUploads.platforms(upload("2", "a.zip", "p_linux", "p_osx")))
        assertEquals(setOf("android"), ItchUploads.platforms(upload("3", "a.apk", "p_android")))
        assertEquals(setOf("web"), ItchUploads.platforms(upload("4", "a.zip", type = "html")))
    }

    @Test fun anUploadWithNoPlatformIsTreatedAsWindows() {
        assertEquals(setOf("windows"), ItchUploads.platforms(upload("5", "a.zip")))
    }

    @Test fun platformsAlsoReadTheFlagObject() {
        val flags = JSONObject().put("windows", false).put("linux", true)
        assertEquals(setOf("linux"), ItchUploads.platforms(JSONObject().put("platforms", flags)))
    }

    @Test fun buildsSkipExternalUploadsAndPutWindowsFirst() {
        val uploads = JSONArray()
            .put(upload("10", "linux.tar.gz", "p_linux", updated = "2026-10-01T00:00:00Z"))
            .put(upload("11", "win.zip", "p_windows", updated = "2026-09-01T00:00:00Z"))
            .put(upload("12", "site", "p_windows", storage = "external"))
        val builds = ItchGame.builds(uploads)
        assertEquals(listOf("11", "10"), builds.map { it.uploadId })
        assertTrue(builds[0].installable)
        assertFalse(builds[1].installable)
    }

    @Test fun aDemoIsFlagged() {
        assertTrue(ItchGame.builds(JSONArray().put(upload("20", "demo.zip", "p_windows", demo = true))).single().demo)
    }

    @Test fun anUploadIsFoundById() {
        val uploads = JSONArray().put(upload("30", "a.zip")).put(upload("31", "b.zip"))
        assertEquals("b.zip", ItchUploads.byId(uploads, "31")?.optString("filename"))
        assertNull(ItchUploads.byId(uploads, "99"))
        assertNull(ItchUploads.byId(uploads, ""))
    }

    @Test fun noDownloadKeyMeansNoKeyInTheAddress() {
        assertEquals("https://api.itch.io/uploads/13619666/download", ItchApi.downloadUrl("13619666", ""))
        assertEquals("https://api.itch.io/uploads/13619666/download?download_key_id=7", ItchApi.downloadUrl("13619666", "7"))
    }
}
