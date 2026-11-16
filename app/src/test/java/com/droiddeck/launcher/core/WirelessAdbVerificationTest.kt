package com.droiddeck.launcher.core

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [DeniedActivityManagerConfig::class])
class WirelessAdbVerificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pairingFiles = listOf("wireless-adb-cert.pem", "wireless-adb-key.pem", "wireless-adb-host.txt", "wireless-adb-connection.txt")

    @Before
    fun setUp() {
        Settings.Global.putString(context.contentResolver, "settings_enable_monitor_phantom_procs", "true")
        rememberUnlimitedCount()
        assertEquals(PhantomProcessStatus.DISABLED, PhantomProcessLimit.read(context))
        val directory = context.noBackupFilesDir
        File(directory, pairingFiles[0]).writeText("invalid certificate")
        File(directory, pairingFiles[1]).writeText("invalid key")
        File(directory, pairingFiles[2]).writeText("203.0.113.10")
        File(directory, pairingFiles[3]).writeText("203.0.113.10\n5555")
    }

    @After
    fun tearDown() {
        PhantomProcessLimit.clearVerification()
        pairingFiles.forEach { File(context.noBackupFilesDir, it).delete() }
    }

    private fun rememberUnlimitedCount() {
        PhantomProcessLimit.rememberVerification(context, "true", "2147483647", true, true)
    }

    @Test
    fun ordinaryLocalAddressValidationPreservesVerifiedCount() {
        assertEquals("127.0.0.1", WirelessAdbFix.usingLocalHost("127.0.0.1", { "Local device required" }) { it })

        assertEquals(PhantomProcessStatus.DISABLED, PhantomProcessLimit.read(context))
    }

    @Test
    fun unrelatedShellRejectionPreservesVerifiedCount() {
        assertThrows(IllegalArgumentException::class.java) {
            WirelessAdbFix.shell(context, BackgroundAppLimit.readCommand())
        }

        assertEquals(PhantomProcessStatus.DISABLED, PhantomProcessLimit.read(context))
    }

    @Test
    fun directLimitWriteAttemptClearsVerifiedCountBeforeConnection() {
        assertThrows(IllegalArgumentException::class.java) {
            WirelessAdbFix.setChildProcessLimit(context, "203.0.113.10", 5555, false)
        }

        assertEquals(PhantomProcessStatus.UNREADABLE, PhantomProcessLimit.read(context))
    }

    @Test
    fun savedLimitWriteAttemptClearsVerifiedCountBeforeConnection() {
        assertThrows(IllegalArgumentException::class.java) {
            WirelessAdbFix.setUsingSavedPairing(context, false)
        }

        assertEquals(PhantomProcessStatus.UNREADABLE, PhantomProcessLimit.read(context))
    }

    @Test
    fun failedRefreshDoesNotRetainEarlierVerification() {
        assertFalse(WirelessAdbFix.refreshUsingSavedPairing(context))

        assertEquals(PhantomProcessStatus.UNREADABLE, PhantomProcessLimit.read(context))
    }
}

@Implements(className = "android.provider.DeviceConfig", isInAndroidSdk = false)
class DeniedActivityManagerConfig {
    companion object {
        @JvmStatic
        @Implementation
        fun getProperty(namespace: String, name: String): String? {
            if (namespace == "activity_manager") throw SecurityException("DeviceConfig read denied")
            return null
        }
    }
}
