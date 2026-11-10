package com.droiddeck.launcher.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.gpu.SteamVr

object SteamVrSession {
    private class Run(val session: String?, var owner: Long, var leave: ((String, Boolean) -> Unit)?) {
        @Volatile var ending = false
        var started = false
    }

    private val lock = Any()
    private val commands = Any()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var current: Run? = null
    private var focusedPanel: Any? = null
    private var panelTask = -1
    @Volatile private var panelFocused = false
    @Volatile var status = SteamVr.Status.UNAVAILABLE
        private set
    var active by mutableStateOf(false)
        private set

    fun attach(leave: (String, Boolean) -> Unit): Long = synchronized(lock) {
        val owner = ++generation
        val run = current?.takeIf { it.session == SessionState.sessionId && !it.ending }
            ?: Run(SessionState.sessionId, owner, leave).also { current = it; status = SteamVr.Status.UNAVAILABLE }
        run.owner = owner
        run.leave = leave
        active = true
        owner
    }

    fun panelFocus(panel: Any, task: Int, focused: Boolean) = synchronized(lock) {
        if (focused) {
            focusedPanel = panel
            panelTask = task
        } else if (focusedPanel === panel) focusedPanel = null
        panelFocused = focusedPanel != null
        Log.i("DroidDeckPanel", "task=$task focused=$focused")
    }

    fun diagnostics(): org.json.JSONObject = synchronized(lock) {
        org.json.JSONObject().put("active", active).put("panelFocused", panelFocused)
            .put("panelTask", panelTask).put("owner", current?.owner ?: 0)
            .put("session", current?.session ?: org.json.JSONObject.NULL)
    }

    fun start(context: Context, owner: Long) {
        val run = synchronized(lock) {
            val run = current ?: return
            if (run.owner != owner || run.started || run.ending) return
            run.started = true
            run
        }
        val app = context.applicationContext
        Thread({
            try {
                val started = synchronized(commands) {
                    if (!valid(run)) false else SteamVr.run(app, "start")
                }
                if (!started) {
                    if (valid(run)) end(run, "SteamVR start failed", true)
                    return@Thread
                }
                val startupDeadline = SystemClock.elapsedRealtime() + 30_000
                var lastRunning: Long? = null
                while (valid(run)) {
                    val nowStatus = SteamVr.status(app)
                    if (!valid(run)) break
                    status = nowStatus
                    val now = SystemClock.elapsedRealtime()
                    if (nowStatus == SteamVr.Status.RUNNING) lastRunning = now
                    if (nowStatus != SteamVr.Status.RUNNING && now >= (lastRunning?.plus(5_000) ?: startupDeadline)) {
                        end(run, "SteamVR stopped or did not finish starting", true)
                        break
                    }
                    Thread.sleep(1000)
                }
                if (!sessionAlive(run)) end(run, "Steam session ended", false)
            } finally {
                synchronized(commands) {
                    if (synchronized(lock) { current === run } && sessionAlive(run)) SteamVr.run(app, "stop")
                    main.post {
                        synchronized(lock) {
                            if (current === run) {
                                current = null
                                status = SteamVr.Status.READY
                                active = false
                            }
                        }
                    }
                }
            }
        }, "steamvr-session").start()
    }

    fun detach(owner: Long, changingConfiguration: Boolean) {
        val run = synchronized(lock) {
            val run = current ?: return
            if (run.owner != owner) return
            run.leave = null
            run
        }
        if (!changingConfiguration) end(run, "XR activity ended", false)
    }

    fun stop() {
        val run = synchronized(lock) { current } ?: return
        SessionState.steamVr = false
        end(run, "SteamVR exit requested from panel", false)
    }

    private fun end(run: Run, reason: String, failed: Boolean) {
        synchronized(lock) {
            if (current !== run || run.ending) return
            run.ending = true
            if (run.session == SessionState.sessionId) SessionState.steamVr = false
        }
        main.post {
            synchronized(lock) {
                if (current !== run) return@post
                active = false
                Log.i("DroidDeckXR", reason)
                run.leave?.invoke(reason, failed)
                if (!run.started) current = null
            }
        }
    }

    private fun valid(run: Run): Boolean = !run.ending && sessionAlive(run) &&
        synchronized(lock) { current === run }

    private fun sessionAlive(run: Run): Boolean = SessionState.running && !SessionState.stopRequested &&
        run.session != null && run.session == SessionState.sessionId
}
