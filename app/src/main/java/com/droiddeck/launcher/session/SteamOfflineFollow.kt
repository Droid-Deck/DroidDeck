package com.droiddeck.launcher.session

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.frontend.AutoComponents

class SteamOfflineFollow : SessionPart() {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var online: Boolean? = null
    private var retries = 0

    private val check = object : Runnable {
        override fun run() {
            val handler = handler ?: return
            if (OfflineMode.follow(app()) == null && retries < MAX_RETRIES) {
                retries++
                handler.postDelayed(this, RETRY_MS)
            }
        }
    }

    override fun start() {
        val started = HandlerThread("steam-offline").also { it.start() }
        val handler = Handler(started.looper)
        thread = started
        this.handler = handler
        val registered = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
            override fun onLost(network: Network) = changed()
        }
        callback = registered
        online = AutoComponents.isOnline(app())
        app().getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(registered, handler)
    }

    private fun changed() {
        val handler = handler ?: return
        val now = AutoComponents.isOnline(app())
        if (now == online) return
        online = now
        handler.removeCallbacks(check)
        retries = 0
        handler.postDelayed(check, if (now) ONLINE_DELAY_MS else OFFLINE_DELAY_MS)
    }

    override fun stop() {
        val registered = callback
        callback = null
        if (registered != null) {
            try {
                app().getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(registered)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "network callback was already gone", e)
            }
        }
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
    }

    companion object {
        private const val TAG = "SteamOfflineFollow"
        const val ONLINE_DELAY_MS = 2_000L
        const val OFFLINE_DELAY_MS = 10_000L
        const val RETRY_MS = 15_000L
        const val MAX_RETRIES = 8
    }
}
