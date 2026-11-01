package com.droiddeck.launcher.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.download.StoreDownloadService

class SyncthingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> Syncthing.setPaused(this, !Syncthing.userPaused)
            ACTION_OFF -> { Syncthing.setEnabled(this, false); return START_NOT_STICKY }
        }
        if (!SyncPrefs.enabled(this) || SyncPrefs.runMode(this) != SyncPrefs.RUN_BACKGROUND) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        Syncthing.onChange = { getSystemService(NotificationManager::class.java)?.notify(ID, notification()) }
        Syncthing.evaluate()
        return START_STICKY
    }

    override fun onDestroy() {
        Syncthing.onChange = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        channel(this)
        val snap = Syncthing.snapshot
        val title = getString(when {
            Syncthing.phase == Syncthing.Phase.FAILED -> R.string.sync_status_failed
            Syncthing.phase != Syncthing.Phase.RUNNING -> R.string.sync_status_starting
            Syncthing.pauseRequested && Syncthing.devicesPaused -> R.string.sync_status_paused
            Syncthing.pauseRequested -> R.string.sync_status_pausing
            Syncthing.devicesPaused -> R.string.sync_status_resuming
            snap?.needsAttention == true -> R.string.sync_status_attention
            snap?.syncing == true -> R.string.sync_status_syncing
            else -> R.string.sync_status_up_to_date
        })
        val text = snap?.let {
            getString(R.string.sync_summary, it.folders.size, it.devices.count { d -> d.connected }, it.devices.size)
        }.orEmpty()
        val open = PendingIntent.getActivity(this, 0, Intent(this, FrontEndActivity::class.java)
            .putExtra(StoreDownloadService.EXTRA_NAV, NAV).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(if (Syncthing.userPaused) R.string.sync_resume else R.string.sync_pause), action(ACTION_PAUSE, 1)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.sync_turn_off), action(ACTION_OFF, 2)).build())
            .build()
    }

    private fun action(name: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, SyncthingService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        const val NAV = "setup:sync"
        private const val CHANNEL = "syncthing"
        private const val ID = 9203
        private const val ACTION_PAUSE = "droiddeck.sync.PAUSE"
        private const val ACTION_OFF = "droiddeck.sync.OFF"
        @Volatile private var running = false

        private fun channel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.sync_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                })
            }
        }

        fun start(context: Context) {
            if (running) return
            running = true
            runCatching { context.startForegroundService(Intent(context, SyncthingService::class.java)) }.onFailure { running = false }
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            context.stopService(Intent(context, SyncthingService::class.java))
        }
    }
}
