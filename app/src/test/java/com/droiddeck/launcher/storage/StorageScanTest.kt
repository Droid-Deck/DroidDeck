package com.droiddeck.launcher.storage

import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StorageScanTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun eachClientsLeftoversAreCheckedAgainstItsOwnGames() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val arm = SessionPrefs.steamRoot(app, "arm64")
        val x64 = SessionPrefs.steamRoot(app, "x86_64")
        File(arm, "steamapps/compatdata/10").mkdirs()
        File(arm, "steamapps/appmanifest_10.acf").writeText("\"appid\" \"10\"\n\"StateFlags\" \"4\"\n\"installdir\" \"Example\"\n")
        val removed = File(x64, "steamapps/compatdata/10").apply { mkdirs() }
        File(removed, "user.reg").writeText("x")

        val report = StorageScan.scan(app)
        assertTrue(report.leftoversChecked)
        assertEquals(listOf(removed.path), report.leftovers.map { it.prefix?.path })
    }

    @Test fun manifestsWithMissingInstallDirsAreOfferedForAdoption() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val card = tmp.newFolder("card")
        SessionPrefs.setGameStorage(app, card.path, "Card")
        File(card, "steamapps/appmanifest_220.acf").apply { parentFile.mkdirs() }.writeText(
            "\"AppState\"\n{\n\t\"appid\"\t\t\"220\"\n\t\"name\"\t\t\"Example\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Example\"\n}\n",
        )

        val report = StorageScan.scan(app)
        val missing = report.missingInstalls.firstOrNull { it.appId == 220 }
        assertNotNull(missing)
        assertEquals("Example", missing!!.installDir)
        assertEquals(canonical(card), canonical(missing.libraryRoot))
    }

    private fun canonical(f: File) = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
}
