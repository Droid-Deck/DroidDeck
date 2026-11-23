package com.droiddeck.launcher.runtime

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class CurseForgeAppTest {
    @get:Rule val temp = TemporaryFolder()

    private val original = CurseForgeApp.guest
    private val url = "ws://127.0.0.1:43117/0f8fad5b-d9cb-469f-a165-70867728950e"
    private val app = UserApps.App("appimage:c", UserApps.Kind.APPIMAGE, "CurseForge", null, null, AppImageManager.LAUNCHER, listOf("/root/apps/curseforge"), feed = CurseForgeApp.FEED)

    @After
    fun restoreGuest() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        launches.forEach { CurseForgeApp.stop(context, app, null, it) }
        holder.get()?.set(true)
        CurseForgeApp.guest = original
    }

    private val launches = mutableListOf<java.util.concurrent.atomic.AtomicBoolean>()

    private fun launch(): java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false).also { launches += it }

    private val holder = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.atomic.AtomicBoolean?>()
    private val released = java.util.concurrent.CountDownLatch(1)

    private fun fakeGuest(startExit: Int, ready: Boolean = true): MutableList<List<String>> {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        CurseForgeApp.guest = { _, argv, cancelled, onLine ->
            calls += argv
            when {
                argv[0] == CurseForgeApp.HELPER && argv[1] == "start" -> {
                    if (startExit == 0) {
                        holder.set(cancelled)
                        onLine(url)
                        while (cancelled?.get() != true) Thread.sleep(10)
                        released.countDown()
                    } else onLine("busy")
                    startExit
                }
                argv[0] == CurseForgeApp.HELPER -> 0
                ready -> { onLine(JSONObject().put("ok", true).put("value", """{"value":true}""").toString()); 0 }
                else -> { onLine(JSONObject().put("ok", false).put("error", JSONObject().put("code", "DEVTOOLS_UNREACHABLE").put("message", "no")).toString()); 0 }
            }
        }
        return calls
    }

    private val search = """{"success":true,"injectedCount":0,
      "pagination":{"index":0,"pageSize":20,"resultCount":20,"totalCount":150},
      "mods":[
        {"id":486246,"name":"RestedXP Guide","summary":"Leveling guide","downloads":12345678,
         "thumbnailUrl":"https://media.forgecdn.net/avatars/rxp.png","authors":[{"name":"RestedXP"}],
         "updateVersion":{"id":7,"filename":"RXPGuides.zip","fileDate":"2026-11-20T10:00:00.000Z"}},
        {"id":2,"name":"Plain","downloads":5,"thumbnailUrl":"http://insecure/x.png","primaryAuthor":{"name":"Main"},
         "authors":[{"name":"Other"}],"projectLatestReleaseDate":"2026-01-01T00:00:00Z"},
        {"id":0,"name":"No id"},
        {"id":3,"name":""}
      ]}"""

    @Test
    fun searchResultsBecomeCatalogEntries() {
        val page = CurseForgeApp.parseSearch(JSONObject(search))
        assertEquals(listOf("486246", "2"), page.items.map { it.id })
        val rxp = page.items[0]
        assertEquals("RestedXP Guide", rxp.name)
        assertEquals("RestedXP", rxp.author)
        assertEquals(12345678L, rxp.downloads)
        assertEquals(java.time.Instant.parse("2026-11-20T10:00:00Z").toEpochMilli(), rxp.updated)
        assertEquals("https://media.forgecdn.net/avatars/rxp.png", rxp.thumb)
        assertEquals("Main", page.items[1].author)
        assertNull(page.items[1].thumb)
        assertTrue(page.more)
    }

    @Test
    fun theLastPageHasNoMore() {
        val last = JSONObject(search).put("pagination", JSONObject("""{"index":140,"resultCount":10,"totalCount":150}"""))
        assertFalse(CurseForgeApp.parseSearch(last).more)
        assertThrows(CurseForgeApp.Failure::class.java) { CurseForgeApp.parseSearch(JSONObject("""{"success":false}""")) }
        assertThrows(CurseForgeApp.Failure::class.java) { CurseForgeApp.parseSearch(null) }
    }

    @Test
    fun searchRequestsUseTheInstanceAndOneBasedPages() {
        val request = CurseForgeApp.searchRequest("guid-1", " Rested ", WowAddonCatalog.Sort.NAME, 0)
        assertEquals(1, request.getInt("gameId"))
        assertEquals("Rested", request.getString("searchText"))
        assertEquals(1, request.getInt("page"))
        assertEquals(1, request.getInt("sortColumn"))
        assertEquals(1, request.getInt("sortOrder"))
        assertEquals("guid-1", request.getString("gameInstanceGuid"))
        assertEquals(2, CurseForgeApp.searchRequest("g", "", WowAddonCatalog.Sort.DOWNLOADS, 1).getInt("page"))
        assertEquals(WowAddonCatalog.Sort.entries.size, WowAddonCatalog.Sort.entries.map(CurseForgeApp::sortOf).toSet().size)
    }

    @Test
    fun installRequestsInstallTheLatestFileIntoTheInstance() {
        val request = CurseForgeApp.installRequest("guid-1", "486246")
        assertEquals("guid-1", request.getString("instanceId"))
        assertEquals(486246L, request.getLong("projectId"))
        assertTrue(request.getBoolean("installLatestFile"))
    }

    @Test
    fun theInstanceIsPickedByItsFlavorFolder() {
        val instances = JSONArray("""[
          {"guid":"retail","installPath":"/root/Games/WoW/_retail_/"},
          {"guid":"forever","installPath":"/root/Games/WoW/_forever_/"}
        ]""")
        assertEquals("forever", CurseForgeApp.instanceFor(instances, "/root/Games/WoW/_forever_", "retail"))
        assertEquals("retail", CurseForgeApp.instanceFor(instances, "/root/Games/WoW/_retail_", null))
        assertNull(CurseForgeApp.instanceFor(instances, "/root/Games/WoW/_classic_", "retail"))
        val single = JSONArray("""[{"guid":"retail","installPath":"/root/Games/WoW/_retail_/"}]""")
        assertNull(CurseForgeApp.instanceFor(single, "/root/Games/WoW/_classic_", "retail"))
        assertEquals("retail", CurseForgeApp.instanceFor(single, "/root/Games/WoW/_retail_", "other"))
        assertEquals("only", CurseForgeApp.instanceFor(JSONArray(), "/x/_forever_", "only"))
        assertEquals("only", CurseForgeApp.instanceFor(null, "/x/_forever_", "only"))
        assertNull(CurseForgeApp.instanceFor(JSONArray(), "/x/_forever_", ""))
    }

    @Test
    fun answersAreUnwrapped() {
        val ok = JSONObject().put("ok", true).put("value", """{"value":{"resultStatus":3,"gameInstanceGuid":"g"}}""")
        assertEquals("g", (CurseForgeApp.unwrap(ok) as JSONObject).getString("gameInstanceGuid"))
        assertEquals(true, CurseForgeApp.unwrap(JSONObject().put("ok", true).put("value", """{"value":true}""")))
        val missing = JSONObject().put("ok", true).put("value", """{"missing":true}""")
        assertThrows(CurseForgeApp.Failure::class.java) { CurseForgeApp.unwrap(missing) }
        val failed = JSONObject().put("ok", false).put("error", JSONObject().put("code", "NO_TARGET").put("message", "no node"))
        val failure = assertThrows(CurseForgeApp.Failure::class.java) { CurseForgeApp.unwrap(failed) }
        assertEquals("no node", failure.message)
        assertEquals("NO_TARGET", failure.code)
        assertFalse(failure.gone)
        val lost = JSONObject().put("ok", false).put("error", JSONObject().put("code", "DEVTOOLS_UNREACHABLE").put("message", "refused"))
        assertTrue(assertThrows(CurseForgeApp.Failure::class.java) { CurseForgeApp.unwrap(lost) }.gone)
    }

    @Test
    fun expressionsDriveTheHiddenDesktopWindow() {
        val text = CurseForgeApp.expression("ProjectAppService", "search", JSONArray().put(JSONObject().put("searchText", "it's \"x\"")))
        assertTrue(text.startsWith("(() => { const w = require('electron').BrowserWindow.getAllWindows()"))
        assertTrue(text.contains("desktop.html"))
        assertTrue(text.contains("executeJavaScript("))
        assertFalse(text.contains("show()"))
        val probe = CurseForgeApp.expression("InstalledProjectsAppService", "installAddon", null)
        assertFalse(probe.contains("await s["))
        val connect = CurseForgeApp.expression("CoreService", "connect", JSONArray(), "r => true")
        assertTrue(connect.contains("(r => true)(await s[\\\"connect\\\"](...[]))"))
    }

    @Test
    fun installedIdsAndExitCodes() {
        assertEquals(setOf("486246", "2"), CurseForgeApp.projectIds(JSONArray("""["486246","2",null]""")))
        assertEquals(emptySet<String>(), CurseForgeApp.projectIds(JSONObject()))
        assertEquals(CurseForgeApp.Start.READY, CurseForgeApp.startState(0))
        assertEquals(CurseForgeApp.Start.BUSY, CurseForgeApp.startState(3))
        assertEquals(CurseForgeApp.Start.FAILED, CurseForgeApp.startState(4))
        assertEquals(CurseForgeApp.Start.FAILED, CurseForgeApp.startState(5))
    }

    @Test
    fun onlyASecretInspectorUrlIsTaken() {
        val url = "ws://127.0.0.1:43117/0f8fad5b-d9cb-469f-a165-70867728950e"
        assertEquals(url, CurseForgeApp.inspectorUrl("noise\n$url\n"))
        assertNull(CurseForgeApp.inspectorUrl("ready\n"))
        assertNull(CurseForgeApp.inspectorUrl("ws://127.0.0.1:9229/\n"))
        assertNull(CurseForgeApp.inspectorUrl("ws://10.0.0.2:43117/0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertNull(CurseForgeApp.inspectorUrl("$url/json"))
    }

    @Test
    fun theWowRootIsTheParentOfEveryFlavor() {
        val root = temp.newFolder("World of Warcraft")
        for (flavor in listOf("_classic_", "_classic_era_")) {
            File(root, "$flavor/Interface/AddOns").mkdirs()
            File(root, "$flavor/.flavor.info").writeText("x")
        }
        val title = BattleNet.TITLES.first { it.flavor == "_classic_" }
        val targets = WowAddons.targets(title, File(root, "_classic_"))
        assertEquals(listOf("_classic_", "_classic_era_"), targets.map { it.flavor }.sorted())
        for (target in targets) {
            assertEquals(root, WowAddons.root(target))
            assertEquals(File(root, target.flavor), WowAddons.flavorDir(target))
        }
    }

    @Test
    fun theAppStartsInTheGuestWithoutASessionAndIsAskedThroughItsInspector() {
        val calls = fakeGuest(0)
        val started = CurseForgeApp.start(org.robolectric.RuntimeEnvironment.getApplication(), app, launch())
        assertEquals(CurseForgeApp.Start.READY, started.state)
        assertEquals(url, started.inspector)
        assertEquals(listOf(CurseForgeApp.HELPER, "start", "/root/apps/curseforge"), calls.first())
        val probe = calls[1]
        assertEquals(listOf(CurseForgeApp.AGENT, "--cdp-inspector", url), probe.take(3))
        assertEquals("10.0", probe[3])
        assertTrue(probe[4].contains("InstalledProjectsAppService"))
    }

    @Test
    fun theLaunchLivesUntilItIsStopped() {
        fakeGuest(0)
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val hold = launch()
        assertEquals(CurseForgeApp.Start.READY, CurseForgeApp.start(context, app, hold).state)
        assertEquals(false, holder.get()?.get())
        assertEquals(1L, released.count)
        CurseForgeApp.stop(context, app, url, hold)
        assertTrue(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun anAppThatIsAlreadyOpenIsNeitherUsedNorStopped() {
        val calls = fakeGuest(3)
        assertEquals(CurseForgeApp.Start.BUSY, CurseForgeApp.start(org.robolectric.RuntimeEnvironment.getApplication(), app, launch()).state)
        assertEquals(listOf(listOf(CurseForgeApp.HELPER, "start", "/root/apps/curseforge")), calls)
    }

    @Test
    fun stoppingAsksTheAppToQuitThenStopsItsHeadlessLaunchOnce() {
        val calls = fakeGuest(0)
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val hold = launch()
        CurseForgeApp.start(context, app, hold)
        calls.clear()
        CurseForgeApp.stop(context, app, url, hold)
        assertEquals(CurseForgeApp.AGENT, calls[0][0])
        assertTrue(calls[0][4].contains("shutdown"))
        assertEquals(listOf(CurseForgeApp.HELPER, "stop", "/root/apps/curseforge"), calls[1])
        assertTrue(hold.get())
        calls.clear()
        CurseForgeApp.stop(context, app, url, hold)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aLateStopLeavesTheNewerLaunchRunning() {
        val calls = fakeGuest(0)
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val old = launch()
        val newer = launch()
        CurseForgeApp.start(context, app, old)
        CurseForgeApp.start(context, app, newer)
        assertTrue(old.get())
        calls.clear()
        CurseForgeApp.stop(context, app, url, old)
        assertTrue(calls.isEmpty())
        assertEquals(false, newer.get())
        CurseForgeApp.stop(context, app, null, newer)
        assertEquals(listOf(listOf(CurseForgeApp.HELPER, "stop", "/root/apps/curseforge")), calls)
    }

    @Test
    fun aStoppedLaunchNeverStarts() {
        val calls = fakeGuest(0)
        val hold = launch().apply { set(true) }
        assertEquals(CurseForgeApp.Start.FAILED, CurseForgeApp.start(org.robolectric.RuntimeEnvironment.getApplication(), app, hold).state)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun anAppWithoutADirectoryIsNeverLaunched() {
        val calls = fakeGuest(0)
        val bare = UserApps.App("appimage:d", UserApps.Kind.APPIMAGE, "CurseForge", null, null, AppImageManager.LAUNCHER, emptyList(), feed = CurseForgeApp.FEED)
        val hold = launch()
        assertEquals(CurseForgeApp.Start.FAILED, CurseForgeApp.start(org.robolectric.RuntimeEnvironment.getApplication(), bare, hold).state)
        CurseForgeApp.stop(org.robolectric.RuntimeEnvironment.getApplication(), bare, url, hold)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun theInspectorUrlIsRedactedFromCommandLogs() {
        val line = CurseForgeApp.evalArgv(url, "x", 1_000).joinToString(" ")
        assertTrue(line.contains(url))
        assertTrue(!GuestCommand.redacted(line, CurseForgeApp.INSPECTOR).contains("127.0.0.1"))
        assertTrue(!GuestCommand.redacted(url, CurseForgeApp.INSPECTOR).contains(url))
    }

    @Test
    fun theAgentAnswerIsTheLastJsonLine() {
        assertEquals("a", CurseForgeApp.lastJson(listOf("noise", """{"v":"a"}""", "== done"))?.getString("v"))
        assertNull(CurseForgeApp.lastJson(listOf("noise")))
    }
}
