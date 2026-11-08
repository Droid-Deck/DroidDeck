package com.droiddeck.launcher.stores.ea

import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreLaunch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class EaTest {
    private val wine = EaOoa.Hardware(
        boardManufacturer = "Wine", boardSerial = "None", biosManufacturer = "The Wine Project", biosSerial = "0",
        osInstallDate = "20240101000000.000000+000", osSerial = "12345-OEM", volumeSerial = "43000000",
        gpuPnpId = "PCI\\VEN_5143&DEV_0001&SUBSYS_00000000&REV_00\\0&DEADBEEF&0&DEAD",
        cpuVendor = "GenuineIntel", cpuEdx = "BFEBFBFF", cpuEax = "000906EA", cpuEcx = "7ffafbbf", brand = "FEX CPU", hostname = "myhost",
    )

    @Test fun machineHashFollowsTheActivationVersion() {
        assertEquals("01804062be939efcd4462c65de292af3a56359b5", EaOoa.machineHash(wine, 4))
        assertEquals("41fa9c614390c5a82534becd338b798197cc5b", EaOoa.machineHash(wine, 3))
    }

    @Test fun launchCodeAndSessionKeyMatchTheProtocol() {
        assertEquals(1196115787L, EaCrypto.rtpCode(LocalDate.of(2026, 11, 7)))
        assertEquals("f057eb86a0bf8c57537b1aa6b98c6495", EaCrypto.hex(EaCrypto.lsxKey(0x6162)))
        assertEquals(EaCrypto.hex(EaCrypto.LSX_DEFAULT_KEY), EaCrypto.hex(EaCrypto.lsxKey(0)))
        val answer = EaCrypto.challengeAnswer("abc")
        assertEquals("abc", String(EaCrypto.ecbDecrypt(EaCrypto.unhex(answer), EaCrypto.LSX_DEFAULT_KEY)))
        assertEquals((answer[0].code shl 8) or answer[1].code, EaCrypto.challengeSeed("3", answer))
        assertNull(EaCrypto.challengeSeed("9", answer))
    }

    @Test fun licenceRoundTripsThroughTheFileFormat() {
        val xml = "<?xml version=\"1.0\"?><License xmlns=\"http://ea.com/license\"><CipherKey>k</CipherKey><MachineHash>h</MachineHash>" +
            "<ContentId>1234</ContentId><UserId>9</UserId><GameToken>tok</GameToken><GrantTime>g</GrantTime><StartTime>s</StartTime><Nonce>n</Nonce></License>"
        val l = EaOoa.parseLicense(xml, "c2ln")
        assertEquals("tok", l.gameToken)
        assertEquals("1234", l.contentId)
        val blob = EaOoa.licenseBlob(l, encoded = true)
        assertEquals("c2ln", String(blob.copyOfRange(0, 4)))
        assertEquals(0, blob[64].toInt())
        val back = String(EaCrypto.licenseDecrypt(blob.copyOfRange(65, blob.size)))
        assertTrue(back.contains("<GameToken>tok</GameToken>"))
        assertEquals("sig", String(EaOoa.licenseBlob(l, encoded = false).copyOfRange(0, 3)))
    }

    @Test fun readsTheVersionOfTheActivationLibrary() {
        val pe = ByteArray(64)
        val fixed = byteArrayOf(0xBD.toByte(), 0x04, 0xEF.toByte(), 0xFE.toByte(), 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7, 0, 4, 0, 0, 0, 6, 0)
        System.arraycopy(fixed, 0, pe, 10, fixed.size)
        assertEquals(Triple(4, 7, 6), EaOoa.productVersion(pe))
    }

    @Test fun manifestNamesTheLauncherAndTouchup() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <DiPManifest version="4.0">
              <buildMetaData><gameVersion version="1.0.64.43203"/></buildMetaData>
              <runtime>
                <launcher uid="trial"><filePath>[HKEY_LOCAL_MACHINE\SOFTWARE\EA Games\Game\Install Dir]trial.exe</filePath><trial>true</trial></launcher>
                <launcher uid="main"><filePath>[HKEY_LOCAL_MACHINE\SOFTWARE\EA Games\Game\Install Dir]bin\Game.exe</filePath><parameters>-dx12</parameters><trial>false</trial></launcher>
              </runtime>
              <touchup><filePath>/__Installer/Touchup.exe</filePath><parameters>-locale {locale} -installPath "{installLocation}"</parameters></touchup>
            </DiPManifest>"""
        val m = EaManifest.parse(xml)
        assertEquals("1.0.64.43203", m.version)
        assertEquals("bin/Game.exe", EaPaths.relative(m.launcher()!!.path))
        assertEquals("-dx12", m.launcher()!!.parameters)
        assertEquals("/__Installer/Touchup.exe", m.touchupPath)
        val r = EaPaths.registered(m.launcher()!!.path)!!
        assertEquals("HKEY_LOCAL_MACHINE\\SOFTWARE\\EA Games\\Game", r.key)
        assertEquals("Install Dir", r.value)
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<DiPManifest><buildMetaData><gameVersion version=\"1.0\"/></buildMetaData></DiPManifest>".toByteArray(Charsets.UTF_16LE)
        assertEquals("1.0", EaManifest.parse(EaManifest.decode(utf16)).version)
    }

    @Test fun findsFilesWhateverTheirCase() {
        val dir = Files.createTempDirectory("ea").toFile()
        File(dir, "Bin").mkdirs()
        File(dir, "Bin/GAME.exe").writeText("x")
        assertEquals("Bin/GAME.exe", EaPaths.relativeOnDisk(dir, "bin/game.exe"))
        assertNull(EaPaths.relativeOnDisk(dir, "bin/other.exe"))
        assertEquals(listOf("-a", "two words", "-c"), EaPaths.splitArgs("-a \"two words\"  -c"))
        dir.deleteRecursively()
    }

    @Test fun lsxRequestsAreParsed() {
        val m = EaLsx.parse("<LSX><Request recipient=\"EbisuSDK\" id=\"4\"><GetSetting SettingId=\"ENVIRONMENT\" version=\"\"/></Request></LSX>")!!
        assertEquals("GetSetting", m.name)
        assertEquals("4", m.id)
        assertEquals("ENVIRONMENT", m.attrs["SettingId"])
        val c = EaLsx.parse("<LSX><Request recipient=\"EALS\" id=\"1\"><ChallengeResponse response=\"ab\" key=\"k\" version=\"3\"><ContentId>1</ContentId><Title>Game</Title></ChallengeResponse></Request></LSX>")!!
        assertEquals(listOf("ContentId" to "1", "Title" to "Game"), c.children)
        assertNull(EaLsx.parse("<LSX><Event sender=\"x\"><Challenge/></Event></LSX>"))
    }

    @Test fun socketOwnerIsReadFromTheProcTable() {
        val rows = listOf(
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
            "   0: 0100007F:C350 0100007F:0C90 01 00000000:00000000 00:00000000 00000000 10234        0 1 1 0",
        )
        assertEquals(10234, EaLsx.owner(rows, 0xC350))
        assertNull(EaLsx.owner(rows, 1234))
        assertNull(EaLsx.owner(emptyList(), 0xC350))
    }

    @Test fun lateExitDoesNotStopANewerLaunch() {
        val game = EaLsx.Game("Origin.OFR.2", "2", "Two", "1", "en_US", null, 0)
        EaLsx.game = game
        EaLsx.stop("Origin.OFR.1")
        assertEquals(game, EaLsx.game)
        EaLsx.stop("Origin.OFR.2")
        assertNull(EaLsx.game)
    }

    @Test fun redirectCarriesTheCode() {
        assertEquals("QUOX", EaAuth.redirectValue("qrc:///html/login_successful.html?code=QUOX&state=1", "code"))
        assertEquals("tok", EaAuth.redirectValue("qrc:/html/login_successful.html#access_token=tok&token_type=Bearer", "access_token"))
        assertNull(EaAuth.redirectValue("https://signin.ea.com/p/x", "code"))
    }

    @Test fun pcSignIsSigned() {
        val sign = EaAuth.pcSign("ab12", java.util.Date(0), "v1")
        val (payload, signature) = sign.split('.')
        val json = JSONObject(String(java.util.Base64.getUrlDecoder().decode(payload)))
        assertEquals("v1", json.getString("sv"))
        assertEquals("1970-01-01 00:00:00:000", json.getString("ts"))
        assertEquals(EaCrypto.base64Url(EaCrypto.hmacSha256("ISa3dpGOc8wW7Adn4auACSQmaccrOyR2".toByteArray(), payload.toByteArray())), signature)
    }

    @Test fun libraryKeepsOneEntryPerGame() {
        fun owned(id: String, slug: String, base: String = "", type: String = "BASE_GAME", trial: Boolean = false) =
            EaApi.OwnedProduct(id, "p$id", "Game $id", slug, base, type, true, trial, emptyList())
        fun offer(id: String, type: String = "FullGame") = EaApi.Offer(id, "c$id", "", type, true, "", "", "", "", "", "1.0", "", emptyList())
        val list = listOf(owned("1", "game"), owned("2", "game-deluxe", base = "game"), owned("3", "other"), owned("4", "trial", trial = true), owned("5", "dlc", type = "EXTRA_CONTENT"))
        val picked = EaLibrary.pick(list, list.associate { it.offerId to offer(it.offerId) })
        assertEquals(listOf("1", "3"), picked.map { it.first.offerId })
    }

    @Test fun ownedPageParses() {
        val page = JSONObject(
            """{"me":{"ownedGameProducts":{"next":"100","items":[{"originOfferId":"Origin.OFR.50.0001","product":{"id":"x","name":"N","downloadable":true,"gameSlug":"titanfall-2",
              "baseItem":{"title":"Titanfall 2","gameType":"BASE_GAME","baseGameSlug":null},"gameProductUser":{"ownershipMethods":["PURCHASE"]}}}]}}}""",
        )
        val (items, next) = EaApi.parseOwned(page)
        assertEquals("100", next)
        assertEquals("Titanfall 2", items.single().name)
        assertEquals("", items.single().baseSlug)
        val offers = EaApi.parseOffers(JSONObject("""{"legacyOffers":[{"offerId":"Origin.OFR.50.0001","contentId":"1035052","displayType":"FullGame","isDownloadable":true,
              "downloads":[{"downloadType":"LIVE","gameVersion":"2.0.11.0"}],"softwareLocales":["en_US","fr_FR"],"executeParameters":null}]}"""))
        assertEquals("2.0.11.0", offers.single().gameVersion)
        assertEquals("", offers.single().executeParameters)
        assertNotNull(EaApi.query("getPreloadedOwnedGames"))
    }

    @Test fun launcherReadsTheSessionFileOnce() {
        val text = StoreLaunch.launcherText(StoreGameSidecar(Store.EA, "Origin.OFR.1", "Game", exe = "bin/Game.exe", args = listOf("-dx12")))
        assertTrue(text.contains("for /f \"usebackq tokens=1,* delims==\" %%a in (\"%~dp0.droiddeck-ea-env\") do set \"%%a=%%b\""))
        assertTrue(text.contains("del \"%~dp0.droiddeck-ea-env\""))
        assertTrue(text.contains("start \"\" \"${StoreLaunch.EA_ORIGIN}\"\r\n\"%~dp0bin\\Game.exe\" -dx12\r\n"))
        assertTrue(text.trimEnd().endsWith("taskkill /f /im Origin.exe >nul 2>&1"))
    }

    @Test fun environmentCarriesTheSession() {
        val account = EaAuth.Account("a", "r", 0, "1001", "2002", "Name", "EaId")
        val env = EaLaunch.environment("Origin.OFR.1", "1035052", "fr_FR", "generic", "jwt", "opaque", account, "session")
        assertEquals("generic", env["EAGenericAuthToken"])
        assertEquals("jwt", env["EAAccessTokenJWS"])
        assertEquals("3216", env["EALsxPort"])
        assertEquals("opaque", env["EALaunchUserAuthToken"])
        assertEquals("EaId", env["EALaunchEAID"])
        assertEquals("1035052", env["ContentId"])
        assertEquals("fr_FR", env["EAGameLocale"])
    }

    @Test fun activationCoreSitsBesideTheExe() {
        val dir = Files.createTempDirectory("ea").toFile()
        File(dir, "Binaries/Win32/Core").mkdirs()
        File(dir, "Binaries/Win32/Core/EACore.dll").writeText("x")
        val core = EaOoa.coreDir(dir, "Binaries/Win32/MassEffect3.exe")
        assertEquals(File(dir, "Binaries/Win32/Core").canonicalPath, core?.canonicalPath)
        assertTrue(EaOoa.legacyCore(core))
        assertNull(EaOoa.coreDir(dir, "Game.exe"))
        File(dir, "Core").mkdirs()
        assertEquals(File(dir, "Core").canonicalPath, EaOoa.coreDir(dir, "Game.exe")?.canonicalPath)
        dir.deleteRecursively()
    }

    @Test fun anIncompleteProbeIsNotHashed() {
        val empty = EaOoa.Hardware.fromJson(JSONObject())
        assertTrue(!empty.complete(0) && !empty.complete(4))
        val wmiOnly = EaOoa.Hardware.fromJson(JSONObject().put("boardManufacturer", "Intel Corporation").put("biosManufacturer", "The Wine Project")
            .put("osSerial", "00330").put("osInstallDate", "2026").put("hostname", "DROIDDECK"))
        assertTrue(wmiOnly.complete(0))
        assertTrue(!wmiOnly.complete(4))
        assertTrue(wine.complete(4))
        assertTrue(wine.complete(2))
        val noInstallDate = EaOoa.Hardware.fromJson(JSONObject().put("boardManufacturer", "Wine").put("biosManufacturer", "Wine").put("osSerial", "1")
            .put("volumeSerial", "43000000").put("cpuVendor", "GenuineIntel").put("cpuEdx", "BFEBFBFF").put("cpuEax", "000906EA").put("cpuEcx", "7ffafbbf"))
        assertTrue(!noInstallDate.complete(2))
        assertTrue(noInstallDate.complete(3) && noInstallDate.complete(4))
    }

    @Test fun legacyManifestNamesItsContentAndTitle() {
        val xml = """<game gameVersion="1.05.0.0" manifestVersion="2.1"><contentIDs><contentID>71073</contentID><contentID>71183</contentID></contentIDs>
            <metadata><localeInfo locale="ru_RU"><title>RU</title></localeInfo><localeInfo locale="en_US"><title>Mass Effect 3</title></localeInfo></metadata>
            <executable><filePath>/__Installer/touchup.exe</filePath><parameters>install -locale {locale}</parameters></executable></game>"""
        val m = EaManifest.parse(xml)
        assertEquals(listOf("71073", "71183"), m.contentIds)
        assertEquals("Mass Effect 3", m.title("en_US"))
        assertEquals("RU", m.title("ru_RU"))
        assertEquals("Mass Effect 3", m.title("de_DE"))
        assertEquals("/__Installer/touchup.exe", m.touchupPath)
    }

    @Test fun aSyncRequestedWhileOneRunsIsNotLost() {
        val gate = SyncGate()
        assertTrue(gate.enter())
        assertTrue(!gate.enter())
        assertTrue(gate.leave())
        assertTrue(!gate.leave())
        assertTrue(gate.enter())
        assertTrue(!gate.leave())
    }

    @Test fun aRequestRacingTheRunnersExitIsPickedUpBySomeone() {
        repeat(200) {
            val gate = SyncGate()
            val runs = java.util.concurrent.atomic.AtomicInteger()
            assertTrue(gate.enter())
            val latch = java.util.concurrent.CountDownLatch(1)
            val requester = Thread { latch.await(); if (gate.enter()) { runs.incrementAndGet(); while (gate.leave()) runs.incrementAndGet() } }
            requester.start()
            latch.countDown()
            while (gate.leave()) runs.incrementAndGet()
            requester.join()
            assertTrue("the second request was dropped", runs.get() >= 1)
        }
    }

    @Test fun aSyncThatSnapshottedBeforeSignOutCannotWriteItsLibrary() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val game = EaGame("Origin.OFR.1", "1", "Old account game", "old")
        val (gen, _) = EaLibrary.snapshot(app)
        EaLibrary.reset(app)
        assertTrue(!EaLibrary.storeCache(app, listOf(game), gen))
        assertTrue(EaLibrary.cached(app).isEmpty())
        val (fresh, cache) = EaLibrary.snapshot(app)
        assertTrue(cache.isEmpty())
        assertTrue(EaLibrary.storeCache(app, listOf(game), fresh))
        assertEquals("Old account game", EaLibrary.cached(app).single().title)
        EaLibrary.reset(app)
    }

    @Test fun aRefreshFinishingAfterSignOutDoesNotRestoreTheSignIn() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        com.droiddeck.launcher.stores.StoreAccounts.write(app, Store.EA, JSONObject().put("access_token", "old").put("refresh_token", "r").put("user_id", "1"))
        assertTrue(EaAuth.storeRefreshed(app, "newer", 3600, "r2"))
        assertEquals("newer", EaAuth.load(app)?.accessToken)
        EaAuth.signOut(app)
        assertTrue(!EaAuth.storeRefreshed(app, "late", 3600, "r3"))
        assertNull(EaAuth.load(app))
    }
}
