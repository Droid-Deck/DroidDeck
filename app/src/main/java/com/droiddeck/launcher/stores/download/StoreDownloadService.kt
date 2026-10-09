package com.droiddeck.launcher.stores.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.droiddeck.launcher.MainActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.formatBytes
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the process alive while a store download runs, with one quiet notification for the queue.
 * Without it Android demotes the app as soon as the launcher leaves the screen and a multi-gigabyte
 * install dies half way; the session has its own service for the same reason.
 *
 * [start] is called when a download begins and [finish] when one ends; the notification is rebuilt
 * from the queue's live rows, and the service stops itself when nothing is active.
 */
class StoreDownloadService : Service() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.stores_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.stores_notification_channel_hint)
                setShowBadge(false)
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        startForegroundCompat(build())
        if (active.isEmpty()) stopNow()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun build(): Notification {
        val entries = StoresState.downloads.filter { it.state == DownloadState.RUNNING }
        val first = entries.firstOrNull()
        val title = if (active.size > 1) getString(R.string.stores_notification_many, active.size) else getString(R.string.stores_notification_one)
        val text = when {
            first == null -> getString(R.string.stores_notification_waiting)
            first.stage == com.droiddeck.launcher.stores.download.DownloadStage.DOWNLOAD && first.bytesTotal > 0 -> "${first.name} · ${first.percent}% (${formatBytes(first.bytesDone)} / ${formatBytes(first.bytesTotal)})"
            first.stageFraction >= 0f -> "${first.name} · ${first.percent}%"
            else -> first.name
        }
        val tap = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(text).setOngoing(true).setContentIntent(tap).build()
    }

    private fun refresh() {
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build()) }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
    }

    private fun stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "StoreDownloadService"
        private const val CHANNEL = "store_downloads"
        private const val ID = 9102
        private val active = ConcurrentHashMap.newKeySet<String>()
        @Volatile private var instance: StoreDownloadService? = null

        /** A download started: the service is up (idempotent). */
        fun start(context: Context) {
            StoresState.downloads.filter { it.state == DownloadState.RUNNING }.forEach { active.add(it.key) }
            try {
                val app = context.applicationContext
                val intent = Intent(app, StoreDownloadService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "could not start: ${e.message}")
            }
        }

        /** The queue changed: redraw the notification line. */
        fun sync(context: Context) {
            instance?.refresh()
        }

        /** A download ended: drop it; the service stops with the last one. */
        fun finish(context: Context, key: String) {
            active.remove(key)
            val inst = instance ?: return
            if (active.isEmpty() && StoresState.downloads.none { it.state == DownloadState.RUNNING }) inst.stopNow() else inst.refresh()
        }
    }
}
