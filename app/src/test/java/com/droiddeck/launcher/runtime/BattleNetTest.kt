package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.GameEnvironmentStore
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
        LinuxRuntime.rootDir(context).deleteRecursively()
    }

    @Test fun theIdIsTheShortcutIdTheScriptNames() {
        val crc = CRC32().apply { update("\"droiddeck-battlenet\"Battle.net".toByteArray()) }.value
        assertEquals(crc or 0x80000000L, BattleNet.APP_ID)
    }

    @Test fun theNewestD2rfixIsTheHighestFexThenTheHighestFix() {
        val items = listOf(
            item("FEXCore-2610-linux.wcp"),
            item("FEXCore-2609-d2rfix25-linux.wcp"),
            item("FEXCore-2610-d2rfix9-linux.wcp"),
            item("FEXCore-2610-d2rfix28-linux.wcp"),
            item("FEXCore-2509.1-d2rfix40-linux.wcp"),
        )
        assertEquals("FEXCore-2610-d2rfix28-linux.wcp", BattleNet.newestD2rFix(items)?.file)
        assertEquals("FEXCore-2610.1-d2rfix1-linux.wcp", BattleNet.newestD2rFix(items + item("FEXCore-2610.1-d2rfix1-linux.wcp"))?.file)
        assertEquals("FexCore-2611-D2RFIX2-linux.wcp", BattleNet.newestD2rFix(items + item("FexCore-2611-D2RFIX2-linux.wcp"))?.file)
    }

    @Test fun onlyFexD2rfixPackagesCount() {
        assertNull(BattleNet.newestD2rFix(listOf(item("FEXCore-2610-linux.wcp"), item("FEXCore-2610-d2rfix28-linux.wcp", comp = "dxvk"))))
        assertNull(BattleNet.d2rfix("FEXCore-2610-d2rfix28-linux.wcp.part"))
        assertNull(BattleNet.d2rfix("FEXCore-2610-d2rfix-linux.wcp"))
        assertEquals(listOf(2610L) to 28L, BattleNet.d2rfix("FEXCore-2610-d2rfix28-linux.wcp"))
    }

    @Test fun theNewestStoredBuildIsSetForThePrefix() {
        catalog("FEXCore-2609-d2rfix25-linux.wcp", "FEXCore-2610-d2rfix28-linux.wcp", "FEXCore-2610-linux.wcp")
        store("FEXCore-2610-d2rfix28-linux.wcp")

        assertEquals("FEXCore-2610-d2rfix28-linux.wcp", BattleNet.ensureFex(context, ::noRefresh))

        assertEquals(mapOf("fex" to "FEXCore-2610-d2rfix28-linux.wcp"), ComponentsManager.gameComponents(context, BattleNet.APP_ID.toString()))
        assertEquals("FEXCore-2610-d2rfix28-linux.wcp", BattleNet.ensureFex(context, ::noRefresh))
    }

    @Test fun aNewerBuildReplacesTheOldOne() {
        catalog("FEXCore-2609-d2rfix25-linux.wcp")
        store("FEXCore-2609-d2rfix25-linux.wcp")
        BattleNet.ensureFex(context, ::noRefresh)
        catalog("FEXCore-2609-d2rfix25-linux.wcp", "FEXCore-2610-d2rfix28-linux.wcp")
        store("FEXCore-2610-d2rfix28-linux.wcp")

        BattleNet.ensureFex(context, ::noRefresh)

        assertEquals("FEXCore-2610-d2rfix28-linux.wcp", ComponentsManager.gameComponents(context, BattleNet.APP_ID.toString())["fex"])
    }

    @Test fun aStaleListingIsFetchedAgain() {
        catalog("FEXCore-2609-d2rfix25-linux.wcp", fetchedAt = 1)
        store("FEXCore-2610-d2rfix28-linux.wcp")

        BattleNet.ensureFex(context) { listOf(item("FEXCore-2609-d2rfix25-linux.wcp"), item("FEXCore-2610-d2rfix28-linux.wcp")) }

        assertEquals("FEXCore-2610-d2rfix28-linux.wcp", ComponentsManager.gameComponents(context, BattleNet.APP_ID.toString())["fex"])
    }

    @Test fun aFailedFetchFallsBackOnTheCachedListing() {
        catalog("FEXCore-2609-d2rfix25-linux.wcp", fetchedAt = 1)
        store("FEXCore-2609-d2rfix25-linux.wcp")

        assertEquals("FEXCore-2609-d2rfix25-linux.wcp", BattleNet.ensureFex(context) { error("offline") })
    }

    @Test fun aListingWithoutD2rfixIsAnError() {
        catalog("FEXCore-2610-linux.wcp")
        var refreshed = 0
        assertThrows(IllegalStateException::class.java) { BattleNet.ensureFex(context) { refreshed++; emptyList() } }
        assertEquals(1, refreshed)
        assertEquals(emptyMap<String, String>(), ComponentsManager.gameComponents(context, BattleNet.APP_ID.toString()))
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
        catalog("FEXCore-2610-d2rfix28-linux.wcp")
        store("FEXCore-2610-d2rfix28-linux.wcp")
        BattleNet.ensureFex(context, ::noRefresh)
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
        assertNull(BattleNet.fexInUse(context))
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
        assertEquals("fenris", BattleNet.STORE.first { it.code == "Fen" }.uid)
        assertTrue(BattleNet.STORE.all { it.uid.matches(Regex("[a-z0-9_]{1,32}")) })
        val installed = listOf("fenris", "diablo3_enus", null)
        val offered = BattleNet.STORE.filter { BattleNet.notInstalled(it, installed) }.map { it.code }
        assertFalse("Fen" in offered || "D3" in offered)
        assertTrue("OSI" in offered)
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
        val command = noSteam.command(game, shortcut)!!
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

    private fun noRefresh(context: Context): List<ComponentsManager.CatalogItem> = error("the cached listing was fresh")

    private fun item(file: String, comp: String = "fex") = ComponentsManager.CatalogItem(
        file, comp, "FexCore-Linux", "https://github.com/The412Banner/Nightlies/releases/download/FexCore-Linux/$file", 4, "sha256:" + "ab".repeat(32),
    )

    private fun catalog(vararg files: String, fetchedAt: Long = System.currentTimeMillis() / 1000) {
        val cache = File(context.filesDir, "components/catalog.json")
        cache.parentFile!!.mkdirs()
        val items = JSONArray()
        for (file in files) items.put(JSONObject().put("file", file).put("comp", "fex").put("release", "FexCore-Linux")
            .put("url", item(file).url).put("size", 4).put("digest", item(file).digest))
        cache.writeText(JSONObject().put("items", items).put("fetchedAt", fetchedAt).toString())
    }

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
