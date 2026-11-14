package com.droiddeck.launcher.session

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SteamUserDefaultsTest {
    @get:Rule val temp = TemporaryFolder()

    private val battery = "\"CachedBatteryPreferences\"\t\t\"{\\\"bShowBatteryPercentage\\\":true}\""

    private fun home(body: String): File = temp.newFolder().also {
        File(it, "userdata/123/config").mkdirs()
        File(it, "userdata/123/config/localconfig.vdf").writeText("\"UserLocalConfigStore\"\n{\n$body}\n")
    }

    private fun text(home: File) = File(home, "userdata/123/config/localconfig.vdf").readText()
    private fun bits(text: String) = Regex("\"displayratesasbits\"\\s+\"([^\"]*)\"").find(text)?.groupValues?.get(1)

    @Test fun freshAccountGetsBothDefaults() {
        val home = home("")
        SessionFiles.seedUserDefaults(home)
        assertEquals("0", bits(text(home)))
        assertTrue(text(home).contains(battery))
        assertTrue(File(home, "userdata/123/config/droiddeck-user-defaults-initialized").isFile)
    }

    @Test fun existingBlocksGainTheKeysBesideOtherSettings() {
        val home = home("\t\"system\"\n\t{\n\t\t\"PushToTalkKey\"\t\t\"0\"\n\t}\n\t\"WebStorage\"\n\t{\n\t\t\"Other\"\t\t\"{\\\"a\\\":1}\"\n\t}\n")
        SessionFiles.seedUserDefaults(home)
        val t = text(home)
        assertEquals("0", bits(t))
        assertTrue(t.contains("\"PushToTalkKey\"\t\t\"0\""))
        assertTrue(t.contains("\"Other\"\t\t\"{\\\"a\\\":1}\""))
        assertTrue(t.contains(battery))
    }

    @Test fun aChoiceAlreadyMadeIsKept() {
        val home = home("\t\"system\"\n\t{\n\t\t\"displayratesasbits\"\t\t\"1\"\n\t}\n\t\"WebStorage\"\n\t{\n\t\t\"CachedBatteryPreferences\"\t\t\"{\\\"bShowBatteryPercentage\\\":false}\"\n\t}\n")
        SessionFiles.seedUserDefaults(home)
        assertEquals("1", bits(text(home)))
        assertTrue(text(home).contains("false"))
    }

    @Test fun laterChangesAreNotReseeded() {
        val home = home("")
        SessionFiles.seedUserDefaults(home)
        File(home, "userdata/123/config/localconfig.vdf").writeText(text(home).replace("\"displayratesasbits\"\t\t\"0\"", "\"displayratesasbits\"\t\t\"1\""))
        SessionFiles.seedUserDefaults(home)
        assertEquals("1", bits(text(home)))
    }

    @Test fun missingLocalConfigIsRetriedLater() {
        val home = temp.newFolder()
        File(home, "userdata/123/config").mkdirs()
        SessionFiles.seedUserDefaults(home)
        assertFalse(File(home, "userdata/123/config/droiddeck-user-defaults-initialized").exists())
    }

    @Test fun librarySortsByRecentlyPlayed() {
        val fresh = home("")
        SessionFiles.seedUserDefaults(fresh)
        assertTrue(text(fresh).contains("\\\"bSortedByRecent\\\":true"))
        val existing = home("\t\"WebStorage\"\n\t{\n\t\t\"UIStoreLocalSteamUIState\"\t\t\"{\\\"nVersion\\\":1,\\\"bSortedByRecent\\\":false,\\\"eGameListView\\\":1}\"\n\t}\n")
        SessionFiles.seedUserDefaults(existing)
        assertTrue(text(existing).contains("\\\"bSortedByRecent\\\":true,\\\"eGameListView\\\":1"))
        assertEquals(1, Regex("UIStoreLocalSteamUIState").findAll(text(existing)).count())
    }
}
