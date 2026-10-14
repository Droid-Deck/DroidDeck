package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhantomProcessLimitTest {
    @Test
    fun developerOptionsToggleAloneCountsAsOff() {
        assertEquals(PhantomProcessStatus.DISABLED, PhantomProcessLimit.status(null, "false"))
    }

    @Test
    fun globalSettingWinsOverTheProperty() {
        assertEquals(PhantomProcessStatus.DISABLED, PhantomProcessLimit.status("false", "true"))
        assertEquals(PhantomProcessStatus.ENABLED, PhantomProcessLimit.status("true", "false"))
    }

    @Test
    fun emptyGlobalFallsThroughToTheProperty() {
        assertEquals(PhantomProcessStatus.ENABLED, PhantomProcessLimit.status("", "true"))
        assertEquals(PhantomProcessStatus.DISABLED, PhantomProcessLimit.status(" ", "0"))
    }

    @Test
    fun neitherSetIsUnset() {
        assertEquals(PhantomProcessStatus.UNSET, PhantomProcessLimit.status(null, null))
        assertEquals(PhantomProcessStatus.UNSET, PhantomProcessLimit.status("", ""))
    }

    @Test
    fun android12UsesDeviceConfig() {
        assertEquals(
            listOf(
                "device_config set_sync_disabled_for_tests persistent",
                "device_config put activity_manager max_phantom_processes 2147483647",
            ),
            PhantomProcessLimit.shellCommands(enabled = false, sdk = 31),
        )
        assertTrue(PhantomProcessLimit.verified("2147483647\n", enabled = false, sdk = 31))
        assertFalse(PhantomProcessLimit.verified("null", enabled = false, sdk = 31))
    }

    @Test
    fun android12LAndLaterUseTheFlag() {
        assertEquals(
            listOf("settings put global settings_enable_monitor_phantom_procs false"),
            PhantomProcessLimit.shellCommands(enabled = false, sdk = 33),
        )
        assertTrue(PhantomProcessLimit.verified("false", enabled = false, sdk = 34))
        assertFalse(PhantomProcessLimit.verified("null", enabled = false, sdk = 33))
    }

    @Test
    fun developerToggleStartsAtAndroid14() {
        assertFalse(PhantomProcessLimit.hasDeveloperToggle(33))
        assertTrue(PhantomProcessLimit.hasDeveloperToggle(34))
    }
}
