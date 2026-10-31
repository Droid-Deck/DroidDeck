package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamDownloadsTest {
    private fun manifest(flags: Long, done: Long, total: Long) = """
        "AppState"
        {
            "appid"		"220"
            "name"		"Half-Life 2"
            "StateFlags"		"$flags"
            "BytesToDownload"		"$total"
            "BytesDownloaded"		"$done"
        }
    """.trimIndent()

    @Test fun anInstalledGameIsNotADownload() {
        assertNull(SteamDownloads.fromManifest("220", "Half-Life 2", manifest(4, 100, 100)))
    }

    @Test fun aRunningUpdateShowsItsBytes() {
        val d = SteamDownloads.fromManifest("220", "Half-Life 2", manifest(1026, 40, 100))!!
        assertEquals(40L, d.done)
        assertEquals(100L, d.total)
    }

    @Test fun aPausedUpdateSaysSo() {
        assertTrue(SteamDownloads.fromManifest("220", "Half-Life 2", manifest(514, 40, 100))!!.paused)
    }

    @Test fun anUpdateNotSizedYetIsStillListed() {
        assertEquals(0L, SteamDownloads.fromManifest("220", "Half-Life 2", manifest(6, 0, 0))!!.total)
    }

    @Test fun onlyShaderUpdatesStillRunningCount() {
        val log = sequenceOf(
            "[2026-10-30 06:33:02] AppID 220 Shader update changed : Running Update,Downloading,",
            "[2026-10-30 06:33:02] AppID 440 Shader update changed : Running Update,",
            "[2026-10-30 06:33:03] AppID 440 Shader update changed : None",
            "[2026-10-30 06:33:03] AppID 570 App update changed : Running Update,",
        )
        assertEquals(listOf("220"), SteamDownloads.runningShaderUpdates(log))
    }

    @Test fun aClientRestartForgetsOlderShaderUpdates() {
        val log = sequenceOf(
            "[2026-10-30 06:33:02] AppID 220 Shader update changed : Running Update,",
            "[2026-10-30 07:00:00] Client version: 1761000000",
        )
        assertEquals(emptyList<String>(), SteamDownloads.runningShaderUpdates(log))
    }
}
