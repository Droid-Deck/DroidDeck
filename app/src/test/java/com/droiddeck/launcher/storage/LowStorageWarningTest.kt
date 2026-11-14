package com.droiddeck.launcher.storage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.R
import com.droiddeck.launcher.ui.createRobolectricComposeRule
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SettingsReset
import com.droiddeck.launcher.ui.LowSpaceDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class LowStorageWarningTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val low = StorageState.LOW_SPACE_BYTES - 1
    private val roomy = StorageState.LOW_SPACE_BYTES

    @Before fun clear() {
        ctx.getSharedPreferences("session", 0).edit().clear().commit()
    }

    @Test fun warningIsOnByDefault() {
        assertTrue(SessionPrefs.lowStorageWarning(ctx))
    }

    @Test fun settingRoundTrips() {
        SessionPrefs.setLowStorageWarning(ctx, false)
        assertFalse(SessionPrefs.lowStorageWarning(ctx))
        SessionPrefs.setLowStorageWarning(ctx, true)
        assertTrue(SessionPrefs.lowStorageWarning(ctx))
    }

    @Test fun resettingSettingsTurnsTheWarningBackOn() {
        SessionPrefs.setLowStorageWarning(ctx, false)
        assertTrue(SettingsReset.resetAll(ctx, gameProfiles = false))
        assertTrue(SessionPrefs.lowStorageWarning(ctx))
    }

    @Test fun warnsOnlyBelowTheThresholdWhenEnabled() {
        assertTrue(StorageState.shouldWarnLowSpace(true, dismissed = false, sessionRunning = false, freeBytes = low))
        assertFalse(StorageState.shouldWarnLowSpace(true, dismissed = false, sessionRunning = false, freeBytes = roomy))
    }

    @Test fun optingOutSuppressesTheWarning() {
        assertFalse(StorageState.shouldWarnLowSpace(false, dismissed = false, sessionRunning = false, freeBytes = low))
    }

    @Test fun existingSuppressionsStillApply() {
        assertFalse(StorageState.shouldWarnLowSpace(true, dismissed = true, sessionRunning = false, freeBytes = low))
        assertFalse(StorageState.shouldWarnLowSpace(true, dismissed = false, sessionRunning = true, freeBytes = low))
    }

    @Test fun dialogToggleFlipsTheSetting() {
        var warn by mutableStateOf(SessionPrefs.lowStorageWarning(ctx))
        compose.setContent {
            LowSpaceDialog(
                free = low, warn = warn,
                onWarnChange = { SessionPrefs.setLowStorageWarning(ctx, it); warn = it },
                onFreeUp = {}, onPlay = {}, onDismiss = {},
            )
        }
        val label = ctx.getString(R.string.storage_low_never)
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
        assertFalse(SessionPrefs.lowStorageWarning(ctx))
        assertFalse(warn)
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
        assertEquals(true, SessionPrefs.lowStorageWarning(ctx))
    }
}
