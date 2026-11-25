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
    @Volatile private var handler: Handler? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var online: Boolean? = null
    private var retries = 0

    private val check = object : Runnable {
        override fun run() {
            val handler = handler ?: return
            if (SessionState.running && SessionState.suspended) {
                handler.postDelayed(this, SUSPENDED_POLL_MS)
                return
            }
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
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                changed(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            override fun onLost(network: Network) = changed(false)
        }
        callback = registered
        active = this
        online = AutoComponents.isOnline(app())
        app().getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(registered, handler)
    }

    private fun changed(now: Boolean) {
        val handler = handler ?: return
        if (now == online) return
        online = now
        handler.removeCallbacks(check)
        retries = 0
        handler.postDelayed(check, if (now) ONLINE_DELAY_MS else OFFLINE_DELAY_MS)
    }

    private fun sync() {
        handler?.post {
            val handler = handler ?: return@post
            handler.removeCallbacks(check)
            retries = 0
            handler.post(check)
        }
    }

    override fun stop() {
        if (active === this) active = null
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
        @Volatile private var active: SteamOfflineFollow? = null
        const val ONLINE_DELAY_MS = 2_000L
        const val OFFLINE_DELAY_MS = 10_000L
        const val RETRY_MS = 15_000L
        const val MAX_RETRIES = 8
        const val SUSPENDED_POLL_MS = 1_000L

        fun request() {
            active?.sync()
        }
    }
}
