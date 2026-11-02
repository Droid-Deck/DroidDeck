package com.droiddeck.launcher.stores.itch

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class ItchBuildsTest {
    private fun file(id: Int, type: String, sub: String, size: Int = 10) =
        JSONObject().put("id", id).put("type", type).put("sub_type", sub).put("size", size).put("state", "uploaded")

    private fun build(id: Int, vararg files: JSONObject) = JSONObject().put("id", id).put("files", JSONArray(files.toList()))

    private fun path(vararg builds: JSONObject) = JSONObject().put("upgrade_path", JSONObject().put("builds", JSONArray(builds.toList())))

    @Test fun buildIdReadsBothKeyStylesAndIgnoresMissingValues() {
        assertEquals("55", ItchBuilds.buildId(JSONObject().put("build_id", 55)))
        assertEquals("56", ItchBuilds.buildId(JSONObject().put("buildId", "56")))
        assertEquals("", ItchBuilds.buildId(JSONObject().put("build_id", JSONObject.NULL)))
        assertEquals("", ItchBuilds.buildId(JSONObject().put("build_id", 0)))
        assertEquals("", ItchBuilds.buildId(JSONObject().put("build_id", "00")))
        assertEquals("57", ItchBuilds.buildId(JSONObject().put("build_id", "057")))
        assertEquals("", ItchBuilds.buildId(JSONObject()))
    }

    @Test fun pathSkipsTheInstalledBuildAndPrefersOptimizedPatches() {
        val reply = path(
            build(1, file(11, "patch", "default")),
            build(2, file(21, "patch", "default", 5), file(22, "patch", "optimized", 3), file(23, "signature", "default")),
        )
        val steps = ItchBuilds.steps(reply, 1, 2) { it.optJSONArray("files") }!!
        assertEquals(1, steps.size)
        assertEquals(2L, steps.single().buildId)
        assertEquals(22L, steps.single().patchFile)
        assertEquals(3L, steps.single().patchSize)
        assertEquals(23L, steps.single().signatureFile)
    }

    @Test fun pathIsRefusedWithoutASignatureOrAPatchOrTheRightEnd() {
        val noSignature = path(build(1), build(2, file(21, "patch", "default")))
        assertNull(ItchBuilds.steps(noSignature, 1, 2) { it.optJSONArray("files") })
        val noPatch = path(build(1), build(2, file(23, "signature", "default")))
        assertNull(ItchBuilds.steps(noPatch, 1, 2) { it.optJSONArray("files") })
        val wrongEnd = path(build(1), build(3, file(31, "patch", "default"), file(32, "signature", "default")))
        assertNull(ItchBuilds.steps(wrongEnd, 1, 2) { it.optJSONArray("files") })
        assertNull(ItchBuilds.steps(JSONObject(), 1, 2) { it.optJSONArray("files") })
    }

    @Test fun pathNeedsFilesFromALookupWhenTheBuildListsNone() {
        val reply = path(JSONObject().put("id", 1), JSONObject().put("id", 2))
        val steps = ItchBuilds.steps(reply, 1, 2) { JSONArray().put(file(21, "patch", "default")).put(file(23, "signature", "default")) }!!
        assertEquals(21L, steps.single().patchFile)
    }
}
