package com.droiddeck.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Looper
import com.droiddeck.launcher.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LegacySyncRecoveryDialogTest {
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private lateinit var activity: Activity
    private var dialog: AlertDialog? = null
    private val confirmations = mutableListOf<Boolean>()
    private var cancellations = 0

    @Before
    fun setUp() {
        activity = controller.setup().get()
        activity.getSharedPreferences("phantom-process-limit", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        dialog?.dismiss()
        controller.pause().stop().destroy()
    }

    private fun markLegacyRepair() {
        activity.getSharedPreferences("phantom-process-limit", Context.MODE_PRIVATE)
            .edit().putBoolean("android12-off", true).commit()
    }

    private fun request(enabled: Boolean = true) {
        dialog = LegacySyncRecoveryDialog.request(activity, enabled, { cancellations++ }, confirmations::add)
    }

    @Test
    fun newUserRestoresWithoutSyncConsentOrDialog() {
        request()

        assertNull(dialog)
        assertEquals(listOf(false), confirmations)
        assertEquals(0, cancellations)
    }

    @Test
    fun removingLimitsDoesNotAskToRestoreSync() {
        markLegacyRepair()

        request(enabled = false)

        assertNull(dialog)
        assertEquals(listOf(false), confirmations)
        assertEquals(0, cancellations)
    }

    @Test
    fun legacyRestorationWaitsForExplicitConfirmation() {
        markLegacyRepair()

        request()

        assertNotNull(dialog)
        assertEquals(emptyList<Boolean>(), confirmations)
        assertEquals(0, cancellations)
        dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true), confirmations)
        assertEquals(0, cancellations)
    }

    @Test
    fun negativeButtonCancelsWithoutApplying() {
        markLegacyRepair()

        request()
        dialog!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptyList<Boolean>(), confirmations)
        assertEquals(1, cancellations)
    }

    @Test
    fun dismissGestureCancelsWithoutApplying() {
        markLegacyRepair()

        request()
        dialog!!.cancel()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptyList<Boolean>(), confirmations)
        assertEquals(1, cancellations)
    }

    @Test
    fun finishingActivityCancelsWithoutApplying() {
        markLegacyRepair()
        activity.finish()

        request()

        assertNull(dialog)
        assertEquals(emptyList<Boolean>(), confirmations)
        assertEquals(1, cancellations)
    }

    @Test
    fun cancellationIsDisplayedAsLocalizedMessage() {
        assertEquals(
            activity.getString(R.string.adbfix_legacy_sync_cancelled),
            LegacySyncRecoveryDialog.displayError(activity, LegacySyncRecoveryDialog.CANCELLED),
        )
        assertEquals("Connection failed", LegacySyncRecoveryDialog.displayError(activity, "Connection failed"))
    }
}
