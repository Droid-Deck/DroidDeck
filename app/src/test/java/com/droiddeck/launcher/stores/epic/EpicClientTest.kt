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

    private fun added(name: String, source: String, storeId: String?) =
        com.droiddeck.launcher.frontend.AddedGames.Game(File(tmp.root, name), name, File(tmp.root, "$name/$name.exe"),
            "/root/Games/$name/$name.exe", "/root/Games/$name", 3_000_000_000L, 0L, emptyList(), source = source, storeId = storeId)

    private fun inSteamSession(block: (android.app.Application) -> Unit) {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val state = com.droiddeck.launcher.session.SessionState
        state.running = true
        state.stopRequested = false
        val mode = state.mode
        state.mode = com.droiddeck.launcher.session.SessionService.MODE_STEAM
        try {
            block(app)
        } finally {
            state.running = false
            state.mode = mode
        }
    }

    @Test fun aGameFromTheLauncherIsFoundAsItsSteamShortcut() {
        val shortcut = added("Fig", "epic", "Fig")
        val games = listOf(added("Gog", "gog", "Fig"), added("Plain", "added", null), shortcut)
        assertEquals(shortcut, EpicClient.steamShortcut(games, "Fig"))
        assertNull(EpicClient.steamShortcut(games, "Other"))
    }

    @Test fun onlyARunningSteamSessionTakesGamesFromTheLauncher() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals("no-steam", EpicClient.playInSteam(app, "Fig").getString("reason"))
        assertEquals("no-steam", EpicClient.runSteamGame(app, "1091500").getString("reason"))
        val state = com.droiddeck.launcher.session.SessionState
        val mode = state.mode
        state.running = true
        state.mode = com.droiddeck.launcher.session.SessionService.MODE_RUN
        try {
            assertEquals("no-steam", EpicClient.runSteamGame(app, "1091500").getString("reason"))
        } finally {
            state.running = false
            state.mode = mode
        }
        inSteamSession {
            com.droiddeck.launcher.session.SessionState.stopRequested = true
            try {
                assertEquals("no-steam", EpicClient.runSteamGame(it, "1091500").getString("reason"))
            } finally {
                com.droiddeck.launcher.session.SessionState.stopRequested = false
            }
        }
    }

    @Test fun aSteamGameTheLauncherListsIsStartedInTheRunningClient() = inSteamSession { app ->
        assertEquals("bad-id", EpicClient.runSteamGame(app, "0").getString("reason"))
        assertTrue(EpicClient.runSteamGame(app, "1091500").getBoolean("ok"))
        val started = org.robolectric.Shadows.shadowOf(app).nextStartedService
        assertEquals(com.droiddeck.launcher.session.SessionService.ACTION_LAUNCH_GAME, started.action)
        assertEquals("1091500", started.getStringExtra(com.droiddeck.launcher.session.SessionService.EXTRA_GAME_ID))
    }

    @Test fun anEpicGameWithoutAShortcutIsNotHandedToSteam() = inSteamSession { app ->
        assertEquals("not-registered", EpicClient.playInSteam(app, "Missing").getString("reason"))
    }

    @Test fun theLauncherShortcutHasTheIdSteamGivesIt() {
        val crc = java.util.zip.CRC32().apply { update("\"/usr/local/bin/droiddeck-epic-steam\"Epic Games Launcher".toByteArray()) }.value
        assertEquals(crc or 0x80000000L, EpicClient.shortcutAppId)
        assertTrue(EpicClient.shortcutAppId in 0x80000000L..0xFFFFFFFFL)
        assertEquals("12918456320", EpicClient.gameId(3L))
        assertEquals(java.lang.Long.toUnsignedString((EpicClient.shortcutAppId shl 32) or 0x02000000L), EpicClient.gameId(EpicClient.shortcutAppId))
        assertTrue(EpicClient.gameId(EpicClient.shortcutAppId).toBigInteger() > Long.MAX_VALUE.toBigInteger())
    }

    @Test fun theSteamListingCarriesTheLauncherOnlyOnceInstalled() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        assertNull(EpicClient.steamListing(app))
        val listing = com.droiddeck.launcher.frontend.AddedGames.writeListing(app, emptyList(),
            listOf(org.json.JSONObject().put("name", "A").put("native", true), org.json.JSONObject().put("name", "B")))
        val parsed = org.json.JSONArray(listing.readText())
        assertEquals(2, parsed.length())
        assertTrue(parsed.getJSONObject(0).getBoolean("native"))
        assertEquals("B", parsed.getJSONObject(1).getString("name"))
        assertEquals(0, org.json.JSONArray(com.droiddeck.launcher.frontend.AddedGames.writeListing(app, emptyList()).readText()).length())
    }

    @Test fun thePadDrivesTheLauncherOnlyWhileSteamShowsIt() = inSteamSession { app ->
        val root = com.droiddeck.launcher.runtime.LinuxRuntime.sessionRoot(app).apply { mkdirs() }
        val open = File(root, EpicClient.OPEN)
        val focused = File(root, EpicClient.FOCUSED_APP)
        try {
            assertFalse(EpicClient.launcherRunning(app))
            assertFalse(EpicClient.launcherFocused(app))
            open.writeText("1\n")
            assertTrue(EpicClient.launcherRunning(app))
            focused.writeText("769\n")
            assertFalse(EpicClient.launcherFocused(app))
            focused.writeText("${EpicClient.shortcutAppId}\n")
            assertTrue(EpicClient.launcherFocused(app))
            open.writeText("3000000001\n")
            assertFalse(EpicClient.launcherFocused(app))
            focused.writeText("3000000001\n")
            assertTrue(EpicClient.launcherFocused(app))
            com.droiddeck.launcher.session.SessionState.mode = com.droiddeck.launcher.session.SessionService.MODE_RUN
            assertFalse(EpicClient.launcherFocused(app))
            assertFalse(EpicClient.launcherRunning(app))
        } finally {
            open.delete()
            focused.delete()
        }
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

    @Test fun signingOutDropsTheLaunchersSignInAndTellsARunningLauncher() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val auth = File(EpicClient.dataDir(app), "auth/auth.json").apply { parentFile?.mkdirs() }
        val marker = File(EpicClient.stateDir(app), "signed-out")
        val auto = File(EpicClient.stateDir(app), "auto-sign-in").apply { parentFile?.mkdirs(); writeText("1\n") }
        try {
            auth.writeText("""{"prod":{"refresh_token":"t"}}""")
            assertTrue(EpicClient.signOut(app))
            assertFalse(auth.exists())
            assertFalse(auto.exists())
            assertTrue(marker.isFile)
            marker.delete()
            auth.writeText("""{"prod":{"refresh_token":""}}""")
            assertFalse(EpicClient.signOut(app))
            assertFalse(auth.exists())
            assertFalse(marker.exists())
            assertFalse(EpicClient.signOut(app))
        } finally {
            auth.delete(); marker.delete(); auto.delete()
        }
    }

    @Test fun uninstallingRemovesTheProgramButKeepsGamesRecordsAndSignIn() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val state = EpicClient.stateDir(app)
        val runtime = File(state, "runtime/4.0").apply { mkdirs() }
        File(runtime, "files").mkdirs()
        val loop = File(runtime, "files/usr")
        java.nio.file.Files.createSymbolicLink(loop.toPath(), java.nio.file.Paths.get("."))
        val outside = File(app.filesDir, "outside-keep").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(runtime, "files/elsewhere").toPath(), outside.toPath())
        File(outside, "keep.txt").writeText("x")
        File(state, "app/0.97.0").mkdirs()
        File(state, "app/0.97.0/AppRun").writeText("x")
        File(state, "current.json").writeText("""{"app":"0.97.0","runtime":"4.0"}""")
        val imports = File(state, "client-imports.json").apply { writeText("{}") }
        val auth = File(EpicClient.dataDir(app), "auth/auth.json").apply { parentFile?.mkdirs(); writeText("{}") }
        val records = File(EpicClient.dataDir(app), "installations.jsonl").apply { writeText("{}\n") }
        try {
            assertTrue(EpicClient.uninstall(app))
            assertFalse(File(state, "app").exists())
            assertFalse(File(state, "runtime").exists())
            assertFalse(File(state, "current.json").exists())
            assertTrue(imports.isFile)
            assertTrue(auth.isFile)
            assertTrue(records.isFile)
            assertTrue(File(outside, "keep.txt").isFile)
        } finally {
            imports.delete(); auth.delete(); records.delete(); outside.deleteRecursively()
        }
    }

    @Test fun theWebSignInIsExpiredForEveryCookieOnBothPathsAndTheDomain() {
        assertEquals(emptyList<String>(), EpicClient.expiredCookies(null))
        assertEquals(emptyList<String>(), EpicClient.expiredCookies(" ; "))
        val lines = EpicClient.expiredCookies("EPIC_SSO=abc; EPIC_SSO_RM=1; EPIC_SSO=again;__Host-x=y")
        assertEquals(12, lines.size)
        assertTrue(lines.contains("EPIC_SSO=; Max-Age=0; Path=/; Secure"))
        assertTrue(lines.contains("EPIC_SSO_RM=; Max-Age=0; Path=/id; Secure; Domain=.epicgames.com"))
        assertTrue(lines.contains("__Host-x=; Max-Age=0; Path=/; Secure"))
        assertTrue(lines.none { "abc" in it })
        val www = EpicClient.expiredCookies("__cf_bm=1", "www.epicgames.com")
        assertEquals(6, www.size)
        assertTrue(www.contains("__cf_bm=; Max-Age=0; Path=/; Secure; Domain=www.epicgames.com"))
        assertEquals(4, EpicClient.expiredCookies("a=1", "example.com").size)
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

    @Test fun theLauncherWaitsForTheFexToolSteamInstalls() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        com.droiddeck.launcher.session.SessionPrefs.setGameStorage(context, com.droiddeck.launcher.session.SessionPrefs.GAME_STORAGE_OFF, "")
        val steamapps = File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps").apply { mkdirs() }
        assertFalse(EpicClient.fexReady(context))
        EpicClient.requestFex(context)
        assertTrue(File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context), "root/.config/droiddeck/fex-wanted").isFile)
        File(steamapps, "appmanifest_3127680.acf").writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"3127680\"\n\t\"installdir\"\t\t\"FEX-Emu\"\n}\n")
        assertFalse(EpicClient.fexReady(context))
        File(steamapps, "common/FEX-Emu/usr/bin").mkdirs()
        assertFalse(EpicClient.fexReady(context))
        File(steamapps, "common/FEX-Emu/usr/bin/FEXInterpreter").writeText("")
        assertTrue(EpicClient.fexReady(context))
    }

    @Test fun fexOnASecondLibraryIsFoundInItsPrivateToolCopy() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val library = File(context.cacheDir, "card-library").apply { mkdirs() }
        com.droiddeck.launcher.session.SessionPrefs.setGameStorage(context, library.path, "SD Card")
        try {
            val steamapps = File(library, "steamapps").apply { mkdirs() }
            File(steamapps, "appmanifest_3127680.acf").writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"3127680\"\n\t\"installdir\"\t\t\"FEX-Emu\"\n}\n")
            File(steamapps, "common/FEX-Emu").mkdirs()
            assertFalse(EpicClient.fexReady(context))
            val private = com.droiddeck.launcher.session.SecondaryLibrary.privateRoot(context.filesDir, library)
            File(private, "steamapps/common/FEX-Emu/usr/bin").mkdirs()
            File(private, "steamapps/common/FEX-Emu/usr/bin/FEX").writeText("")
            assertTrue(EpicClient.fexReady(context))
        } finally {
            com.droiddeck.launcher.session.SessionPrefs.setGameStorage(context, com.droiddeck.launcher.session.SessionPrefs.GAME_STORAGE_OFF, "")
            library.deleteRecursively()
        }
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
