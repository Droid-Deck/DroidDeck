package com.droiddeck.launcher.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncthingConfigTest {
    private val generated = """
        <configuration version="51">
            <folder id="abc" label="Camera" path="/storage/emulated/0/DCIM" type="sendreceive">
                <paused>false</paused>
            </folder>
            <folder id="def" label="Saves" path="/data/user/0/app/Sync/saves" type="sendreceive">
                <paused>false</paused>
            </folder>
            <gui enabled="true" tls="true" debugging="false">
                <address>127.0.0.1:8384</address>
                <user>droiddeck</user>
                <apikey>key123</apikey>
            </gui>
            <options>
                <listenAddress>default</listenAddress>
                <localAnnouncePort>21027</localAnnouncePort>
                <urAccepted>0</urAccepted>
                <startBrowser>true</startBrowser>
            </options>
            <defaults>
                <folder id="" label="" path="~" type="sendreceive"></folder>
            </defaults>
        </configuration>
    """.trimIndent()

    private val ports = SyncthingPorts(18384, 22100, 21127)

    @Test
    fun pausesDisallowedFoldersBeforeLaunch() {
        val out = SyncthingConfig.apply(generated, ports, "/ext/Sync") { !it.startsWith("/storage/emulated/0/DCIM") }
        val camera = out.substringAfter("id=\"abc\"").substringBefore("</folder>")
        val saves = out.substringAfter("id=\"def\"").substringBefore("</folder>")
        assertTrue(camera.contains("<paused>true</paused>"))
        assertTrue(saves.contains("<paused>false</paused>"))
    }

    @Test
    fun applyIsStable() {
        val once = SyncthingConfig.apply(generated, ports, "/ext/Sync") { true }
        assertEquals(once, SyncthingConfig.apply(once, ports, "/ext/Sync") { true })
    }
}
