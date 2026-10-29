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
class ItchUpdatesTest {
    private fun upload(id: String, updated: String, filename: String = "game-$id.zip", windows: Boolean = true, type: String = "default") =
        JSONObject().put("id", id).put("filename", filename).put("updated_at", updated).put("type", type).put("storage", "hosted")
            .put("platforms", JSONObject().put("windows", windows).put("linux", !windows))

    private fun list(vararg uploads: JSONObject) = JSONArray().also { a -> uploads.forEach { a.put(it) } }

    @Test fun aNewerStampOnTheSameUploadIsAnUpdate() {
        val update = ItchUpdates.evaluate(list(upload("7", "2026-10-20T10:00:00Z")), "7", "2026-09-01T10:00:00Z")!!
        assertEquals("7", update.uploadId)
        assertEquals("2026-10-20T10:00:00Z", update.updatedAt)
        assertFalse(update.replaced)
    }

    @Test fun theSameStampIsNotAnUpdate() {
        assertNull(ItchUpdates.evaluate(list(upload("7", "2026-09-01T10:00:00Z")), "7", "2026-09-01T10:00:00Z"))
    }

    @Test fun anOlderStampIsNotAnUpdate() {
        assertNull(ItchUpdates.evaluate(list(upload("7", "2026-08-01T10:00:00Z")), "7", "2026-09-01T10:00:00Z"))
    }

    @Test fun aMissingUploadFallsBackToTheNewestWindowsOneAndAsksFirst() {
        val update = ItchUpdates.evaluate(
            list(upload("8", "2026-10-01T10:00:00Z"), upload("9", "2026-10-05T10:00:00Z"), upload("10", "2026-10-09T10:00:00Z", windows = false)),
            "7", "2026-09-01T10:00:00Z",
        )!!
        assertEquals("9", update.uploadId)
        assertTrue(update.replaced)
    }

    @Test fun aReplacedUploadWithNoWindowsBuildLeftIsNotOffered() {
        val linux = list(upload("8", "2026-10-01T10:00:00Z", windows = false))
        assertNull(ItchUpdates.evaluate(linux, "7", "2026-09-01T10:00:00Z"))
        assertNull(ItchUpdates.evaluate(linux, "", "2026-09-01T10:00:00Z"))
    }

    @Test fun aWindowsBuildOfAnotherTypeBeatsALinuxDefaultOne() {
        val update = ItchUpdates.evaluate(
            list(upload("8", "2026-10-09T10:00:00Z", windows = false), upload("9", "2026-10-01T10:00:00Z", type = "other")),
            "7", "2026-09-01T10:00:00Z",
        )!!
        assertEquals("9", update.uploadId)
        assertTrue(update.replaced)
    }

    @Test fun anUnknownInstalledVersionNeverReportsAnUpdate() {
        assertNull(ItchUpdates.evaluate(list(upload("7", "2026-10-20T10:00:00Z")), "7", ""))
    }

    @Test fun noAnswerOrNoWindowsBuildMeansNoUpdate() {
        assertNull(ItchUpdates.evaluate(null, "7", "2026-09-01T10:00:00Z"))
        assertNull(ItchUpdates.evaluate(JSONArray(), "7", "2026-09-01T10:00:00Z"))
    }

    @Test fun anInstallWithoutAnUploadIdComparesTheNewestWindowsBuild() {
        val update = ItchUpdates.evaluate(list(upload("8", "2026-10-01T10:00:00Z")), "", "2026-09-01T10:00:00Z")!!
        assertEquals("8", update.uploadId)
        assertFalse(update.replaced)
        assertNull(ItchUpdates.evaluate(list(upload("8", "2026-08-01T10:00:00Z")), "", "2026-09-01T10:00:00Z"))
    }

    @Test fun foundUpdatesSurviveTheCache() {
        val found = mapOf("itch:a/b" to ItchUpdate("9", "2026-10-05T10:00:00Z", "b.zip", true))
        val back = ItchUpdates.decode(ItchUpdates.encode(found))
        assertEquals(setOf("itch:a/b"), back.keys)
        assertEquals("9", back.getValue("itch:a/b").uploadId)
        assertTrue(back.getValue("itch:a/b").replaced)
        assertEquals(emptyMap<String, ItchUpdate>(), ItchUpdates.decode(null))
        assertEquals(emptyMap<String, ItchUpdate>(), ItchUpdates.decode("not json"))
    }
}
