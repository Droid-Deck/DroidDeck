package com.droiddeck.launcher.core

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.droiddeck.launcher.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WirelessAdbShellTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun saveRemotePairing() {
        val directory = context.noBackupFilesDir
        File(directory, "wireless-adb-cert.pem").writeText("invalid certificate")
        File(directory, "wireless-adb-key.pem").writeText("invalid key")
        File(directory, "wireless-adb-host.txt").writeText("203.0.113.10")
        File(directory, "wireless-adb-connection.txt").writeText("203.0.113.10\n5555")
    }

    @After
    fun removePairingFixture() {
        for (name in listOf("wireless-adb-cert.pem", "wireless-adb-key.pem", "wireless-adb-host.txt", "wireless-adb-connection.txt")) {
            File(context.noBackupFilesDir, name).delete()
        }
    }

    @Test
    fun shellRejectsSavedRemoteAddressBeforeLoadingTheIdentity() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WirelessAdbFix.shell(context, "am kill-all")
        }

        assertEquals(context.getString(R.string.adbfix_local_only), error.message)
        assertEquals("203.0.113.10:5555", WirelessAdbFix.savedConnectionAddress(context))
    }

    @Test
    fun cachedAppCleanupUsesTheSameLocalOnlyShellPath() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WirelessAdbFix.clearCachedApps(context)
        }

        assertEquals(context.getString(R.string.adbfix_local_only), error.message)
        assertEquals("203.0.113.10:5555", WirelessAdbFix.savedConnectionAddress(context))
    }
}
