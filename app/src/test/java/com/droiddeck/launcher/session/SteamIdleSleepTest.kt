package com.droiddeck.launcher.session

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SteamIdleSleepTest {
    @get:Rule val temp = TemporaryFolder()

    private val keys = listOf(
        "IdleSuspendBatterySeconds", "IdleSuspendACSeconds",
    )

    private fun home(vararg steamBody: String): File = temp.newFolder().also {
        File(it, "config").mkdirs()
        File(it, "config/config.vdf").writeText(
            "\"InstallConfigStore\"\n{\n\t\"Software\"\n\t{\n\t\t\"Valve\"\n\t\t{\n\t\t\t\"Steam\"\n\t\t\t{\n" +
                steamBody.joinToString("") + "\t\t\t}\n\t\t}\n\t}\n}\n",
        )
    }

    private fun text(home: File) = File(home, "config/config.vdf").readText()
    private fun value(text: String, key: String) = Regex("\"$key\"\\s+\"([^\"]*)\"").find(text)?.groupValues?.get(1)

    @Test fun freshClientGetsBothSleepTimersDisabled() {
        val home = home()
        assertTrue(SessionFiles.disableIdleSleep(home))
        keys.forEach { assertEquals(it, "0", value(text(home), it)) }
    }

    @Test fun existingSleepTimersAreReplacedBesideTheOtherSettings() {
        val home = home(
            "\t\t\t\t\"system\"\n\t\t\t\t{\n\t\t\t\t\t\"IdleSuspendACSeconds\"\t\t\"900\"\n" +
                "\t\t\t\t\t\"IdleScreensaverACSeconds\"\t\t\"300\"\n\t\t\t\t\t\"IdleBacklightDimBatterySeconds\"\t\t\"120\"\n\t\t\t\t}\n",
        )
        assertTrue(SessionFiles.disableIdleSleep(home))
        keys.forEach { assertEquals(it, "0", value(text(home), it)) }
        assertEquals("300", value(text(home), "IdleScreensaverACSeconds"))
        assertEquals("120", value(text(home), "IdleBacklightDimBatterySeconds"))
    }

    @Test fun aChoiceMadeInSteamIsResetAtTheNextSession() {
        val home = home()
        SessionFiles.disableIdleSleep(home)
        File(home, "config/config.vdf").writeText(text(home).replace("\"IdleSuspendACSeconds\"\t\t\"0\"", "\"IdleSuspendACSeconds\"\t\t\"600\""))
        assertTrue(SessionFiles.disableIdleSleep(home))
        assertEquals("0", value(text(home), "IdleSuspendACSeconds"))
    }

    @Test fun clientWithoutAConfigIsRetriedNextSession() {
        val home = temp.newFolder()
        assertFalse(SessionFiles.disableIdleSleep(home))
    }
}
