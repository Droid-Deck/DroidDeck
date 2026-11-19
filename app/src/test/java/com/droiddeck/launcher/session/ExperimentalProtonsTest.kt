package com.droiddeck.launcher.session

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ExperimentalProtonsTest {
    private fun artifact(id: Long, name: String, size: Long, expired: Boolean = false) =
        JSONObject().put("id", id).put("name", name).put("size_in_bytes", size).put("expired", expired)

    @Test
    fun parsesRunAndArtifactLinks() {
        assertEquals(
            ExperimentalProtons.RunLink("FryMcNugget", "DroidDeck-Proton", 44221503320, null),
            ExperimentalProtons.parseRunLink("https://github.com/FryMcNugget/DroidDeck-Proton/actions/runs/44221503320/job/123"),
        )
        assertEquals(
            14392944037L,
            ExperimentalProtons.parseRunLink("github.com/a/b/actions/runs/1/artifacts/14392944037")?.artifact,
        )
        assertNull(ExperimentalProtons.parseRunLink("https://github.com/a/b/releases/tag/v1"))
    }

    @Test
    fun picksTheProtonArtifactOverLogs() {
        val list = JSONArray()
            .put(artifact(1, "make-log-wine-stage1", 900_000))
            .put(artifact(2, "build-info", 1000))
            .put(artifact(3, "DroidDeck-Proton-11-12.8-aarch64", 626_000_000))
            .put(artifact(4, "state-wine-stage1", 2_000_000_000))
        assertEquals(3L, ExperimentalProtons.pickArtifact(list, null)?.id)
        assertEquals(1L, ExperimentalProtons.pickArtifact(list, 1)?.id)
        assertNull(ExperimentalProtons.pickArtifact(JSONArray().put(artifact(3, "DroidDeck-Proton", 1, expired = true)), null))
    }

    @Test
    fun namesNeverCollideWithReleasedBuilds() {
        val name = ExperimentalProtons.dirName("DroidDeck-Proton-11-12.8-aarch64", "pr34-build")
        assertEquals("Experimental-DroidDeck-Proton-11-12.8-aarch64-pr34-build", name)
        assertFalse(name.startsWith("DroidDeck-Proton"))
        assertEquals("Experimental-DroidDeck-Proton-11-12.8-aarch64",
            ExperimentalProtons.dirName("DroidDeck-Proton-11-12.8-aarch64", "DroidDeck-Proton-11-12.8-aarch64"))
    }
}
