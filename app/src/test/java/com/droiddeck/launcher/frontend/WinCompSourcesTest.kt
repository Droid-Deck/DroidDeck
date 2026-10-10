package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.WinComponents
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Each source's list of Windows components, how it maps to the catalog, and what turns on by itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WinCompSourcesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    private fun game(folder: File?, source: String, appId: Int = 0x8123_4567.toInt(), library: String = Library.ADDED, name: String = "Game") =
        Library.SteamGame(appId, name, null, library, gameFiles = folder, source = source)

    private fun sidecar(folder: File, store: Store, extra: Map<String, String>) =
        StoreGameSidecar(store, "1", "Game", exe = "Game.exe", extra = extra).write(folder)

    private fun auto(findings: List<WinCompSources.Finding>) = findings.filter { it.auto }.map { it.component to it.origin }

    // ---- mapping tables ----

    @Test fun gogDependencyIdsMapToComponents() {
        assertEquals(listOf("vcredist2015"), WinCompSources.gog("MSVC2017_x64"))
        assertEquals(listOf("vcredist2022"), WinCompSources.gog("MSVC2022"))
        assertEquals(listOf("vcredist2013"), WinCompSources.gog("msvc2013"))
        assertEquals(listOf("d3dx9"), WinCompSources.gog("DirectX"))
        assertEquals(listOf("dotnet48"), WinCompSources.gog("DOTNET48"))
        assertEquals(listOf("dotnet472"), WinCompSources.gog("DotNet471"))
        assertEquals(listOf("physx"), WinCompSources.gog("PhysX"))
        assertEquals(listOf("oalinst"), WinCompSources.gog("OpenAL"))
        assertEquals(listOf("xna40"), WinCompSources.gog("XNA4"))
        assertEquals(emptyList<String>(), WinCompSources.gog("ISI"))
    }

    @Test fun epicPrerequisitesMapToComponents() {
        assertEquals(listOf("vcredist2022", "d3dx9"), WinCompSources.epic("Engine/Extras/Redist/en-us/UEPrereqSetup_x64.exe", ""))
        assertEquals(listOf("vcredist2022", "d3dx9"), WinCompSources.epic("Engine/Extras/Redist/en-us/UE4PrereqSetup_x64.exe", "UE4 Prerequisites (x64)"))
        assertEquals(listOf("vcredist2022", "d3dx9"), WinCompSources.epic("", "UE4 Prerequisites (x64)"))
        assertEquals(listOf("vcredist2013"), WinCompSources.epic("Redist/vcredist/2013/vcredist_x64.exe", "Visual C++"))
        assertEquals(emptyList<String>(), WinCompSources.epic("Installers/LauncherHelper.exe", "Launcher Helper"))
    }

    @Test fun steamworksSharedDepotsMapToComponents() {
        assertEquals(listOf("vcredist2015_dll" to "VC++ 2015", "d3dx9" to "DirectX (June 2010)"), WinCompSources.steamDepots(listOf("228986", "228990", "999")))
    }

    // ---- per source ----

    @Test fun steamTakesItsSharedDepotsAndTurnsThemOn() {
        val steamapps = tmp.newFolder("steamapps")
        val folder = File(steamapps, "common/Game").apply { mkdirs() }
        File(steamapps, "appmanifest_570.acf").writeText(
            "\"AppState\"\n{\n\t\"appid\"\t\t\"570\"\n\t\"SharedDepots\"\n\t{\n\t\t\"228986\"\t\t\"228980\"\n\t\t\"228990\"\t\t\"228980\"\n\t}\n}\n",
        )
        val findings = WinCompSources.forGame(app, game(folder, Library.SOURCE_STEAM, 570, library = "steam", name = "Dota"))
        assertEquals(listOf("vcredist2015_dll" to WinCompSources.Origin.STEAM, "d3dx9" to WinCompSources.Origin.STEAM), auto(findings))
        assertEquals(listOf("VC++ 2015", "Dota", "570"), findings.first().args)
    }

    @Test fun gogTakesTheBuildsDependencies() {
        val folder = tmp.newFolder("GogGame")
        sidecar(folder, Store.GOG, mapOf(WinCompSources.GOG_DEPENDENCIES to "MSVC2017_x64,DirectX,ISI"))
        val findings = WinCompSources.forGame(app, game(folder, "gog"))
        assertEquals(listOf("vcredist2015" to WinCompSources.Origin.GOG, "d3dx9" to WinCompSources.Origin.GOG), auto(findings))
        // ISI is none of ours: a suggestion under its own name.
        assertEquals(listOf("ISI"), findings.filter { it.component == null }.map { it.original })
    }

    @Test fun epicTakesTheManifestsPrerequisite() {
        val folder = tmp.newFolder("EpicGame")
        sidecar(folder, Store.EPIC, mapOf(WinCompSources.EPIC_PREREQ_PATH to "Engine/Extras/Redist/en-us/UEPrereqSetup_x64.exe", WinCompSources.EPIC_PREREQ_NAME to "UE Prerequisites"))
        assertEquals(listOf("vcredist2022" to WinCompSources.Origin.EPIC, "d3dx9" to WinCompSources.Origin.EPIC), auto(WinCompSources.forGame(app, game(folder, "epic"))))
    }

    @Test fun amazonTakesFuelJsonsPostInstall() {
        val folder = tmp.newFolder("AmazonGame")
        sidecar(folder, Store.AMAZON, emptyMap())
        File(folder, "fuel.json").writeText(JSONObject()
            .put("Main", JSONObject().put("Command", "Game.exe"))
            .put("PostInstall", org.json.JSONArray()
                .put(JSONObject().put("Command", "_CommonRedist\\vcredist\\2015\\vc_redist.x64.exe").put("Args", org.json.JSONArray().put("/q")))
                .put(JSONObject().put("Command", "_CommonRedist\\DirectX\\Jun2010\\DXSETUP.exe"))
                .put(JSONObject().put("Command", "Tools\\Activate.exe")))
            .toString())
        val findings = WinCompSources.forGame(app, game(folder, "amazon"))
        assertEquals(listOf("vcredist2015" to WinCompSources.Origin.AMAZON, "d3dx9" to WinCompSources.Origin.AMAZON), auto(findings))
        assertEquals(listOf("Activate.exe"), findings.filter { it.component == null }.map { it.original })
    }

    // ---- Custom: certainty decides ----

    /** A version 29 appinfo.vdf with one app, whose depots point at two Steamworks Shared ones. */
    private fun appinfo(file: File, appId: Int) {
        val strings = listOf("appinfo", "common", "name", "depots", "228986", "depotfromapp", "228990", "1001", "maxsize")
        fun key(s: String) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(strings.indexOf(s)).array()
        val kv = ByteArrayOutputStream().apply {
            fun map(k: String) { write(0x00); write(key(k)) }
            fun str(k: String, v: String) { write(0x01); write(key(k)); write(v.toByteArray()); write(0) }
            fun end() = write(0x08)
            map("appinfo"); map("common"); str("name", "Some Game"); end()
            map("depots"); map("228986"); str("depotfromapp", "228980"); end(); map("228990"); str("depotfromapp", "228980"); end()
            map("1001"); str("maxsize", "1"); end(); end(); end(); end()
        }.toByteArray()
        val body = ByteArray(60) + kv
        val table = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(strings.size).array())
            strings.forEach { write(it.toByteArray()); write(0) }
        }.toByteArray()
        val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(0x07564429).putInt(1)
        val entries = ByteBuffer.allocate(8 + body.size + 4).order(ByteOrder.LITTLE_ENDIAN).putInt(appId).putInt(body.size).put(body).putInt(0).array()
        head.putLong(16L + entries.size)
        file.parentFile!!.mkdirs()
        file.writeBytes(head.array() + entries + table)
    }

    @Test fun steamsAppInfoIsReadForAnAppWithoutAManifest() {
        val file = File(tmp.newFolder(), "appinfo.vdf")
        appinfo(file, 1145360)
        val info = SteamAppInfo.read(file, 1145360)!!
        assertEquals("Some Game", info.name)
        assertEquals(listOf("228986", "228990"), info.sharedDepots)
        assertEquals(null, SteamAppInfo.read(file, 1))
    }

    @Test fun customWithAnAppidInItsFilesIsCertainAndOnByItself() {
        appinfo(SteamAppInfo.appinfoFile(app), 1145360)
        val folder = tmp.newFolder("Hades")
        File(folder, "steam_appid.txt").writeText("1145360")
        val exe = File(folder, "Hades.exe").apply { writeText("x") }
        assertEquals(SteamMatch.Certainty.FILES, SteamMatch.resolve(app, folder.path, exe, "Hades").certainty)
        val findings = WinCompSources.forGame(app, game(folder, Library.ADDED, name = "Hades"))
        assertEquals(listOf("vcredist2015_dll" to WinCompSources.Origin.STEAM, "d3dx9" to WinCompSources.Origin.STEAM), auto(findings))
    }

    @Test fun customMatchedByNameIsOnlyRecommended() {
        appinfo(SteamAppInfo.appinfoFile(app), 1145360)
        val folder = tmp.newFolder("Hades Named")
        val exe = File(folder, "Hades.exe").apply { writeText("x") }
        assertEquals(SteamMatch.Certainty.NAME, SteamMatch.resolve(app, folder.path, exe, "Hades") { 1145360 }.certainty)
        val findings = WinCompSources.forGame(app, game(folder, Library.ADDED, name = "Hades"))
        assertTrue(auto(findings).isEmpty())
        assertEquals(listOf("vcredist2015_dll", "d3dx9"), findings.filter { it.origin == WinCompSources.Origin.STEAM_NAME }.map { it.component })
    }

    @Test fun customWithNoMatchHasTheFolderScanOnly() {
        val folder = tmp.newFolder("Unknown")
        File(folder, "_CommonRedist/vcredist/2013").mkdirs()
        File(folder, "_CommonRedist/vcredist/2013/vcredist_x64.exe").writeText("x")
        val findings = WinCompSources.forGame(app, game(folder, Library.ADDED))
        assertTrue(auto(findings).isEmpty())
        assertEquals(listOf("vcredist2013" to WinCompSources.Origin.FOLDER), findings.map { it.component to it.origin })
    }

    // ---- what turns on, and the user's switches ----

    private fun ready(name: String) = WinComponents.Component(name, "", "", "ready", emptyList(),
        listOf(WinComponents.Step("override_dll", JSONObject().put("dll", "x").put("type", "native"))))

    @Test fun onlyAutoFindingsThatInstallHereTurnOn() {
        val catalog = listOf(ready("vcredist2015_dll"), ready("vcredist2015"), ready("d3dx9"),
            WinComponents.Component("physx", "", "", "broken", emptyList(), emptyList())).associateBy { it.name }
        val findings = listOf(
            WinCompSources.Finding("vcredist2015", "MSVC2017", WinCompSources.Origin.GOG, true),
            WinCompSources.Finding("physx", "PhysX", WinCompSources.Origin.GOG, true),
            WinCompSources.Finding("d3dx9", "d3dx9_43.dll", WinCompSources.Origin.FOLDER, false),
        )
        val picks = AutoComponents.autoPicks(findings, catalog)
        // The detector's name installs as its DLL twin; physx cannot install; the folder scan only recommends.
        assertEquals(listOf("vcredist2015_dll"), picks.keys.toList())
        assertEquals("GOG", picks.getValue("vcredist2015_dll").kind)
    }

    @Test fun theUsersSwitchWinsOverAnAutoPickEitherWay() {
        val key = "12345"
        WinComponents.setAuto(app, key, mapOf("d3dx9" to WinComponents.AutoReason("GOG", listOf("DirectX")), "xact" to WinComponents.AutoReason("GOG", listOf("XACT"))))
        assertEquals(listOf("d3dx9", "xact"), WinComponents.picks(app, key))
        WinComponents.setUser(app, key, "d3dx9", false)
        WinComponents.setUser(app, key, "oalinst_dll", true)
        assertEquals(listOf("xact", "oalinst_dll"), WinComponents.picks(app, key))
        // A new automatic list keeps the user's switches.
        WinComponents.setAuto(app, key, mapOf("d3dx9" to WinComponents.AutoReason("GOG", listOf("DirectX"))))
        assertEquals(listOf("oalinst_dll"), WinComponents.picks(app, key))
        assertEquals(mapOf("d3dx9" to false, "oalinst_dll" to true), WinComponents.selection(app, key).user)
        // What the launch reads stays version 1 with the effective list.
        val text = File(app.filesDir, "wincomponents.json").readText()
        assertEquals(1, JSONObject(text).getInt("version"))
        assertEquals("oalinst_dll", JSONObject(text).getJSONObject("games").getJSONArray(key).getString(0))
    }

    @Test fun picksFromBeforeOverridesAreTheUsersOwn() {
        File(app.filesDir, "wincomponents.json").writeText("""{"version":1,"games":{"777":["d3dx9","xact"]}}""")
        assertEquals(mapOf("d3dx9" to true, "xact" to true), WinComponents.selection(app, "777").user)
        assertEquals(listOf("d3dx9", "xact"), WinComponents.picks(app, "777"))
    }

    @Test fun artAndComponentsShareOneSteamMatch() {
        val folder = tmp.newFolder("Shared")
        val exe = File(folder, "Shared.exe").apply { writeText("x") }
        val art = AddedGames.Game(folder, "Shared", exe, "/g/Shared.exe", "/g", 0x81234567L, 0L, emptyList())
        // The art fetch finds it by name ...
        val match = AddedGameArt.steamMatch(app, art) { 4242 }
        assertEquals(4242, match.appId)
        // ... and the components read the same record.
        assertEquals(4242, SteamMatch.get(app, folder.path)!!.appId)
        assertEquals(SteamMatch.Certainty.NAME, SteamMatch.get(app, folder.path)!!.certainty)
        assertFalse(WinCompSources.forGame(app, game(folder, Library.ADDED)).any { it.auto })
    }

    @Test fun aSteamListedComponentNotYetDownloadedTurnsOnWhenItLands() {
        val steamapps = tmp.newFolder("steamapps")
        val folder = File(steamapps, "common/Payback").apply { mkdirs() }
        File(steamapps, "appmanifest_1262580.acf").writeText(
            "\"AppState\"\n{\n\t\"SharedDepots\"\n\t{\n\t\t\"228985\"\t\t\"228980\"\n\t\t\"228988\"\t\t\"228980\"\n\t\t\"228990\"\t\t\"228980\"\n\t}\n}\n",
        )
        val payback = game(folder, Library.SOURCE_STEAM, 1262580, library = "steam", name = "Need for Speed Payback")
        val catalog = listOf(ready("d3dx9"), ready("vcredist2013_dll"), ready("vcredist2019_dll"))
        val installed = mutableSetOf("d3dx9")
        val downloaded = ArrayList<String>()
        AutoComponents.catalogOf = { _, _ -> catalog }
        AutoComponents.installedOf = { installed.toSet() }
        AutoComponents.installer = { _, c, _, _ -> downloaded.add(c.name); installed.add(c.name); null }
        AutoComponents.runNow = true
        try {
            // The user had switched one off: it is never downloaded for an automatic pick.
            WinComponents.setUser(app, "1262580", "vcredist2019_dll", false)
            // The page opens (no downloads on the caller's path): what is there is on now, the rest queued.
            AutoComponents.refresh(app, payback, download = false)
            assertEquals(listOf("vcredist2013_dll"), downloaded)
            val sel = WinComponents.selection(app, "1262580")
            assertEquals(setOf("d3dx9", "vcredist2013_dll", "vcredist2019_dll"), sel.auto.keys)
            assertEquals(setOf("d3dx9", "vcredist2013_dll"), WinComponents.picks(app, "1262580").toSet())
            assertEquals("STEAM", sel.auto.getValue("vcredist2013_dll").kind)
        } finally {
            AutoComponents.runNow = false
            AutoComponents.catalogOf = { c, network -> if (network) WinComponents.fetch(c) else WinComponents.cached(c) }
            AutoComponents.installedOf = { WinComponents.installedIds(it).toSet() }
            AutoComponents.installer = { c, comp, all, progress -> WinComponents.install(c, comp, all, progress) }
        }
    }
}
