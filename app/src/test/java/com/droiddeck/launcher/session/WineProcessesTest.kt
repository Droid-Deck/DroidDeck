package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WineProcessesTest {
    private fun argv(vararg args: String) = args.joinToString("\u0000", postfix = "\u0000").toByteArray()

    @Test fun aWineserverIsKnownByItsProgramOrItsName() {
        assertTrue(WineProcesses.isWineserver(argv("/root/.local/share/droiddeck-esync/dist/valve-11/files/bin-arm64/wineserver"), "wineserver\n"))
        assertTrue(WineProcesses.isWineserver(argv("wineserver", "-p0"), null))
        assertTrue(WineProcesses.isWineserver(null, "wineserver\n"))
        assertTrue(WineProcesses.isWineserver(ByteArray(0), "wineserver"))
    }

    @Test fun nothingElseIs() {
        assertFalse(WineProcesses.isWineserver(argv("C:\\windows\\system32\\services.exe"), "services.exe\n"))
        assertFalse(WineProcesses.isWineserver(argv("/usr/bin/python3", "/x/files/bin-arm64/wineserver"), "python3\n"))
        assertFalse(WineProcesses.isWineserver(argv("/opt/wineserver-helper"), "wineserver-help"))
        assertFalse(WineProcesses.isWineserver(null, null))
    }
}
