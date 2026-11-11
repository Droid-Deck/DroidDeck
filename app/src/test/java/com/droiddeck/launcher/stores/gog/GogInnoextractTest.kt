package com.droiddeck.launcher.stores.gog

import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.session.SecondaryLibrary
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.StoreInstallRoot
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GogInnoextractTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before @After fun resetStorage() {
        SessionPrefs.setDefaultStorageFolder(app, "", "")
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
    }

    private fun installer(folder: File, names: List<String>) = names.map { File(folder, it).apply { parentFile?.mkdirs(); writeText("MZ") } }

    private fun hostOf(guest: String, binds: List<String>): File? = binds
        .map { it.substringBeforeLast(':') to it.substringAfterLast(':') }
        .filter { (_, mount) -> guest == mount || guest.startsWith("$mount/") }
        .maxByOrNull { it.second.length }
        ?.let { (host, mount) -> File(host + guest.removePrefix(mount)) }

    private fun assertExtractorSeesEveryPart(folder: File, parts: List<String>) {
        val files = installer(folder, parts)
        val guestDir = AddedGames.guestPath(app, folder)
        assertNotNull(guestDir)
        val argv = GogInnoextract.argv("$guestDir/${parts.first()}", guestDir!!)
        assertEquals("$guestDir/${parts.first()}", argv.last())
        assertEquals(guestDir, argv[argv.indexOf("-d") + 1])
        assertTrue(argv.contains("--gog"))
        val binds = StoreInstallRoot.guestBinds(app)
        assertEquals(folder.canonicalFile, hostOf(guestDir, binds)?.canonicalFile)
        for (file in files) assertEquals(file.canonicalFile, hostOf("$guestDir/${file.name}", binds)?.canonicalFile)
        assertNull(hostOf("$guestDir/${parts.first()}", emptyList()))
    }

    @Test fun singleFileAndMultiPartInstallersOnAChosenFolderAreVisibleToTheExtractor() {
        val drive = File(app.filesDir, "usb/SSD").apply { mkdirs() }
        SessionPrefs.setDefaultStorageFolder(app, drive.path, "SSD")
        val single = File(drive, "Games/GOG/Single")
        assertTrue(AddedGames.guestPath(app, single)!!.startsWith(StoreInstallRoot.GUEST_EXTERNAL + "/"))
        assertExtractorSeesEveryPart(single, listOf("setup_1207658930.exe"))
        assertExtractorSeesEveryPart(File(drive, "Games/GOG/Multi Part"), listOf("setup_1207658931.exe", "setup_1207658931-1.bin", "setup_1207658931-2.bin"))
    }

    @Test fun installersOnTheGameStorageCardAreVisibleToTheExtractor() {
        val card = File(app.filesDir, "card/steam").apply { mkdirs() }
        SessionPrefs.setGameStorage(app, card.path, "Card")
        val folder = File(card, "Games/GOG/Multi Part")
        assertTrue(SecondaryLibrary.CARD_GUESTS.any { AddedGames.guestPath(app, folder)!!.startsWith("$it/") })
        assertExtractorSeesEveryPart(folder, listOf("setup_1207658932.exe", "setup_1207658932-1.bin"))
    }

    @Test fun anUnpluggedGameStorageCardDoesNotStopAnInternalExtraction() {
        SessionPrefs.setGameStorage(app, File(app.filesDir, "unplugged/steam").path, "Card")
        val folder = File(StoreInstallRoot.internalRoot(app), "GOG/Internal")
        installer(folder, listOf("setup_1207658933.exe"))
        val binds = StoreInstallRoot.guestBinds(app)
        assertTrue(binds.none { bind -> SecondaryLibrary.CARD_GUESTS.any { bind.endsWith(":$it") } })
        assertTrue(AddedGames.guestPath(app, folder)!!.startsWith("/root/Games/Stores/GOG/"))
    }

    @Test fun anAddedFolderNamedStoresIsMountedWithoutHidingTheInternalInstallRoot() {
        val drive = File(app.filesDir, "usb/Stores").apply { mkdirs() }
        AddedGames.bindFolder(app, drive)
        val guest = AddedGames.guestPath(app, drive)!!
        assertTrue(guest != StoreInstallRoot.INTERNAL_GUEST && !guest.startsWith(StoreInstallRoot.INTERNAL_GUEST + "/"))
        SessionPrefs.setDefaultStorageFolder(app, drive.path, "Stores")
        val chosen = File(drive, "Games/GOG/Chosen")
        assertTrue(AddedGames.guestPath(app, chosen)!!.startsWith("$guest/"))
        assertExtractorSeesEveryPart(chosen, listOf("setup_1207658934.exe", "setup_1207658934-1.bin"))
        val internal = File(StoreInstallRoot.internalRoot(app), "GOG/Internal")
        installer(internal, listOf("setup_1207658935.exe"))
        val internalGuest = AddedGames.guestPath(app, internal)!!
        assertTrue(internalGuest.startsWith(StoreInstallRoot.INTERNAL_GUEST + "/GOG/"))
        assertNull(hostOf("$internalGuest/setup_1207658935.exe", StoreInstallRoot.guestBinds(app)))
    }

    @Test fun aFailedExtractionReportsTheExtractorsOwnError() {
        val result = GogInnoextract.Result(2, GogInnoextract.reason(listOf(
            "Warning: Unexpected setup data version: Inno Setup Setup Data (6.4.0)",
            "Stream error while parsing setup headers!",
            " ├─ detected setup version: 6.4.0",
            " └─ error reason: iostream error",
            "",
        )))
        assertEquals(
            "The installer could not be extracted (innoextract exit 2): Stream error while parsing setup headers! detected setup version: 6.4.0 error reason: iostream error",
            result.failure("The installer could not be extracted (innoextract exit 2)"),
        )
    }

    @Test fun aStreamErrorDuringExtractionReportsItsReasonWithoutTheExtractorsBoilerplate() {
        val result = GogInnoextract.Result(2, GogInnoextract.reason(listOf(
            "proot warning: can't sanitize binding \"/proc/self/fd/0\": No such file or directory",
            "proot warning: can't sanitize binding \"/proc/self/fd/1\": No such file or directory",
            "Stream error while extracting files!",
            " └─ error reason: zlib error: iostream error",
            "If you are sure the setup file is not corrupted, consider ",
            "filing a bug report at https://innoextract.constexpr.org/issues",
            "Done with 1 error.",
        )))
        assertEquals(
            "The installer could not be extracted (innoextract exit 2): Stream error while extracting files! error reason: zlib error: iostream error",
            result.failure("The installer could not be extracted (innoextract exit 2)"),
        )
    }

    @Test fun theReasonKeepsTheLastErrorLinesAndStaysShort() {
        val lines = (1..10).map { "Could not open file \"/mnt/droiddeck-stores/card/setup_$it.exe\"" }
        assertEquals(lines.takeLast(3).joinToString(" "), GogInnoextract.reason(lines))
        assertEquals(400, GogInnoextract.reason(listOf("x".repeat(1000))).length)
    }

    @Test fun anExtractionWithoutOutputKeepsTheExitMessage() {
        val result = GogInnoextract.Result(2, GogInnoextract.reason(listOf("", "Warning: Unexpected setup data version")))
        assertEquals("GOG installer extraction failed (exit 2)", result.failure("GOG installer extraction failed (exit 2)"))
    }
}
