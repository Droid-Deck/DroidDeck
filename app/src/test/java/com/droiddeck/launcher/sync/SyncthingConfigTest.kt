package com.droiddeck.launcher.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncthingConfigTest {
    @get:Rule val tmp = TemporaryFolder()

    private val generated = """
        <configuration version="51">
            <folder id="abc" label="Camera" path="/storage/emulated/0/DCIM" type="sendreceive">
                <paused>false</paused>
            </folder>
            <folder id="def" label="Saves" path="/data/user/0/app/Sync/saves" type="sendreceive">
                <paused>false</paused>
            </folder>
            <device id="PEER" name="Desktop" compression="metadata">
                <address>dynamic</address>
                <paused>false</paused>
            </device>
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

    private val ports = SyncthingPorts(22100, 21127)

    @Test
    fun pausesDisallowedFoldersBeforeLaunch() {
        val out = SyncthingConfig.apply(generated, ports, "/data/st/gui.sock", "/ext/Sync") { !it.startsWith("/storage/emulated/0/DCIM") }
        val camera = out.substringAfter("id=\"abc\"").substringBefore("</folder>")
        val saves = out.substringAfter("id=\"def\"").substringBefore("</folder>")
        assertTrue(camera.contains("<paused>true</paused>"))
        assertTrue(saves.contains("<paused>false</paused>"))
    }

    @Test
    fun pausesDevicesBeforeLaunchWhenPolicyRequires() {
        val paused = SyncthingConfig.apply(generated, ports, "/data/st/gui.sock", "/ext/Sync", pauseDevices = true) { true }
        assertTrue(paused.substringAfter("id=\"PEER\"").substringBefore("</device>").contains("<paused>true</paused>"))
        val open = SyncthingConfig.apply(generated, ports, "/data/st/gui.sock", "/ext/Sync", pauseDevices = false) { true }
        assertTrue(open.substringAfter("id=\"PEER\"").substringBefore("</device>").contains("<paused>false</paused>"))
    }

    @Test
    fun replacesConfigFileWithoutLeavingStagedCopy() {
        val dir = tmp.newFolder()
        val file = File(dir, "config.xml").apply { writeText(generated) }
        SyncthingConfig.apply(file, ports, File("/data/st/gui.sock"), File("/ext/Sync"), false) { true }
        assertEquals(SyncthingConfig.apply(generated, ports, "/data/st/gui.sock", "/ext/Sync") { true }, file.readText())
        assertEquals(listOf("config.xml"), dir.list()!!.toList())
    }

    @Test
    fun applyIsStable() {
        val once = SyncthingConfig.apply(generated, ports, "/data/st/gui.sock", "/ext/Sync") { true }
        assertEquals(once, SyncthingConfig.apply(once, ports, "/data/st/gui.sock", "/ext/Sync") { true })
    }

    @Test
    fun guiListensOnlyOnPrivateSocket() {
        val gui = SyncthingConfig.apply(generated, ports, "/data/st/gui.sock", "/ext/Sync") { true }.substringAfter("<gui").substringBefore("</gui>")
        assertTrue(gui.contains("<address>/data/st/gui.sock</address>"))
        assertTrue(gui.contains("<unixSocketPermissions>600</unixSocketPermissions>"))
        assertTrue(gui.contains("tls=\"false\""))
    }
}
