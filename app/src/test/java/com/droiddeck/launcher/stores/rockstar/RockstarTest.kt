package com.droiddeck.launcher.stores.rockstar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class RockstarTest {
    @Test fun sdkTicketExportsOnlyTheShortLivedServerResponse() {
        val root = RockstarRos.parse("""<Response><Status>1</Status><Result><Ticket>opaque-game-ticket</Ticket><PlayerAccountId>456</PlayerAccountId><SessionKey>session-key</SessionKey><SessionTicket>session-ticket</SessionTicket><Services Count="1"><Service Name="game"><Hosts Count="1"><Host>example.invalid</Host></Hosts></Service><ScAuthToken>service-refresh-secret</ScAuthToken></Services><ScAuthToken>refresh-secret</ScAuthToken><RockstarAccount><RockstarId>123</RockstarId><Nickname>Player</Nickname><Email>player@example.invalid</Email><ScAuthToken>nested-refresh-secret</ScAuthToken></RockstarAccount></Result></Response>""")
        val sdk = RockstarRos.parse(RockstarAuth.sdkTicket(root))
        assertEquals("opaque-game-ticket", RockstarRos.text(sdk, "Ticket"))
        assertEquals("session-key", RockstarRos.text(sdk, "SessionKey"))
        assertEquals("123", RockstarRos.text(sdk, "RockstarId"))
        assertEquals("456", RockstarRos.text(sdk, "PlayerAccountId"))
        assertEquals("1", RockstarRos.first(sdk, "Services")?.getAttribute("Count"))
        assertEquals("example.invalid", RockstarRos.text(sdk, "Host"))
        assertNull(RockstarRos.first(sdk, "ScAuthToken"))
        assertFalse(RockstarAuth.sdkTicket(root).contains("refresh-secret"))
    }

    @Test fun sdkTitleStateRequiresAnOwnedPackageInsteadOfCatalogVisibility() {
        val visible = RockstarApp("1", "future_title", "123", emptyList())
        val featured = RockstarAccountApps(listOf(visible), listOf(RockstarPackage("future_title", true)))
        assertFalse(RockstarLibrary.owns(featured, 123, "future_title"))
        val owned = RockstarAccountApps(listOf(visible), listOf(RockstarPackage("future_title", false)))
        assertTrue(RockstarLibrary.owns(owned, 123, "future_title"))
        assertFalse(RockstarLibrary.owns(owned, 124, "other_title"))
    }

    @Test fun directLaunchPreservesArgumentsThroughTheWindowsBatchInterpreter() {
        org.junit.Assume.assumeTrue(java.io.File.separatorChar == '\\')
        val root = java.nio.file.Files.createTempDirectory("rockstar-batch").toFile()
        try {
            java.io.File(System.getenv("SystemRoot"), "System32/cscript.exe").copyTo(java.io.File(root, "Game.exe"))
            java.io.File(root, "probe.vbs").writeText("""
                Set output = CreateObject("Scripting.FileSystemObject").CreateTextFile("args.txt", True)
                For Each arg In WScript.Arguments
                    output.WriteLine(arg)
                Next
                output.Close
            """.trimIndent())
            val args = listOf("", "&literal", "one argument", "%PATH%", "!PATH!", "^&|<>()")
            val game = com.droiddeck.launcher.stores.StoreGameSidecar(com.droiddeck.launcher.stores.Store.ROCKSTAR,
                "future_title", "Game", "Game.exe", args = listOf("//nologo", "probe.vbs") + args)
            com.droiddeck.launcher.stores.StoreLaunch.writeLauncher(root, game)
            val process = ProcessBuilder("cmd.exe", "/d", "/c", java.io.File(root, com.droiddeck.launcher.stores.StoreLaunch.LAUNCHER).path)
                .directory(root).redirectErrorStream(true).start()
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
            assertEquals(args, java.io.File(root, "args.txt").readLines())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun launcherAdoptionDoesNotFollowSymbolicLinks() {
        val folder = java.nio.file.Files.createTempDirectory("rockstar-launch-link").toFile()
        val outside = java.io.File.createTempFile("rockstar-keep", ".txt").apply { writeText("keep") }
        val link = java.io.File(folder, com.droiddeck.launcher.stores.StoreLaunch.LAUNCHER)
        try {
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            } catch (e: java.nio.file.FileSystemException) {
                org.junit.Assume.assumeNoException(e)
            }
            java.io.File(folder, "gta3.exe").writeText("game")
            val game = com.droiddeck.launcher.stores.StoreGameSidecar(com.droiddeck.launcher.stores.Store.ROCKSTAR, "gta3", "GTA III", "gta3.exe", launcher = com.droiddeck.launcher.stores.StoreLaunch.LAUNCHER)
            assertNull(com.droiddeck.launcher.stores.StoreLaunch.adopt(folder, game).launcher)
            assertNull(com.droiddeck.launcher.stores.StoreLaunch.writeLauncher(folder, game).launcher)
            assertEquals("keep", outside.readText())
            assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
        } finally {
            java.nio.file.Files.deleteIfExists(link.toPath())
            folder.deleteRecursively()
            outside.delete()
        }
    }

    @Test fun resumeHashingStopsWhenCancelled() {
        val file = java.io.File.createTempFile("rockstar-hash", ".bin")
        try {
            val bytes = ByteArray(1024 * 1024)
            file.writeBytes(bytes)
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val chunk = RockstarChunk("1", "1", hash, 0, bytes.size.toLong())
            var checks = 0
            assertFalse(RockstarManifest.verified(file, chunk) { ++checks >= 3 })
            assertEquals(3, checks)
            assertTrue(RockstarManifest.verified(file, chunk))
        } finally {
            file.delete()
        }
    }

    @Test fun otherRockstarGamesRegisterAndLaunchTheirInstalledExecutable() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        com.droiddeck.launcher.session.SessionPrefs.setGameStorage(app, com.droiddeck.launcher.session.SessionPrefs.GAME_STORAGE_OFF, "")
        val folder = java.io.File(com.droiddeck.launcher.stores.StoreInstallRoot.storeDir(com.droiddeck.launcher.stores.StoreInstallRoot.installRoot(app), com.droiddeck.launcher.stores.Store.ROCKSTAR), "RDR2").apply { mkdirs() }
        try {
            val game = com.droiddeck.launcher.stores.StoreGameSidecar(com.droiddeck.launcher.stores.Store.ROCKSTAR, "rdr2", "RDR2", "RDR2.exe")
            java.io.File(folder, game.exe).writeText("installed game")
            val script = java.io.File(folder, com.droiddeck.launcher.stores.StoreLaunch.LAUNCHER)
            script.writeText("old launcher")
            val finished = com.droiddeck.launcher.stores.StoreLaunch.writeLauncher(folder, game)
            finished.write(folder)
            assertFalse(RockstarLaunch.usesClassicLauncher(game.id))
            assertEquals(com.droiddeck.launcher.stores.StoreLaunch.LAUNCHER, finished.launcher)
            assertTrue(script.readText().contains("\"%~dp0RDR2.exe\""))
            assertTrue(script.readText().contains("if not exist \"C:\\Program Files\\Rockstar Games\\Launcher\\launch.json\" goto direct"))
            assertTrue(java.io.File(folder, game.exe).isFile)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun otherRockstarGamesCanRegisterShortcuts() {
        org.junit.Assume.assumeTrue(java.io.File.separatorChar == '/')
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        com.droiddeck.launcher.session.SessionPrefs.setGameStorage(app, com.droiddeck.launcher.session.SessionPrefs.GAME_STORAGE_OFF, "")
        val folder = java.io.File(com.droiddeck.launcher.stores.StoreInstallRoot.storeDir(com.droiddeck.launcher.stores.StoreInstallRoot.installRoot(app), com.droiddeck.launcher.stores.Store.ROCKSTAR), "RDR2").apply { mkdirs() }
        try {
            java.io.File(folder, "RDR2.exe").writeText("installed game")
            com.droiddeck.launcher.stores.StoreGameSidecar(com.droiddeck.launcher.stores.Store.ROCKSTAR, "rdr2", "RDR2", "RDR2.exe").write(folder)
            val game = com.droiddeck.launcher.frontend.AddedGames.single(app, folder.path)
            assertTrue(game != null)
            assertTrue(game!!.guestExe.endsWith("/" + com.droiddeck.launcher.stores.StoreLaunch.LAUNCHER))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun existingRockstarLaunchScriptsAreRefreshed() {
        val folder = java.nio.file.Files.createTempDirectory("rockstar-launch").toFile()
        try {
            java.io.File(folder, "gta3.exe").writeBytes(byteArrayOf(1))
            val game = com.droiddeck.launcher.stores.StoreGameSidecar(com.droiddeck.launcher.stores.Store.ROCKSTAR, "gta3", "GTA III", "gta3.exe", launcher = com.droiddeck.launcher.stores.StoreLaunch.LAUNCHER)
            val script = java.io.File(folder, com.droiddeck.launcher.stores.StoreLaunch.LAUNCHER)
            script.writeText("old launch script")
            com.droiddeck.launcher.stores.StoreLaunch.adopt(folder, game)
            assertTrue(script.readText().contains("Rockstar Games\\Launcher\\Launcher.exe"))
            assertFalse(script.readText().contains("old launch script"))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    @Config(sdk = [28])
    fun classicLaunchDefaultsAreSetBeforeWineAndKeepExplicitSettings() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val explicit = com.droiddeck.launcher.core.GameEnvironment.Config(games = mapOf(
            "100" to mapOf("FEX_X87REDUCEDPRECISION" to "1"),
            "104" to mapOf("PROTON_USE_WINED3D" to "0"),
        ))
        com.droiddeck.launcher.session.GameEnvironmentStore.save(app, explicit)
        RockstarLaunch.applyDefaults(app, "gta3", 100)
        RockstarLaunch.applyDefaults(app, "gta3", 101)
        RockstarLaunch.applyDefaults(app, "gtasa", 102)
        RockstarLaunch.applyDefaults(app, "gtavc", 103)
        RockstarLaunch.applyDefaults(app, "gtasa", 104)
        val config = com.droiddeck.launcher.session.GameEnvironmentStore.read(app)
        assertEquals("1", config.games["100"]?.get("FEX_X87REDUCEDPRECISION"))
        assertEquals("0", config.games["101"]?.get("FEX_X87REDUCEDPRECISION"))
        assertEquals(mapOf("PROTON_USE_WINED3D" to "1"), config.games["102"])
        assertEquals("0", config.games["104"]?.get("PROTON_USE_WINED3D"))
        assertFalse(config.games.containsKey("103"))
    }

    @Test fun classicTitlesUseTheirObservedPipeNames() {
        assertEquals(mapOf("gta3" to "GTA3_Pipe", "gtavc" to "GTA-VC_Pipe", "gtasa" to "GTA_SA_Pipe", "gta4" to "GTAIV_Pipe", "lanoire" to "LANOIRE_Pipe"), RockstarLaunch.pipes)
        for (id in RockstarLaunch.pipes.keys) assertTrue(RockstarLaunch.usesClassicLauncher(id))
    }

    @Test fun signingOutClearsOwnedShelfCards() {
        val store = com.droiddeck.launcher.stores.Store.ROCKSTAR
        com.droiddeck.launcher.stores.StoresState.library[store] = emptyList()
        com.droiddeck.launcher.stores.StoresState.shelves[store] = com.droiddeck.launcher.stores.StoreShelves()
        RockstarLibrary.reset()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse(com.droiddeck.launcher.stores.StoresState.library.containsKey(store))
        assertFalse(com.droiddeck.launcher.stores.StoresState.shelves.containsKey(store))
    }

    @Test fun resumedChunksMustStillMatchTheirFiles() {
        val folder = java.nio.file.Files.createTempDirectory("rockstar-resume").toFile()
        try {
            val file = java.io.File(folder, "game.bin")
            val bytes = "verified chunk".toByteArray()
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val chunk = RockstarChunk("1", "2", hash, 2, bytes.size.toLong())
            assertFalse(RockstarManifest.verified(file, chunk))
            file.writeBytes(byteArrayOf(0, 0) + bytes)
            assertTrue(RockstarManifest.verified(file, chunk))
            java.io.RandomAccessFile(file, "rw").use { it.setLength(4) }
            assertFalse(RockstarManifest.verified(file, chunk))
            file.writeBytes(ByteArray(bytes.size + 2))
            assertFalse(RockstarManifest.verified(file, chunk))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun manifestWritesRejectSymbolicLinks() {
        val folder = java.nio.file.Files.createTempDirectory("rockstar-path").toFile()
        val outside = java.nio.file.Files.createTempDirectory("rockstar-outside").toFile()
        try {
            val link = java.io.File(folder, "linked")
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            } catch (e: java.nio.file.FileSystemException) {
                org.junit.Assume.assumeNoException(e)
            }
            assertTrue(runCatching { RockstarManifest.inside(folder, "linked/game.bin") }.isFailure)
            assertEquals(java.io.File(folder.canonicalFile, "normal/game.bin"), RockstarManifest.inside(folder, "normal/game.bin"))
            java.nio.file.Files.deleteIfExists(link.toPath())
        } finally {
            folder.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun rockstarLaunchScriptsContainNoCredentials() {
        val sidecar = com.droiddeck.launcher.stores.StoreGameSidecar(
            com.droiddeck.launcher.stores.Store.ROCKSTAR, "gtavc", "Vice City", "gta-vc.exe",
        )
        val text = com.droiddeck.launcher.stores.StoreLaunch.launcherText(sidecar)
        assertTrue(text.contains("\"C:\\Program Files\\Rockstar Games\\Launcher\\Launcher.exe\""))
        assertTrue(text.contains("exit /b %errorlevel%"))
        assertTrue(text.contains("goto direct"))
        assertTrue(text.contains("\"%~dp0gta-vc.exe\""))
        assertFalse(text.contains("ticket", true))
        assertFalse(text.contains("rem ", true))
    }

    private val apps = """<?xml version="1.0" encoding="utf-8"?>
<Response xmlns="GetAppsAndPackages"><Status>1</Status><Result><Apps>
  <App Id="2" Name="launcher" TitleId="21" IsReleased="true"><Branches>
    <Branch Id="4" Name="default" BuildId="1311" IsDefault="true" AppId="2"><AccessToken>BRANCHACCESS token="L"</AccessToken></Branch>
  </Branches></App>
  <App Id="3" Name="gta5" TitleId="11" IsReleased="true"><Branches /></App>
  <App Id="10" Name="rdr2" TitleId="13" IsReleased="true"><Branches /></App>
  <App Id="15" Name="rdr2_sp_steam" TitleId="13" IsReleased="false"><Branches>
    <Branch Id="23" Name="default" BuildId="-1" IsDefault="true" AppId="15"><AccessToken>BRANCHACCESS token="S"</AccessToken></Branch>
  </Branches></App>
  <App Id="11" Name="gta4" TitleId="1" IsReleased="true"><Branches>
    <Branch Id="30" Name="beta" BuildId="7" IsDefault="false" AppId="11"><AccessToken>BRANCHACCESS token="B"</AccessToken></Branch>
    <Branch Id="31" Name="default" BuildId="8" IsDefault="true" AppId="11"><AccessToken>BRANCHACCESS token="D"</AccessToken></Branch>
  </Branches></App>
  <App Id="20" Name="gtatrilogy" TitleId="18" IsReleased="true"><Branches /></App>
</Apps><RglUiPackages>
  <Package><PackageJson>{"title":"gta4","isFeatured":false,"packageType":"title"}</PackageJson></Package>
  <Package><PackageJson>{"title":"rdr2","isFeatured":false,"packageType":"title"}</PackageJson></Package>
  <Package><PackageJson>{"title":"gtatrilogy","isFeatured":true,"packageType":"bundle"}</PackageJson></Package>
  <Package><PackageJson>{"title":"gta5","isFeatured":false,"packageType":"title"}</PackageJson></Package>
</RglUiPackages></Result></Response>"""

    private fun account(xml: String = apps) = RockstarRos.parse(xml).let { RockstarAccountApps(RockstarApps.parse(it), RockstarApps.packages(it)) }

    private val manifest = """<?xml version="1.0" encoding="utf-8"?>
<Response xmlns="GetBuildManifestFull"><Status>1</Status>
  <Result BuildId="1311" VersionNumber="1.0.110.3150"><FileManifest>
    <FileDetails FileEntryId="973" FileEntryVersionId="25837" FileSize="30">
      <RelativePath>Launcher.exe</RelativePath>
      <FileChunks><Chunk FileChunkId="46859" SHA256Hash="AB" StartByteOffset="0" Size="20" /><Chunk FileChunkId="46860" SHA256Hash="cd" StartByteOffset="20" Size="10" /></FileChunks>
      <SHA256Hash>FF</SHA256Hash>
    </FileDetails>
    <FileDetails FileEntryId="981" FileEntryVersionId="2" FileSize="4">
      <RelativePath>ThirdParty\Steam\steam_api64.dll</RelativePath>
      <FileChunks><Chunk FileChunkId="1" SHA256Hash="ee" StartByteOffset="0" Size="4" /></FileChunks>
      <SHA256Hash>ee</SHA256Hash>
    </FileDetails>
  </FileManifest><IsPreload>false</IsPreload></Result></Response>"""

    @Test fun branchesNeedARealBuild() {
        val parsed = account().apps
        val gta4 = parsed.first { it.name == "gta4" }
        assertEquals("31", RockstarApps.branch(gta4)!!.id)
        assertNull(RockstarApps.branch(parsed.first { it.name == "rdr2_sp_steam" }))
        assertNull(RockstarApps.branch(parsed.first { it.name == "gta5" }))
    }

    @Test fun ownedTitlesAreTheAccountsOwnPackagesNotTheFeaturedOnes() {
        assertEquals(listOf("gta4", "rdr2", "gta5"), RockstarLibrary.owned(account()).map { it.id })
    }

    @Test fun libraryListsOnlyOwnedTitlesRockstarDelivers() {
        val games = RockstarLibrary.games(account())
        assertEquals(listOf("gta4"), games.map { it.id })
        assertEquals("gta4", games[0].extra["app"])
        assertTrue(games.all { it.owned })
    }

    @Test fun anOwnedTrilogyBundleIsAllThreeDefinitiveEditions() {
        val owned = RockstarLibrary.owned(account(apps.replace("\"isFeatured\":true,\"packageType\":\"bundle\"", "\"isFeatured\":false,\"packageType\":\"bundle\"")))
        assertTrue(owned.map { it.id }.containsAll(listOf("gta3unreal", "gtavcunreal", "gtasaunreal")))
    }

    @Test fun manifestFilesCarryTheirChunksAndWindowsPathsBecomeRelative() {
        val build = RockstarManifest.parse(RockstarRos.parse(manifest))
        assertEquals("1311", build.buildId)
        assertFalse(build.preload)
        assertEquals(34L, build.bytes)
        val exe = build.files[0]
        assertEquals(listOf("46859", "46860"), exe.chunks.map { it.chunkId })
        assertEquals("ab", exe.chunks[0].sha256)
        assertEquals("ff", exe.sha256)
        assertEquals("ThirdParty/Steam/steam_api64.dll", build.files[1].path)
    }

    @Test fun pathsThatLeaveTheInstallAreRefused() {
        assertNull(RockstarManifest.safePath("..\\..\\Windows\\x.dll"))
        assertNull(RockstarManifest.safePath("C:\\x.dll"))
        assertEquals("a/b.rpf", RockstarManifest.safePath("\\a\\.\\b.rpf"))
    }

    @Test fun urlBatchesStayWithinTheirByteAndCountLimits() {
        val chunks = (1..5).map { RockstarChunk("1", "$it", "", 0, if (it == 3) 100 else 10) }
        val batches = RockstarManifest.batches(chunks, maxBytes = 30, maxCount = 2)
        assertEquals(listOf(listOf("1", "2"), listOf("3"), listOf("4", "5")), batches.map { b -> b.map { it.chunkId } })
    }

    @Test fun rosErrorsNameTheirCode() {
        val root = RockstarRos.parse("<Response><Status>0</Status><Error Code=\"AuthenticationFailed\" CodeEx=\"Ticket\"/></Response>")
        assertEquals("AuthenticationFailed", RockstarRos.first(root, "Error")!!.getAttribute("Code"))
    }

    @Test fun ticketAnswerGivesTheAccountAndExpiry() {
        val root = RockstarRos.parse("""<Response xmlns="CreateTicketResponse"><Status>1</Status><Ticket>T</Ticket><SessionKey>K</SessionKey>
            <SessionTicket>S</SessionTicket><SecsUntilExpiration>86399</SecsUntilExpiration>
            <RockstarAccount><RockstarId>42</RockstarId><Nickname>player</Nickname></RockstarAccount></Response>""")
        val t = RockstarAuth.parseTicket(root, now = 1000L)!!
        assertEquals("T", t.ticket)
        assertEquals("42", t.rockstarId)
        assertEquals("player", t.nickname)
        assertEquals(1000L + 86_399_000L, t.expiresAt)
    }

    @Test fun gatewayStateIsWrittenAsThePageWritesIt() {
        val fp = RockstarAuth.fingerprintOf("DROIDDECK-DUO", "0a0b0c0d", "0011223344556677")
        assertEquals("{\"fp\": {\"device_name\":\"DROIDDECK-DUO\",\"machine_name\":\"DROIDDECK-DUO\",\"os_version\":\"10.0.19045\",\"volume_serial\":\"0a0b0c0d\",\"cpu_info\":\"0011223344556677\"}}",
            RockstarAuth.state(fp))
    }

    @Test fun bridgeAnswersTheLauncherQueriesAndHandsBackTheCode() {
        val js = RockstarAuth.bridgeScript(RockstarAuth.fingerprintOf("D", "1", "2"), "launcher")
        for (m in listOf("GetWindowConfig", "GetDeviceId", "GetVersionInfo", "GetProfileList", "OnStartAuth", "CallAuthResult", "rgscQueryCancel", "/signin/user-form?cid=launcher"))
            assertTrue(m, m in js)
        assertTrue(RockstarAuth.isCodeUrl(RockstarAuth.CODE_URL + "?code=abc"))
        assertEquals("a+b/c", RockstarAuth.codeFrom(RockstarAuth.CODE_URL + "?code=a%2Bb%2Fc"))
    }

    @Test fun subAppsResolveToTheirGame() {
        assertEquals("rdr2", RockstarTitles.byId("rdr2_sp_rgl")!!.id)
        assertEquals("gtasaunreal", RockstarTitles.byId("gtatrilogy")!!.id)
        assertNull(RockstarTitles.byId("launcher"))
    }
}
