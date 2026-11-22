package com.droiddeck.launcher.runtime

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        assertEquals("only", CurseForgeApp.instanceFor(JSONArray(), "/x/_forever_", "only"))
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
        assertEquals("no node", assertThrows(CurseForgeApp.Failure::class.java) { CurseForgeApp.unwrap(failed) }.message)
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
        assertEquals(CurseForgeApp.Start.NO_DISPLAY, CurseForgeApp.startState(4))
        assertEquals(CurseForgeApp.Start.FAILED, CurseForgeApp.startState(5))
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
}
