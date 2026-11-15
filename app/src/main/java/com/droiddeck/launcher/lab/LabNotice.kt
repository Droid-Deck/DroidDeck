package com.droiddeck.launcher.lab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.download.StoreDownloadService

/**
 * Says what happened to a Lab build when the app starts, so a restart onto a new build does not
 * look like a crash, and a build Android or the safety checks turned off is not lost silently.
 */
object LabNotice {
    private const val CHANNEL_ID = "lab"
    private const val NOTIFICATION_ID = 4120

    /** Where a start that just moved a Lab build into use opens; the agent that built it sets it. */
    @Volatile var openOnInstall: String = "updates"

    fun started(context: Context) {
        val app = context.applicationContext
        val active = LabOverlay.active
        val skipped = LabOverlay.skipped
        val current = LabOverlay.current(app)
        when {
            LabOverlay.promoted && active != null ->
                post(app, app.getString(R.string.lab_installed_title), app.getString(R.string.lab_installed_text, active.label), openOnInstall)
            skipped == LabOverlay.SKIPPED_APP_IMAGE && current != null ->
                post(app, app.getString(R.string.lab_paused_title), app.getString(R.string.lab_paused_text, current.label), "updates")
            skipped == LabOverlay.Reason.OTHER_INSTALL.name || skipped == LabOverlay.Reason.DID_NOT_START.name -> {
                val reason = if (skipped == LabOverlay.Reason.OTHER_INSTALL.name) R.string.lab_off_other_install else R.string.lab_off_did_not_start
                post(app, app.getString(R.string.lab_off_title), app.getString(reason), "updates")
            }
        }
    }

    private fun post(app: Context, title: String, text: String, nav: String) {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, app.getString(R.string.upd_lab), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            app, NOTIFICATION_ID,
            Intent(app, FrontEndActivity::class.java).putExtra(StoreDownloadService.EXTRA_NAV, nav)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}
