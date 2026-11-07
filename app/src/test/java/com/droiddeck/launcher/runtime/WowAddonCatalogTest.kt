package com.droiddeck.launcher.runtime

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class WowAddonCatalogTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private val files = """[
      {"UID":"1","UICATID":"19","UIVersion":"1.0","UIDate":300,"UIName":"Bartender","UIAuthorName":"Nevcairiel","UIDownloadTotal":"500","UICompatibility":[{"version":"12.0.0","name":"Midnight"}],"UIIMG_Thumbs":["https://cdn.example/a.jpg"]},
      {"UID":"2","UICATID":"20","UIVersion":"2.0","UIDate":100,"UIName":"Zygor","UIAuthorName":"Kappa","UIDownloadTotal":"900","UICompatibility":[{"version":"1.15.7","name":"Classic"},{"version":"4.4.2","name":"Cataclysm Classic"}],"UIIMG_Thumbs":null},
      {"UID":"3","UICATID":"21","UIVersion":"3.0","UIDate":200,"UIName":"Alpha","UIAuthorName":"bartuser","UIDownloadTotal":"10","UIDownloadMonthly":"9","UICompatibility":null},
      {"UID":"4","UICATID":"19","UIVersion":"4.0","UIDate":400,"UIName":"TBCOnly","UIAuthorName":"x","UIDownloadTotal":"20","UIDownloadMonthly":"5","UICompatibility":[{"version":"2.5.4","name":"The Burning Crusade Classic"}]}
    ]"""

    private val cats = """[
      {"UICATID":"19","UICATTitle":"Action Bar Mods","UICATParentIDs":["0","23"]},
      {"UICATID":"20","UICATTitle":"Bags","UICATParentIDs":["0","23"]},
      {"UICATID":"21","UICATTitle":"Child","UICATParentIDs":["0","20"]}
    ]"""

    private fun c(folder: String, version: String? = null) = WowAddonCatalog.client(folder, version)

    private fun run(q: WowAddonCatalog.Query) = WowAddonCatalog.filterWowi(WowAddonCatalog.parseWowiFiles(files), WowAddonCatalog.parseWowiCategories(cats), q).map { it.id }

    @Test fun parsesFileListEntries() {
        val e = WowAddonCatalog.parseWowiFiles(files)
        assertEquals(4, e.size)
        val a = e[0].addon
        assertEquals("Bartender", a.name)
        assertEquals("Nevcairiel", a.author)
        assertEquals(500L, a.downloads)
        assertEquals(300L, a.updated)
        assertEquals("https://cdn.example/a.jpg", a.thumb)
        assertNull(e[1].addon.thumb)
        assertEquals("19", e[0].category)
    }

    @Test fun versionsMapToFlavors() {
        assertEquals("_retail_", WowAddonCatalog.flavorOf("12.1.0"))
        assertEquals("_retail_", WowAddonCatalog.flavorOf("11.0.5"))
        assertEquals("_classic_era_", WowAddonCatalog.flavorOf("1.15.7"))
        assertEquals("_anniversary_", WowAddonCatalog.flavorOf("2.5.4"))
        assertEquals("_classic_", WowAddonCatalog.flavorOf("4.4.2"))
        assertNull(WowAddonCatalog.flavorOf("9.2.7"))
    }

    @Test fun flavorFilterKeepsNullCompatibilityEverywhere() {
        assertEquals(setOf("1", "3"), run(WowAddonCatalog.Query(client = c("_retail_"))).toSet())
        assertEquals(setOf("2", "3"), run(WowAddonCatalog.Query(client = c("_classic_era_"))).toSet())
        assertEquals(setOf("2", "3"), run(WowAddonCatalog.Query(client = c("_classic_"))).toSet())
        assertEquals(setOf("3", "4"), run(WowAddonCatalog.Query(client = c("_anniversary_"))).toSet())
    }

    @Test fun categoryIncludesChildren() {
        assertEquals(setOf("2", "3"), run(WowAddonCatalog.Query(category = "20", client = c("_classic_"))).toSet())
        assertEquals(setOf("3"), run(WowAddonCatalog.Query(category = "21", client = c("_classic_"))).toSet())
        assertEquals(setOf("1"), run(WowAddonCatalog.Query(category = "19", client = c("_retail_"))).toSet())
    }

    @Test fun textMatchesNameOrAuthorIgnoringCase() {
        assertEquals(setOf("1", "3"), run(WowAddonCatalog.Query(text = "BART", client = c("_retail_"))).toSet())
        assertEquals(listOf("2"), run(WowAddonCatalog.Query(text = "kappa", client = c("_classic_era_"))))
    }

    @Test fun sortOrders() {
        val f = "_anniversary_"
        assertEquals(listOf("3", "4"), run(WowAddonCatalog.Query(client = c(f), sort = WowAddonCatalog.Sort.POPULAR)))
        assertEquals(listOf("4", "3"), run(WowAddonCatalog.Query(client = c(f), sort = WowAddonCatalog.Sort.DOWNLOADS)))
        assertEquals(listOf("4", "3"), run(WowAddonCatalog.Query(client = c(f), sort = WowAddonCatalog.Sort.UPDATED)))
        assertEquals(listOf("3", "4"), run(WowAddonCatalog.Query(client = c(f), sort = WowAddonCatalog.Sort.NAME)))
        assertEquals(listOf("2", "1", "4", "3"), WowAddonCatalog.filterWowi(
            WowAddonCatalog.parseWowiFiles(files).map { WowAddonCatalog.Entry(it.addon, it.category, null) }, emptyList(),
            WowAddonCatalog.Query(client = c(f), sort = WowAddonCatalog.Sort.DOWNLOADS),
        ).map { it.id })
    }

    @Test fun pagingReportsMore() {
        val all = WowAddonCatalog.parseWowiFiles(files).map { it.addon }
        val p0 = WowAddonCatalog.pageOf(all, 0, 3)
        assertEquals(3, p0.items.size)
        assertTrue(p0.more)
        val p1 = WowAddonCatalog.pageOf(all, 1, 3)
        assertEquals(1, p1.items.size)
        assertFalse(p1.more)
        assertTrue(WowAddonCatalog.pageOf(all, 5, 3).items.isEmpty())
    }

    private val cfTypes = """{"data":[
      {"id":517,"name":"WoW Retail","slug":"wow_retail"},
      {"id":67408,"name":"WoW Classic","slug":"wow_classic"},
      {"id":73246,"name":"WoW Burning Crusade Classic","slug":"wow_burning_crusade_classic"},
      {"id":73713,"name":"WoW Wrath of the Lich King Classic","slug":"wow-wrath-of-the-lich-king-classic"},
      {"id":77522,"name":"WoW Cataclysm Classic","slug":"wow-cataclysm-classic"},
      {"id":79434,"name":"WoW Mists of Pandaria Classic","slug":"wow-mists-of-pandaria-classic"}
    ]}"""

    private val cfTypesWithForever = cfTypes.replace("]}", """,{"id":81000,"name":"WoW Forever","slug":"wow-forever"}]}""")

    @Test fun curseVersionTypesMapToFlavors() {
        assertEquals(517, WowAddonCatalog.curseVersionType(cfTypes, c("_retail_")))
        assertEquals(67408, WowAddonCatalog.curseVersionType(cfTypes, c("_classic_era_")))
        assertEquals(73246, WowAddonCatalog.curseVersionType(cfTypes, c("_anniversary_")))
        assertEquals(79434, WowAddonCatalog.curseVersionType(cfTypes, c("_classic_")))
        assertEquals(517, WowAddonCatalog.curseVersionType(cfTypes, c("_retail_beta_")))
        assertNull(WowAddonCatalog.curseVersionType("""{"data":[]}""", c("_retail_")))
        assertNull(WowAddonCatalog.curseVersionType(cfTypes, c("_unknown_")))
    }

    @Test fun foreverUsesItsOwnCurseTypeAndFallsBackToTheClientFamily() {
        assertEquals(81000, WowAddonCatalog.curseVersionType(cfTypesWithForever, c("_forever_")))
        assertEquals(81000, WowAddonCatalog.curseVersionType(cfTypesWithForever, c("_forever_beta_")))
        assertEquals(517, WowAddonCatalog.curseVersionType(cfTypes, c("_forever_")))
        assertEquals(67408, WowAddonCatalog.curseVersionType(cfTypes, c("_forever_", "1.15.8.60000")))
        assertEquals(517, WowAddonCatalog.curseVersionType(cfTypesWithForever, c("_retail_")))
    }

    @Test fun clientVersionDecidesTheFamilyBeforeTheFolderName() {
        assertEquals("_retail_", c("_forever_").family)
        assertEquals("_classic_era_", c("_forever_", "1.15.8.61000").family)
        assertEquals("_classic_era_", c("_anniversary_", "1.15.7.60000").family)
        assertEquals("_classic_", c("_classic_beta_").family)
        assertEquals("_retail_", c("_retail_ptr_").family)
        assertNull(c("_mystery_").family)
        assertEquals("_anniversary_", c("_mystery_", "2.5.5.1").family)
    }

    @Test fun foreverShowsMainlineAddonsAndHidesClassicOnlyOnes() {
        assertEquals(setOf("1", "3"), run(WowAddonCatalog.Query(client = c("_forever_"))).toSet())
        assertEquals(setOf("2", "3"), run(WowAddonCatalog.Query(client = c("_forever_", "1.15.8.1"))).toSet())
    }

    @Test fun unknownFamilyShowsEverything() {
        assertEquals(setOf("1", "2", "3", "4"), run(WowAddonCatalog.Query(client = c("_mystery_"))).toSet())
    }

    private val buildInfo = """Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|KeyRing!HEX:16|Product!STRING:0
us|1|aa|bb|cc||tpr/wow|h|s|t||1|12.0.7.64000|kk|wow
us|1|aa|bb|cc||tpr/wow|h|s|t||1|1.15.8.63000|kk|wow_classic_era
us|1|aa|bb|cc||tpr/wow|h|s|t||1|12.1.0.65000|kk|wowf
"""

    @Test fun buildInfoAndFlavorInfoGiveTheInstalledVersion() {
        assertEquals("12.1.0.65000", WowAddonCatalog.buildInfoVersion(buildInfo, "wowf"))
        assertEquals("1.15.8.63000", WowAddonCatalog.buildInfoVersion(buildInfo.replace("\n", "\r\n"), "WOW_CLASSIC_ERA"))
        assertNull(WowAddonCatalog.buildInfoVersion(buildInfo, "wow_beta"))
        assertNull(WowAddonCatalog.buildInfoVersion("garbage", "wow"))
        assertNull(WowAddonCatalog.buildInfoVersion("", "wow"))
        assertEquals("wowf", WowAddonCatalog.flavorProduct("Product Flavor!STRING:0\nwowf\n"))
        assertNull(WowAddonCatalog.flavorProduct("Product Flavor!STRING:0\n"))
    }

    @Test fun clientReadsTheInstallOnDisk() {
        val root = File(context.cacheDir, "wowaddon-client-test")
        val flavor = File(root, "_forever_")
        File(flavor, "Interface/AddOns").mkdirs()
        File(root, ".build.info").writeText(buildInfo.replace("12.1.0.65000", "1.15.9.1"))
        File(flavor, ".flavor.info").writeText("Product Flavor!STRING:0\nwowf\n")
        val target = WowAddons.Target("_forever_", File(flavor, "Interface/AddOns"))
        assertEquals("1.15.9.1", WowAddonCatalog.clientVersion(flavor))
        assertEquals("_classic_era_", WowAddonCatalog.client(target).family)
        File(flavor, ".flavor.info").delete()
        assertNull(WowAddonCatalog.clientVersion(flavor))
        assertEquals("_retail_", WowAddonCatalog.client(target).family)
        File(root, ".build.info").delete()
        File(flavor, "Interface/AddOns").delete()
        File(flavor, "Interface").delete()
        flavor.delete()
        root.delete()
    }

    @Test fun curseSearchDropsModsThatDisallowDistribution() {
        val json = """{"data":[
          {"id":10,"name":"Good","summary":"s","downloadCount":42,"dateModified":"2026-01-01T00:00:00Z","allowModDistribution":true,"logo":{"thumbnailUrl":"https://t/x.png"},"authors":[{"name":"Au"}],"latestFilesIndexes":[{"gameVersionTypeId":517,"fileId":99,"filename":"g.zip","releaseType":1}]},
          {"id":11,"name":"Blocked","summary":"s","downloadCount":1,"allowModDistribution":false,"authors":[],"latestFilesIndexes":[{"gameVersionTypeId":517,"fileId":100}]},
          {"id":12,"name":"WrongType","summary":"s","downloadCount":1,"allowModDistribution":true,"authors":[],"latestFilesIndexes":[{"gameVersionTypeId":67408,"fileId":101}]}
        ],"pagination":{"index":0,"pageSize":3,"resultCount":3,"totalCount":50}}"""
        val p = WowAddonCatalog.parseCurseSearch(json, 517)
        assertEquals(listOf("10"), p.items.map { it.id })
        val a = p.items[0]
        assertEquals("99", a.fileId)
        assertEquals("Au", a.author)
        assertEquals(42L, a.downloads)
        assertEquals("https://t/x.png", a.thumb)
        assertEquals(WowAddonCatalog.Source.CURSEFORGE, a.source)
        assertTrue(a.updated > 0)
        assertTrue(p.more)
    }

    @Test fun curseDownloadUrlAndHashParsing() {
        assertEquals("https://edge/x.zip", WowAddonCatalog.parseCurseDownloadUrl("""{"data":"https://edge/x.zip"}"""))
        assertNull(WowAddonCatalog.parseCurseDownloadUrl("""{"data":null}"""))
        val file = WowAddonCatalog.parseCurseFile("""{"data":{"id":5,"downloadUrl":"https://edge/f.zip","hashes":[{"value":"zzz","algo":1},{"value":"abc","algo":2}]}}""")
        assertEquals("https://edge/f.zip", file.first)
        assertEquals("abc", file.second)
        val hidden = WowAddonCatalog.parseCurseFile("""{"data":{"id":5,"downloadUrl":null,"hashes":[]}}""")
        assertNull(hidden.first)
        assertNull(hidden.second)
        assertNull(WowAddonCatalog.parseCurseFile("""{"data":null}""").first)
    }

    @Test fun pickCurseFileHonoursTypeAndDistribution() {
        val mod = """{"data":{"id":7,"name":"M","allowModDistribution":true,"latestFilesIndexes":[{"gameVersionTypeId":67408,"fileId":1},{"gameVersionTypeId":517,"fileId":2}]}}"""
        assertEquals("2", WowAddonCatalog.pickCurseFile(mod, 517))
        assertEquals("1", WowAddonCatalog.pickCurseFile(mod, null))
        assertNull(WowAddonCatalog.pickCurseFile(mod, 73246))
        assertNull(WowAddonCatalog.pickCurseFile(mod.replace("\"allowModDistribution\":true", "\"allowModDistribution\":false"), 517))
        assertNull(WowAddonCatalog.pickCurseFile("""{"data":null}""", 517))
    }

    @Test fun curseSearchUrlCarriesTypeSortAndPaging() {
        val url = WowAddonCatalog.curseSearchUrl(WowAddonCatalog.Query("deadly boss", "1004", c("_retail_"), WowAddonCatalog.Sort.NAME), 517, 2, 40)
        assertTrue(url.startsWith("https://api.curseforge.com/v1/mods/search?gameId=1&searchFilter=deadly+boss"))
        assertTrue(url.contains("&categoryId=1004"))
        assertTrue(url.contains("&gameVersionTypeId=517"))
        assertTrue(url.contains("&sortField=4&sortOrder=asc&index=80&pageSize=40"))
        val noType = WowAddonCatalog.curseSearchUrl(WowAddonCatalog.Query(client = c("_mystery_")), null, 0, 500)
        assertFalse(noType.contains("gameVersionTypeId"))
        assertTrue(noType.contains("sortField=2&sortOrder=desc&index=0&pageSize=50"))
    }

    @Test fun curseSearchStopsAtTheApiWindow() {
        val json = """{"data":[],"pagination":{"index":9950,"pageSize":50,"resultCount":50,"totalCount":20000}}"""
        assertFalse(WowAddonCatalog.parseCurseSearch(json, null).more)
    }

    @Test fun wowiDetailsGiveUrlAndMd5() {
        val d = WowAddonCatalog.parseWowiDetails("""[{"UID":"1","UIMD5":"abc","UIDownload":"https://cdn.wowinterface.com/downloads/getfile.php?id=1"}]""")
        assertEquals("https://cdn.wowinterface.com/downloads/getfile.php?id=1", d?.first)
        assertEquals("abc", d?.second)
        assertNull(WowAddonCatalog.parseWowiDetails("""[{"UID":"1","UIDownload":""}]"""))
        assertNull(WowAddonCatalog.parseWowiDetails("[]"))
    }

    @Test fun nonHttpsDownloadsAreRefused() {
        assertFalse(WowAddonCatalog.isHttps("http://example.com/a.zip"))
        assertFalse(WowAddonCatalog.isHttps("file:///etc/passwd"))
        assertTrue(WowAddonCatalog.isHttps("https://example.com/a.zip"))
    }

    @Test fun md5Check() {
        val f = File(context.cacheDir, "wowaddon-md5-test.zip")
        f.writeText("hello")
        assertFalse(WowAddonCatalog.md5Matches(f, "00000000000000000000000000000000"))
        assertTrue(WowAddonCatalog.md5Matches(f, "5D41402ABC4B2A76B9719D911017C592"))
        assertTrue(WowAddonCatalog.md5Matches(f, null))
        f.delete()
    }

    @Test fun userKeyIsTrimmedAndBlankMeansNone() {
        WowAddonCatalog.setUserKey(context, "  k123  ")
        assertEquals("k123", WowAddonCatalog.apiKey(context))
        assertTrue(WowAddonCatalog.available(context))
        WowAddonCatalog.setUserKey(context, "   ")
        assertEquals(com.droiddeck.launcher.BuildConfig.CURSEFORGE_API_KEY.trim(), WowAddonCatalog.apiKey(context))
    }

    @Test fun staleCacheIsUsedWhenOfflineAndFreshCacheSkipsTheNetwork() {
        val f = File(context.cacheDir, "wowaddon-cache-test/x.json")
        assertEquals("a", WowAddonCatalog.cached(f, 1_000L) { "a" })
        assertEquals("a", WowAddonCatalog.cached(f, 2_000L) { "b" })
        val later = 1_000L + WowAddonCatalog.FRESH_MS + 1
        assertEquals("b", WowAddonCatalog.cached(f, later) { "b" })
        assertEquals("b", WowAddonCatalog.cached(f, later + WowAddonCatalog.FRESH_MS * 2) { null })
        f.delete()
    }
}
