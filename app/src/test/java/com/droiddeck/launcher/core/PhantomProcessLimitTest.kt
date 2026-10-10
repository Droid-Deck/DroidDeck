package com.droiddeck.launcher.core

import android.content.Context
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PhantomProcessLimitTest {
    @Before
    fun clearPrefs() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("phantom-process-limit", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun blocksSteamOnlyWhenBlocking() {
        assertFalse(PhantomProcessLimit.blocksSteam(PhantomProcessStatus.NOT_APPLICABLE))
        assertFalse(PhantomProcessLimit.blocksSteam(PhantomProcessStatus.DISABLED))
        assertFalse(PhantomProcessLimit.blocksSteam(PhantomProcessStatus.OVERRIDDEN))
        assertTrue(PhantomProcessLimit.blocksSteam(PhantomProcessStatus.ENABLED))
        assertTrue(PhantomProcessLimit.blocksSteam(PhantomProcessStatus.UNSET))
        assertTrue(PhantomProcessLimit.blocksSteam(PhantomProcessStatus.UNREADABLE))
    }

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
    fun android12DefaultsToUnreadableWithoutSavedState() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(PhantomProcessStatus.UNREADABLE, PhantomProcessLimit.read(context, sdk = 31))
    }

    @Test
    fun android12ReturnsDisabledWhenRemembered() {
        val context = RuntimeEnvironment.getApplication()
        PhantomProcessLimit.rememberAndroid12(context, off = true)
        assertEquals(PhantomProcessStatus.DISABLED, PhantomProcessLimit.read(context, sdk = 31))
    }

    @Test
    fun android12ReturnsOverriddenWhenManualOverrideIsSet() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(PhantomProcessLimit.isOverridden(context))
        PhantomProcessLimit.setOverridden(context, true)
        assertTrue(PhantomProcessLimit.isOverridden(context))
        assertEquals(PhantomProcessStatus.OVERRIDDEN, PhantomProcessLimit.read(context, sdk = 31))
    }

    @Test
    fun clearingOverrideRestoresUnreadableOnAndroid12() {
        val context = RuntimeEnvironment.getApplication()
        PhantomProcessLimit.setOverridden(context, true)
        assertEquals(PhantomProcessStatus.OVERRIDDEN, PhantomProcessLimit.read(context, sdk = 31))
        PhantomProcessLimit.setOverridden(context, false)
        assertFalse(PhantomProcessLimit.isOverridden(context))
        assertEquals(PhantomProcessStatus.UNREADABLE, PhantomProcessLimit.read(context, sdk = 31))
    }

    @Test
    fun android12ReturnsDisabledWhenGlobalSettingIsFalse() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Global.putString(context.contentResolver, "settings_enable_monitor_phantom_procs", "false")
        assertEquals(PhantomProcessStatus.DISABLED, PhantomProcessLimit.read(context, sdk = 31))
    }

    @Test
    fun android13ReturnsOverriddenWhenUnsetAndOverridden() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(PhantomProcessStatus.UNSET, PhantomProcessLimit.read(context, sdk = 33))
        PhantomProcessLimit.setOverridden(context, true)
        assertEquals(PhantomProcessStatus.OVERRIDDEN, PhantomProcessLimit.read(context, sdk = 33))
    }

    @Test
    fun android13PrefersVerifiedDisabledOverOverride() {
        val context = RuntimeEnvironment.getApplication()
        PhantomProcessLimit.setOverridden(context, true)
        Settings.Global.putString(context.contentResolver, "settings_enable_monitor_phantom_procs", "false")
        assertEquals(PhantomProcessStatus.DISABLED, PhantomProcessLimit.read(context, sdk = 33))
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
