package com.droiddeck.launcher.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.LegacyDeviceConfigSync
import com.droiddeck.launcher.core.PhantomProcessStatus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.ArrayDeque

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-rUS-w1200dp-h752dp-land", application = Application::class)
class SetupLegacySyncRecoveryTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val requests = mutableListOf<Boolean>()
    private val completions = ArrayDeque<(String?) -> Unit>()
    private var wirelessRequests = 0

    @Before
    fun setUp() {
        context.getSharedPreferences("phantom-process-limit", Context.MODE_PRIVATE).edit().clear().commit()
        SetupTabs.openLauncher = false
    }

    @Test
    fun enabledLimitStillOffersLegacyRestoreAndCancellationAllowsRetry() {
        markLegacyRepair()
        show(PhantomProcessStatus.ENABLED)
        openLimitDetails()

        retry().performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(true), requests) }
        retry().assertIsNotEnabled()

        compose.runOnIdle { completions.removeFirst()(LegacySyncRecoveryDialog.CANCELLED) }
        retry().assertIsEnabled()
        compose.onNodeWithText(text(R.string.adbfix_legacy_sync_cancelled)).assertExists()
        retry().performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(listOf(true, true), requests)
            assertEquals(0, wirelessRequests)
            completions.removeFirst()(LegacySyncRecoveryDialog.CANCELLED)
        }
    }

    @Test
    fun unsetLimitStillOffersLegacyRestore() {
        assertRetryAvailable(PhantomProcessStatus.UNSET)
    }

    @Test
    fun unreadableLimitStillOffersLegacyRestore() {
        assertRetryAvailable(PhantomProcessStatus.UNREADABLE)
    }

    @Test
    fun noLegacyMarkerDoesNotOfferOlderSettingsRestore() {
        show(PhantomProcessStatus.ENABLED)
        openLimitDetails()

        retry().assertDoesNotExist()
    }

    @Test
    fun completedRecoveryRemovesRetryWithoutReopeningThePanel() {
        markLegacyRepair()
        show(PhantomProcessStatus.ENABLED)
        openLimitDetails()
        retry().performScrollTo().performClick()

        compose.runOnIdle {
            LegacyDeviceConfigSync.clear(context)
            completions.removeFirst()(null)
        }

        retry().assertDoesNotExist()
    }

    private fun assertRetryAvailable(status: PhantomProcessStatus) {
        markLegacyRepair()
        show(status)
        openLimitDetails()

        retry().performScrollTo().assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(listOf(true), requests)
            completions.removeFirst()(LegacySyncRecoveryDialog.CANCELLED)
        }
    }

    private fun markLegacyRepair() {
        context.getSharedPreferences("phantom-process-limit", Context.MODE_PRIVATE)
            .edit().putBoolean("android12-off", true).commit()
    }

    private fun retry() = compose.onNodeWithText(text(R.string.adbfix_legacy_sync_retry))

    private fun openLimitDetails() {
        compose.onNodeWithText(text(R.string.setup_fix_it)).performScrollTo().performClick()
    }

    private fun show(status: PhantomProcessStatus) {
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus(), LocalNarrowPane provides false) {
                    FocusGlideHost {
                        SetupPanel(FrontEndState(
                            installed = null, ready = false, available = null, busy = false, stage = "", percent = -1,
                            desktopInstalled = false, offlineAccount = null, offline = false, frameGenLabel = "",
                            romsDir = null, logsEnabled = false, steamGames = emptyList(), emulators = emptyList(), running = null,
                            phantomProcessStatus = status,
                        ), FrontEndActions(
                            onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {},
                            onRom = {}, onResume = {}, onSteamSettings = {}, onDesktopSettings = {},
                            onInstallPackage = {}, onRemovePackage = {}, onRuntime = {}, onFrameGenPick = {},
                            onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {},
                            onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
                            onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
                            onSetPhantomProcessLimit = { enabled, done -> requests += enabled; completions.addLast(done) },
                        ), onOpenDeveloperOptions = {}, onRequestWirelessAdb = { wirelessRequests++ })
                    }
                }
            }
        }
    }

    private fun text(id: Int) = context.getString(id)
}
