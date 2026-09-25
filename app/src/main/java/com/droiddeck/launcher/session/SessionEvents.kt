package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Writes the machine-readable lifecycle stream beside the existing session artifacts. */
object SessionEvents {
    private const val TAG = "SessionEvents"
    private val lock = Any()

    fun begin(context: Context, mode: String) {
        // A runtime-install failure can leave the folder claimed before the service ever starts.
        // Keep that folder, but give the next attempt its own session directory.
        SessionPaths.take()
        val dir = SessionPaths.beginOrCurrent(context)
        synchronized(SessionState) {
            SessionState.phase = SessionPhase.PREPARING
            SessionState.mode = mode
            SessionState.sessionId = dir.name
            SessionState.lastTransitionAt = System.currentTimeMillis()
            SessionState.failureCode = null
            SessionState.failureMessage = null
            SessionState.failureStatus = null
            SessionState.logDirectory = dir
            SessionState.logFile = File(dir, "session.log")
            SessionState.eventsFile = File(dir, "events.jsonl")
            SessionState.guestPid = -1
            SessionState.installing = null
            SessionState.stopRequested = false
            SessionState.program = null
            SessionState.programArgs = emptyList()
            SessionState.steamUi = null
            SessionState.steamUrl = null
            SessionState.firstFrameSeen = false
            SessionState.suspended = false
        }
        record("session.created", mapOf("mode" to mode), dir)
    }

    fun transition(
        phase: SessionPhase,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        synchronized(SessionState) {
            if (SessionState.phase != phase) {
                SessionState.phase = phase
                SessionState.lastTransitionAt = System.currentTimeMillis()
            }
        }
        record(event, fields)
    }

    fun record(event: String, fields: Map<String, Any?> = emptyMap(), directory: File? = null) {
        val dir = directory ?: SessionState.logDirectory ?: SessionPaths.current() ?: return
        val target = if (directory == null) SessionState.eventsFile ?: File(dir, "events.jsonl")
            else File(dir, "events.jsonl")
        val payload = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("event", event)
            .put("sessionId", dir.name)
        fields.forEach { (key, value) -> payload.put(key, value ?: JSONObject.NULL) }

        try {
            synchronized(lock) {
                target.parentFile?.mkdirs()
                FileOutputStream(target, true).use { stream ->
                    stream.write((payload.toString() + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "writing ${target.name}", e)
        }
    }

    fun fail(code: String, message: String, status: Int? = null) {
        synchronized(SessionState) {
            SessionState.phase = SessionPhase.FAILED
            SessionState.lastTransitionAt = System.currentTimeMillis()
            SessionState.failureCode = code
            SessionState.failureMessage = message
            SessionState.failureStatus = status
        }
        record("session.failed", mapOf("code" to code, "message" to message, "status" to status))
    }

    fun firstFrame() {
        SessionState.firstFrameSeen = true
        record("frame.first")
        markReadyIfPossible()
    }

    fun guestStarted(pid: Int) {
        SessionState.guestPid = pid
        record("guest.started", mapOf("pid" to pid))
        if (SessionState.mode == SessionService.MODE_STEAM) {
            transition(SessionPhase.STARTING_STEAM, "steam.starting")
        }
        markReadyIfPossible()
    }

    fun markReadyIfPossible() {
        if (!SessionState.running || SessionState.guestPid <= 1 || !SessionState.firstFrameSeen || SessionState.suspended) return
        if (SessionState.phase != SessionPhase.READY) {
            transition(SessionPhase.READY, "session.ready")
        }
    }

    fun resumePhase(): SessionPhase = when {
        SessionState.firstFrameSeen && SessionState.guestPid > 1 -> SessionPhase.READY
        SessionState.mode == SessionService.MODE_STEAM && SessionState.guestPid > 1 -> SessionPhase.STARTING_STEAM
        else -> SessionPhase.STARTING_GUEST
    }
}
