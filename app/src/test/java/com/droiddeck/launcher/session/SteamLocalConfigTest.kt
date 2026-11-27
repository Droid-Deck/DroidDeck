package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamLocalConfigTest {
    private val config = """
        "UserLocalConfigStore"
        {
            "Software"
            {
                "Valve"
                {
                    "Steam"
                    {
                        "apps"
                        {
                            "620"
                            {
                                "cloud"
                                {
                                    "last_sync_state"   "synchronized"
                                }
                                "LastPlayed"        "1764000000"
                                "Playtime"          "754"
                            }
                            "1145360"
                            {
                                "LastPlayed"        "1764100000"
                            }
                        }
                    }
                }
            }
            "1145360" { "Playtime" "9999" }
        }
    """.trimIndent()

    @Test
    fun readsPlaytimeAndLastPlayedPastNestedBlocks() {
        val stats = SteamLocalConfig.appStats(config, 620)!!
        assertEquals(754L, stats.playtimeMinutes)
        assertEquals(1764000000L, stats.lastPlayed)
    }

    @Test
    fun onlyReadsTheAppsBlock() {
        val stats = SteamLocalConfig.appStats(config, 1145360)!!
        assertEquals(0L, stats.playtimeMinutes)
        assertEquals(1764100000L, stats.lastPlayed)
    }

    @Test
    fun unknownAppIsNull() {
        assertNull(SteamLocalConfig.appStats(config, 413150))
        assertNull(SteamLocalConfig.appStats("not vdf", 620))
    }
}
