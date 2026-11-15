package com.droiddeck.launcher.session

import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
    var installCancellation: com.droiddeck.launcher.core.Downloader.Cancellation? = null

    @Volatile
    var stopRequested = false

    @Volatile
    var running = false
    @Volatile
    var activityDisplayId = 0
    @Volatile
    var runtimeDriver: String? = null
    var micAvailable by mutableStateOf(false)
    var micEnabled by mutableStateOf(false)
    var suspended by mutableStateOf(false)
    var resumeRequested by mutableStateOf(false)
    var resuming by mutableStateOf(false)
    /** A freeze has begun and has not finished; it already chose which processes it stops. */
    @Volatile var suspending = false
    var resumeFailures by mutableIntStateOf(0)
    var resumeFailed by mutableStateOf(false)
    var manualPause by mutableStateOf(false)
    @Volatile var pipActive = false
    @Volatile
    var program: String? = null
    @Volatile
    var programArgs: List<String> = emptyList()
    @Volatile
    var gameId: String? = null
    @Volatile
    var steamUi: String? = null
    @Volatile
    var steamUrl: String? = null
    @Volatile
    var steamClientArch: String? = null
    var steamVr by mutableStateOf(false)
    @Volatile
    var relaunch: android.content.Intent? = null
    @Volatile
    private var relaunchSessionId: String? = null
    @Volatile
    private var relaunchStopRequested = false

    fun queueRelaunch(intent: android.content.Intent) {
        synchronized(this) {
            relaunch = intent
            relaunchSessionId = sessionId
            relaunchStopRequested = false
        }
    }

    fun confirmRelaunchStop() {
        synchronized(this) {
            relaunchStopRequested = relaunch != null && relaunchSessionId == sessionId
        }
    }

    fun takeRelaunch(): android.content.Intent? = synchronized(this) {
        val next = relaunch
        val valid = relaunchStopRequested && relaunchSessionId == sessionId
        relaunch = null
        relaunchSessionId = null
        relaunchStopRequested = false
        next.takeIf { valid }
    }

    fun clearRelaunch() {
        synchronized(this) {
            relaunch = null
            relaunchSessionId = null
            relaunchStopRequested = false
        }
    }

    @JvmStatic var hdr = false

    @Volatile private var deckPadValue = false
    @JvmStatic var deckPad: Boolean
        get() = deckPadValue
        set(value) {
            if (deckPadValue == value) return
            deckPadValue = value
            deckPadListener?.invoke()
        }

    @Volatile var deckPadListener: (() -> Unit)? = null

    @Volatile var padBridge: com.droiddeck.launcher.input.PadBridge? = null

    @Volatile var secondScreenMode = com.droiddeck.launcher.input.SecondScreenMode.NONE
    @Volatile var secondScreenDisplay = -1
    @Volatile var secondScreenAutoOpened = false
    @Volatile var secondScreenProvisional = false
    @Volatile var secondScreenAutomatic = false
    @Volatile var gameOnBottom = false

    @Volatile var steamLibraryMounts: Map<String, java.io.File> = emptyMap()

    @Volatile
    var mode = "steam"

    @Volatile
    var outputSize: Pair<Int, Int> = Pair(1920, 1080)

    @Volatile
    var followScreen = false
    var followScale = 1f

    @Volatile
    var refreshHz: Float = 60f

    @Volatile
    var upscaleRatio: Float = 0f

    @Volatile
    var fpsLimit: Int = 0

    @Volatile
    var firstFrameSeen = false

    @Volatile
    var logFile: File? = null

    @Volatile
    var fakeInputDir: File? = null

    @Volatile
    var endListener: ((Int) -> Unit)? = null

    fun notifyEnded(status: Int) {
        endListener?.invoke(status)
    }
}

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
