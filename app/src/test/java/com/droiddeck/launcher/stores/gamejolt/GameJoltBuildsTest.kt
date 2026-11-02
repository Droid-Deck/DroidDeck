package com.droiddeck.launcher.stores.gamejolt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class GameJoltBuildsTest {
    private fun build(id: Int, pkg: Int, release: Int, name: String, vararg flags: String) = JSONObject()
        .put("id", id).put("game_package_id", pkg).put("game_release_id", release)
        .put("primary_file", JSONObject().put("filename", name).put("filesize", 100))
        .also { b -> flags.forEach { b.put(it, true) } }

    private fun payload(vararg builds: JSONObject) = JSONObject()
        .put("packages", JSONArray().put(JSONObject().put("id", 1).put("title", "Main")))
        .put("releases", JSONArray().put(JSONObject().put("id", 10).put("published_on", 100).put("version_number", "1.0")).put(JSONObject().put("id", 11).put("published_on", 200).put("version_number", "1.1")))
        .put("builds", JSONArray(builds.toList()))
        .put("launchOptions", JSONArray())

    @Test fun onlyTheNewestReleaseOfAPackageIsKept() {
        val list = GameJoltBuilds.parse(payload(build(1, 1, 10, "old.zip", "os_windows"), build(2, 1, 11, "new.zip", "os_windows")))
        assertEquals(listOf("2"), list.map { it.id })
        assertEquals("1.1", list.first().version)
    }

    @Test fun windowsBuildsAreInstallableAndSortFirst() {
        val list = GameJoltBuilds.parse(payload(build(1, 1, 11, "game-linux.zip", "os_linux"), build(2, 1, 11, "game-win.zip", "os_windows")))
        assertEquals(listOf("2", "1"), list.map { it.id })
        assertTrue(list[0].installable)
        assertFalse(list[1].installable)
    }

    @Test fun aBuildWithoutAnOsFlagIsWeb() {
        assertEquals(setOf("web"), GameJoltBuilds.platforms(JSONObject()))
        assertEquals(setOf("windows", "osx"), GameJoltBuilds.platforms(JSONObject().put("os_windows", true).put("os_mac", true)))
    }
}
