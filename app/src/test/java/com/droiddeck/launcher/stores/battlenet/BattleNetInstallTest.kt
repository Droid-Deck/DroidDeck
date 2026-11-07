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

    private val osiVersions = """
        Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16
        ## seqn = 3964805
        us|11111111111111111111111111111111|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa||93847|3.3.93847|bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
        eu|11111111111111111111111111111111|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa||93847|3.3.93847|bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
        kr|11111111111111111111111111111111|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa||93847|3.3.93847|bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
    """.trimIndent()

    private val osicVersions = """
        Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16
        ## seqn = 3964866
        cn|44444444444444444444444444444444|cccccccccccccccccccccccccccccccc||93854|3.3.93854|dddddddddddddddddddddddddddddddd
    """.trimIndent()

    private val s2Versions = """
        Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16
        ## seqn = 4064835
        us|55555555555555555555555555555555|eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee||98310|5.0.17.98310|ffffffffffffffffffffffffffffffff
        cn|66666666666666666666666666666666|eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee||93333|5.0.14.93333|ffffffffffffffffffffffffffffffff
    """.trimIndent()

    private val s2cVersions = """
        Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16
        ## seqn = 4064962
        cn|77777777777777777777777777777777|eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee||98370|5.0.17.98370|ffffffffffffffffffffffffffffffff
    """.trimIndent()

    private val anbsVersions = """
        Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16
        ## seqn = 4105154
        us|88888888888888888888888888888888|99999999999999999999999999999999||3935504|5.1.3.3935504|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab
        eu|88888888888888888888888888888888|99999999999999999999999999999999||3935504|5.1.3.3935504|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab
        kr|88888888888888888888888888888888|99999999999999999999999999999999||3935504|5.1.3.3935504|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab
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

    @Test fun theChinaProductsAreNotOnTheGlobalVersionLine() {
        assertNull(BattleNetInstall.latestBuild(osiVersions, "cn"))
        assertEquals("44444444444444444444444444444444", BattleNetInstall.latestBuild(osicVersions, "cn"))
        assertEquals("66666666666666666666666666666666", BattleNetInstall.latestBuild(s2Versions, "cn"))
        assertEquals("77777777777777777777777777777777", BattleNetInstall.latestBuild(s2cVersions, "cn"))
        assertNull(BattleNetInstall.latestBuild(anbsVersions, "cn"))
    }

    @Test fun theVersionServiceIsBlizzardsChineseOneForChina() {
        assertEquals("https://us.version.battle.net/v2/products/wow/versions", BattleNetInstall.versionsUrl("us", "wow"))
        assertEquals("https://cn.version.battlenet.com.cn/v2/products/d3cn/versions", BattleNetInstall.versionsUrl("cn", "d3cn"))
        assertEquals("https://cn.version.battlenet.com.cn/v2/products/s2c/versions", BattleNetInstall.versionsUrl("cn", "s2c"))
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
        assertEquals("d3cn", BattleNetInstall.product("d3cn"))
        assertEquals("osic", BattleNetInstall.product("osic"))
        assertEquals("s2c", BattleNetInstall.product("s2_cn"))
        assertEquals("anbs", BattleNetInstall.product("anbs_cn"))
        assertEquals("osic", BattleNetInstall.chinaProduct("osi"))
        assertEquals("s2c", BattleNetInstall.chinaProduct("s2"))
        assertEquals("d3cn", BattleNetInstall.chinaProduct("diablo3"))
        assertNull(BattleNetInstall.chinaProduct("anbs"))
        assertNull(BattleNetInstall.chinaProduct("wow"))
        assertTrue(BattleNetInstall.headless(CatalogItem(Store.BATTLENET, "d3cn", "Diablo III", null)))
        assertFalse(BattleNetInstall.headless(CatalogItem(Store.EPIC, "gryphon", "Warcraft Rumble", null)))
    }

    @Test fun aCheckThatCouldNotRunIsTriedAgainNextTime() {
        val folder = Files.createTempDirectory("bnet").toFile()
        try {
            BattleNetInstall.check("gryphon", folder, false)
            BattleNetInstall.checks.submit {}.get()
            assertFalse(synchronized(BattleNetInstall.checkedAt) {
                BattleNetInstall.updateKey("gryphon", folder) in BattleNetInstall.checkedAt
            })
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun updateStateIsSeparateForEachInstallFolder() {
        val one = Files.createTempDirectory("bnet-one").toFile()
        val two = Files.createTempDirectory("bnet-two").toFile()
        try {
            val first = BattleNetInstall.updateKey("diablo3", one)
            val second = BattleNetInstall.updateKey("diablo3", two)
            assertFalse(first == second)
            BattleNetInstall.updates[first] = true
            assertNull(BattleNetInstall.updates[second])
            BattleNetInstall.updates.remove(first)
        } finally {
            one.deleteRecursively()
            two.deleteRecursively()
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
