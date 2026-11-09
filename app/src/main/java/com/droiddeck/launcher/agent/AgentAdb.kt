package com.droiddeck.launcher.agent

import android.content.Context
import com.droiddeck.launcher.core.WirelessAdbFix
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

/**
 * Loopback adb for the agent, so droiddeckctl in the Linux userland reaches the app. It reuses
 * the Wireless debugging pairing DroidDeck already made (for the phantom process fix): the paired
 * key goes to the guest as an adb vendor key, and the serial is the paired TLS connect port.
 * Without a pairing it falls back to adbd on 127.0.0.1:5555, which some devices and `adb tcpip`
 * leave listening.
 */
object AgentAdb {
    data class Link(val serial: String, val vendorKeys: String, val paired: Boolean)

    private const val LEGACY = "127.0.0.1:5555"
    private const val VENDOR_KEY = "/root/.android/droiddeck-paired.adbkey"

    /** The display the browser opens on for sign-ins: the one the agent is being used on. */
    @Volatile var browserDisplay = 0

    /** Blocks while it looks up the connect port when none is saved; call it off the main thread. */
    fun prepare(app: Context): Link {
        val key = File(app.noBackupFilesDir, "wireless-adb-key.pem")
        val address = WirelessAdbFix.savedConnectionAddress(app)
        if (address == null || !key.isFile) return Link(LEGACY, VENDOR_KEY, false)
        val guestKey = File(LinuxRuntime.rootDir(app), VENDOR_KEY.removePrefix("/"))
        runCatching {
            if (!guestKey.isFile || !guestKey.readBytes().contentEquals(key.readBytes())) {
                guestKey.parentFile?.mkdirs()
                guestKey.writeBytes(key.readBytes())
                guestKey.setReadable(false, false)
                guestKey.setReadable(true, true)
            }
        }
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toIntOrNull()
            ?: WirelessAdbFix.findConnectPort(app, host.trim('[', ']'))
            ?: return Link(LEGACY, VENDOR_KEY, false)
        return Link("$host:$port", VENDOR_KEY, true)
    }
}
