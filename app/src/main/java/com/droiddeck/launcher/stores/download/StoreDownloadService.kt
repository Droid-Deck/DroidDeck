package com.droiddeck.launcher.stores.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.droiddeck.launcher.MainActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.formatSpeed
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps store downloads going with the screen off or the app in the background: a foreground
 * service (dataSync) with a notification showing the active download's progress, Pause / Resume
 * and a two-step Cancel; a Wi-Fi lock and a partial wake lock while anything is running, so the
 * radio and the CPU do not sleep under a multi-gigabyte install; and a notification when a game
 * has installed or failed.
 *
 * [start] is called when a download begins and [finish] when one ends; the service stops itself when
 * nothing is running.
 */
class StoreDownloadService : Service() {
    private var wifi: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null
    private var wakeAt = 0L
    /** The download whose Cancel was tapped once: its actions read Delete / Keep until [confirmUntil]. */
    private var confirmKey: String? = null
    private var confirmUntil = 0L
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        channels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        startForegroundCompat(build())
        val key = intent?.getStringExtra(EXTRA_KEY)
        when (intent?.action) {
            ACTION_PAUSE -> key?.let {
                DownloadQueue.pause(it)
                // The progress notification goes with the last running download; this one stays to resume from.
                pausedNote(this, it)
            }
            ACTION_RESUME -> key?.let {
                runCatching { getSystemService(NotificationManager::class.java)?.cancel(pausedId(it)) }
                DownloadQueue.resume(this, it)
            }
            ACTION_CANCEL -> key?.let {
                confirmKey = it; confirmUntil = System.currentTimeMillis() + CONFIRM_MS
                main.postDelayed({ if (System.currentTimeMillis() >= confirmUntil) { confirmKey = null; refresh() } }, CONFIRM_MS + 100)
            }
            ACTION_DELETE -> key?.let { confirmKey = null; DownloadQueue.cancel(this, it) }
            ACTION_KEEP -> confirmKey = null
        }
        refresh()
        if (active.isEmpty() && StoresState.latestDownloads.none { it.state == DownloadState.RUNNING }) stopNow()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pending(action: String, key: String, code: Int): PendingIntent {
        val i = Intent(this, StoreDownloadService::class.java).setAction(action).putExtra(EXTRA_KEY, key)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) PendingIntent.getForegroundService(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        else PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun build(): Notification {
        val rows = StoresState.latestDownloads.filter { it.isActive }
        val first = rows.firstOrNull { it.state == DownloadState.RUNNING } ?: rows.firstOrNull()
        val title = if (rows.size > 1) getString(R.string.stores_notification_many, rows.size) else first?.name ?: getString(R.string.stores_notification_one)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openDownloads(this, 1))
        if (first == null) return b.setContentText(getString(R.string.stores_notification_waiting)).setProgress(0, 0, true).build()
        b.setContentText(DownloadNotificationText.line(first, stageName(first.stage), getString(R.string.stores_dl_paused), getString(R.string.stores_dl_queued)))
        val fraction = first.stageFraction
        if (fraction >= 0f) b.setProgress(1000, (fraction * 1000).toInt(), false) else b.setProgress(0, 0, true)
        val code = first.key.hashCode()
        if (confirmKey == first.key && System.currentTimeMillis() < confirmUntil) {
            b.addAction(Notification.Action.Builder(null, getString(R.string.stores_notification_delete), pending(ACTION_DELETE, first.key, code + 3)).build())
            b.addAction(Notification.Action.Builder(null, getString(R.string.stores_notification_keep), pending(ACTION_KEEP, first.key, code + 4)).build())
        } else {
            if (first.state == DownloadState.PAUSED) b.addAction(Notification.Action.Builder(null, getString(R.string.stores_dl_resume), pending(ACTION_RESUME, first.key, code + 1)).build())
            else b.addAction(Notification.Action.Builder(null, getString(R.string.stores_dl_pause), pending(ACTION_PAUSE, first.key, code)).build())
            b.addAction(Notification.Action.Builder(null, getString(R.string.stores_dl_cancel), pending(ACTION_CANCEL, first.key, code + 2)).build())
        }
        return b.build()
    }

    private fun stageName(stage: DownloadStage): String = getString(when (stage) {
        DownloadStage.MANIFEST -> R.string.stores_stage_manifest
        DownloadStage.DOWNLOAD -> R.string.stores_stage_downloading
        DownloadStage.VERIFY -> R.string.stores_stage_verifying
        DownloadStage.INSTALL -> R.string.stores_stage_installing
        DownloadStage.DONE -> R.string.stores_stage_done
    })

