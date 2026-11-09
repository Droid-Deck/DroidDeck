package com.droiddeck.launcher.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.R

/** Keeps the app process alive while the agent works and the launcher is in the background. */
class AgentHostService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.agent_channel_name),
            NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, FrontEndActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(com.droiddeck.launcher.stores.download.StoreDownloadService.EXTRA_NAV, "agent"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(getString(R.string.agent_working))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "agent"
        private const val NOTIFICATION_ID = 4107

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, AgentHostService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentHostService::class.java))
        }
    }
}
