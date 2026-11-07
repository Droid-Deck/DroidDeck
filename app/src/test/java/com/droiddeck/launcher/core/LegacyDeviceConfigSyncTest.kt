package com.droiddeck.launcher.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayDeque

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegacyDeviceConfigSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("phantom-process-limit", Context.MODE_PRIVATE)
    private val get = LegacyDeviceConfigSync.GET_MODE
    private val legacyGet = LegacyDeviceConfigSync.IS_DISABLED
    private val restore = LegacyDeviceConfigSync.RESTORE
    private val unsupported = WirelessAdbFix.ShellResult(255, "", "Unknown command: get_sync_disabled_for_tests")

    private fun ok(value: String) = WirelessAdbFix.ShellResult(0, value)

    private class Script(vararg steps: Pair<String, WirelessAdbFix.ShellResult>) {
        private val remaining = ArrayDeque(steps.toList())

        fun run(command: String): WirelessAdbFix.ShellResult {
            assertFalse("Unexpected shell command: $command", remaining.isEmpty())
            val (expected, result) = remaining.removeFirst()
            assertEquals(expected, command)
            return result
        }

        fun complete() = assertTrue("Expected shell commands were skipped", remaining.isEmpty())
    }

    @Before
    fun clean() {
        prefs.edit().clear().commit()
    }

    @Test
    fun pendingRequiresTheOldBooleanMarker() {
        assertFalse(LegacyDeviceConfigSync.pending(context))
        prefs.edit().putBoolean("android12-off", true).commit()
        assertTrue(LegacyDeviceConfigSync.pending(context))
        prefs.edit().putBoolean("android12-off", false).commit()
        assertFalse(LegacyDeviceConfigSync.pending(context))
        prefs.edit().putString("android12-off", "true").commit()
        assertFalse(LegacyDeviceConfigSync.pending(context))
    }

    @Test
    fun clearingLegacyMarkerPreservesOtherPreferences() {
        prefs.edit().putBoolean("android12-off", true).putString("other", "retained").commit()

        LegacyDeviceConfigSync.clear(context)

        assertFalse(prefs.contains("android12-off"))
        assertFalse(LegacyDeviceConfigSync.pending(context))
        assertEquals("retained", prefs.getString("other", null))
    }

    @Test
    fun noneNeedsNoPolicyWrite() {
        val script = Script(get to ok("none\n"))

        assertTrue(LegacyDeviceConfigSync.recover(script::run).confirmed)
        script.complete()
    }

    @Test
    fun untilRebootIsPreserved() {
        val script = Script(get to ok("until_reboot\n"))

        assertTrue(LegacyDeviceConfigSync.recover(script::run).confirmed)
        script.complete()
    }

    @Test
    fun persistentRequiresWriteAndReadBackNone() {
        val script = Script(get to ok("persistent"), restore to ok(""), get to ok("none"))

        assertTrue(LegacyDeviceConfigSync.recover(script::run).confirmed)
        script.complete()
    }

    @Test
    fun unsuccessfulRestoreKeepsTheLegacyMarker() {
        prefs.edit().putBoolean("android12-off", true).commit()
        val script = Script(get to ok("persistent"), restore to WirelessAdbFix.ShellResult(1, "", "Permission denied"))

        val result = LegacyDeviceConfigSync.recover(script::run)

        assertFalse(result.confirmed)
        assertTrue(result.diagnostics.contains("Permission denied"))
        assertTrue(LegacyDeviceConfigSync.pending(context))
        script.complete()
    }

    @Test
    fun successfulReadDoesNotClearMarkerBeforeCallerFinishes() {
        prefs.edit().putBoolean("android12-off", true).commit()
        val script = Script(get to ok("none"))

        assertTrue(LegacyDeviceConfigSync.recover(script::run).confirmed)
        assertTrue(LegacyDeviceConfigSync.pending(context))
        LegacyDeviceConfigSync.clear(context)
        assertFalse(LegacyDeviceConfigSync.pending(context))
        script.complete()
    }

    @Test
    fun modernReadBackMustBeExactlyNone() {
        for (value in listOf("persistent", "until_reboot", "", "null", "false", "none\nPermission denied")) {
            val script = Script(get to ok("persistent"), restore to ok(""), get to ok(value))

            assertFalse(value, LegacyDeviceConfigSync.recover(script::run).confirmed)
            script.complete()
        }
    }

    @Test
    fun malformedModernModeDoesNotFallBackOrWrite() {
        for (value in listOf("", "null", "false", "true", "PERSISTENT", "none\nnoise")) {
            val script = Script(get to ok(value))

            assertFalse(value, LegacyDeviceConfigSync.recover(script::run).confirmed)
            script.complete()
        }
    }

    @Test
    fun permissionFailureDoesNotFallBackToLegacyCommand() {
        val script = Script(get to WirelessAdbFix.ShellResult(1, "", "Permission denied"))

        assertFalse(LegacyDeviceConfigSync.recover(script::run).confirmed)
        script.complete()
    }

    @Test
    fun nonzeroModernReadExitDoesNotTrustNoneOutput() {
        val script = Script(get to WirelessAdbFix.ShellResult(1, "none", "Permission denied"))

        assertFalse(LegacyDeviceConfigSync.recover(script::run).confirmed)
        script.complete()
    }

    @Test
    fun failedModernReadBackDoesNotTrustNoneOutput() {
        val script = Script(get to ok("persistent"), restore to ok(""), get to WirelessAdbFix.ShellResult(1, "none"))

        assertFalse(LegacyDeviceConfigSync.recover(script::run).confirmed)
        script.complete()
    }

    @Test
    fun legacyFalseNeedsNoPolicyWrite() {
        val script = Script(get to unsupported, legacyGet to ok("false\n"))

        assertTrue(LegacyDeviceConfigSync.recover(script::run).confirmed)
        script.complete()
    }

    @Test
    fun legacyTrueRequiresWriteAndReadBackFalse() {
        val script = Script(get to unsupported, legacyGet to ok("true"), restore to ok(""), legacyGet to ok("false"))

        assertTrue(LegacyDeviceConfigSync.recover(script::run).confirmed)
        script.complete()
    }

    @Test
    fun malformedLegacyBooleanDoesNotWrite() {
        for (value in listOf("", "null", "0", "1", "TRUE", "persistent", "false\nnoise")) {
            val script = Script(get to unsupported, legacyGet to ok(value))

            assertFalse(value, LegacyDeviceConfigSync.recover(script::run).confirmed)
            script.complete()
        }
    }

    @Test
    fun failedLegacyReadDoesNotTrustFalseOutput() {
        val script = Script(get to unsupported, legacyGet to WirelessAdbFix.ShellResult(1, "false"))

        assertFalse(LegacyDeviceConfigSync.recover(script::run).confirmed)
        script.complete()
    }

    @Test
    fun legacyReadBackMustBeFalseWithSuccessfulExit() {
        for (result in listOf(ok("true"), ok(""), ok("0"), WirelessAdbFix.ShellResult(1, "false"))) {
            val script = Script(get to unsupported, legacyGet to ok("true"), restore to ok(""), legacyGet to result)

            assertFalse(LegacyDeviceConfigSync.recover(script::run).confirmed)
            script.complete()
        }
    }

    @Test
    fun shellExceptionKeepsRecoveryUnconfirmed() {
        val result = LegacyDeviceConfigSync.recover { throw SecurityException("Permission denied") }

        assertFalse(result.confirmed)
        assertTrue(result.diagnostics.contains("Permission denied"))
    }
}
