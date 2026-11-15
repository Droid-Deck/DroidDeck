package com.droiddeck.launcher.session

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class SteamImportPromptTest {
    @get:Rule val temp = TemporaryFolder()

    private fun ask(root: File, text: String) = File(root, "root/.cache/droiddeck").apply { mkdirs() }.resolve("steam-import.json").writeText(text)

    @Test fun aRequestFromTheGuestIsRead() {
        val root = temp.newFolder()
        ask(root, """{"token":"7","games":["Absolum","","Hades"]}""")
        assertEquals(SteamImportPrompt.Ask("7", listOf("Absolum", "Hades")), SteamImportPrompt.read(root))
    }

    @Test fun noRequestOrABrokenOneIsIgnored() {
        val root = temp.newFolder()
        assertNull(SteamImportPrompt.read(root))
        ask(root, "{")
        assertNull(SteamImportPrompt.read(root))
        ask(root, """{"games":["Hades"]}""")
        assertNull(SteamImportPrompt.read(root))
    }

    @Test fun theAnswerCarriesTheTokenAndTheChoice() {
        val root = temp.newFolder()
        for ((now, choice) in listOf(true to "now", false to "later")) {
            SteamImportPrompt.answer(root, "9", now)
            val json = JSONObject(File(root, "root/.cache/droiddeck/steam-import.answer").readText())
            assertEquals("9", json.getString("token"))
            assertEquals(choice, json.getString("choice"))
        }
    }
}
