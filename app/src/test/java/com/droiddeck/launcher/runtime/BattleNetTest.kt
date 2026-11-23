package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.GameEnvironmentStore
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.battlenet.BattleNetCleanup
import com.droiddeck.launcher.stores.battlenet.BattleNetInstall
import com.droiddeck.launcher.stores.battlenet.BattleNetInstaller
import com.droiddeck.launcher.stores.battlenet.Ownership
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

private val REGISTRY = """
WINE REGISTRY Version 2
;; All keys relative to \\Machine

[Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Battle.net] 1760380000
#time=1dc3c6e0a5e1f2a
"DisplayName"="Battle.net"
"InstallLocation"="C:\\Program Files (x86)\\Battle.net"
"UninstallString"="\"C:\\Program Files (x86)\\Battle.net\\Battle.net Uninstaller.exe\"  --lang=enUS"

[Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Diablo IV] 1760380001
"DisplayIcon"="C:\\Program Files (x86)\\Diablo IV\\Diablo IV Launcher.exe"
"DisplayName"="Diablo\xae IV"
"InstallLocation"="C:\\Program Files (x86)\\Diablo IV"
"Publisher"="Blizzard Entertainment"
"UninstallString"="\"C:\\ProgramData\\Battle.net\\Agent\\Blizzard Uninstaller.exe\" --lang=enUS --uid=fenris --displayname=\"Diablo IV\""

[Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Overwatch] 1760380002
"InstallLocation"="C:\\Program Files (x86)\\Overwatch"
"UninstallString"="\"C:\\ProgramData\\Battle.net\\Agent\\Blizzard Uninstaller.exe\" --lang=enUS --uid=prometheus --displayname=\"Overwatch\""

[Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Steam] 1760380003
"InstallLocation"="C:\\Program Files (x86)\\Steam"
"UninstallString"="C:\\Program Files (x86)\\Steam\\uninstall.exe --uid=steam"

[Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Diablo II Resurrected] 1760380004
"UninstallString"=str(2):"\"C:\\ProgramData\\Battle.net\\Agent\\Blizzard Uninstaller.exe\" --lang=enUS --uid=osi --displayname=\"Diablo II: Resurrected\""
"InstallLocation"="C:/Games/Diablo II Resurrected"

[Software\\Wine\\Fonts] 1760380005
"InstallLocation"="C:\\x"
"UninstallString"="Battle.net --uid=wow"
""".trimStart()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BattleNetTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "components").deleteRecursively()
        File(context.filesDir, "battlenet-cleanup").deleteRecursively()
        LinuxRuntime.rootDir(context).deleteRecursively()
    }

    @Test fun theIdIsTheShortcutIdTheScriptNames() {
        val crc = CRC32().apply { update("\"droiddeck-battlenet\"Battle.net".toByteArray()) }.value
        assertEquals(crc or 0x80000000L, BattleNet.APP_ID)
    }

    @Test fun installedIsTheLauncherInThePrefix() {
        assertFalse(BattleNet.installed(context))
        launcher().apply { parentFile!!.mkdirs(); writeText("MZ") }
        assertTrue(BattleNet.installed(context))
    }

    @Test fun theEnvironmentEditorNamesBattleNetsProfileOnceItIsInstalled() {
        assertNull(BattleNet.profile(context))
        launcher().apply { parentFile!!.mkdirs(); writeText("MZ") }
        assertEquals(BattleNet.APP_ID.toString() to "Battle.net", BattleNet.profile(context))
    }

    @Test fun removeTakesThePrefixItsMenuEntryAndCacheButNothingItLinksTo() {
        store("FEXCore-2610-linux.wcp")
        ComponentsManager.setGameComponent(context, BattleNet.APP_ID.toString(), "fex", "FEXCore-2610-linux.wcp")
        assertEquals(mapOf("fex" to "FEXCore-2610-linux.wcp"), ComponentsManager.gameComponents(context, BattleNet.APP_ID.toString()))
        val root = LinuxRuntime.rootDir(context)
        fun put(relative: String) = File(root, relative).apply { parentFile!!.mkdirs(); writeText("x") }
        launcher().apply { parentFile!!.mkdirs(); writeText("MZ") }
        val outside = File(context.cacheDir, "bnet-outside").apply { mkdirs() }
        val kept = File(outside, "kept").apply { writeText("x") }
        val drives = File(BattleNet.prefix(context), "pfx/dosdevices").apply { mkdirs() }
        Files.createSymbolicLink(File(drives, "z:").toPath(), outside.toPath())
        val menu = put("usr/local/share/applications/droiddeck-battlenet.desktop")
        val otherMenu = put("usr/local/share/applications/other.desktop")
        val log = put("root/.cache/droiddeck/battlenet/battlenet.log")
        val otherPrefix = put("root/.local/share/Steam/steamapps/compatdata/123/pfx/system.reg")

        BattleNet.remove(context)

        assertFalse(BattleNet.prefix(context).exists())
        assertFalse(BattleNet.installed(context))
        assertTrue(kept.isFile)
        assertFalse(menu.exists())
        assertTrue(otherMenu.isFile)
        assertFalse(log.parentFile!!.exists())
        assertTrue(otherPrefix.isFile)
        assertEquals(emptyMap<String, String>(), ComponentsManager.gameComponents(context, BattleNet.APP_ID.toString()))
        outside.deleteRecursively()
    }

    @Test fun anIdNamesItsTitleWithOrWithoutItsLocale() {
        assertEquals("Pro", BattleNet.title("prometheus")?.code)
        assertEquals("D3", BattleNet.title("diablo3_enus")?.code)
        assertEquals("Fen", BattleNet.title("Fenris")?.code)
        assertEquals("WTCG", BattleNet.title("hs_beta")?.code)
        assertEquals("Diablo II: Resurrected", BattleNet.title("osi_dede")?.name)
        assertEquals("WoWC", BattleNet.title("wow_classic")?.code)
        assertEquals("WoWC", BattleNet.title("wow_classic_era_enus")?.code)
        assertNull(BattleNet.title("agent"))
        assertNull(BattleNet.title("osi_beta"))
    }

    @Test fun theUninstallEntriesOfBattleNetsGamesAreRead() {
        val entries = BattleNet.uninstallEntries(REGISTRY.lineSequence())
        assertEquals(listOf(
            "fenris" to "C:\\Program Files (x86)\\Diablo IV",
            "prometheus" to "C:\\Program Files (x86)\\Overwatch",
            "osi" to "C:/Games/Diablo II Resurrected",
        ), entries)
    }

    @Test fun productDbsInstallsAreRead() {
        val db = message(
            field(1, message(field(1, "agent"), field(2, "agent"), field(3, message(field(1, "C:/ProgramData/Battle.net/Agent"))))),
            varintField(7, 300),
            field(1, message(field(1, "hs_beta"), field(2, "wtcg"), varintField(9, 1), field(3, message(field(1, "C:/Program Files (x86)/Hearthstone"), field(2, "US"))))),
            field(1, message(field(1, "s2"))),
            field(1, message(field(1, "s1"), field(3, message(field(1, "Z:/mnt/droiddeck-sd/Games/Battle.net/StarCraft"))), field(4, message(field(1, message(varintField(1, 0), varintField(2, 0))))))),
            field(1, message(field(1, "wow_classic_anniversary"), field(3, message(field(1, "Z:/root/Games/World of Warcraft"))), field(4, message(field(1, message(varintField(1, 1), varintField(2, 1))))))),
        )
        assertEquals(listOf("agent" to "C:/ProgramData/Battle.net/Agent", "hs_beta" to "C:/Program Files (x86)/Hearthstone", "wow_classic_anniversary" to "Z:/root/Games/World of Warcraft"),
            BattleNet.productDbEntries(db))
        assertNull(BattleNet.productDbEntries(db.copyOf(db.size - 3)))
        assertNull(BattleNet.productDbEntries(byteArrayOf(0x0a, 0x7f, 0x01)))
        assertNull(BattleNet.productDbEntries(ByteArray(0)))
        assertNull(BattleNet.productDbEntries(byteArrayOf(0x0a, 0x03, 0x0a, 0x7f, 0x01)))
        assertEquals(emptyList<Pair<String, String>>(), BattleNet.productDbEntries(message(field(1, message(field(1, "s1"), field(3, message(field(1, "Z:/Games/StarCraft"))), field(4, message(field(1, message(varintField(1, 0))))))))))
    }

    @Test fun theAgentDoesNotStartWithoutTheGameStorageCard() {
        val card = Files.createTempDirectory("card").toFile()
        com.droiddeck.launcher.session.SessionPrefs.setGameStorage(context, card.path, "SD Card")
        assertTrue(com.droiddeck.launcher.stores.battlenet.BattleNetInstall.binds(context).any { it.endsWith(":/mnt/droiddeck-sd") })
        card.deleteRecursively()
        assertThrows(IllegalStateException::class.java) { com.droiddeck.launcher.stores.battlenet.BattleNetInstall.binds(context) }
    }

    @Test fun aGameTheAgentNoLongerTracksIsNotInstalled() {
        val registry = listOf("wow" to "Z:\\mnt\\droiddeck-sd\\Games\\Battle.net\\World of Warcraft", "hs_beta" to "C:\\Hearthstone")
        assertEquals(listOf("hs_beta" to "C:\\Hearthstone", "HS_BETA" to "C:/Hearthstone"), BattleNet.installs(registry, listOf("HS_BETA" to "C:/Hearthstone")))
        assertEquals(registry, BattleNet.installs(registry, null))
        assertEquals(emptyList<Pair<String, String>>(), BattleNet.installs(registry, emptyList()))
    }

    @Test fun theGamesAreTheTitlesInstalledInTheirFolders() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        File(pfx, "system.reg").writeText(REGISTRY)
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").writeBytes(message(
            field(1, message(field(1, "hs_beta"), field(3, message(field(1, "C:/Program Files (x86)/Hearthstone"))))),
            field(1, message(field(1, "fenris"), field(3, message(field(1, "C:/Program Files (x86)/Diablo IV"))))),
            field(1, message(field(1, "wow_classic"), field(3, message(field(1, "C:/Program Files (x86)/World of Warcraft"))))),
        ))
        for (folder in listOf("Program Files (x86)/Diablo IV", "Games/Diablo II Resurrected", "Program Files (x86)/Hearthstone", "Program Files (x86)/World of Warcraft")) {
            File(pfx, "drive_c/$folder").mkdirs()
        }
        val games = BattleNet.games(context)
        assertEquals(listOf("Fen", "WTCG", "WoWC"), games.map { it.code })
        assertEquals(listOf("Diablo IV", "Hearthstone", "World of Warcraft Classic"), games.map { it.name })
        assertEquals(File(pfx, "drive_c/Program Files (x86)/Hearthstone"), games[1].folder)
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").delete()
        assertEquals(listOf("Fen", "OSI"), BattleNet.games(context).map { it.code })
        assertNull(BattleNet.hostFolder(context, "D:\\Games\\X"))
        assertNull(BattleNet.hostFolder(context, "C:\\..\\..\\etc"))
        assertNull(BattleNet.hostFolder(context, "Z:\\root\\..\\..\\etc"))
        assertEquals(File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/Hearthstone"),
            BattleNet.hostFolder(context, "Z:/root/Games/Stores/Battle.net/Hearthstone/"))
    }

    @Test fun aMappedWineDriveResolvesThroughItsLink() {
        val devices = File(BattleNet.prefix(context), "pfx/dosdevices").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(devices, "d:").toPath(), java.nio.file.Paths.get("/root/Games/Card"))
        java.nio.file.Files.createSymbolicLink(File(devices, "e:").toPath(), java.nio.file.Paths.get("../drive_c"))
        assertEquals(File(LinuxRuntime.rootDir(context), "root/Games/Card/Battle.net/Hearthstone"),
            BattleNet.hostFolder(context, "D:\\Battle.net\\Hearthstone"))
        assertEquals(File(BattleNet.prefix(context), "pfx/drive_c/Games/X"), BattleNet.hostFolder(context, "E:\\Games\\X"))
        assertNull(BattleNet.hostFolder(context, "F:\\Games\\X"))
        assertNull(BattleNet.hostFolder(context, "D:\\..\\etc"))
    }

    @Test fun aDriveLinkTheNativeUserlandMadeRelativeStillResolvesInTheGuest() {
        val root = LinuxRuntime.rootDir(context)
        val devices = File(BattleNet.prefix(context), "pfx/dosdevices").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(devices, "d:").toPath(), java.nio.file.Paths.get("/root/Games/Card"))
        java.nio.file.Files.createSymbolicLink(File(devices, "g:").toPath(), java.nio.file.Paths.get("../../../../../../../../../../.."))
        NativeGuest.relinkAbsoluteSymlinks(context, root)
        assertFalse(java.nio.file.Files.readSymbolicLink(File(devices, "d:").toPath()).isAbsolute)
        assertEquals(File(root, "root/Games/Card/Battle.net/Hearthstone"),
            BattleNet.hostFolder(context, "D:\\Battle.net\\Hearthstone"))
        assertNull(BattleNet.hostFolder(context, "G:\\etc\\passwd"))
    }


    @Test fun wowForeverIsListedBesideRetailInTheSharedWorldOfWarcraftFolder() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        val wow = File(pfx, "drive_c/Program Files (x86)/World of Warcraft").apply { mkdirs() }
        File(wow, "_retail_").mkdirs()
        File(wow, "_forever_").mkdirs()
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").writeBytes(message(
            field(1, message(field(1, "wow"), field(2, "wow"), field(3, message(field(1, "C:/Program Files (x86)/World of Warcraft"))))),
            field(1, message(field(1, "wow_forever"), field(2, "wowf"), field(3, message(field(1, "C:/Program Files (x86)/World of Warcraft"))))),
        ))
        val games = BattleNet.games(context)
        assertEquals(listOf("WoW", "WoWF"), games.map { it.code })
        assertEquals(listOf(wow, File(wow, "_forever_")), games.map { it.folder })
        assertEquals("World of Warcraft: Forever", games[1].name)
        assertEquals(2, games.map { it.folder.canonicalPath }.distinct().size)
        val shortcut = BattleNet.shortcut(context, games[1])!!
        assertTrue(shortcut.launchOptions.endsWith("--exec=\"launch WoWF\""))
    }

    @Test fun anInstallWithAnUnknownUidIsListedByItsProductCode() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        File(pfx, "drive_c/Games/World of Warcraft").mkdirs()
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").writeBytes(message(
            field(1, message(field(1, "wowf_live"), field(2, "wowf"), field(3, message(field(1, "C:/Games/World of Warcraft"))))),
            field(1, message(field(1, "unknown_thing"), field(2, "nothing"), field(3, message(field(1, "C:/Games/World of Warcraft"))))),
        ))
        val games = BattleNet.games(context)
        assertEquals(listOf("WoWF"), games.map { it.code })
        assertEquals("wowf_live", games[0].installUid)
        assertEquals("wow_forever", games[0].uid)
        assertEquals("WoWF", BattleNet.titleForProduct("WOWF")?.code)
        assertEquals("WTCG", BattleNet.titleForProduct("wtcg")?.code)
        assertNull(BattleNet.titleForProduct(""))
        assertEquals(listOf(Triple("wowf_live", "C:/Games/World of Warcraft", "wowf")),
            BattleNet.productDbInstalls(message(field(1, message(field(1, "wowf_live"), field(2, "wowf"), field(3, message(field(1, "C:/Games/World of Warcraft")))))))!!
                .map { Triple(it.uid, it.path, it.product) })
    }

    @Test fun aGameKeepsTheFolderItWasListedWithWhenAnotherVersionComesOrGoes() {
        val root = File(context.filesDir, "World of Warcraft").apply { mkdirs() }
        val retailDir = File(root, "_retail_").apply { mkdirs() }
        val foreverDir = File(root, "_forever_").apply { mkdirs() }
        val wow = BattleNet.title("wow")!!
        val forever = BattleNet.title("wow_forever")!!
        val classic = BattleNet.title("wow_classic")!!
        assertEquals(mapOf("WoWF" to root), BattleNet.ownFolders(mapOf("WoWF" to (forever to root)), mapOf("WoWF" to null)))
        assertEquals(mapOf("WoW" to root, "WoWF" to foreverDir),
            BattleNet.ownFolders(linkedMapOf("WoW" to (wow to root), "WoWF" to (forever to root)), mapOf("WoW" to null, "WoWF" to null)))
        assertEquals(mapOf("WoWF" to foreverDir), BattleNet.ownFolders(mapOf("WoWF" to (forever to root)), mapOf("WoWF" to foreverDir.path)))
        assertEquals(mapOf("WoW" to retailDir, "WoWC" to root),
            BattleNet.ownFolders(linkedMapOf("WoW" to (wow to root), "WoWC" to (classic to root)), mapOf("WoW" to null, "WoWC" to root.path)))
        assertEquals(mapOf("WoW" to root, "WoWC" to root),
            BattleNet.ownFolders(linkedMapOf("WoW" to (wow to root), "WoWC" to (classic to root)), mapOf("WoW" to null, "WoWC" to null)))
        assertEquals(mapOf("WoWF" to root), BattleNet.ownFolders(mapOf("WoWF" to (forever to root)), mapOf("WoWF" to "/elsewhere")))
    }

    @Test fun theListedFolderIsRememberedAcrossScans() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        val wow = File(pfx, "drive_c/Program Files (x86)/World of Warcraft").apply { mkdirs() }
        File(wow, "_forever_").mkdirs()
        val db = File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").apply { parentFile!!.mkdirs() }
        val retail = field(1, message(field(1, "wow"), field(2, "wow"), field(3, message(field(1, "C:/Program Files (x86)/World of Warcraft")))))
        val forever = field(1, message(field(1, "wow_forever"), field(2, "wowf"), field(3, message(field(1, "C:/Program Files (x86)/World of Warcraft")))))
        db.writeBytes(message(retail, forever))
        assertEquals(listOf(wow, File(wow, "_forever_")), BattleNet.games(context).map { it.folder })
        db.writeBytes(message(forever))
        assertEquals(listOf(File(wow, "_forever_")), BattleNet.games(context).map { it.folder })
        db.writeBytes(message(forever, retail))
        assertEquals(listOf(File(wow, "_forever_"), wow), BattleNet.games(context).map { it.folder })
    }

    @Test fun aGameInstalledUnderBlizzardsChineseUidIsStillListed() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").writeBytes(message(
            field(1, message(field(1, "osic"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/Diablo II Resurrected"))))),
            field(1, message(field(1, "s2_cn"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/StarCraft II"))))),
            field(1, message(field(1, "d3cn"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/Diablo III"))))),
        ))
        for (folder in listOf("Diablo II Resurrected", "StarCraft II", "Diablo III")) {
            File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/$folder").mkdirs()
        }
        val games = BattleNet.games(context)
        assertEquals(listOf("osic", "s2_cn", "d3cn"), games.map { it.installUid })
        assertEquals(listOf("osi", "s2", "diablo3"), games.map { it.uid })
        assertEquals(listOf("Diablo II: Resurrected" to "OSI", "StarCraft II" to "S2", "Diablo III" to "D3CN"), games.map { it.name to it.code })
        assertEquals("osic", BattleNet.title("osic")?.product)
        assertEquals("s2c", BattleNet.title("s2_cn")?.product)
        assertEquals("anbs", BattleNet.title("anbs_cn")?.product)
        assertEquals("d3cn", BattleNet.title("d3cn")?.product)
        assertEquals("s2c", BattleNet.title("s2_cn_zhcn")?.product)
        assertEquals("osic", BattleNet.title("osic_zhcn")?.product)
        assertEquals("anbs", BattleNet.title("anbs_cn_zhcn")?.product)
    }

    @Test fun theFolderPicksBetweenTwoDiabloThreeInstalls() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        val legacy = File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/Diablo III")
        val china = File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/Diablo III China")
        legacy.mkdirs()
        china.mkdirs()
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").writeBytes(message(
            field(1, message(field(1, "diablo3"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/Diablo III"))))),
            field(1, message(field(1, "d3cn"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/Diablo III China"))))),
        ))
        assertEquals(setOf("diablo3", "d3cn"), BattleNet.games(context).map { it.installUid }.toSet())

        val sidecar = StoreGameSidecar(Store.BATTLENET, "diablo3", "Diablo III", "")
        val oldGame = InstalledStoreGame(sidecar, legacy)
        val chinaGame = InstalledStoreGame(sidecar, china)
        val records = listOf(oldGame, chinaGame)
        assertEquals(chinaGame, StoresState.pickInstalled(Store.BATTLENET, "diablo3", china, records))
        assertEquals(oldGame, StoresState.pickInstalled(Store.BATTLENET, "diablo3", legacy, records))
        assertEquals("d3cn", BattleNetInstall.installUid(context, chinaGame))
        assertEquals("diablo3", BattleNetInstall.installUid(context, oldGame))
        val missing = File(china, "missing")
        assertNull(StoresState.pickInstalled(Store.BATTLENET, "diablo3", missing, records))
        assertNull(BattleNetInstall.installUid(context, InstalledStoreGame(sidecar, missing)))
    }

    @Test fun anUnfinishedInstallIsARegistrationButNotAGame() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/StarCraft II").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").writeBytes(message(
            field(1, message(
                field(1, "s2_cn"), field(2, "s2c"),
                field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/StarCraft II"))),
                field(4, message(field(1, message(varintField(1, 0), varintField(2, 0))))),
            )),
        ))
        assertTrue("an unfinished install is not a library game", BattleNet.games(context).none { it.installUid == "s2_cn" })
        assertEquals(listOf("s2_cn"), BattleNet.registrationsAt(context, "s2", folder))
    }

    @Test fun aChinaCopyLaunchesWithItsRecordedCode() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        val legacy = File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/Diablo III").apply { mkdirs() }
        val china = File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/Diablo III China").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").writeBytes(message(
            field(1, message(field(1, "diablo3"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/Diablo III"))))),
            field(1, message(field(1, "d3cn"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/Diablo III China"))))),
        ))
        assertEquals("D3CN", BattleNet.launchCode(context, "diablo3", china))
        assertEquals("D3CN", BattleNet.launchCode(context, "d3cn", china))
        assertEquals(listOf("d3cn"), BattleNet.registrationsAt(context, "d3cn", china))
        assertEquals("D3", BattleNet.launchCode(context, "diablo3", legacy))
        assertEquals("D3", BattleNet.launchCode(context, "diablo3", null))
        assertNull(BattleNet.launchCode(context, "diablo3", File(china, "missing")))
        val game = com.droiddeck.launcher.frontend.Library.SteamGame(
            1234, "Diablo III", null, com.droiddeck.launcher.frontend.Library.ADDED,
            source = Store.BATTLENET.id, storeId = "diablo3", gameFiles = china,
        )
        val command = com.droiddeck.launcher.session.NoSteamLaunch.command(context, game, null)!!
        assertEquals(com.droiddeck.launcher.session.NoSteamLaunch.BATTLENET, command.program)
        assertEquals(listOf("launch", "D3CN"), command.args)
    }

    @Test fun aCopyInAnotherFolderResolvesItsOwnLaunchCode() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        val first = File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/StarCraft II").apply { mkdirs() }
        val second = File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/StarCraft II Copy").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").writeBytes(message(
            field(1, message(field(1, "s2_cn"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/StarCraft II"))))),
            field(1, message(field(1, "s2_cn"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/StarCraft II Copy"))))),
        ))
        // games() keeps one copy per launch code, so the second folder is only reachable per folder.
        assertEquals(1, BattleNet.games(context).size)
        assertEquals("S2", BattleNet.launchCode(context, "s2", first))
        assertEquals("S2", BattleNet.launchCode(context, "s2", second))
    }

    @Test fun aCopyRegisteredUnderAnotherUidOfTheSameProductStillLaunches() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/Diablo IV").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").writeBytes(message(
            field(1, message(field(1, "fen"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/Diablo IV"))))),
        ))
        assertTrue(BattleNet.sameTitle("fen", "fenris"))
        assertFalse(BattleNet.sameTitle("fen", "wow"))
        assertEquals("Fen", BattleNet.launchCode(context, "fenris", folder))
        assertEquals("Fen", BattleNet.launchCode(context, "fen", folder))
    }

    @Test fun aRegistrationForAMissingFolderStillCountsAsPreexisting() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/Diablo III")
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").writeBytes(message(
            field(1, message(field(1, "d3cn"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/Diablo III"))))),
        ))
        assertTrue(BattleNet.games(context).none { it.folder.absolutePath == folder.absolutePath })
        assertEquals(listOf("d3cn"), BattleNet.registrationsAt(context, "diablo3", folder))
        folder.mkdirs()
        val uninstalled = AtomicBoolean(false)
        val item = CatalogItem(Store.BATTLENET, "diablo3", "Diablo III", null)
        BattleNetInstaller(context, item, folder, false, uninstall = {
            uninstalled.set(true)
            true
        }).cancel(true)
        Thread.sleep(100)
        assertFalse("a registration whose folder was missing is still pre-existing", uninstalled.get())
    }

    @Test fun aStaleRegistryRowDoesNotHideAnAgentRegistrationInAnotherFolder() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        val a = File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/Diablo III A")
        val b = File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/Diablo III B")
        File(pfx, "system.reg").writeText("""
            WINE REGISTRY Version 2
            [Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Diablo III] 1
            "InstallLocation"="Z:/root/Games/Stores/Battle.net/Diablo III A"
            "UninstallString"="\"C:\\ProgramData\\Battle.net\\Agent\\Blizzard Uninstaller.exe\" --uid=d3cn"
        """.trimIndent())
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").writeBytes(message(
            field(1, message(field(1, "d3cn"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/Diablo III B"))))),
        ))
        assertEquals(emptyList<String>(), BattleNet.registrationsAt(context, "diablo3", a))
        assertEquals(listOf("d3cn"), BattleNet.registrationsAt(context, "diablo3", b))
    }

    @Test fun aPathOnlyAgentRegistrationMakesTheFolderUnknown() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/Diablo III")
        val db = message(field(1, message(field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/Diablo III"))))))
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").writeBytes(db)
        assertEquals("", BattleNet.productDbRegistrations(db)!!.single().uid)
        assertNull(BattleNet.registrationsAt(context, "diablo3", folder))
    }

    @Test fun aPreexistingRegistrationIsNotUninstalled() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        val item = CatalogItem(
            Store.BATTLENET, "osi", "Diablo II: Resurrected", null,
            extra = mapOf(BattleNetInstall.PREEXISTING to "1"),
        )
        val uninstalled = AtomicBoolean(false)
        val installer = BattleNetInstaller(context, item, folder, false, uninstall = {
            uninstalled.set(true)
            true
        }) { listOf("osic") }
        installer.cancel(true)
        Thread.sleep(100)
        assertFalse("a registration that existed before the task must survive", uninstalled.get())
    }

    @Test fun aCancelledInstallUninstallsOnlyTheRegistrationItCreated() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        val item = CatalogItem(
            Store.BATTLENET, "osi", "Diablo II: Resurrected", null,
            extra = mapOf(BattleNetInstall.PREEXISTING to "0"),
        )
        val seen = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        val installer = BattleNetInstaller(context, item, folder, false, cleanupVerifyMs = 0, uninstall = { uid ->
            seen.set(uid)
            done.countDown()
            true
        }) { listOf("osic") }
        installer.cancel(true)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("osic", seen.get())
    }

    @Test fun aCancelledInstallsCleanupHoldsTheFolderForUninstall() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        val item = CatalogItem(
            Store.BATTLENET, "osi", "Diablo II: Resurrected", null,
            extra = mapOf(BattleNetInstall.PREEXISTING to "0"),
        )
        val holding = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val ownership = Ownership(item)
        ownership.createdFolder.set(true)
        var registered = true
        val installer = BattleNetInstaller(context, item, folder, false, ownership = ownership, cleanupVerifyMs = 0, uninstall = { uid ->
            holding.set(StoreInstalls.isDiscarding(folder))
            registered = false
            done.countDown()
            true
        }) { if (registered) listOf("osic") else emptyList() }
        installer.cancel(true)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue("the cleanup must hold the folder against uninstall", holding.get())
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (StoreInstalls.isDiscarding(folder) && System.nanoTime() < until) Thread.sleep(10)
        assertFalse(StoreInstalls.isDiscarding(folder))
        assertFalse(folder.exists())
    }

    @Test fun aLateRegistrationReportedWhileTheWorkerStopsIsUsedForCancellation() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        val item = CatalogItem(Store.BATTLENET, "osi", "Diablo II: Resurrected", null)
        var visible = false
        val seen = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        val installer = BattleNetInstaller(context, item, folder, false, registrationWaitMs = 1000, cleanupVerifyMs = 0, uninstall = { uid ->
            seen.set(uid)
            done.countDown()
            true
        }) {
            if (visible) listOf("osic") else emptyList()
        }
        val started = BattleNetInstaller::class.java.getDeclaredField("started").apply { isAccessible = true }
        (started.get(installer) as AtomicBoolean).set(true)
        val ended = BattleNetInstaller::class.java.getDeclaredField("ended").apply { isAccessible = true }
            .get(installer) as CountDownLatch
        installer.cancel(true)
        visible = true
        ended.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("osic", seen.get())
    }

    @Test fun aCancelledInstallRemovesAFolderItCreatedWithoutARegistration() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected")
        assertFalse(folder.exists())
        folder.mkdirs()
        File(folder, "partial.bin").writeBytes(byteArrayOf(1, 2, 3))
        val item = CatalogItem(
            Store.BATTLENET, "osi", "Diablo II: Resurrected", null,
            extra = mapOf(BattleNetInstall.PREEXISTING to "0"),
        )
        val ownership = Ownership(item)
        val uninstalled = AtomicBoolean(false)
        val installer = BattleNetInstaller(context, item, folder, false, ownership = ownership, registrationWaitMs = 0, uninstall = {
            uninstalled.set(true)
            false
        }) { emptyList() }
        ownership.createdFolder.set(true)
        BattleNetInstaller::class.java.getDeclaredField("started").apply { isAccessible = true }
            .set(installer, AtomicBoolean(true))
        val ended = BattleNetInstaller::class.java.getDeclaredField("ended").apply { isAccessible = true }
            .get(installer) as CountDownLatch
        installer.cancel(true)
        ended.countDown()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (folder.exists() && System.nanoTime() < until) Thread.sleep(10)
        assertFalse("a folder this task created must not survive a cancel", folder.exists())
        assertFalse(uninstalled.get())
    }

    @Test fun aCancelledInstallKeepsTheFolderWhileItsRegistrationRemains() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        File(folder, "partial.bin").writeBytes(byteArrayOf(1, 2, 3))
        val item = CatalogItem(
            Store.BATTLENET, "osi", "Diablo II: Resurrected", null,
            extra = mapOf(BattleNetInstall.PREEXISTING to "0"),
        )
        val installer = BattleNetInstaller(context, item, folder, false, cleanupVerifyMs = 0, uninstall = { true }) {
            listOf("osic")
        }
        BattleNetInstaller::class.java.getDeclaredField("started").apply { isAccessible = true }
            .set(installer, AtomicBoolean(true))
        val ended = BattleNetInstaller::class.java.getDeclaredField("ended").apply { isAccessible = true }
            .get(installer) as CountDownLatch
        installer.cancel(true)
        ended.countDown()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (BattleNetCleanup.get(context, folder) == null && System.nanoTime() < until) Thread.sleep(10)
        assertTrue("the folder must stay while the registration exists", folder.exists())
        assertEquals(BattleNetCleanup.PENDING, BattleNetCleanup.get(context, folder)?.state)
    }

    @Test fun aCancelledInstallDeletesTheFolderOnceItsRegistrationGoes() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        File(folder, "partial.bin").writeBytes(byteArrayOf(1, 2, 3))
        val item = CatalogItem(
            Store.BATTLENET, "osi", "Diablo II: Resurrected", null,
            extra = mapOf(BattleNetInstall.PREEXISTING to "0"),
        )
        val ownership = Ownership(item)
        ownership.createdFolder.set(true)
        var registered = true
        val installer = BattleNetInstaller(context, item, folder, false, ownership = ownership, cleanupVerifyMs = 0, uninstall = {
            registered = false
            true
        }) { if (registered) listOf("osic") else emptyList() }
        BattleNetInstaller::class.java.getDeclaredField("started").apply { isAccessible = true }
            .set(installer, AtomicBoolean(true))
        val ended = BattleNetInstaller::class.java.getDeclaredField("ended").apply { isAccessible = true }
            .get(installer) as CountDownLatch
        installer.cancel(true)
        ended.countDown()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (folder.exists() && System.nanoTime() < until) Thread.sleep(10)
        assertFalse(folder.exists())
        assertNull(BattleNetCleanup.get(context, folder))
    }

    @Test fun reconcileCleansAMarkerAfterItsRegistrationGoes() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/Diablo III").apply { mkdirs() }
        File(folder, "partial.bin").writeBytes(byteArrayOf(1, 2, 3))
        val db = File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db")
        db.writeBytes(message(
            field(1, message(field(1, "d3cn"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/Diablo III"))))),
        ))
        BattleNetCleanup.mark(context, folder, "diablo3", createdFolder = true, state = BattleNetCleanup.PENDING)
        BattleNetInstall.reconcileNow(context, uninstall = { uid ->
            assertEquals("d3cn", uid)
            db.writeBytes(ByteArray(0))
            true
        }, verifyMs = 0, watchMs = 0)
        assertFalse(folder.exists())
        assertNull(BattleNetCleanup.get(context, folder))
    }

    @Test fun aCancellationRecordsItsMarkerBeforeItWaitsForTheWorker() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        val item = CatalogItem(
            Store.BATTLENET, "osi", "Diablo II: Resurrected", null,
            extra = mapOf(BattleNetInstall.PREEXISTING to "0"),
        )
        val installer = BattleNetInstaller(context, item, folder, false, registrationWaitMs = 0, cleanupVerifyMs = 0) {
            emptyList()
        }
        BattleNetInstaller::class.java.getDeclaredField("started").apply { isAccessible = true }
            .set(installer, AtomicBoolean(true))
        val ended = BattleNetInstaller::class.java.getDeclaredField("ended").apply { isAccessible = true }
            .get(installer) as CountDownLatch
        installer.cancel(true)
        assertEquals(BattleNetCleanup.PENDING, BattleNetCleanup.get(context, folder)?.state)
        ended.countDown()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (BattleNetCleanup.get(context, folder)?.state == BattleNetCleanup.PENDING && System.nanoTime() < until) Thread.sleep(10)
    }

    @Test fun anActiveMarkerCannotOverwriteACancellation() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        BattleNetCleanup.mark(context, folder, "osi", createdFolder = false, state = BattleNetCleanup.PENDING)
        BattleNetCleanup.markActive(context, folder, "osi", createdFolder = true)
        val pending = BattleNetCleanup.get(context, folder)!!
        assertEquals(BattleNetCleanup.PENDING, pending.state)
        assertTrue(pending.createdFolder)
    }

    @Test fun aCleanupMarkerRewrittenForAReplacementHasANewStamp() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        BattleNetCleanup.mark(context, folder, "osi", createdFolder = false, state = BattleNetCleanup.PENDING)
        val first = BattleNetCleanup.get(context, folder)!!
        BattleNetCleanup.mark(context, folder, "osi", createdFolder = true, state = BattleNetCleanup.PENDING)
        val second = BattleNetCleanup.get(context, folder)!!
        assertTrue(second.stamp > first.stamp)
    }

    @Test fun aCleanupMarkerIsRestoredWithoutTheTask() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        BattleNetCleanup.mark(context, folder, "osi", createdFolder = true, state = BattleNetCleanup.PENDING)
        val pending = BattleNetCleanup.pending(context).single()
        assertEquals(folder.absolutePath, pending.folder.absolutePath)
        assertEquals("osi", pending.id)
        assertTrue(pending.createdFolder)
        assertEquals(BattleNetCleanup.PENDING, pending.state)
        BattleNetCleanup.clear(context, folder)
        assertTrue(BattleNetCleanup.pending(context).isEmpty())
    }

    @Test fun aRegistrationSourceFailureMakesOwnershipUnknown() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db")
            .writeBytes(byteArrayOf(0x0a, 0x03, 0x0a, 0x7f, 0x01))
        assertNull(BattleNet.registrationsAt(context, "osi", File(LinuxRuntime.rootDir(context), "root/Games/D2R")))
    }

    @Test fun aWorkerOwnershipSnapshotFailureDoesNotUninstall() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        val item = CatalogItem(Store.BATTLENET, "osi", "Diablo II: Resurrected", null)
        val ownership = Ownership(item)
        val uninstalled = AtomicBoolean(false)
        val installer = BattleNetInstaller(context, item, folder, false, ownership = ownership, uninstall = {
            uninstalled.set(true)
            true
        }) { null }
        runCatching { installer.run(DownloadQueue.JobHandle("battlenet:snapshot-failure", AtomicBoolean(false))) }
        assertFalse("a failed read must not become a final ownership decision", ownership.known)
        assertTrue(ownership.preexisting)
        installer.cancel(true)
        Thread.sleep(100)
        assertFalse("a failed worker snapshot must disable destructive cleanup", uninstalled.get())
    }

    @Test fun aFailedRegistrationReadDoesNotUninstall() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        val item = CatalogItem(
            Store.BATTLENET, "osi", "Diablo II: Resurrected", null,
            extra = mapOf(BattleNetInstall.PREEXISTING to "0"),
        )
        val uninstalled = AtomicBoolean(false)
        BattleNetInstaller(context, item, folder, false, uninstall = {
            uninstalled.set(true)
            true
        }) { null }.cancel(true)
        Thread.sleep(100)
        assertFalse("an unreadable registration source must disable destructive cleanup", uninstalled.get())
    }

    @Test fun aResumedInstallerKeepsTheOwnershipDecision() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        val item = CatalogItem(Store.BATTLENET, "osi", "Diablo II: Resurrected", null)
        val ownership = Ownership(item)
        ownership.known = true
        ownership.preexisting = false
        val seen = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        BattleNetInstaller(context, item, folder, false, ownership = ownership, cleanupVerifyMs = 0, uninstall = { uid ->
            seen.set(uid)
            done.countDown()
            true
        }) { listOf("osic") }.cancel(true)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("osic", seen.get())
    }

    @Test fun aRestoredPausedInstallUninstallsTheRegistrationItCreated() {
        val folder = File(LinuxRuntime.rootDir(context), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        val item = CatalogItem(
            Store.BATTLENET, "osi", "Diablo II: Resurrected", null,
            extra = mapOf(BattleNetInstall.PREEXISTING to "0"),
        )
        val seen = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        BattleNetInstaller(context, item, folder, false, cleanupVerifyMs = 0, uninstall = { uid ->
            seen.set(uid)
            done.countDown()
            true
        }) { listOf("osic") }.cancel(true)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("osic", seen.get())
    }

    @Test fun theTitlePicksBetweenWarcraftFlavorsInOneFolder() {
        val pfx = File(BattleNet.prefix(context), "pfx").apply { mkdirs() }
        File(pfx, "drive_c/ProgramData/Battle.net/Agent").mkdirs()
        val wow = File(LinuxRuntime.rootDir(context), "root/Games/Stores/Battle.net/World of Warcraft")
        wow.mkdirs()
        File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").writeBytes(message(
            field(1, message(field(1, "wow"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/World of Warcraft"))))),
            field(1, message(field(1, "wow_classic"), field(3, message(field(1, "Z:/root/Games/Stores/Battle.net/World of Warcraft"))))),
        ))
        val sidecar = StoreGameSidecar(Store.BATTLENET, "wow_classic", "World of Warcraft Classic", "")
        assertEquals("wow_classic", BattleNetInstall.installUid(context, InstalledStoreGame(sidecar, wow)))
        assertEquals(listOf("wow"), BattleNet.registrationsAt(context, "wow", File(wow, "_retail_")))
        assertEquals(listOf("wow_classic"), BattleNet.registrationsAt(context, "wow_classic", File(wow, "_classic_")))
        val single = StoreGameSidecar(Store.BATTLENET, "wow", "World of Warcraft", "")
        assertNull(BattleNetInstall.installUid(context, InstalledStoreGame(single, File(wow, "missing"))))
    }

    @Test fun aScannedGamesShortcutReusesTheKeptRankingInsteadOfWalkingItsFolder() {
        val added = com.droiddeck.launcher.frontend.AddedGames
        val folder = File(BattleNet.prefix(context), "pfx/drive_c/Program Files (x86)/Kept Game").apply { mkdirs() }
        File(folder, "Game.exe").writeBytes(byteArrayOf(0x4d, 0x5a))
        val kept = File(folder, "Other.exe").apply { writeBytes(byteArrayOf(0x4d, 0x5a)) }
        com.droiddeck.launcher.frontend.AddedGamesCache(File(context.filesDir, com.droiddeck.launcher.frontend.AddedGamesCache.FILE)).apply {
            keepRank(folder, com.droiddeck.launcher.frontend.GameExePicker.Ranking(listOf(kept), listOf(10), false))
            save()
        }
        added.scan(context)
        assertEquals(kept, BattleNet.shortcut(context, BattleNet.Game("fenris", "Kept Game", "Fen", folder))!!.exe)
        assertEquals("Game.exe", added.candidates(folder).first().name)
    }

    @Test fun aGamesShortcutHasBattleNetLaunchItInItsPrefix() {
        val folder = File(BattleNet.prefix(context), "pfx/drive_c/Program Files (x86)/Diablo IV").apply { mkdirs() }
        File(folder, "Diablo IV Launcher.exe").writeBytes(byteArrayOf(0x4d, 0x5a))
        val shortcut = BattleNet.shortcut(context, BattleNet.Game("fenris", "Diablo IV", "Fen", folder))!!
        val exe = "/root/.local/share/Steam/steamapps/compatdata/2596113170/pfx/drive_c/Program Files (x86)/Battle.net/Battle.net.exe"
        assertEquals(exe, shortcut.guestExe)
        assertEquals(exe.substringBeforeLast('/'), shortcut.guestDir)
        assertEquals("STEAM_COMPAT_DATA_PATH=/root/.local/share/Steam/steamapps/compatdata/2596113170 %command% --exec=\"launch Fen\"", shortcut.launchOptions)
        val crc = CRC32().apply { update("\"$exe\"Diablo IV".toByteArray()) }.value
        assertEquals(crc or 0x80000000L, shortcut.appId)
        assertEquals((shortcut.appId shl 32) or 0x02000000L, shortcut.gameId)
        assertEquals(com.droiddeck.launcher.stores.Store.BATTLENET.id, shortcut.source)
        assertEquals("Diablo IV Launcher.exe", shortcut.exe.name)
        val listing = com.droiddeck.launcher.frontend.AddedGames.writeListing(context, listOf(shortcut)).readText()
        val entry = JSONArray(listing).getJSONObject(0)
        assertEquals(com.droiddeck.launcher.stores.Store.BATTLENET.id, entry.getString("store"))
        assertEquals(shortcut.launchOptions, entry.getString("launch"))
        assertEquals(exe, entry.getString("exe"))
    }

    @Test fun titlesSharingALaunchCodeShareTheirArt() {
        val cover = File(context.filesDir, "battlenet-art/fenris/p.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertEquals(cover, BattleNet.art(context, BattleNet.title("fen")!!).cover)
        val wide = File(context.filesDir, "battlenet-art/wow_classic/blizzard.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertEquals(wide, BattleNet.art(context, BattleNet.title("wow_classic_anniversary")!!).header)
    }

    @Test fun theStoreOffersEachTitleOnceAndHidesWhatIsInstalled() {
        val codes = BattleNet.STORE.map { it.code }
        assertEquals(codes.distinct(), codes)
        assertTrue("Fen" in codes && "OSI" in codes && "WTCG" in codes)
        assertFalse(BattleNet.STORE.any { it.uid == "d3cn" || it.uid == "fen" })
        assertFalse(BattleNet.STORE.any { it.code == "WoWC" })
        assertEquals("wow_forever", BattleNet.STORE.first { it.code == "WoWF" }.uid)
        assertEquals("fenris", BattleNet.STORE.first { it.code == "Fen" }.uid)
        assertTrue(BattleNet.STORE.all { it.uid.matches(Regex("[a-z0-9_]{1,32}")) })
        val installedUids = listOf("fenris", "diablo3_enus", null)
            .mapNotNull { uid -> uid?.let { BattleNet.title(it)?.uid } }.toSet()
        val offered = BattleNet.STORE.filterNot { it.uid in installedUids }.map { it.code }
        assertFalse("Fen" in offered || "D3" in offered)
        assertTrue("OSI" in offered && "WoWF" in offered)
        assertFalse("WoWF" in BattleNet.STORE.filter { BattleNet.notInstalled(it, listOf("wow_forever")) }.map { it.code })
    }

    @Test fun aBattleNetGameStartsWithoutSteamThroughItsShortcutUntilThatIsSwitchedOff() {
        File(context.filesDir, "game-environment.json").delete()
        val folder = File(BattleNet.prefix(context), "pfx/drive_c/Program Files (x86)/Diablo IV").apply { mkdirs() }
        File(folder, "Diablo IV Launcher.exe").writeBytes(byteArrayOf(0x4d, 0x5a))
        val shortcut = BattleNet.shortcut(context, BattleNet.Game("fenris", "Diablo IV", "Fen", folder))!!
        val game = com.droiddeck.launcher.frontend.Library.SteamGame(
            shortcut.appId.toInt(), shortcut.name, null, com.droiddeck.launcher.frontend.Library.ADDED, shortcut.gameId,
            source = com.droiddeck.launcher.stores.Store.BATTLENET.id, storeId = shortcut.storeId)
        val noSteam = com.droiddeck.launcher.session.NoSteamLaunch
        assertEquals(BattleNet.APP_ID.toString(), noSteam.scope(game))
        assertFalse(noSteam.enabled(context, game))
        assertTrue(BattleNet.ensureNoSteam(context))
        assertTrue(noSteam.enabled(context, game))
        val command = noSteam.command(context, game, shortcut)!!
        assertEquals(noSteam.BATTLENET, command.program)
        assertEquals(listOf("launch", "Fen"), command.args)
        GameEnvironmentStore.setGameNoSteam(context, BattleNet.APP_ID.toString(), false)
        assertFalse(BattleNet.ensureNoSteam(context))
        assertFalse(noSteam.enabled(context, game))
    }

    @Test fun theGamesAreToldOfAFifthOfThePhonesMemory() {
        assertEquals(2048, BattleNet.videoMemory(11113))
        assertEquals(1536, BattleNet.videoMemory(7468))
        assertEquals(2560, BattleNet.videoMemory(15200))
        assertEquals(3584, BattleNet.videoMemory(60000))
        assertTrue(BattleNet.videoMemory(Long.MAX_VALUE / 2) < 4096)
        assertEquals(
            "dxgi.maxDeviceMemory = 2048; dxgi.maxSharedMemory = 2048; d3d9.maxAvailableMemory = 2048",
            BattleNet.videoMemoryOptions(2048),
        )
    }

    @Test fun theCapIsTheBattleNetProfilesOwnDxvkConfigUnlessItHasOne() {
        File(context.filesDir, "game-environment.json").delete()
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).setMemoryInfo(android.app.ActivityManager.MemoryInfo().apply { totalMem = 11113L shl 20 })
        val scope = BattleNet.APP_ID.toString()
        assertTrue(BattleNet.ensureVideoMemory(context))
        assertEquals(BattleNet.videoMemoryOptions(2048), GameEnvironmentStore.read(context).games[scope]?.get("DXVK_CONFIG"))
        val published = File(LinuxRuntime.rootDir(context), "root/.config/droiddeck/game-environment.json").readText()
        assertTrue("dxgi.maxDeviceMemory = 2048" in JSONObject(published).getJSONObject("games").getJSONObject(scope).getString("DXVK_CONFIG"))
        assertFalse(BattleNet.ensureVideoMemory(context))
        for (own in listOf("dxvk.maxFrameRate = 30", null)) {
            GameEnvironmentStore.save(context, GameEnvironmentStore.read(context).let { it.withEntries(scope, it.entries(scope) + ("DXVK_CONFIG" to own)) })
            assertFalse(BattleNet.ensureVideoMemory(context))
            assertEquals(own, GameEnvironmentStore.read(context).games[scope]?.get("DXVK_CONFIG"))
        }
        GameEnvironmentStore.save(context, GameEnvironmentStore.read(context).let { it.withEntries(scope, it.entries(scope) - "DXVK_CONFIG") })
        assertTrue(BattleNet.ensureVideoMemory(context))
    }

    @Test fun theProfileDefaultsAreSetForTheBattleNetProgramOrOnceInstalledAndNeverForAnythingElse() {
        File(context.filesDir, "game-environment.json").delete()
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).setMemoryInfo(android.app.ActivityManager.MemoryInfo().apply { totalMem = 11113L shl 20 })
        val scope = BattleNet.APP_ID.toString()
        BattleNet.ensureDefaults(context, null)
        assertNull(GameEnvironmentStore.read(context).games[scope]?.get("DXVK_CONFIG"))
        BattleNet.ensureDefaults(context, BattleNet.PROGRAM)
        assertEquals(BattleNet.videoMemoryOptions(2048), GameEnvironmentStore.read(context).games[scope]?.get("DXVK_CONFIG"))
        assertTrue(com.droiddeck.launcher.session.SessionPrefs.battleNetNoSteamSet(context))
        File(context.filesDir, "game-environment.json").delete()
        launcher().apply { parentFile!!.mkdirs(); writeText("MZ") }
        BattleNet.ensureDefaults(context, null)
        assertEquals(BattleNet.videoMemoryOptions(2048), GameEnvironmentStore.read(context).games[scope]?.get("DXVK_CONFIG"))
    }

    private fun launcher() = File(BattleNet.prefix(context), "pfx/drive_c/Program Files (x86)/Battle.net/Battle.net Launcher.exe")

    private fun varint(value: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var v = value
        while (v >= 0x80) { out.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
        return out.toByteArray()
    }

    private fun field(number: Int, bytes: ByteArray) = varint((number shl 3 or 2).toLong()) + varint(bytes.size.toLong()) + bytes
    private fun field(number: Int, text: String) = field(number, text.toByteArray())
    private fun varintField(number: Int, value: Long) = varint((number shl 3).toLong()) + varint(value)
    private fun message(vararg fields: ByteArray) = fields.fold(ByteArray(0)) { all, f -> all + f }

    private fun store(file: String) {
        val wcp = File(context.filesDir, "components/packages/$file")
        wcp.parentFile!!.mkdirs()
        TarArchiveOutputStream(ZstdOutputStream(wcp.outputStream())).use { tar ->
            fun put(name: String, bytes: ByteArray) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
            put("profile.json", JSONObject().put("type", "FEXCore-Linux").put("versionName", file.removeSuffix(".wcp")).toString().toByteArray())
            put("files/lib/wine/aarch64-windows/libarm64ecfex.dll", "arm64ec $file".toByteArray())
            put("files/lib/wine/aarch64-windows/libwow64fex.dll", "wow64 $file".toByteArray())
        }
    }
}
