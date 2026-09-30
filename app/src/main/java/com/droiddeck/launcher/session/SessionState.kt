package com.droiddeck.launcher.session

import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The little that the activity and the service have to agree on. Process-wide rather than passed
 * through intents, because both halves live in one process and the point of the split is that
 * either can outlive the other.
 */
object SessionState {
    @Volatile
    var phase = SessionPhase.IDLE

    @Volatile
    var sessionId: String? = null

    @Volatile
    var lastTransitionAt = 0L

    @Volatile
    var failureCode: String? = null

    @Volatile
    var failureMessage: String? = null

    @Volatile
    var failureStatus: Int? = null

    @Volatile
    var logDirectory: File? = null

    @Volatile
    var eventsFile: File? = null

    @Volatile
    var guestPid = -1

    @Volatile
    var installing: String? = null

    @Volatile
    var stopRequested = false

    @Volatile
    var running = false
    var suspended by mutableStateOf(false)
    /** MODE_RUN: the program inside the runtime the session was started for. */
    @Volatile
    var program: String? = null
    @Volatile
    var programArgs: List<String> = emptyList()
    /** MODE_STEAM: "desktop" for the client's desktop UI, else Big Picture; and a steam:// URL to hand it. */
    @Volatile
    var steamUi: String? = null
    @Volatile
    var steamUrl: String? = null
    /** A session the guest asked for (the desktop's Steam launchers): started by the activity once this one has ended. */
    var relaunch: android.content.Intent? = null
    /** HDR10 was asked for and the panel can show it: the compositor was told, and the session
     *  gets DXVK_HDR=1 and gamescope --hdr-enabled. Decided by the activity before the compositor starts. */
    @JvmStatic var hdr = false

    /** Which session this is: SessionService.MODE_STEAM or MODE_DESKTOP. */
    @Volatile
    var mode = "steam"

    /** The size gamescope was told to render at; set by the activity before the service starts. */
    @Volatile
    var outputSize: Pair<Int, Int> = Pair(1920, 1080)

    @Volatile
    var refreshHz: Float = 60f

    /** The session's frame cap (SessionPrefs.fpsLimit), fixed when it starts; 0 = none. */
    @Volatile
    var fpsLimit: Int = 0

    /** The compositor has presented a frame, so the loading panel is behind us for this session. */
    @Volatile
    var firstFrameSeen = false

    /** Where this session is writing its log, for the "it ended" message. */
    @Volatile
    var logFile: File? = null

    /** The fake evdev directory, once the service has prepared the rings. */
    @Volatile
    var fakeInputDir: File? = null

    /** Told when the session ends, so a visible activity can close itself. */
    @Volatile
    var endListener: ((Int) -> Unit)? = null

    fun notifyEnded(status: Int) {
        endListener?.invoke(status)
    }
}

/** Stable lifecycle values returned by the debug agent bridge. */
enum class SessionPhase {
    IDLE,
    PREPARING,
    INSTALLING_RUNTIME,
    STARTING_COMPOSITOR,
    STARTING_GUEST,
    STARTING_STEAM,
    READY,
    SUSPENDED,
    STOPPING,
    FAILED,
}
