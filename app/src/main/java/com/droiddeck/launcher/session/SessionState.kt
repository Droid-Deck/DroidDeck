package com.droiddeck.launcher.session

import java.io.File
import androidx.compose.runtime.getValue
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
    var stopRequested = false

    @Volatile
    var running = false
    var suspended by mutableStateOf(false)
    @Volatile var pipActive = false
    @Volatile
    var program: String? = null
    @Volatile
    var programArgs: List<String> = emptyList()
    @Volatile
    var steamUi: String? = null
    @Volatile
    var steamUrl: String? = null
    var relaunch: android.content.Intent? = null
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

    @Volatile
    var mode = "steam"

    @Volatile
    var outputSize: Pair<Int, Int> = Pair(1920, 1080)

    @Volatile
    var followScreen = false

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
