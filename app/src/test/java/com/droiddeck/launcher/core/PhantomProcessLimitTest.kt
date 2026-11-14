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
    fun android12AndNewerAttemptBothSupportedControls() {
        for (sdk in listOf(31, 32, 33, 34, 35, 36)) {
            assertEquals(
                listOf(
                    "device_config put activity_manager max_phantom_processes 2147483647",
                    "settings put global settings_enable_monitor_phantom_procs false",
                ),
                PhantomProcessLimit.shellCommands(enabled = false, sdk = sdk),
            )
            assertEquals(
                listOf(
                    "device_config delete activity_manager max_phantom_processes",
                    "settings put global settings_enable_monitor_phantom_procs true",
                ),
                PhantomProcessLimit.shellCommands(enabled = true, sdk = sdk),
            )
        }
    }

    @Test
    fun computerCommandAttemptsBothControlsInOneQuotedShell() {
        assertEquals(
            "adb shell \"device_config put activity_manager max_phantom_processes 2147483647; " +
                "settings put global settings_enable_monitor_phantom_procs false\"",
            PhantomProcessLimit.adbCommand(false, 33),
        )
        assertEquals(
            "adb shell \"device_config delete activity_manager max_phantom_processes; " +
                "settings put global settings_enable_monitor_phantom_procs true\"",
            PhantomProcessLimit.adbCommand(true, 35),
        )
    }

    @Test
    fun android12IgnoresTheLaterMonitorFlag() {
        assertEquals(PhantomProcessStatus.ENABLED, PhantomProcessLimit.status("false", "false", "32", sdk = 31))
        assertEquals(PhantomProcessStatus.UNREADABLE,
            PhantomProcessLimit.status("false", "false", deviceConfigReadable = false, sdk = 31))
        assertEquals(PhantomProcessStatus.DISABLED, PhantomProcessLimit.status("true", null, "2147483647", sdk = 31))
    }

    @Test
    fun disablingTheMonitorStillWorksWhenDeviceConfigCannotBeRead() {
        assertEquals(PhantomProcessStatus.DISABLED,
            PhantomProcessLimit.status("false", null, deviceConfigReadable = false, sdk = 33))
        assertEquals(PhantomProcessStatus.DISABLED,
            PhantomProcessLimit.status(null, "0", deviceConfigReadable = false, sdk = 34))
    }

    @Test
    fun hyperOsDeviceConfigWorkaroundIsAcceptedOnAndroid15() {
        assertTrue(PhantomProcessLimit.verificationMatches(null, "2147483647\n", true, true, false, 35))
    }

    @Test
    fun realmeDeviceConfigWorkaroundIsAcceptedOnAndroid13() {
        assertTrue(PhantomProcessLimit.verificationMatches(null, "2147483647\n", true, true, false, 33))
    }

    @Test
    fun numericZeroCanDisableButOneDoesNotProveMonitoringWasRestored() {
        assertTrue(PhantomProcessLimit.verificationMatches("0\n", null, true, false, false, 33))
        assertFalse(PhantomProcessLimit.verificationMatches("1\n", "32", true, true, true, 34))
        assertEquals(PhantomProcessStatus.ENABLED, PhantomProcessLimit.status("1", null, "32", sdk = 34))
    }

    @Test
    fun unknownAndroid12ReadbackDoesNotProveMonitoringIsEnabled() {
        assertFalse(PhantomProcessLimit.verificationMatches(null, "permission denied", false, false, true, 31))
        assertFalse(PhantomProcessLimit.verificationMatches(null, "2147483647", false, true, true, 31))
    }

    @Test
    fun hyperOsAndRealmeCountWorkaroundDoesNotRequireTheGlobalSetting() {
        for (sdk in listOf(33, 35)) {
            assertEquals(PhantomProcessStatus.DISABLED,
                PhantomProcessLimit.status(null, null, "2147483647\n", sdk = sdk))
            assertEquals(PhantomProcessStatus.DISABLED,
                PhantomProcessLimit.status(null, null, "2147483647", globalReadable = false, sdk = sdk))
        }
    }

    @Test
    fun highCountLimitIsIndependentOfAnEnabledMonitorFlag() {
        assertEquals(PhantomProcessStatus.DISABLED,
            PhantomProcessLimit.status("true", "true", "2147483647", sdk = 33))
    }

    @Test
    fun permissionErrorsAndMalformedValuesNeverDisableTheGate() {
        for (maximum in listOf("permission denied", "2147483648", "-1", "1024", "2147483647 extra")) {
            assertTrue(PhantomProcessLimit.blocksSteam(PhantomProcessLimit.status(null, null, maximum, sdk = 33)))
        }
        assertEquals(PhantomProcessStatus.UNREADABLE,
            PhantomProcessLimit.status("garbage", null, "32", sdk = 33))
        assertEquals(PhantomProcessStatus.UNREADABLE,
            PhantomProcessLimit.status(null, "false", "32", globalReadable = false, sdk = 33))
        assertEquals(PhantomProcessStatus.UNREADABLE,
            PhantomProcessLimit.status(null, null, deviceConfigReadable = false, sdk = 33))
    }

    @Test
    fun literalNullFallsThroughToPropertyWithoutProvingDisabledOnItsOwn() {
        assertEquals(PhantomProcessStatus.DISABLED, PhantomProcessLimit.status("null", "false", sdk = 33))
        assertEquals(PhantomProcessStatus.UNSET, PhantomProcessLimit.status("null", "null", "null", sdk = 33))
    }

    @Test
    fun enableVerificationRequiresBothTheMonitorAndFiniteCountLimit() {
        assertTrue(PhantomProcessLimit.verificationMatches("true", "null", true, true, true, 33))
        assertFalse(PhantomProcessLimit.verificationMatches("1", "32", true, true, true, 34))
        assertFalse(PhantomProcessLimit.verificationMatches("true", "2147483647", true, true, true, 33))
        assertFalse(PhantomProcessLimit.verificationMatches("false", "32", true, true, true, 33))
        assertFalse(PhantomProcessLimit.verificationMatches("true", "permission denied", true, true, true, 33))
        assertFalse(PhantomProcessLimit.verificationMatches("true", null, true, false, true, 33))
        assertFalse(PhantomProcessLimit.verificationMatches(null, "32", true, true, true, 33, override = "false"))
    }

    @Test
    fun android12EnableVerificationDependsOnlyOnTheRealCountReadback() {
        assertTrue(PhantomProcessLimit.verificationMatches(null, "null", false, true, true, 31))
        assertFalse(PhantomProcessLimit.verificationMatches("true", "2147483647", true, true, true, 31))
        assertFalse(PhantomProcessLimit.verificationMatches("true", null, true, false, true, 31))
    }

    @Test
    fun disableVerificationAcceptsOnlySupportedReliableSources() {
        assertTrue(PhantomProcessLimit.verificationMatches(null, "2147483647", false, true, false, 33))
        assertTrue(PhantomProcessLimit.verificationMatches("0", null, true, false, false, 33))
        assertFalse(PhantomProcessLimit.verificationMatches("false", null, true, false, false, 31))
        assertFalse(PhantomProcessLimit.verificationMatches(null, "32", false, true, false, 33))
        assertFalse(PhantomProcessLimit.verificationMatches(null, "2147483647", false, false, false, 33))
    }

    private val inaccessibleDeviceConfig = PhantomProcessLimit.Readings(null, "true", null, true, true, false)
    private val adbVerifiedUnlimited = PhantomProcessLimit.Readings(null, "true", "2147483647", true, true, true)
    private val snapshot = PhantomProcessLimit.VerificationSnapshot(
        adbVerifiedUnlimited, inaccessibleDeviceConfig, 33, "rom-build", 100_000L,
    )

    @Test
    fun realAdbSnapshotCanBridgeThePrivateDeviceConfigReadPermission() {
        assertEquals(PhantomProcessStatus.DISABLED,
            PhantomProcessLimit.snapshotStatus(snapshot, inaccessibleDeviceConfig, 33, "rom-build", 100_001L))
    }

    @Test
    fun verificationExpiresAndCannotSurviveDifferentBootOrSystemBuild() {
        assertEquals(null,
            PhantomProcessLimit.snapshotStatus(snapshot, inaccessibleDeviceConfig, 33, "rom-build", 160_001L))
        assertEquals(null,
            PhantomProcessLimit.snapshotStatus(snapshot, inaccessibleDeviceConfig, 33, "rom-build", 99_999L))
        assertEquals(null,
            PhantomProcessLimit.snapshotStatus(snapshot, inaccessibleDeviceConfig, 34, "rom-build", 100_001L))
        assertEquals(null,
            PhantomProcessLimit.snapshotStatus(snapshot, inaccessibleDeviceConfig, 33, "new-rom", 100_001L))
    }

    @Test
    fun currentReadableSettingsCannotBeOverriddenByAnOldSnapshot() {
        for (current in listOf(
            inaccessibleDeviceConfig.copy(global = "true"),
            inaccessibleDeviceConfig.copy(override = "false"),
            inaccessibleDeviceConfig.copy(globalReadable = false),
            inaccessibleDeviceConfig.copy(overrideReadable = false),
            inaccessibleDeviceConfig.copy(maxPhantom = "32", deviceConfigReadable = true),
        )) {
            assertEquals(null, PhantomProcessLimit.snapshotStatus(snapshot, current, 33, "rom-build", 100_001L))
        }
    }

    @Test
    fun monitorChangedBetweenShellReadAndSnapshotCreationUsesCurrentGlobal() {
        val current = inaccessibleDeviceConfig.copy(global = "true")
        val raced = snapshot.copy(
            readings = adbVerifiedUnlimited.copy(global = "false", maxPhantom = "32"),
            observed = current,
        )
        assertEquals(PhantomProcessStatus.ENABLED,
            PhantomProcessLimit.snapshotStatus(raced, current, 33, "rom-build", 100_001L))
    }

    @Test
    fun monitorChangedBetweenShellReadAndSnapshotCreationUsesCurrentProperty() {
        val raced = snapshot.copy(readings = adbVerifiedUnlimited.copy(override = "false", maxPhantom = "32"))
        assertEquals(PhantomProcessStatus.ENABLED,
            PhantomProcessLimit.snapshotStatus(raced, inaccessibleDeviceConfig, 33, "rom-build", 100_001L))
    }

    @Test
    fun currentGlobalStillWinsOverAConflictingCachedProperty() {
        val current = inaccessibleDeviceConfig.copy(global = "true", override = "false")
        val raced = snapshot.copy(
            readings = adbVerifiedUnlimited.copy(global = null, override = "false", maxPhantom = "32"),
            observed = current,
        )
        assertEquals(PhantomProcessStatus.ENABLED,
            PhantomProcessLimit.snapshotStatus(raced, current, 33, "rom-build", 100_001L))
    }

    @Test
    fun verifiedUnlimitedCountRemainsIndependentOfCurrentMonitorFlag() {
        val current = inaccessibleDeviceConfig.copy(global = "true")
        val raced = snapshot.copy(readings = adbVerifiedUnlimited.copy(global = "false"), observed = current)
        assertEquals(PhantomProcessStatus.DISABLED,
            PhantomProcessLimit.snapshotStatus(raced, current, 33, "rom-build", 100_001L))
    }

    @Test
    fun snapshotDoesNotReplaceAnUnreadableCurrentMonitorFlag() {
        val current = inaccessibleDeviceConfig.copy(globalReadable = false)
        val raced = snapshot.copy(
            readings = adbVerifiedUnlimited.copy(global = "false", maxPhantom = "32"),
            observed = current,
        )
        assertEquals(PhantomProcessStatus.UNREADABLE,
            PhantomProcessLimit.snapshotStatus(raced, current, 33, "rom-build", 100_001L))
    }

    @Test
    fun unknownAdbReadbackDoesNotTurnIntoAnOffSnapshot() {
        val unknown = snapshot.copy(readings = adbVerifiedUnlimited.copy(maxPhantom = null, deviceConfigReadable = false))
        assertEquals(PhantomProcessStatus.UNREADABLE,
            PhantomProcessLimit.snapshotStatus(unknown, inaccessibleDeviceConfig, 33, "rom-build", 100_001L))
    }

    @Test
    fun developerToggleStartsAtAndroid14() {
        assertFalse(PhantomProcessLimit.hasDeveloperToggle(33))
        assertTrue(PhantomProcessLimit.hasDeveloperToggle(34))
    }
}