    private fun refresh() {
        locks()
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build()) }
    }

    /**
     * While a download runs: a Wi-Fi lock (high-performance before Android 14, low-latency from it,
     * where the old mode is deprecated) and a partial wake lock, taken with a timeout and renewed
     * as progress comes in rather than held indefinitely. Released as soon as nothing runs.
     */
    @SuppressLint("WakelockTimeout")
    private fun locks() {
        val running = StoresState.latestDownloads.any { it.state == DownloadState.RUNNING }
        if (!running) { releaseLocks(); return }
        if (wifi == null) {
            runCatching {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 34) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifi = wm.createWifiLock(mode, "DroidDeck:store-download").apply { setReferenceCounted(false); acquire() }
                Log.i(TAG, "wifi lock held")
            }
        }
        val now = System.currentTimeMillis()
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidDeck:store-download").apply { setReferenceCounted(false) }
            Log.i(TAG, "wake lock held")
        }
        if (now - wakeAt > WAKE_RENEW_MS) {
            wake?.acquire(WAKE_TIMEOUT_MS)
            wakeAt = now
        }
    }

    private fun releaseLocks() {
        wifi?.let { runCatching { if (it.isHeld) it.release() }; Log.i(TAG, "wifi lock released") }
        wifi = null
        wake?.let { runCatching { if (it.isHeld) it.release() }; Log.i(TAG, "wake lock released") }
        wake = null
        wakeAt = 0L
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
    }

    private fun stopNow() {
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "StoreDownloadService"
        private const val CHANNEL = "store_downloads"
        private const val DONE_CHANNEL = "store_downloads_done"
        private const val ID = 9102
        private const val CONFIRM_MS = 10_000L
        private const val WAKE_TIMEOUT_MS = 10 * 60_000L
        private const val WAKE_RENEW_MS = 5 * 60_000L
        private const val EXTRA_KEY = "key"
        private const val ACTION_PAUSE = "droiddeck.stores.PAUSE"
        private const val ACTION_RESUME = "droiddeck.stores.RESUME"
        private const val ACTION_CANCEL = "droiddeck.stores.CANCEL"
        private const val ACTION_DELETE = "droiddeck.stores.DELETE"
        private const val ACTION_KEEP = "droiddeck.stores.KEEP"
        /** MainActivity: which page to open ("stores:downloads", or a Games selection such as "app:<id>"). */
        const val EXTRA_NAV = "droiddeck.nav"
        private val active = ConcurrentHashMap.newKeySet<String>()
        @Volatile private var instance: StoreDownloadService? = null

        private fun channels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.stores_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.stores_notification_channel_hint)
                    setShowBadge(false)
                })
            }
            if (nm.getNotificationChannel(DONE_CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL, context.getString(R.string.stores_notification_done_channel), NotificationManager.IMPORTANCE_DEFAULT))
            }
        }

        private fun openPage(context: Context, nav: String, code: Int): PendingIntent =
            PendingIntent.getActivity(context, code, Intent(context, MainActivity::class.java).putExtra(EXTRA_NAV, nav)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun openDownloads(context: Context, code: Int) = openPage(context, "stores:downloads", code)

        /** A download started: the service is up (idempotent). */
        fun start(context: Context) {
            StoresState.latestDownloads.filter { it.state == DownloadState.RUNNING }.forEach { active.add(it.key) }
            try {
                val app = context.applicationContext
                val intent = Intent(app, StoreDownloadService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "could not start: ${e.message}")
            }
        }

        /** Progress moved: redraw the notification (StoresState calls it at most once a second). */
        fun tick() {
            instance?.refresh()
        }

        /** The queue changed: redraw the notification. */
        fun sync(context: Context) {
            instance?.refresh()
        }

        /** A download ended: drop it; the service stops with the last one. */
        fun finish(context: Context, key: String) {
            active.remove(key)
            val inst = instance ?: return
            if (active.isEmpty() && StoresState.latestDownloads.none { it.state == DownloadState.RUNNING }) inst.stopNow() else inst.refresh()
        }

        private fun pausedId(key: String) = ID + 0x20000 + (key.hashCode() and 0xFFFF)

        /** "<game> paused" with Resume, once the progress notification has gone with the service. */
        private fun pausedNote(context: Context, key: String) {
            val entry = StoresState.latestDownloads.firstOrNull { it.key == key } ?: return
            val i = Intent(context, StoreDownloadService::class.java).setAction(ACTION_RESUME).putExtra(EXTRA_KEY, key)
            val resume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) PendingIntent.getForegroundService(context, key.hashCode() + 5, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                else PendingIntent.getService(context, key.hashCode() + 5, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(context, DONE_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(entry.name).setContentText(context.getString(R.string.stores_dl_paused))
                .setContentIntent(resume).setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, context.getString(R.string.stores_dl_resume), resume).build())
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java)?.notify(pausedId(key), n) }
        }

        /** "<game> installed" (opens it in Games) or "<game> download failed" (opens Downloads); dismissible. */
        fun ended(context: Context, entry: DownloadEntry, installed: Boolean, gamesKey: String?) {
            val app = context.applicationContext
            channels(app)
            val code = entry.key.hashCode()
            val n = Notification.Builder(app, DONE_CHANNEL)
                .setSmallIcon(if (installed) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(app.getString(if (installed) R.string.stores_notification_installed else R.string.stores_notification_failed, entry.name))
                .setAutoCancel(true)
                .setContentIntent(if (installed && gamesKey != null) openPage(app, gamesKey, code + 10) else openDownloads(app, code + 11))
                .build()
            runCatching { app.getSystemService(NotificationManager::class.java)?.notify(ID + 1 + (code and 0xFFFF), n) }
        }
    }
}

/** The notification's one line for a download. Pure, for the test. */
object DownloadNotificationText {
    fun line(d: DownloadEntry, stageName: String, paused: String, queued: String): String {
        val parts = ArrayList<String>()
        parts += d.name
        when {
            d.state == DownloadState.PAUSED -> parts += paused
            d.state == DownloadState.QUEUED -> parts += queued
            d.stage == DownloadStage.DOWNLOAD -> {
                if (d.bytesTotal > 0) { parts += "${d.percent}%"; parts += "${formatBytes(d.bytesDone)}/${formatBytes(d.bytesTotal)}" }
                if (d.speedBps > 0) parts += formatSpeed(d.speedBps)
                if (d.etaSeconds >= 0) parts += eta(d.etaSeconds)
            }
            else -> parts += if (d.stageFraction >= 0f) "$stageName ${d.percent}%" else stageName
        }
        return parts.joinToString(" · ")
    }

    fun eta(seconds: Long): String = when {
        seconds < 60 -> "<1 min"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }
}
