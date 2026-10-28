package com.droiddeck.launcher.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.DeviceSupport

class AppUpdateService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var started = false
    private var destroyed = false
    private var lastNotification = 0L
    private val renew = object : Runnable {
        override fun run() {
            wake?.acquire(10 * 60_000L)
            main.postDelayed(this, 5 * 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_NOT_STICKY
        val state = AppUpdateState.snapshot
        val apk = state.apk
        if (state.phase != AppUpdateState.Phase.DOWNLOADING || apk == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.upd_title), NotificationManager.IMPORTANCE_LOW))
        try {
            val notification = notification(getString(R.string.upd_stage_downloading), 0, true)
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, notification)
            acquireLocks()
        } catch (e: Exception) {
            AppUpdateState.fail(this, e.message)
            finish(getString(R.string.upd_failed))
            return START_NOT_STICKY
        }
        val app = applicationContext
        Thread({
            val result = runCatching {
                SelfInstaller.download(app, apk) { percent ->
                    main.post {
                        if (!destroyed) {
                            AppUpdateState.progress(percent)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastNotification >= 1000) {
                                lastNotification = now
                                runCatching { manager.notify(ID, notification(getString(R.string.upd_stage_downloading), percent, true)) }
                            }
                        }
                    }
                }
            }
            main.post {
                result.onSuccess {
                    // Release download resources before publishing completion, since synchronous observers may start installation.
                    if (!destroyed) finish(getString(R.string.upd_download_ready))
                    AppUpdateState.ready(app, it)
                }.onFailure {
                    if (!destroyed) finish(getString(R.string.upd_failed))
                    AppUpdateState.fail(app, it.message)
                }
            }
        }, "app-update-download").start()
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidDeck:app-update").apply { setReferenceCounted(false) }
        renew.run()
        if (DeviceSupport.lowLatencyWifiSafe()) runCatching {
            val manager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DroidDeck:app-update").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        main.removeCallbacks(renew)
        wake?.let { runCatching { if (it.isHeld) it.release() } }
        wifi?.let { runCatching { if (it.isHeld) it.release() } }
        wake = null
        wifi = null
    }

    private fun notification(text: String, percent: Int = -1, active: Boolean = false): Notification {
        val open = Intent(this, FrontEndActivity::class.java).putExtra(EXTRA_NAV, "updates")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (active) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.upd_title)).setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true).setOngoing(active).setAutoCancel(!active)
            .apply { if (active) setProgress(100, percent.coerceAtLeast(0), percent < 0) }
            .build()
    }

    private fun finish(text: String) {
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching { getSystemService(NotificationManager::class.java).notify(ID, notification(text)) }
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NAV = "droiddeck.update.nav"
        private const val CHANNEL = "app_updates"
        private const val ID = 9103

        internal fun start(context: Context, apk: AppUpdates.Apk, targetLabel: String? = null) {
            val app = context.applicationContext
            AppUpdateState.initialize(app)
            if (!AppUpdateState.begin(apk, targetLabel)) return
            try {
                app.startForegroundService(Intent(app, AppUpdateService::class.java))
            } catch (e: Exception) {
                AppUpdateState.fail(app, e.message)
            }
        }
    }
}
