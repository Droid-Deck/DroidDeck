package com.droiddeck.launcher.session

import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SteamRepairTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before @After fun idle() {
        SessionState.running = false
        SessionState.steamClientArch = null
    }

    private fun marker(home: String) = File(LinuxRuntime.rootDir(app), "root/$home/package/droiddeck-unconfirmed").apply {
        parentFile?.mkdirs()
        writeText("tracked\nstart\nstart\n")
    }

    @Test fun aShownClientClearsOnlyItsOwnUnconfirmedStarts() {
        val arm = marker(".local/share/Steam")
        val x64 = marker(".droiddeck-x64/.local/share/Steam")
        SteamRepair.clientShown(app)
        assertFalse(arm.exists())
        assertTrue(x64.exists())

        marker(".local/share/Steam")
        SessionPrefs.setSteamClientArch(app, "x86_64")
        SteamRepair.clientShown(app)
        assertTrue(arm.exists())
        assertFalse(x64.exists())
    }
}
