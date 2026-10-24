package com.droiddeck.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.LegacyDeviceConfigSync

internal object LegacySyncRecoveryDialog {
    const val CANCELLED = "droiddeck:legacy-sync-recovery-cancelled"

    fun displayError(context: Context, error: String): String =
        if (error == CANCELLED) context.getString(R.string.adbfix_legacy_sync_cancelled) else error

    fun request(
        activity: Activity,
        enabled: Boolean,
        onCancelled: () -> Unit,
        onConfirmed: (Boolean) -> Unit,
    ): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) {
            onCancelled()
            return null
        }
        if (!enabled || !LegacyDeviceConfigSync.pending(activity)) {
            onConfirmed(false)
            return null
        }
        return AlertDialog.Builder(activity)
            .setTitle(R.string.adbfix_legacy_sync_title)
            .setMessage(R.string.adbfix_legacy_sync_message)
            .setPositiveButton(R.string.adbfix_legacy_sync_restore) { _, _ -> onConfirmed(true) }
            .setNegativeButton(R.string.common_cancel) { _, _ -> onCancelled() }
            .setOnCancelListener { onCancelled() }
            .show()
    }
}
