package com.droiddeck.launcher.stores.battlenet

import com.droiddeck.launcher.runtime.BattleNet
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Locale

class BattleNetInstallTest {
    private val buildInfo = """
        Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|Version!STRING:0|Product!STRING:0
        eu|0|11111111111111111111111111111111|db0583272ed15286569b5f24ce516876|||16.1.1.1|gryphon
        us|1|e474008a3535d8ea978949a90d9873e6|db0583272ed15286569b5f24ce516876|||16.1.2.119725|gryphon
    """.trimIndent()

    private val versions = """
        Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16
        ## seqn = 4048130
        us|f00d008a3535d8ea978949a90d9873e6|db0583272ed15286569b5f24ce516876||119800|16.1.3.119800|f6a454799d25791e5a37a7547d75ce49
        eu|e474008a3535d8ea978949a90d9873e6|db0583272ed15286569b5f24ce516876||119725|16.1.2.119725|f6a454799d25791e5a37a7547d75ce49
    """.trimIndent()

    @Test fun theActiveRowGivesTheRegionAndBuildOnDisk() {
        assertEquals("us" to "e474008a3535d8ea978949a90d9873e6", BattleNetInstall.buildKey(buildInfo))
        assertNull(BattleNetInstall.buildKey("Branch!STRING:0|Active!DEC:1\n"))
        assertNull(BattleNetInstall.buildKey("Branch!STRING:0|Active!DEC:1|Build Key!HEX:16\nus|1|not-a-key\n"))
    }

    @Test fun theLiveBuildIsReadForTheSameRegion() {
        assertEquals("f00d008a3535d8ea978949a90d9873e6", BattleNetInstall.latestBuild(versions, "us"))
        assertEquals("e474008a3535d8ea978949a90d9873e6", BattleNetInstall.latestBuild(versions, "eu"))
        assertNull(BattleNetInstall.latestBuild(versions, "kr"))
        assertNull(BattleNetInstall.latestBuild("", "us"))
    }

    @Test fun theDeviceLanguagePicksABlizzardLocale() {
        assertEquals("enUS", BattleNetInstall.locale(Locale.US))
        assertEquals("enGB", BattleNetInstall.locale(Locale.UK))
        assertEquals("enUS", BattleNetInstall.locale(Locale("en", "AU")))
        assertEquals("deDE", BattleNetInstall.locale(Locale.GERMANY))
        assertEquals("ptBR", BattleNetInstall.locale(Locale("pt", "BR")))
        assertEquals("ptPT", BattleNetInstall.locale(Locale("pt", "PT")))
        assertEquals("zhTW", BattleNetInstall.locale(Locale.forLanguageTag("zh-Hant-HK")))
        assertEquals("zhCN", BattleNetInstall.locale(Locale.SIMPLIFIED_CHINESE))
        assertEquals("enUS", BattleNetInstall.locale(Locale("nl", "NL")))
    }

    @Test fun theCountryIsSentInBothForms() {
        assertEquals("DEU" to "DE", BattleNetInstall.country(Locale.GERMANY))
        assertEquals("USA" to "US", BattleNetInstall.country(Locale("en")))
    }

    @Test fun everyStoreTitleWithAProductInstallsHeadless() {
        val products = BattleNet.TITLES.mapNotNull { it.product }
        assertTrue(products.all { Regex("[a-z0-9_]{1,32}").matches(it) })
        assertTrue(BattleNetInstall.headless(CatalogItem(Store.BATTLENET, "gryphon", "Warcraft Rumble", null)))
        assertEquals("hsb", BattleNetInstall.product("hs_beta"))
        assertEquals("d3", BattleNetInstall.product("diablo3_enus"))
        assertFalse(BattleNetInstall.headless(CatalogItem(Store.BATTLENET, "d3cn", "Diablo III", null)))
        assertFalse(BattleNetInstall.headless(CatalogItem(Store.EPIC, "gryphon", "Warcraft Rumble", null)))
    }

    @Test fun aCheckThatCouldNotRunIsTriedAgainNextTime() {
        val folder = Files.createTempDirectory("bnet").toFile()
        try {
            BattleNetInstall.check("gryphon", folder, false)
            BattleNetInstall.checks.submit {}.get()
            assertFalse(synchronized(BattleNetInstall.checkedAt) { "gryphon" in BattleNetInstall.checkedAt })
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun theAgentStatusPicksTheDownloadStep() {
        assertEquals(BattleNetInstall.Step.PREPARING, BattleNetInstall.step(1008, 0, 0, 0))
        assertEquals(BattleNetInstall.Step.PREPARING, BattleNetInstall.step(1003, 500, 0, 0))
        assertEquals(BattleNetInstall.Step.DOWNLOADING, BattleNetInstall.step(1003, 500, 31405777, 6746574227))
        assertEquals(BattleNetInstall.Step.APPLYING, BattleNetInstall.step(1003, 500, 6746574227, 6746574227))
        assertEquals(BattleNetInstall.Step.APPLYING, BattleNetInstall.step(1003, 2000, 1, 9))
        assertEquals(BattleNetInstall.Step.APPLYING, BattleNetInstall.step(1004, 1000, 0, 0))
    }
}
