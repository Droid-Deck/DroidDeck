package com.droiddeck.launcher.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.droiddeck.launcher.MainActivity
import com.droiddeck.launcher.R

/**
 * Keeps the process in the foreground while the Linux runtime downloads and unpacks. The runtime
 * is several hundred megabytes; without a foreground service Android may kill the app the moment
 * the user switches away, and the install starts over from the resumable part file.
 *
 * The install itself is [LinuxRuntimeInstaller.install], which runs one job per process: the
 * screen that asked for it joins the same job to show progress, so it does not matter which of the
 * two gets there first.
 */
class RuntimeInstallService : Service() {
    override fun attachBaseContext(newBase: android.content.Context) =
        super.attachBaseContext(com.droiddeck.launcher.core.AppLanguage.wrap(newBase))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val removal = intent?.action == ACTION_REMOVE
        val release = intent?.let(::releaseFrom)
        startForeground(NOTIFICATION_ID, notification(getString(if (LinuxRuntimeInstaller.isMaintaining()) R.string.desktop_removing else if (removal) R.string.rtinst_removing else R.string.user_apps_starting), -1))
        if (release == null && !removal) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Thread({
            val manager = getSystemService(NotificationManager::class.java)
            var shown = ""
            val progress = LinuxRuntimeInstaller.ProgressListener { stage, percent ->
                // Install percentages and throttled removal counts, including stage transitions.
                if ("$stage:$percent" != shown) {
                    shown = "$stage:$percent"
                    manager?.notify(NOTIFICATION_ID, notification(stage, percent))
                }
            }
            if (removal) LinuxRuntimeInstaller.attach(progress)
            else LinuxRuntimeInstaller.install(applicationContext, release!!, progress)
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf(startId)
        }, "runtime-install").start()
        return START_NOT_STICKY
    }

    private fun notification(stage: String, percent: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.setup_runtime),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.rtinst_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        })
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(getString(if (LinuxRuntimeInstaller.isMaintaining()) R.string.desktop_removing else if (LinuxRuntimeInstaller.isRemoving()) R.string.rtinst_title_removing else R.string.rtinst_title_installing))
            .setContentText(stage)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "runtime-install"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_REMOVE = "com.droiddeck.launcher.REMOVE_RUNTIME"
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SHA256 = "sha256"
        private const val EXTRA_SIZE = "size"

        /** Starts the install of [release] in the foreground; a running install is joined instead. */
        fun start(context: Context, release: LinuxRuntimeInstaller.Release) {
            val intent = Intent(context, RuntimeInstallService::class.java)
                .putExtra(EXTRA_VERSION, release.version)
                .putExtra(EXTRA_URL, release.url)
                .putExtra(EXTRA_SHA256, release.sha256)
                .putExtra(EXTRA_SIZE, release.size)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun keepRemovalAlive(context: Context) {
            val intent = Intent(context, RuntimeInstallService::class.java).setAction(ACTION_REMOVE)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        private fun releaseFrom(intent: Intent): LinuxRuntimeInstaller.Release? {
            val version = intent.getStringExtra(EXTRA_VERSION) ?: return null
            val url = intent.getStringExtra(EXTRA_URL) ?: return null
            val sha256 = intent.getStringExtra(EXTRA_SHA256) ?: return null
            return LinuxRuntimeInstaller.Release(version, url, sha256, intent.getLongExtra(EXTRA_SIZE, 0L))
        }
    }
}
