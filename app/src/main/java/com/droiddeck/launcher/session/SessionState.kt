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
    @Volatile var pipActive = false
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

    /** The pad is presented to the Steam client as a Steam Deck controller this session
     *  (SteamDeckPad): its Quick Access button is a real button, not the Guide+A chord. Decided by
     *  the service as the session starts, which can be after the activity has resumed - hence
     *  [deckPadListener]. */
    @Volatile private var deckPadValue = false
    @JvmStatic var deckPad: Boolean
        get() = deckPadValue
        set(value) {
            if (deckPadValue == value) return
            deckPadValue = value
            deckPadListener?.invoke()
        }

    /** Told (on the thread that changed it) whenever [deckPad] changes. */
    @Volatile var deckPadListener: (() -> Unit)? = null

    /** The session screen's virtual pad, for the agent bridge's pad input (AgentInput). */
    @Volatile var padBridge: com.droiddeck.launcher.input.PadBridge? = null

    /** What the second screen shows, and on which display: kept for the session, so a screen
     *  turned off (sleep, a closed lid) or an activity recreated brings it back as it was. */
    @Volatile var secondScreenMode = com.droiddeck.launcher.input.SecondScreenMode.NONE
    @Volatile var secondScreenDisplay = -1

    /** Which session this is: SessionService.MODE_STEAM or MODE_DESKTOP. */
    @Volatile
    var mode = "steam"

    /** The size gamescope was told to render at; set by the activity before the service starts. */
    @Volatile
    var outputSize: Pair<Int, Int> = Pair(1920, 1080)

    @Volatile
    var refreshHz: Float = 60f

    /**
     * How far the compositor enlarges the session onto the panel (panel over outputSize, long
     * side to long side); set with outputSize. 0 before a session has been sized. Texture
     * sharpness "Auto" (core/TextureFiltering) is derived from it when a game launches.
     */
    @Volatile
    var upscaleRatio: Float = 0f

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
