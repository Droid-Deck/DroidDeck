package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.util.concurrent.Executors

object SessionHdr {
    private const val RETRIES = 5
    private const val RETRY_MS = 2_000L

    private val requests = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "session-hdr").apply { isDaemon = true }
    }
    @Volatile private var wanted = false

    fun set(context: Context, mode: String, on: Boolean) {
        SessionPrefs.setHdr(context, mode, on)
        if (!SessionState.running || !SessionState.hdrCapable || !CompositorHost.isStarted) return
        if (SessionPrefs.prefMode(SessionState.mode) != mode) return
        SessionState.hdr = on
        wanted = on
        WaylandCompositor.nativeSetHdrOutput(on)
        WaylandCompositor.nativeSetZeroCopy(on || SessionPrefs.zeroCopy(context, mode))
        val app = context.applicationContext
        requests.execute {
            var tries = 0
            while (wanted == on && SessionState.running &&
                !AgentGuest.setGamescopeConvar(app, "hdr_enabled", if (on) 1 else 0) && tries++ < RETRIES) {
                try { Thread.sleep(RETRY_MS) } catch (e: InterruptedException) { return@execute }
            }
        }
    }
}
