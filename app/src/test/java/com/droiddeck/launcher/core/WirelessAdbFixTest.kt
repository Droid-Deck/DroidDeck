package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class WirelessAdbFixTest {
    private val globalOff = "settings put global settings_enable_monitor_phantom_procs false"
    private val deviceConfigOff = "device_config put activity_manager max_phantom_processes 2147483647"
    private val globalOn = "settings put global settings_enable_monitor_phantom_procs true"
    private val deviceConfigOn = "device_config delete activity_manager max_phantom_processes"

    @Test
    fun legacyPersistentSyncIsRestoredOnlyAfterChildLimitsAreVerified() {
        val shell = Shell(
            global = WirelessAdbFix.ShellResult(0, "true"),
            maxPhantom = WirelessAdbFix.ShellResult(0, "null"),
        )
        val commands = mutableListOf<String>()
        var syncMode = "persistent"

        val result = WirelessAdbFix.changeLimit(true, 33, legacySyncPending = true, recoverLegacySync = true) { command ->
            commands += command
            when (command) {
                "device_config get_sync_disabled_for_tests" -> WirelessAdbFix.ShellResult(0, syncMode)
                "device_config set_sync_disabled_for_tests none" -> {
                    syncMode = "none"
                    WirelessAdbFix.ShellResult(0, "")
                }
                else -> shell.execute(command)
            }
        }

        assertTrue(result.confirmed)
        assertTrue(result.legacySyncRecovered)
        assertEquals("none", syncMode)
        assertTrue(commands.indexOf(PhantomProcessLimit.DEVICE_CONFIG_QUERY) < commands.indexOf("device_config set_sync_disabled_for_tests none"))
    }

    @Test
    fun pendingLegacySyncWithoutConsentRejectsBeforeAnyShellCommand() {
        for (sdk in listOf(31, 33, 35)) {
            val commands = mutableListOf<String>()

            assertThrows(IllegalStateException::class.java) {
                WirelessAdbFix.changeLimit(true, sdk, legacySyncPending = true) { command ->
                    commands += command
                    WirelessAdbFix.ShellResult(0, "true")
                }
            }

            assertTrue("SDK $sdk", commands.isEmpty())
        }
    }

    @Test
    fun newUserRestoreDoesNotReadOrWriteSharedSyncPolicy() {
        val shell = Shell(
            global = WirelessAdbFix.ShellResult(0, "true"),
            maxPhantom = WirelessAdbFix.ShellResult(0, "null"),
        )

        val result = WirelessAdbFix.changeLimit(true, 33, recoverLegacySync = true, shell = shell::execute)

        assertTrue(result.confirmed)
        assertFalse(result.legacySyncRecovered)
        assertFalse(result.legacySyncFailure)
        assertFalse(shell.commands.any { "_sync_disabled_for_tests" in it })
    }

    @Test
    fun disablingChildLimitDoesNotRecoverPendingSyncPolicy() {
        val shell = Shell(
            global = WirelessAdbFix.ShellResult(0, "false"),
            maxPhantom = WirelessAdbFix.ShellResult(0, "2147483647"),
        )

        val result = WirelessAdbFix.changeLimit(false, 33, legacySyncPending = true, shell = shell::execute)

        assertTrue(result.confirmed)
        assertFalse(result.legacySyncRecovered)
        assertFalse(shell.commands.any { "_sync_disabled_for_tests" in it })
    }

    @Test
    fun failedChildVerificationDoesNotTouchLegacySyncPolicy() {
        val shell = Shell(
            global = WirelessAdbFix.ShellResult(0, "true"),
            maxPhantom = WirelessAdbFix.ShellResult(0, "2147483647"),
        )

        val result = WirelessAdbFix.changeLimit(true, 33, legacySyncPending = true, recoverLegacySync = true, shell = shell::execute)

        assertFalse(result.confirmed)
        assertFalse(result.legacySyncRecovered)
        assertFalse(result.legacySyncFailure)
        assertFalse(shell.commands.any { "_sync_disabled_for_tests" in it })
    }

    @Test
    fun unconfirmedLegacyRecoveryReportsPartialFailure() {
        val shell = Shell(
            global = WirelessAdbFix.ShellResult(0, "true"),
            maxPhantom = WirelessAdbFix.ShellResult(0, "null"),
        )

        val result = WirelessAdbFix.changeLimit(true, 33, legacySyncPending = true, recoverLegacySync = true) { command ->
            when (command) {
                LegacyDeviceConfigSync.GET_MODE -> WirelessAdbFix.ShellResult(0, "persistent")
                LegacyDeviceConfigSync.RESTORE -> WirelessAdbFix.ShellResult(0, "")
                else -> shell.execute(command)
            }
        }

        assertFalse(result.confirmed)
        assertTrue(result.legacySyncFailure)
        assertFalse(result.legacySyncRecovered)
        assertEquals("true", result.readings.global)
        assertEquals("null", result.readings.maxPhantom)
    }

    @Test
    fun legacyMarkerRemainsRecoverableAfterAndroidUpgrade() {
        for (sdk in listOf(31, 35)) {
            val shell = Shell(
                global = WirelessAdbFix.ShellResult(0, "true"),
                maxPhantom = WirelessAdbFix.ShellResult(0, "null"),
            )

            val result = WirelessAdbFix.changeLimit(true, sdk, legacySyncPending = true, recoverLegacySync = true) { command ->
                if (command == LegacyDeviceConfigSync.GET_MODE) WirelessAdbFix.ShellResult(0, "until_reboot")
                else shell.execute(command)
            }

            assertTrue("SDK $sdk", result.confirmed)
            assertTrue("SDK $sdk", result.legacySyncRecovered)
            assertFalse(shell.commands.any { "_sync_disabled_for_tests" in it })
        }
    }

    private fun address(vararg octets: Int): InetAddress = InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())

    @Test
    fun loopbackIsLocalEvenWhenNetworkInterfacesCannotBeRead() {
        assertTrue(WirelessAdbFix.isLocalAddress(address(127, 0, 0, 1)) {
            throw SecurityException("Network interfaces unavailable")
        })
    }

    @Test
    fun deviceNetworkAddressIsLocal() {
        val local = address(192, 168, 1, 20)

        assertTrue(WirelessAdbFix.isLocalAddress(local) { listOf(local) })
        assertFalse(WirelessAdbFix.isLocalAddress(address(192, 168, 1, 21)) { listOf(local) })
    }

    @Test
    fun networkInterfaceFailureDoesNotTrustArbitraryIpv4Address() {
        assertFalse(WirelessAdbFix.isLocalAddress(address(192, 168, 1, 21)) {
            throw SecurityException("Network interfaces unavailable")
        })
    }

    @Test
    fun localHostGuardUsesResolvedNumericAddressForTheConnection() {
        var verificationPresent = true
        val connected = WirelessAdbFix.usingLocalHost(
            "device.example",
            { "Local device required" },
            resolve = { address(127, 0, 0, 1) },
            clearVerification = { verificationPresent = false },
        ) { host -> host }

        assertEquals("127.0.0.1", connected)
        assertFalse(verificationPresent)
    }

    @Test
    fun remoteHostIsRejectedBeforeShellCallsAndClearsOldVerification() {
        var verificationPresent = true
        var shellCalls = 0
        val remote = address(192, 168, 1, 21)
        val local = address(192, 168, 1, 20)

        assertThrows(IllegalArgumentException::class.java) {
            WirelessAdbFix.usingLocalHost(
                "remote.example",
                { "Local device required" },
                resolve = { remote },
                localAddress = { candidate -> WirelessAdbFix.isLocalAddress(candidate) { listOf(local) } },
                clearVerification = { verificationPresent = false },
            ) {
                shellCalls++
                verificationPresent = true
            }
        }

        assertEquals(0, shellCalls)
        assertFalse(verificationPresent)
    }

    @Test
    fun addressResolutionFailureDoesNotRunShellOrRetainVerification() {
        var verificationPresent = true
        var shellCalls = 0

        assertThrows(IllegalArgumentException::class.java) {
            WirelessAdbFix.usingLocalHost(
                "unresolvable.example",
                { "Local device required" },
                resolve = { throw UnknownHostException("No address") },
                clearVerification = { verificationPresent = false },
            ) {
                shellCalls++
            }
        }

        assertEquals(0, shellCalls)
        assertFalse(verificationPresent)
    }

    @Test
    fun localAddressCheckFailureDoesNotRunShellOrRetainVerification() {
        var verificationPresent = true
        var shellCalls = 0

        assertThrows(IllegalArgumentException::class.java) {
            WirelessAdbFix.usingLocalHost(
                "device.example",
                { "Local device required" },
                resolve = { address(192, 168, 1, 20) },
                localAddress = { throw SecurityException("Network interfaces unavailable") },
                clearVerification = { verificationPresent = false },
            ) {
                shellCalls++
            }
        }

        assertEquals(0, shellCalls)
        assertFalse(verificationPresent)
    }

    private class Shell(
        private val global: WirelessAdbFix.ShellResult = WirelessAdbFix.ShellResult(0, "null\n"),
        private val maxPhantom: WirelessAdbFix.ShellResult = WirelessAdbFix.ShellResult(0, "32\n"),
        private val override: WirelessAdbFix.ShellResult = WirelessAdbFix.ShellResult(0, ""),
        private val failures: Map<String, WirelessAdbFix.ShellResult> = emptyMap(),
        private val throwing: String? = null,
    ) {
        val commands = mutableListOf<String>()

        fun execute(command: String): WirelessAdbFix.ShellResult {
            commands += command
            if (command == throwing) throw SecurityException("WRITE_SECURE_SETTINGS denied")
            return failures[command] ?: when (command) {
                PhantomProcessLimit.GLOBAL_QUERY -> global
                PhantomProcessLimit.OVERRIDE_QUERY -> override
                PhantomProcessLimit.DEVICE_CONFIG_QUERY -> maxPhantom
                else -> WirelessAdbFix.ShellResult(0, "")
            }
        }
    }

    @Test
    fun deniedGlobalWriteDoesNotPreventDeviceConfigFallback() {
        val shell = Shell(
            maxPhantom = WirelessAdbFix.ShellResult(0, "2147483647\n"),
            failures = mapOf(globalOff to WirelessAdbFix.ShellResult(1, "", "SecurityException")),
        )

        val result = WirelessAdbFix.changeLimit(false, 33, shell = shell::execute)

        assertTrue(result.confirmed)
        assertTrue(shell.commands.containsAll(listOf(globalOff, deviceConfigOff)))
        assertTrue(result.diagnostics.contains("SecurityException"))
    }

    @Test
    fun deniedDeviceConfigWriteDoesNotPreventGlobalFallback() {
        val shell = Shell(
            global = WirelessAdbFix.ShellResult(0, "false\n"),
            failures = mapOf(deviceConfigOff to WirelessAdbFix.ShellResult(1, "", "Permission denied")),
        )

        val result = WirelessAdbFix.changeLimit(false, 35, shell = shell::execute)

        assertTrue(result.confirmed)
        assertTrue(shell.commands.containsAll(listOf(globalOff, deviceConfigOff)))
    }

    @Test
    fun shellExceptionDoesNotStopOtherCommandsOrVerification() {
        val shell = Shell(
            maxPhantom = WirelessAdbFix.ShellResult(0, "2147483647"),
            throwing = globalOff,
        )

        val result = WirelessAdbFix.changeLimit(false, 31, shell = shell::execute)

        assertTrue(result.confirmed)
        assertTrue(shell.commands.containsAll(listOf(globalOff, deviceConfigOff)))
        assertTrue(shell.commands.containsAll(listOf(PhantomProcessLimit.GLOBAL_QUERY, PhantomProcessLimit.DEVICE_CONFIG_QUERY)))
    }

    @Test
    fun successfulCommandExitWithoutChangedValuesIsNotConfirmation() {
        val shell = Shell()

        val result = WirelessAdbFix.changeLimit(false, 33, shell = shell::execute)

        assertFalse(result.confirmed)
        assertEquals("null\n", result.readings.global)
        assertEquals("32\n", result.readings.maxPhantom)
    }

    @Test
    fun numericZeroReadBackConfirmsMonitoringDisabled() {
        val shell = Shell(global = WirelessAdbFix.ShellResult(0, "0\n"))

        assertTrue(WirelessAdbFix.changeLimit(false, 33, shell = shell::execute).confirmed)
    }

    @Test
    fun developerPropertyCanConfirmMonitoringDisabledWhenGlobalIsUnset() {
        val shell = Shell(override = WirelessAdbFix.ShellResult(0, "false\n"))

        assertTrue(WirelessAdbFix.changeLimit(false, 34, shell = shell::execute).confirmed)
    }

    @Test
    fun android12StillRequiresVerifiedLargeDeviceConfigLimit() {
        val shell = Shell(global = WirelessAdbFix.ShellResult(0, "false"))

        assertFalse(WirelessAdbFix.changeLimit(false, 31, shell = shell::execute).confirmed)
    }

    @Test
    fun unreadableGlobalDoesNotHideVerifiedLargeLimit() {
        val shell = Shell(
            global = WirelessAdbFix.ShellResult(1, "", "Permission denied"),
            maxPhantom = WirelessAdbFix.ShellResult(0, "2147483647"),
        )

        val result = WirelessAdbFix.changeLimit(false, 33, shell = shell::execute)

        assertTrue(result.confirmed)
        assertFalse(result.readings.globalReadable)
        assertEquals(null, result.readings.global)
    }

    @Test
    fun unreadableDeviceConfigDoesNotHideVerifiedGlobalFlag() {
        val shell = Shell(
            global = WirelessAdbFix.ShellResult(0, "false"),
            maxPhantom = WirelessAdbFix.ShellResult(1, "", "Permission denied"),
        )

        val result = WirelessAdbFix.changeLimit(false, 35, shell = shell::execute)

        assertTrue(result.confirmed)
        assertFalse(result.readings.deviceConfigReadable)
        assertEquals(null, result.readings.maxPhantom)
    }

    @Test
    fun failedReadBackDoesNotConfirmSuccessfulWrites() {
        val shell = Shell(
            global = WirelessAdbFix.ShellResult(1, "", "Global read failed"),
            maxPhantom = WirelessAdbFix.ShellResult(1, "", "DeviceConfig read failed"),
        )

        val result = WirelessAdbFix.changeLimit(false, 33, shell = shell::execute)

        assertFalse(result.confirmed)
        assertTrue(result.diagnostics.contains("Global read failed"))
        assertTrue(result.diagnostics.contains("DeviceConfig read failed"))
    }

    @Test
    fun nonzeroReadExitDoesNotTrustOtherwiseValidOutput() {
        val shell = Shell(
            global = WirelessAdbFix.ShellResult(1, "false", "Global read failed"),
            maxPhantom = WirelessAdbFix.ShellResult(1, "2147483647", "DeviceConfig read failed"),
        )

        assertFalse(WirelessAdbFix.changeLimit(false, 33, shell = shell::execute).confirmed)
    }

    @Test
    fun refreshReadsAllSourcesWithoutWritingSettings() {
        val shell = Shell()

        val result = WirelessAdbFix.readLimit(shell::execute)

        assertEquals(listOf(
            PhantomProcessLimit.GLOBAL_QUERY,
            PhantomProcessLimit.OVERRIDE_QUERY,
            PhantomProcessLimit.DEVICE_CONFIG_QUERY,
        ), shell.commands)
        assertTrue(result.globalReadable)
        assertTrue(result.overrideReadable)
        assertTrue(result.deviceConfigReadable)
    }

    @Test
    fun permissionErrorWithZeroExitCodeDoesNotCountAsSettingValue() {
        val shell = Shell(
            global = WirelessAdbFix.ShellResult(0, "SecurityException: permission denied"),
            maxPhantom = WirelessAdbFix.ShellResult(0, "Permission denied"),
        )

        assertFalse(WirelessAdbFix.changeLimit(false, 33, shell = shell::execute).confirmed)
        assertFalse(WirelessAdbFix.changeLimit(true, 33, shell = shell::execute).confirmed)
    }

    @Test
    fun restoringFlagAloneDoesNotRestoreRestrictionWithLargeLimit() {
        val shell = Shell(
            global = WirelessAdbFix.ShellResult(0, "true"),
            maxPhantom = WirelessAdbFix.ShellResult(0, "2147483647"),
        )

        assertFalse(WirelessAdbFix.changeLimit(true, 33, shell = shell::execute).confirmed)
        assertTrue(shell.commands.containsAll(listOf(globalOn, deviceConfigOn)))
    }

    @Test
    fun restoringMaxAloneDoesNotRestoreRestrictionWithDisabledFlag() {
        val shell = Shell(global = WirelessAdbFix.ShellResult(0, "false"))

        assertFalse(WirelessAdbFix.changeLimit(true, 35, shell = shell::execute).confirmed)
    }

    @Test
    fun restoredFlagAndDefaultMaxConfirmRestrictionEnabled() {
        val shell = Shell(
            global = WirelessAdbFix.ShellResult(0, "true"),
            maxPhantom = WirelessAdbFix.ShellResult(0, "null\n"),
        )

        assertTrue(WirelessAdbFix.changeLimit(true, 33, shell = shell::execute).confirmed)
        assertTrue(shell.commands.containsAll(listOf(globalOn, deviceConfigOn)))
        assertFalse(shell.commands.any { "set_sync_disabled_for_tests" in it })
    }

    @Test
    fun emptyDeviceConfigStdoutCannotConfirmRestoration() {
        for (sdk in listOf(31, 33)) {
            val shell = Shell(
                global = WirelessAdbFix.ShellResult(0, "true"),
                maxPhantom = WirelessAdbFix.ShellResult(0, " \n"),
            )

            val result = WirelessAdbFix.changeLimit(true, sdk, shell = shell::execute)

            assertFalse("SDK $sdk", result.confirmed)
            assertFalse("SDK $sdk", result.readings.deviceConfigReadable)
        }
    }

    @Test
    fun deviceConfigErrorOnlyOnStderrCannotConfirmRestoration() {
        for (sdk in listOf(31, 33)) {
            val shell = Shell(
                global = WirelessAdbFix.ShellResult(0, "true"),
                maxPhantom = WirelessAdbFix.ShellResult(0, "", "Permission denied"),
            )

            val result = WirelessAdbFix.changeLimit(true, sdk, shell = shell::execute)

            assertFalse("SDK $sdk", result.confirmed)
            assertTrue(result.diagnostics.contains("Permission denied"))
        }
    }

    @Test
    fun emptyGlobalStdoutDoesNotAllowFallbackToDisabledProperty() {
        val shell = Shell(
            global = WirelessAdbFix.ShellResult(0, ""),
            override = WirelessAdbFix.ShellResult(0, "false"),
        )

        val result = WirelessAdbFix.changeLimit(false, 33, shell = shell::execute)

        assertFalse(result.confirmed)
        assertFalse(result.readings.globalReadable)
    }

    @Test
    fun emptyMaxIsNotReadableAndDoesNotConfirmDisabledState() {
        val shell = Shell(maxPhantom = WirelessAdbFix.ShellResult(0, ""))

        val result = WirelessAdbFix.changeLimit(false, 31, shell = shell::execute)

        assertFalse(result.confirmed)
        assertFalse(result.readings.deviceConfigReadable)
        assertEquals(null, result.readings.maxPhantom)
    }

    @Test
    fun allWriteFailuresAreRetainedWhenNoSettingCanBeVerified() {
        val shell = Shell(failures = mapOf(
            globalOff to WirelessAdbFix.ShellResult(1, "", "Global write failed"),
            deviceConfigOff to WirelessAdbFix.ShellResult(1, "", "DeviceConfig write failed"),
        ))

        val result = WirelessAdbFix.changeLimit(false, 33, shell = shell::execute)

        assertFalse(result.confirmed)
        assertTrue(result.diagnostics.contains("Global write failed"))
        assertTrue(result.diagnostics.contains("DeviceConfig write failed"))
    }
}
