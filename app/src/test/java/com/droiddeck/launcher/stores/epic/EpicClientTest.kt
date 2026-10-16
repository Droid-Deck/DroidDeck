package com.droiddeck.launcher.stores.epic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class EpicClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun game(appId: Int, source: String) =
        com.droiddeck.launcher.frontend.Library.SteamGame(appId, "g$appId", null, "internal", source = source)

    @Test fun theLauncherSitsRightAboveTheFirstEpicGame() {
        val launcher = game(EpicClient.LAUNCHER_APP_ID, "epic")
        val games = listOf(game(1, "steam"), game(2, "epic"), game(3, "steam"), game(4, "epic"))
        assertEquals(listOf(1, 0, 2, 3, 4), EpicClient.withLauncher(games, launcher).map { it.appId })
        assertEquals(listOf(1, 3, 0), EpicClient.withLauncher(listOf(games[0], games[2]), launcher).map { it.appId })
        assertEquals(listOf(1, 2), EpicClient.withLauncher(games.take(2), null).map { it.appId })
    }

    @Test fun aGameFromTheLauncherIsFoundAsItsSteamShortcut() {
        val shortcut = com.droiddeck.launcher.frontend.Library.SteamGame(9, "Fig", null, "added", 3_000_000_000L, source = "epic", storeId = "Fig")
        val games = listOf(
            com.droiddeck.launcher.frontend.Library.SteamGame(EpicClient.LAUNCHER_APP_ID, "Launcher", null, "epic", source = "epic", storeId = "Fig"),
            com.droiddeck.launcher.frontend.Library.SteamGame(8, "Fig", null, "added", source = "gog", storeId = "Fig"),
            shortcut,
        )
        assertEquals(shortcut, EpicClient.steamShortcut(games, "Fig"))
        assertNull(EpicClient.steamShortcut(games, "Other"))
    }

    @Test fun onlyAnOpenLauncherHandsGamesToSteam() {
        assertEquals("no-launcher", EpicClient.playInSteam(org.robolectric.RuntimeEnvironment.getApplication(), "Fig").getString("reason"))
    }

    @Test fun theLauncherEntryNeedsAnInstalledLauncherAndUsesItsIcon() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        assertNull(EpicClient.launcherEntry(app, "Epic Games Launcher", EpicClient.Status(installed = false, version = "1.0")))
        val icon = File(EpicClient.stateDir(app), "app/1.0/epic-games-launcher.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val entry = EpicClient.launcherEntry(app, "Epic Games Launcher", EpicClient.Status(installed = true, version = "1.0"))!!
        assertEquals(com.droiddeck.launcher.frontend.Library.EPIC_LAUNCHER, entry.library)
        assertEquals("epic", entry.source)
        assertEquals(icon, entry.art)
        assertNull(EpicClient.launcherEntry(app, "x", EpicClient.Status(installed = true, version = "2.0"))!!.art)
    }

    @Test fun onlyAnInstalledLauncherIsUpdatedAutomatically() {
        assertFalse(EpicClient.updateWanted(EpicClient.Status(installed = false), "1.0"))
        assertFalse(EpicClient.updateWanted(EpicClient.Status(installed = false, version = "0.9"), "1.0"))
        assertTrue(EpicClient.updateWanted(EpicClient.Status(installed = true, version = "0.9"), "1.0"))
        assertFalse(EpicClient.updateWanted(EpicClient.Status(installed = true, version = "1.0"), "1.0"))
        assertFalse(EpicClient.updateWanted(EpicClient.Status(installed = true, version = "0.9"), null))
    }

    @Test fun anAutomaticSignInSkipsTheAccountPicker() {
        val url = "https://www.epicgames.com/id/authorize?client_id=c85f&prompt=select_account&redirect_uri=http%3A%2F%2F127.0.0.1%3A4%2Fa"
        assertEquals("https://www.epicgames.com/id/authorize?client_id=c85f&redirect_uri=http%3A%2F%2F127.0.0.1%3A4%2Fa", EpicClient.withoutAccountPrompt(url))
        assertEquals("https://www.epicgames.com/id/authorize", EpicClient.withoutAccountPrompt("https://www.epicgames.com/id/authorize?prompt=select_account"))
        val other = "https://www.epicgames.com/id/authorize?prompt=login&x=prompt%3Dselect_account"
        assertEquals(other, EpicClient.withoutAccountPrompt(other))
        assertEquals("https://www.epicgames.com/id/authorize", EpicClient.withoutAccountPrompt("https://www.epicgames.com/id/authorize"))
    }

    @Test fun noDroidDeckAccountMeansNoHandOff() {
        assertFalse(EpicClient.canHandOff(org.robolectric.RuntimeEnvironment.getApplication()))
    }

    @Test fun launcherVersionComesFromTheRedirect() {
        val location = "https://content-download-egs.distro.on.epicgames.com/Builds/EGLv2/0.95.2%2Bgfa9ad49/Linux/EpicGamesLauncher-x86_64.AppImage?launcherfilename=EpicGamesLauncher-x86_64.AppImage"
        assertEquals("0.95.2+gfa9ad49", EpicClientSources.launcherVersion(location))
        assertNull(EpicClientSources.launcherVersion("http://content-download-egs.distro.on.epicgames.com/Builds/EGLv2/1.0/Linux/a"))
        assertNull(EpicClientSources.launcherVersion("https://example.com/Builds/EGLv2/..%2F..%2Fetc/Linux/a"))
        assertNull(EpicClientSources.launcherVersion("https://example.com/other/path"))
    }

    @Test fun runtimeChecksumIsReadFromTheSumsFile() {
        val sums = """
            0f3e16b00ed7256db38733d50995b82484ef730167cbf5693e19634dcb04f39a *com.valvesoftware.SteamRuntime.Platform-amd64,i386-steamrt4-runtime-debug.tar.gz
            A0F3E16B00ED7256DB38733D50995B82484EF730167CBF5693E19634DCB04F39 *com.valvesoftware.SteamRuntime.Platform-amd64,i386-steamrt4-runtime.tar.gz
        """.trimIndent()
        assertEquals("a0f3e16b00ed7256db38733d50995b82484ef730167cbf5693e19634dcb04f39", EpicClientSources.sha256For(sums, EpicClientSources.RUNTIME_FILE))
        assertNull(EpicClientSources.sha256For(sums, "missing.tar.gz"))
    }

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test fun multipartEtagIsChecked() {
        val part = 8 shl 20
        val data = ByteArray(part * 2 + 123) { (it * 31).toByte() }
        val file = File(tmp.root, "image").apply { writeBytes(data) }
        val digest = hex(md5(md5(data.copyOfRange(0, part)) + md5(data.copyOfRange(part, 2 * part)) + md5(data.copyOfRange(2 * part, data.size))))
        assertEquals(true, EpicClientSources.etagMatches(file, "\"$digest-3\""))
        assertEquals(false, EpicClientSources.etagMatches(file, "\"${"0".repeat(32)}-3\""))
        assertNull(EpicClientSources.etagMatches(file, "\"$digest-99\""))
        assertNull(EpicClientSources.etagMatches(file, "W/\"$digest-3\""))
        assertEquals(true, EpicClientSources.etagMatches(file, hex(md5(data))))
    }

    @Test fun appImageMagicIsRequired() {
        val good = File(tmp.root, "a").apply { writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0, 'A'.code.toByte(), 'I'.code.toByte(), 2, 0)) }
        val bad = File(tmp.root, "b").apply { writeBytes(ByteArray(16)) }
        assertTrue(EpicClientSources.isAppImage(good))
        assertFalse(EpicClientSources.isAppImage(bad))
    }

    @Test fun signInUrlsMustRedirectToLoopback() {
        val good = "https://www.epicgames.com/id/authorize?client_id=c85f4c6238b04d3399bc0080e83b83fd&response_type=code&redirect_uri=http%3A%2F%2F127.0.0.1%3A41234%2Flauncher%2Fauthorized&state=x"
        assertEquals("http://127.0.0.1:41234/launcher/authorized", EpicClient.redirectOf(good))
        assertNull(EpicClient.redirectOf(good.replace("www.epicgames.com", "evil.example")))
        assertNull(EpicClient.redirectOf(good.replace("https://", "http://")))
        assertNull(EpicClient.redirectOf(good.replace("127.0.0.1%3A41234", "example.com%3A80")))
        assertNull(EpicClient.redirectOf(good.replace("%3A41234", "")))
        assertFalse(EpicClient.loopback("http://user@127.0.0.1:1/x"))
        assertTrue(EpicClient.loopback("http://localhost:5/launcher/authorized?code=1"))
    }

    @Test fun itemsDescribeTheInstallForTheLauncher() {
        val install = EpicClientLibrary.Install(
            "Fig", "ns1", "item1", "Fig Game", "1.0.5", "Binaries/Win64/Fig.exe", "/root/Games/Stores/Epic/Fig Game",
            File(tmp.root, "Fig Game"), "dep1", 1234L,
        )
        val item = EpicClientLibrary.item(install, listOf("en"), ownership = true)
        val guid = EpicClientLibrary.guid("Fig")
        assertEquals(32, guid.length)
        assertEquals(guid, EpicClientLibrary.guid("Fig"))
        assertTrue(guid.all { it in "0123456789ABCDEF" })
        assertEquals(guid, item.getString("InstallationGuid"))
        assertEquals("/root/Games/Stores/Epic/Fig Game", item.getString("InstallLocation"))
        assertEquals("/root/Games/Stores/Epic/Fig Game/.egstore", item.getString("ManifestLocation"))
        assertEquals("Binaries/Win64/Fig.exe", item.getString("LaunchExecutable"))
        assertEquals("Fig", item.getString("AppName"))
        assertEquals("Fig", item.getString("MainGameAppName"))
        assertEquals("ns1", item.getString("CatalogNamespace"))
        assertEquals("item1", item.getString("CatalogItemId"))
        assertEquals("1.0.5", item.getString("AppVersionString"))
        assertEquals("true", item.getString("OwnershipToken"))
        assertEquals("dep1", item.getString("SidecarDeploymentId"))
        assertEquals("Fig Game", item.getString("MandatoryAppFolderName"))
        assertFalse(item.getBoolean("bIsIncompleteInstall"))
        val list = EpicClientLibrary.aggregateFromManifests(File(tmp.root, "none"), listOf(install)).getJSONArray("InstallationList")
        assertEquals(1, list.length())
        assertEquals("Fig", list.getJSONObject(0).getString("ArtifactId"))
        assertEquals("ns1", list.getJSONObject(0).getString("NamespaceId"))
        assertEquals("item1", list.getJSONObject(0).getString("ItemId"))
    }

    @Test fun clientManifestsRemainInTheAggregateAndSupplyDroidDeckShortcuts() {
        val manifests = File(tmp.root, "Manifests").apply { mkdirs() }
        File(manifests, "client.item").writeText(
            """{"AppName":"ClientGame","CatalogNamespace":"ns2","CatalogItemId":"item2","InstallLocation":"/root/Games/ClientGame","LaunchExecutable":"Binaries/ClientGame.exe","AppVersionString":"2.0","DisplayName":"Client Game","bIsIncompleteInstall":false}""",
        )
        File(manifests, "partial.item").writeText(
            """{"AppName":"Partial","CatalogNamespace":"ns","CatalogItemId":"partial","InstallLocation":"/root/partial","bIsIncompleteInstall":true}""",
        )
        val managed = EpicClientLibrary.Install(
            "RustGame", "ns1", "item1", "Rust Game", "1.0", "Game.exe", "/root/Games/RustGame", File(tmp.root, "RustGame"), "dep", 10,
        )
        val old = org.json.JSONObject().put("InstallationList", org.json.JSONArray().put(
            org.json.JSONObject().put("AppName", "InstallingGame").put("ArtifactId", "InstallingGame").put("InstallLocation", "/root/Games/InstallingGame"),
        ))
        val list = EpicClientLibrary.aggregateFromManifests(manifests, listOf(managed), old).getJSONArray("InstallationList")
        assertEquals(3, list.length())
        val names = (0 until list.length()).map { list.getJSONObject(it).getString("AppName") }
        assertTrue("ClientGame" in names)
        assertTrue("RustGame" in names)
        assertTrue("InstallingGame" in names)
        assertEquals("Binaries/ClientGame.exe", EpicClientLibrary.executableRelative("/root/Games/ClientGame", "Binaries/ClientGame.exe"))
        assertNull(EpicClientLibrary.executableRelative("/root/Games/ClientGame", "../outside.exe"))
        assertNull(EpicClientLibrary.executableRelative("/root/Games/ClientGame", "C:/outside.exe"))
    }

    @Test fun gamesInstalledByTheClientBecomeEpicLibraryShortcuts() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val game = File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context), "root/Games/ClientGame")
        File(game, "Binaries").mkdirs()
        File(game, "Binaries/ClientGame.exe").writeBytes(byteArrayOf(1))
        val manifests = EpicClientLibrary.manifestsDir(context).apply { mkdirs() }
        File(manifests, "client.item").writeText(
            """{"AppName":"ClientGame","CatalogNamespace":"ns2","CatalogItemId":"item2","InstallLocation":"/root/Games/ClientGame","LaunchExecutable":"Binaries/ClientGame.exe","AppVersionString":"2.0","DisplayName":"Client Game","bIsIncompleteInstall":false}""",
        )

        assertTrue(EpicClientLibrary.importClientGames(context))
        val sidecar = com.droiddeck.launcher.stores.StoreGameSidecar.read(game)!!
        assertEquals(com.droiddeck.launcher.stores.Store.EPIC, sidecar.store)
        assertEquals("ClientGame", sidecar.id)
        assertEquals("Binaries/ClientGame.exe", sidecar.exe)
        assertEquals("true", sidecar.extra["clientManaged"])
        assertTrue(File(game, sidecar.launcher!!).isFile)
        assertEquals(File(game, "Binaries/ClientGame.exe").path,
            com.droiddeck.launcher.frontend.AddedExes.list(context).first { it.folder == game.path }.exe)
    }

    @Test fun gamesTheLauncherInstallsItselfAreImportedFromItsInstallationList() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val game = File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context), "root/Games/Stores/Epic/Luftrausers51924")
        File(game, "bin").mkdirs()
        File(game, "bin/Luftrausers.exe").writeBytes(byteArrayOf(1))
        val list = EpicClientLibrary.installationsFile(context).apply { parentFile!!.mkdirs() }
        list.writeText(listOf(
            """{"version":1,"entries":2}""",
            """{"installationId":"F00B","complete":false,"dir":"/root/Games/Stores/Epic/Luftrausers51924","platform":"Windows","namespace":"ns","itemId":"it","artifactId":"luft","launchExecutable":"bin/Luftrausers.exe"}""",
            """{"installationId":"F00B","complete":true,"dir":"/root/Games/Stores/Epic/Luftrausers51924","platform":"Windows","namespace":"ns","itemId":"it","artifactId":"luft","version":"1.0","sidecar":{"deploymentId":"dep"},"launchExecutable":"bin/Luftrausers.exe"}""",
        ).joinToString("\n"))

        assertTrue(EpicClientLibrary.importClientGames(context))
        val sidecar = com.droiddeck.launcher.stores.StoreGameSidecar.read(game)!!
        assertEquals(listOf("luft", "Luftrausers51924", "bin/Luftrausers.exe", "1.0", "dep", "true"),
            listOf(sidecar.id, sidecar.title, sidecar.exe, sidecar.installVersion, sidecar.extra["deploymentId"], sidecar.extra["clientManaged"]))
        assertTrue(com.droiddeck.launcher.frontend.AddedGames.scan(context).any { it.folder.path == game.path && it.source == "epic" })
        assertFalse(EpicClientLibrary.importClientGames(context))

        list.appendText("\n" + """{"installationId":"F00B","deleted":true}""")
        assertTrue(EpicClientLibrary.importClientGames(context))
        assertNull(com.droiddeck.launcher.stores.StoreGameSidecar.read(game))
        assertTrue(com.droiddeck.launcher.frontend.AddedExes.list(context).none { it.folder == game.path })
    }

    @Test fun anUnfinishedOrLinuxInstallIsNotImported() {
        val (records, readable) = EpicClientLibrary.clientInstallations(tmp.newFile().apply {
            writeText("""{"installationId":"a","complete":true,"platform":"Linux","artifactId":"x","dir":"/root/Games/X"}""" + "\nnot json\n")
        })
        assertFalse(readable)
        assertTrue(EpicClientLibrary.manifestOf(records.single(), null).getBoolean("bIsIncompleteInstall"))
        assertEquals("X", EpicClientLibrary.manifestOf(records.single(), " ").getString("DisplayName"))
        assertEquals(emptyList<org.json.JSONObject>() to true, EpicClientLibrary.clientInstallations(File(tmp.root, "missing")))
    }

    @Test fun theLauncherInstallsWhereDroidDeckInstallsEpicGames() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val settings = File(EpicClient.dataDir(context), "settings/app-settings.json").apply { parentFile!!.mkdirs() }
        settings.writeText("""{"defaultInstallDir":"/root/Games","verboseLogs":false}""")
        assertEquals("/root/Games/Stores/Epic", EpicClient.prepareInstallDir(context))
        val written = org.json.JSONObject(settings.readText())
        assertEquals("/root/Games/Stores/Epic", written.getString("defaultInstallDir"))
        assertFalse(written.getBoolean("verboseLogs"))
        assertTrue(File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context), "root/Games/Stores/Epic").isDirectory)
        settings.writeText("""{"defaultInstallDir":"/root/MyGames"}""")
        assertEquals("/root/MyGames", EpicClient.prepareInstallDir(context))
        assertEquals("/root/MyGames", org.json.JSONObject(settings.readText()).getString("defaultInstallDir"))
        settings.writeText("""{"defaultInstallDir":"/mnt/droiddeck-sd/Stores/Epic"}""")
        assertEquals("/root/Games/Stores/Epic", EpicClient.prepareInstallDir(context))
    }
}
