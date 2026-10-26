package com.droiddeck.launcher.lan

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.droiddeck.launcher.R

class LocalHotspotService : Service() {
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var requesting = false
    private var alive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.lan_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, LocalLanActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, LocalHotspotService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1842, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(getString(R.string.lan_title))
            .setContentText(getString(R.string.lan_notification))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.lan_stop), stop).build())
            .build())
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (requesting || reservation != null) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            fail(R.string.lan_permission_needed)
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 28 && !getSystemService(LocationManager::class.java).isLocationEnabled) {
            fail(R.string.lan_location_needed)
            return START_NOT_STICKY
        }
        requesting = true
        LocalLan.hotspotState.value = HotspotState(starting = true)
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi == null) fail(R.string.lan_failed)
            else wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(ready: WifiManager.LocalOnlyHotspotReservation) {
                    if (!alive) {
                        ready.close()
                        return
                    }
                    requesting = false
                    reservation = ready
                    val ssid: String?
                    val password: String?
                    if (Build.VERSION.SDK_INT >= 30) {
                        ssid = ready.softApConfiguration.ssid
                        password = ready.softApConfiguration.passphrase
                    } else {
                        ssid = ready.wifiConfiguration?.SSID
                        password = ready.wifiConfiguration?.preSharedKey
                    }
                    if (ssid.isNullOrEmpty() || password.isNullOrEmpty()) fail(R.string.lan_failed)
                    else LocalLan.hotspotState.value = HotspotState(ssid = ssid, password = password)
                }

                override fun onStopped() {
                    if (!alive) return
                    LocalLan.hotspotState.value = HotspotState()
                    stopSelf()
                }

                override fun onFailed(reason: Int) {
                    if (alive) fail(when (reason) {
                        ERROR_INCOMPATIBLE_MODE -> R.string.lan_incompatible
                        ERROR_TETHERING_DISALLOWED -> R.string.lan_disallowed
                        ERROR_NO_CHANNEL -> R.string.lan_no_channel
                        else -> R.string.lan_failed
                    })
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: SecurityException) {
            fail(R.string.lan_permission_needed)
        } catch (_: IllegalStateException) {
            fail(R.string.lan_incompatible)
        } catch (_: UnsupportedOperationException) {
            fail(R.string.lan_failed)
        }
        return START_NOT_STICKY
    }

    private fun fail(message: Int) {
        LocalLan.hotspotState.value = HotspotState(error = message)
        stopSelf()
    }

    override fun onDestroy() {
        alive = false
        reservation?.close()
        reservation = null
        requesting = false
        LocalLan.hotspotState.value = HotspotState(error = LocalLan.hotspot.value.error)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "local-lan"
        private const val STOP = "com.droiddeck.launcher.lan.STOP"
    }
}
