package com.droiddeck.launcher.input

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Receives controller input (DualSense / Edge with full features, any other SDL3-supported pad) from the companion SDL3 bridge app
 * and feeds it into DroidDeck's pad pipeline.
 *
 *  sticks / triggers / D-pad / face + shoulder buttons -> PadBridge.applyExternal
 *  Edge paddles (L/R)  -> Deck grips L4 / R4;  Edge Fn (L/R) -> L5 / R5
 *  touchpad            -> decoupled by the bridge: left half = left Deck trackpad, right half = right trackpad (+ click)
 *  gyro / accel        -> FakeInputWriter.writeMotion (slot 0)
 *
 * Usage:  DualSenseBridge.attach(context, padBridge)  when the session starts,
 *         DualSenseBridge.endSession(token)           when it stops.
 */
object DualSenseBridge {
    private const val BRIDGE_PKG = "com.example.dsedge"
    private const val BRIDGE_ACTION = "com.example.dsedge.BRIDGE"
    private const val MSG_REGISTER = 1
    private const val MSG_UNREGISTER = 2
    private const val MSG_OUTPUT = 3
    private const val MSG_FRAME = 10
    private const val MSG_STATUS = 11
    private const val MSG_PAD_GONE = 12
    // Steam Controller (2026) raw passthrough over USB (cable or wireless puck), see the bridge's triton.h
    private const val MSG_TRITON_OUTPUT = 4
    private const val MSG_TRITON_GET = 5
    private const val MSG_TRITON_RUMBLE = 6
    private const val MSG_TRITON_STATE = 13
    private const val MSG_TRITON_CONN = 14
    private const val MSG_TRITON_FEATURE = 15
    private const val TRITON_SLOT = 0            // the ring slot DroidDeck's Steam Controller node reads
    private const val TRITON_STATE_BYTES = 45
    private const val TRITON_POLL_MS = 2L
    // Steam Native Hook-up (see HookupBridge.kt and the bridge's hookup.h)
    private const val MSG_HOOKUP_OUT = 7
    private const val MSG_HOOKUP_GET = 8
    private const val MSG_HOOKUP_ADD = 20
    private const val MSG_HOOKUP_INPUT = 21
    private const val MSG_HOOKUP_REMOVE = 22
    private const val MSG_HOOKUP_REPLY = 23
    private const val HOOKUP_POLL_MS = 1L
    private const val MSG_BRIDGE_LOG = 30
    // Background watchdog (like Steam on a PC: controllers are picked up whenever they appear)
    private const val MSG_HOOKUP_SYNC = 9      // -> bridge: "send me everything you hand over right now"
    private const val MSG_HOOKUP_SHM = 25      // -> bridge: "fd" = the shared hook-up file (fast path)
    private const val MSG_HOOKUP_STATE = 24    // <- bridge: "slots": the hook-up slots that are live
    private const val WATCHDOG_MS = 1500L
    private const val SLOTS = 4

    // ---- Tunables (verify on device) ----
    private const val DEAD_ZONE = 0.12f              // same as PadBridge
    private const val MIN_AXIS_INTERVAL_MS = 4L      // don't flood the input ring with stick jitter
    private const val GYRO_LSB_PER_DPS = 16.384f     // Steam Deck IMU: 2000 dps full scale (assumed)
    private const val ACCEL_LSB_PER_G = 16384f       // Steam Deck IMU: 2 g full scale (assumed)
    private val GYRO_SIGN = floatArrayOf(1f, 1f, 1f) // flip an entry to -1f if an axis feels inverted
    private val ACCEL_SIGN = floatArrayOf(1f, 1f, 1f)
    private const val GYRO_GAIN = 1f                 // multiplies gyro only; raise if motion is too slow
    // Touchpad orientation on the DroidDeck side (the bridge app has the same settings; use one or the other, not both).
    private const val TOUCH_INV_X = false
    private const val TOUCH_INV_Y = false
    private const val TOUCH_SWAP = false
    private const val MAP_TOUCHPAD = true            // left half = left trackpad, right half = right trackpad
    private const val TOUCH_HALF = 960               // DualSense touchpad is 1920 x 1080
    private const val TOUCH_H = 1079f

    // bridge mask bit -> PadState button
    private val BUTTONS = mapOf(
        0 to PadState.A, 1 to PadState.B, 2 to PadState.X, 3 to PadState.Y,
        4 to PadState.LB, 5 to PadState.RB,
        8 to PadState.SELECT, 9 to PadState.START,
        10 to PadState.L3, 11 to PadState.R3,
        12 to PadState.GUIDE, 14 to PadState.QAM,
    )
    // bridge mask bit -> Deck grip bit
    private val GRIPS = mapOf(
        19 to DeckControls.L4, 20 to DeckControls.R4,
        21 to DeckControls.L5, 22 to DeckControls.R5,
    )

    private var appContext: Context? = null
    @Volatile private var owner: Any? = null
    private var sessionOwner: Any? = null
    private var pad: PadBridge? = null
    private var server: Messenger? = null
    private var bound = false

    private var lastMask = 0
    private val lastAxes = FloatArray(6)
    private var lastApplyMs = 0L
    // Per bridge slot (0 = player 1 with all features, 1..3 = extra controllers: buttons, sticks, triggers, gyro, rumble).
    private val vidS = IntArray(SLOTS)
    private val pidS = IntArray(SLOTS)
    private val frameMsS = LongArray(SLOTS)
    private val ddSlot = intArrayOf(0, -1, -1, -1)       // bridge slot -> DroidDeck player slot (-1 = not claimed)
    private val maskS = IntArray(SLOTS)
    private val axesS = Array(SLOTS) { FloatArray(6) }
    private val applyMsS = LongArray(SLOTS)
    private val lastTouchKey = intArrayOf(-1, -1)  // [left, right]
    private var clickSide = -1                     // -1 none, 0 left, 1 right

    // DeckControls bit layout (those constants are private over there)
    private const val DC_LEFT_TOUCH = 16
    private const val DC_RIGHT_TOUCH = 32
    private const val DC_LEFT_CLICK = 64
    private const val DC_RIGHT_CLICK = 128

    // Separate-controller mode: the DualSense becomes its OWN DroidDeck controller slot (its own entry in Steam),
    // with full back buttons, both trackpads and gyro, instead of driving the Steam Deck pad.
    private var sepSlot = -1
    private var sepMs = 0L
    private var sepVid = 0
    private var sepPid = 0
    private var sepMask = 0
    private val sepAxes = FloatArray(6)
    private var sepApplyMs = 0L
    private var lastPadType = 0
    private var lastSepControls = -1
    private val lastSepPads = ShortArray(4)
    private val lastSepPressure = ShortArray(2)

    private val worker = android.os.HandlerThread("ds-bridge", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val workerHandler = Handler(worker.looper)
    private val inbox = Messenger(object : Handler(worker.looper) {
        /*
         * Called on the Binder thread that received the bridge's message, before it is queued. Input must never
         * wait in a queue: with a game running the worker thread can get less CPU than 500-1000 messages a second
         * need, and an ever-growing queue of old input is lag that builds up over time. So:
         *  - controller reports for Steam (hook-up) and Steam Controller states go straight into shared memory;
         *  - frames are "latest wins" per controller (see offerFrame), so only fresh state waits for the worker.
         */
        override fun sendMessageAtTime(msg: Message, uptimeMillis: Long): Boolean {
            if (msg.callback == null) when (msg.what) {
                MSG_FRAME -> { offerFrame(msg.data); return true }
                MSG_HOOKUP_INPUT -> {
                    msg.data.getByteArray("data")?.let { HookupBridge.input(msg.data.getInt("slot"), it) }
                    return true
                }
                MSG_TRITON_STATE -> {
                    val d = msg.data.getByteArray("data")
                    if (d != null && tritonOn) FakeInputWriter.writeTritonState(TRITON_SLOT, msg.data.getInt("id"), d)
                    return true
                }
            }
            return super.sendMessageAtTime(msg, uptimeMillis)
        }

        override fun handleMessage(m: Message) {
            if (!sessionActive) return
            when (m.what) {
                MSG_FRAME -> onFrame(m.data)
                MSG_STATUS -> if (m.data.getString("text")?.startsWith("Connected") != true) release()
                MSG_PAD_GONE -> padGone(m.data.getInt("slot"))
                MSG_TRITON_STATE -> m.data.getByteArray("data")?.let {
                    if (tritonOn) FakeInputWriter.writeTritonState(TRITON_SLOT, m.data.getInt("id"), it)
                }
                MSG_TRITON_CONN -> setTriton(m.data.getBoolean("on"), m.data.getString("what") ?: "")
                MSG_HOOKUP_ADD -> { HookupBridge.add(m.data); startHookupPoll(); updateHookupPad() }
                MSG_HOOKUP_INPUT -> m.data.getByteArray("data")?.let { HookupBridge.input(m.data.getInt("slot"), it) }
                MSG_HOOKUP_STATE -> {
                    val live = m.data.getIntArray("slots") ?: IntArray(0)
                    // the bridge maps a different (or no) shared file: hand it the current one (at most every 10 s)
                    if (m.data.getLong("fast_ino", 0L) != HookupBridge.shmInode() &&
                        android.os.SystemClock.uptimeMillis() - shmSentMs > 10_000) sendShm()
                    for (s in 0 until HookupBridge.SLOTS) if (HookupBridge.isPresent(s) && s !in live) {
                        HookupBridge.note("hookup: the bridge no longer hands over slot $s; removing it")
                        HookupBridge.remove(s)
                    }
                    if (live.isNotEmpty()) startHookupPoll()
                    updateHookupPad()
                }
                MSG_BRIDGE_LOG -> m.data.getString("text")?.split('\n')?.forEach { HookupBridge.note("sdl3bridge: $it") }
                MSG_HOOKUP_REMOVE -> { HookupBridge.remove(m.data.getInt("slot")); updateHookupPad() }
                MSG_HOOKUP_REPLY -> HookupBridge.reply(m.data.getInt("slot"), m.data.getLong("request"), m.data.getByteArray("data"))
                MSG_TRITON_FEATURE -> {
                    val request = m.data.getLong("request")
                    if (request == tritonFeatureAsked) FakeInputWriter.writeTritonFeatureReply(TRITON_SLOT, request, m.data.getByteArray("data"))
                }
            }
        }
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            server = Messenger(b)
            send(Message.obtain(null, MSG_REGISTER).apply { replyTo = inbox })
            sendShm()
            workerHandler.post { if (onDesktop()) sendSync() }   // the desktop: say at once whether Steam runs
        }
        override fun onServiceDisconnected(n: ComponentName) {
            server = null; release()
            workerHandler.post { setTriton(false, "bridge stopped"); HookupBridge.removeAll() }
        }
    }

    /** @return false if the bridge app is not installed / not bindable. */
    @JvmStatic
    @Synchronized
    fun attach(context: Context, padBridge: PadBridge): Boolean {
        if (!com.droiddeck.launcher.session.SessionPrefs.sdl3Controllers(context)) return false
        if (pad !== padBridge) {
            release()
            releaseSep()
            lastMask = -1
            lastAxes.fill(Float.NaN)
            lastApplyMs = 0L
            for (slot in 1 until SLOTS) {
                maskS[slot] = -1
                axesS[slot].fill(Float.NaN)
                applyMsS[slot] = 0L
            }
            sepMask = -1
            sepAxes.fill(Float.NaN)
            sepApplyMs = 0L
        }
        pad = padBridge
        owner = context
        if (!sessionActive) {
            sessionActive = true
            workerHandler.removeCallbacks(watchdog)
            workerHandler.postDelayed(watchdog, WATCHDOG_MS)
        }
        if (bound) {
            // A new session screen while the old one is still being torn down (Android recreates it when you
            // come back from another app): keep the connection, and ask the bridge to resend its state.
            send(Message.obtain(null, MSG_REGISTER).apply { replyTo = inbox })
            sendShm()
            HookupBridge.note("bridge: new session screen, connection kept, state re-requested")
            return true
        }
        val app = context.applicationContext
        appContext = app
        bound = try {
            app.bindService(Intent(BRIDGE_ACTION).setPackage(bridgePackage(app)), conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) { false }
        return bound
    }

    @JvmStatic
    @Synchronized
    fun beginSession(token: Any) { sessionOwner = token }

    @JvmStatic
    @Synchronized
    fun endSession(token: Any) {
        if (sessionOwner !== token) return
        sessionOwner = null
        disconnect()
    }

    /**
     * The session screen is closing (Android also does this when you switch apps; a new screen attaches when you
     * come back). Only its PadBridge is dropped -- the bridge stays connected, so Steam Native Hook-up and the
     * Steam Controller passthrough keep running until the session ends or DroidDeck is closed.
     */
    @JvmStatic
    @Synchronized
    fun screenClosed(who: Any?) {
        if (who !== owner) return
        HookupBridge.note("bridge: session screen closed; the bridge stays connected until the session ends")
        owner = null
        release()
        releaseSep()
        pad = null
    }

    /** Only the screen that attached last may detach (an old screen's late onDestroy must not cut the new one off). */
    @JvmStatic
    @Synchronized
    fun detach(who: Any) {
        if (who !== owner || sessionOwner != null) {
            HookupBridge.note("bridge: an old session screen closed; the bridge stays connected")
            return
        }
        disconnect()
    }

    private fun disconnect() {
        HookupBridge.note("bridge: disconnected (session ended)")
        sessionActive = false
        steamAwayNoted = null
        workerHandler.removeCallbacks(watchdog)
        owner = null
        if (bound) {
            send(Message.obtain(null, MSG_UNREGISTER).apply { replyTo = inbox })
            try { appContext?.unbindService(conn) } catch (_: IllegalArgumentException) {}
            bound = false
        }
        release()
        releaseSep()
        setTriton(false, "session ended")
        HookupBridge.removeAll()
        pad = null
        server = null
    }

    // ---------------- Steam Controller (2026) raw passthrough ----------------
    // The bridge streams the controller's own HID reports; they go into the same shared block DroidDeck's
    // Bluetooth LE Steam Controller support uses, so Steam sees DroidDeck's Steam Controller node and drives
    // it natively (capacitive stick touch, grip sense, trackpad pressure, IMU, haptics, settings). Steam's
    // output reports and feature reads/writes travel back to the controller the same way.
    @Volatile private var tritonOn = false
    private var tritonOutputSeq = 0L
    private var tritonFeatureAsked = 0L
    private val tritonPoll = object : Runnable {
        override fun run() {
            if (!tritonOn) return
            while (true) {
                val out = FakeInputWriter.readTritonOutput(TRITON_SLOT, tritonOutputSeq) ?: break
                tritonOutputSeq = java.nio.ByteBuffer.wrap(out, 0, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
                send(Message.obtain(null, MSG_TRITON_OUTPUT).apply {
                    data = Bundle().apply {
                        putByteArray("data", out.copyOfRange(9, out.size))
                        putBoolean("feature", out[8] != 0.toByte())
                    }
                })
            }
            val request = FakeInputWriter.readTritonFeatureRequest(TRITON_SLOT)
            if (request != 0L && request != tritonFeatureAsked) {
                tritonFeatureAsked = request
                send(Message.obtain(null, MSG_TRITON_GET).apply {
                    data = Bundle().apply { putLong("request", request); putInt("id", 1) }
                })
            }
            workerHandler.postDelayed(this, TRITON_POLL_MS)
        }
    }

    // ---------------- Steam Native Hook-up fast path ----------------
    // The bridge gets the shared hook-up file itself (as a descriptor: Android lets an app use a descriptor it is
    // handed, only opening another app's file by path is forbidden). It then writes each report straight into
    // the file and wakes Steam's reader -- no message per report, no polling -- and DroidDeck only keeps the
    // controller list and its sysfs description. An older bridge simply ignores the message.
    @Volatile private var shmSentMs = 0L

    private fun sendShm() {
        val file = HookupBridge.shmFile ?: return
        if (server == null) return
        shmSentMs = android.os.SystemClock.uptimeMillis()
        try {
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_WRITE).use { pfd ->
                send(Message.obtain(null, MSG_HOOKUP_SHM).apply { data = Bundle().apply { putParcelable("fd", pfd) } })
            }
        } catch (e: Exception) {
            HookupBridge.note("hookup: could not hand the shared file to the bridge: $e")
        }
    }

    // ---------------- background watchdog ----------------
    // For the whole session (not just while the session screen is open), like Steam on a PC: reconnect to the
    // bridge if that connection is gone (bridge restarted, killed, installed late), and ask it every 1.5 s what it
    // is handing over -- controllers that appeared are added, ones it no longer has are removed, and nothing
    // changes for ones that are already there. Also keeps DroidDeck's own pad withdrawn while Steam has a real one.
    @Volatile private var sessionActive = false
    private var serverLostTicks = 0
    private val watchdog = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            val ctx = appContext
            if (!bound && ctx != null) {
                bound = try {
                    ctx.bindService(Intent(BRIDGE_ACTION).setPackage(bridgePackage(ctx)), conn, Context.BIND_AUTO_CREATE)
                } catch (_: SecurityException) { false }
                if (bound) HookupBridge.note("bridge: watchdog (re)connecting to the SDL3 bridge")
            } else if (server != null) {
                serverLostTicks = 0
                sendSync()
            } else if (bound && ++serverLostTicks >= 4) {
                // bound but no connection for ~6 s (the bridge app was force-stopped): start over
                serverLostTicks = 0
                try { ctx?.unbindService(conn) } catch (_: IllegalArgumentException) {}
                bound = false
                HookupBridge.note("bridge: the SDL3 bridge went away; the watchdog will reconnect")
            }
            updateHookupPad()
            workerHandler.postDelayed(this, WATCHDOG_MS)
        }
    }

    // ---------------- DroidDeck's desktop: Steam Native Hook-up waits for Steam ----------------
    // On the desktop Steam is one program among others, and it may not be running. The bridge then hands controllers
    // to Steam only while the Steam client runs (its heartbeat in the hook-up file); until it starts -- and after it
    // quits -- they stay ordinary gamepads every program can use. In the Steam session Steam always runs.
    @Volatile private var steamAwayNoted: Boolean? = null

    private fun onDesktop(): Boolean = try {
        com.droiddeck.launcher.session.SessionState.mode == com.droiddeck.launcher.session.SessionService.MODE_DESKTOP
    } catch (_: Throwable) { false }

    private fun desktopWithoutSteam(): Boolean {
        val desktop = onDesktop()
        val away = desktop && !HookupBridge.steamRunning()
        if (desktop && away != steamAwayNoted) {
            steamAwayNoted = away
            HookupBridge.note(if (away) "hookup: Steam is not running on the desktop: with Steam Native Hook-up on, the SDL3 " +
                "bridge keeps controllers as ordinary gamepads until Steam starts"
            else "hookup: Steam runs on the desktop: with Steam Native Hook-up on, the SDL3 bridge hands controllers to Steam")
        }
        return away
    }

    /** "Send me everything you hand over right now" -- and, on the desktop, whether Steam is there to take it. */
    private fun sendSync() {
        send(Message.obtain(null, MSG_HOOKUP_SYNC).apply {
            replyTo = inbox
            data = Bundle().apply { putBoolean("steamAway", desktopWithoutSteam()) }
        })
    }

    // ---------------- Steam Native Hook-up: Steam -> controller ----------------
    private var hookupPolling = false
    private var hookupPollTicks = 0
    private val hookupPoll = object : Runnable {
        override fun run() {
            if (!HookupBridge.anyPresent()) { hookupPolling = false; return }
            // every ~250 ms: keep DroidDeck's own pad withdrawn (also once a bridged gamepad's last frame is
            // more than a second old -- e.g. right after hook-up was switched back on)
            if (++hookupPollTicks % 250 == 0) updateHookupPad()
            HookupBridge.poll(
                { slot, kind, report ->
                    send(Message.obtain(null, MSG_HOOKUP_OUT).apply {
                        data = Bundle().apply { putInt("slot", slot); putInt("kind", kind); putByteArray("data", report) }
                    })
                },
                { slot, request, id, input, length ->
                    send(Message.obtain(null, MSG_HOOKUP_GET).apply {
                        data = Bundle().apply {
                            putInt("slot", slot); putLong("request", request); putInt("id", id)
                            putBoolean("input", input); putInt("length", length)
                        }
                    })
                })
            workerHandler.postDelayed(this, HOOKUP_POLL_MS)
        }
    }

    // While real controllers are handed to Steam and no bridged gamepad is streaming, DroidDeck's own pad
    // is withdrawn so the real controller is player 1 in Steam (pad type 7 = none), like on a PC.
    private var hookupPadHidden = false

    private fun currentPadType(): Int = FakeInputWriter.currentPadType()

    private fun updateHookupPad() {
        val hide = HookupBridge.anyPresent() && !isActive()
        if (hide && hookupPadHidden && currentPadType() != FakeInputWriter.PAD_TYPE_NONE) {
            // something switched DroidDeck's pad back on (e.g. the pad-type menu): withdraw it again
            HookupBridge.note("hookup: DroidDeck's pad came back while a controller is handed to Steam; withdrawing it again")
            applyPadType(FakeInputWriter.PAD_TYPE_NONE)
        }
        if (hide && !hookupPadHidden) {
            hookupPadHidden = true
            lastPadType = -1
            applyPadType(FakeInputWriter.PAD_TYPE_NONE)
        } else if (!hide && hookupPadHidden) {
            hookupPadHidden = false
            val ctx = appContext ?: return
            val pt = com.droiddeck.launcher.session.SessionPrefs.padType(com.droiddeck.launcher.session.SessionPrefs.steamController(ctx))
            lastPadType = pt
            applyPadType(pt)
        }
    }

    private fun startHookupPoll() {
        if (hookupPolling) return
        hookupPolling = true
        workerHandler.post(hookupPoll)
    }

    /** Runs on the worker thread. */
    private fun setTriton(on: Boolean, what: String) {
        if (on == tritonOn) return
        tritonOn = on
        HookupBridge.tritonPassthrough = on
        workerHandler.removeCallbacks(tritonPoll)
        if (on) {
            tritonFeatureAsked = 0L
            FakeInputWriter.setTritonConnected(TRITON_SLOT, true)
            workerHandler.post(tritonPoll)
        } else {
            tritonRumbleStop?.let { rumbleTimer.removeCallbacks(it) }
            tritonRumbleStop = null
            FakeInputWriter.setTritonConnected(TRITON_SLOT, false)
            FakeInputWriter.writeTritonState(TRITON_SLOT, 0x45, ByteArray(TRITON_STATE_BYTES))
        }
        android.util.Log.i("SessionService", "steam controller: SDL3 bridge passthrough ${if (on) "on ($what)" else "off ($what)"}")
    }

    /** True while a Steam Controller (2026) is passed through by the bridge (cable or puck). */
    @JvmStatic fun isTritonPassthrough(): Boolean = tritonOn

    /**
     * True when the SDL3 bridge app is installed. DroidDeck then prepares its Steam Controller node at session
     * start even without a Bluetooth-paired one, so a cable/puck controller can appear at any time (hot-plug).
     */
    @JvmStatic
    fun isBridgeInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(bridgePackage(context), 0); true
    } catch (_: Exception) { false }

    @Volatile private var bridgePkg: String? = null

    /**
     * Where the SDL3 bridge is: built into this app (SDL3DroidDeck: the same bridge, in its own ":sdl3" process), or
     * the separate SDL3Bridge app (SDL3Deck). The built-in one is used whenever this app has it.
     */
    @JvmStatic
    fun bridgePackage(context: Context): String {
        bridgePkg?.let { return it }
        val own = context.packageName
        val builtIn = try {
            context.packageManager.resolveService(Intent(BRIDGE_ACTION).setPackage(own), 0) != null
        } catch (_: Exception) { false }
        return (if (builtIn) own else BRIDGE_PKG).also { bridgePkg = it }
    }

    /** True when the bridge is built into this app (SDL3DroidDeck). */
    @JvmStatic
    fun bridgeBuiltIn(context: Context): Boolean = bridgePackage(context) == context.packageName

    private var tritonRumbleStop: Runnable? = null

    private fun tritonRumble(strong: Int, weak: Int, ms: Int) {
        rumbleTimer.post {
            tritonRumbleStop?.let { rumbleTimer.removeCallbacks(it) }
            tritonRumbleStop = null
            sendTritonRumble(strong, weak)
            if (strong == 0 && weak == 0) return@post
            val hold = if (ms == RUMBLE_HOLD_MS || ms <= 0) HOLD_SAFETY_MS else ms.toLong().coerceIn(1L, 5000L)
            val stop = Runnable { sendTritonRumble(0, 0); tritonRumbleStop = null }
            tritonRumbleStop = stop
            rumbleTimer.postDelayed(stop, hold)
        }
    }

    private fun sendTritonRumble(strong: Int, weak: Int) {
        send(Message.obtain(null, MSG_TRITON_RUMBLE).apply {
            data = Bundle().apply { putInt("l", strong.coerceIn(0, 65535)); putInt("r", weak.coerceIn(0, 65535)) }
        })
    }

    /** Forward game rumble (0..255 each) to the physical controller of player 1. */
    // Per-bridge-slot pending auto-stop. The bridge's rumble is level-based (stays on until set to 0),
    // so we must stop it ourselves after the effect's duration — exactly as RumbleComponent does with its
    // one-shots. Without this a single "ping" (one 2000 ms buzz) would rumble the controller forever.
    private val rumbleTimer = Handler(worker.looper)
    private val rumbleStop = arrayOfNulls<Runnable>(SLOTS)
    private const val RUMBLE_HOLD_MS = 0xFFFF   // matches RumbleComponent.HOLD_MS
    private const val HOLD_SAFETY_MS = 30_000L

    @JvmStatic
    fun rumble(strong: Int, weak: Int) = rumble(0, strong, weak, RUMBLE_HOLD_MS)

    @JvmStatic
    fun rumble(deckSlot: Int, strong: Int, weak: Int) = rumble(deckSlot, strong, weak, RUMBLE_HOLD_MS)

    /**
     * Rumble for a DroidDeck player slot (0 = player 1), honoring the effect duration [ms].
     * HOLD effects (0xFFFF) play until the explicit stop that follows them (30 s safety cap); a timed
     * one-shot (e.g. the controller "ping") plays for its own duration and then stops.
     */
    @JvmStatic
    fun rumble(deckSlot: Int, strong: Int, weak: Int, ms: Int) {
        rumbleTimer.post {
            val bridgeSlot = (0 until SLOTS).firstOrNull { ddSlot[it] == deckSlot } ?: return@post
            rumbleStop[bridgeSlot]?.let { rumbleTimer.removeCallbacks(it); rumbleStop[bridgeSlot] = null }
            sendRumble(bridgeSlot, strong, weak)
            if (strong == 0 && weak == 0) return@post
            // HOLD = play until the game's/Steam's explicit stop (they always send one); the long cap is only a
            // safety net in case that stop is lost (e.g. the game crashes mid-rumble).
            val hold = if (ms == RUMBLE_HOLD_MS || ms <= 0) HOLD_SAFETY_MS else ms.toLong().coerceIn(1L, 5000L)
            val stop = Runnable { sendRumble(bridgeSlot, 0, 0); rumbleStop[bridgeSlot] = null }
            rumbleStop[bridgeSlot] = stop
            rumbleTimer.postDelayed(stop, hold)
        }
    }

    private fun sendRumble(bridgeSlot: Int, strong: Int, weak: Int) {
        send(Message.obtain(null, MSG_OUTPUT).apply {
            data = Bundle().apply {
                putInt("slot", bridgeSlot)
                putInt("rl", strong.coerceIn(0, 255)); putInt("rr", weak.coerceIn(0, 255))
            }
        })
    }

    private fun send(m: Message) { try { server?.send(m) } catch (_: RemoteException) {} }

    // ---------------- frame handling ----------------

    private fun stick(v: Int): Float {
        val f = ((v - 128) / 127f).coerceIn(-1f, 1f)
        return if (Math.abs(f) < DEAD_ZONE) 0f else f
    }

    /** True while player 1's controller is streaming; PadMotion uses this to stop writing the phone's gyro. */
    @JvmStatic
    fun isActive(): Boolean = frameMsS[0] != 0L && SystemClock.elapsedRealtime() - frameMsS[0] < 1000L

    // ---------------- frames: latest wins ----------------
    // Each bridge frame is a complete controller state, so when several wait for the worker only the newest matters
    // -- except frames where a button, trackpad touch or click changed, which are all kept so no tap is lost. A
    // frame that only moves sticks / gyro and is already older than FRAME_STALE_NS (a backlog inside Binder) is
    // dropped: its state is out of date and a newer frame follows.
    private const val FRAME_STALE_NS = 50_000_000L
    private const val FRAME_PENDING_MAX = 16
    private val frames = FrameMailbox<Bundle>(SLOTS, FRAME_STALE_NS, FRAME_PENDING_MAX,
        sigOf = ::frameSig, sentNsOf = { it.getLong("t", 0L) }, nowNs = SystemClock::elapsedRealtimeNanos)
    private val drainFrames = Array(SLOTS) { slot -> Runnable { drainFrames(slot) } }

    /** The digital part of a frame: buttons, trackpad click side, which trackpads are touched. */
    private fun frameSig(b: Bundle): Long {
        val padL = b.getFloatArray("padL")
        val padR = b.getFloatArray("padR")
        val touch = b.getIntArray("touch")
        return (b.getInt("mask").toLong() and 0xffffffffL) or
            ((b.getInt("padClick", -1) + 1).toLong() shl 32) or
            ((if (padL != null && padL.isNotEmpty() && padL[0] > 0f) 1L else 0L) shl 34) or
            ((if (padR != null && padR.isNotEmpty() && padR[0] > 0f) 1L else 0L) shl 35) or
            (((touch?.size ?: 0) / 3).toLong() shl 36)
    }

    private fun offerFrame(b: Bundle) {
        val slot = b.getInt("slot", 0).coerceIn(0, SLOTS - 1)
        if (frames.offer(slot, b)) workerHandler.post(drainFrames[slot])
    }

    private fun drainFrames(slot: Int) {
        val now = SystemClock.elapsedRealtimeNanos()
        for (f in frames.take(slot)) {
            val sent = f.getLong("t", 0L)
            if (sent != 0L) lagMaxNs = maxOf(lagMaxNs, now - sent)
            lagHandled++
            onFrame(f)
        }
        lagReport(now)
    }

    // Lag meter, written to pad.log every 10 s when input was late: how old frames were when handled (bridge ->
    // Binder -> this thread) and how many stale ones were dropped. Lag that keeps growing shows up here.
    private var lagHandled = 0
    private var lagMaxNs = 0L
    private var lagSinceNs = 0L
    private fun lagReport(now: Long) {
        if (lagSinceNs == 0L) lagSinceNs = now
        if (now - lagSinceNs < 10_000_000_000L) return
        val dropped = frames.takeDropped()
        if (lagMaxNs > 20_000_000L || dropped > 0)
            HookupBridge.note("bridge: input lag, last 10 s: %d frames handled, oldest %.1f ms; %d stale frames dropped"
                .format(lagHandled, lagMaxNs / 1e6, dropped))
        lagSinceNs = now; lagHandled = 0; lagMaxNs = 0L
    }

    /**
     * True when [dev] is the same physical controller the SDL3 bridge is currently streaming (any player slot).
     * Android reports that controller too (basic input), so DroidDeck's own gamepad handling must
     * ignore it while the bridge is active, otherwise every press arrives twice.
     */
    @JvmStatic
    fun isBridged(dev: InputDevice?): Boolean {
        if (dev == null) return false
        val now = SystemClock.elapsedRealtime()
        for (i in 0 until SLOTS) {
            if (vidS[i] != 0 && frameMsS[i] != 0L && now - frameMsS[i] < 1000L &&
                dev.vendorId == vidS[i] && dev.productId == pidS[i]) return true
        }
        if (sepVid != 0 && sepMs != 0L && now - sepMs < 1000L &&
            dev.vendorId == sepVid && dev.productId == sepPid) return true
        return false
    }

    /** True when DroidDeck player [deckSlot] is currently driven by a bridge controller (for rumble routing). */
    @JvmStatic
    fun isBridgedSlot(deckSlot: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        for (i in 0 until SLOTS) {
            if (ddSlot[i] == deckSlot && frameMsS[i] != 0L && now - frameMsS[i] < 1000L) return true
        }
        return false
    }

    /**
     * Called first thing in RumbleComponent.buzz(). Returns true when the effect was sent to a bridged
     * controller (and must NOT also go to the phone / an Android pad).
     *
     *  - slot 0..3: a DroidDeck player slot -> its bridged controller, if one drives it.
     *  - slot >= PadBridge.SLOTS (e.g. 16): a game rumbling Steam's VIRTUAL pad. Upstream sends that to the
     *    "last active Android controller", which is nobody while the bridge holds the pad, so it fell back to
     *    the phone's vibrator. Route it to player 1's bridged controller instead (unless a real Android pad is
     *    the active one, or it is a SteamVR Joy-Con hand slot).
     */
    @JvmStatic
    fun routeRumble(slot: Int, strong: Int, weak: Int, ms: Int): Boolean {
        // A real controller Steam drives itself is connected (Steam Native Hook-up / Steam Controller
        // passthrough): game rumble on Steam's virtual pad goes to Steam (fakeinput_steam.cpp hands it the
        // uinput force-feedback request) and Steam rumbles that controller with its own driver.
        if (slot >= PadBridge.SLOTS && JoyCon.vrRumbleDevice(slot) == null && HookupBridge.ffToSteam()) return true
        // Only a passed-through Steam Controller (2026), no bridged gamepad: player 1 / virtual-pad rumble -> it.
        if (tritonOn && !isBridgedSlot(0) && (slot == 0 || slot >= PadBridge.SLOTS) && JoyCon.vrRumbleDevice(slot) == null &&
            (slot == 0 || !realAndroidPadActive())) {
            val on = (strong != 0 || weak != 0) && ms != 0
            if (on) tritonRumble(strong, weak, ms) else tritonRumble(0, 0, 0)
            return true
        }
        val deckSlot = when {
            slot in 0 until PadBridge.SLOTS -> if (isBridgedSlot(slot)) slot else return false
            JoyCon.vrRumbleDevice(slot) != null -> return false
            !isBridgedSlot(0) -> return false
            else -> {
                if (realAndroidPadActive()) return false   // a real Android pad with motors is in use: leave it to upstream
                0
            }
        }
        val on = (strong != 0 || weak != 0) && ms != 0   // ms == 0 is a stop, as in RumbleComponent
        if (on != rumbleLogged) {
            rumbleLogged = on
            android.util.Log.i("SessionService", "rumble: slot $slot -> SDL3 bridge (player ${deckSlot + 1}) ${if (on) "on ${strong shr 8}/${weak shr 8} ${ms}ms" else "off"}")
        }
        if (on) rumble(deckSlot, strong shr 8, weak shr 8, ms) else rumble(deckSlot, 0, 0, 0)
        return true
    }
    @Volatile private var rumbleLogged = false

    private fun realAndroidPadActive(): Boolean {
        val id = PadBridge.activeControllerId()
        if (id == PadBridge.NO_CONTROLLER) return false
        val dev = InputDevice.getDevice(id) ?: return false
        return !isBridged(dev) && hasMotors(dev)
    }

    private fun hasMotors(dev: InputDevice): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 31) dev.vibratorManager.vibratorIds.isNotEmpty()
        else @Suppress("DEPRECATION") dev.vibrator.hasVibrator()
    } catch (_: Exception) { false }

    @JvmStatic fun isBridged(e: KeyEvent): Boolean = isBridged(e.device)
    @JvmStatic fun isBridged(e: MotionEvent): Boolean = isBridged(e.device)

    /** A controller in bridge slot [slot] was unplugged. */
    @Synchronized private fun padGone(slot: Int) {
        if (slot !in 0 until SLOTS) return
        if (slot == 0) { release(); return }
        val p = pad
        val dd = ddSlot[slot]
        if (p != null && dd > 0) {
            p.applyExternalSlot(dd, { s: PadState -> fill(s, 0, FloatArray(6)) }, false)
            p.releaseBridgeSlot(dd)
        }
        ddSlot[slot] = -1
        frameMsS[slot] = 0L
        vidS[slot] = 0; pidS[slot] = 0
        maskS[slot] = 0; axesS[slot].fill(0f)
    }

    /** Extra controllers (bridge slots 1..3) become DroidDeck players 2..4: plain gamepad plus gyro. */
    private fun onExtraFrame(slot: Int, b: Bundle) {
        val p = pad ?: return
        val now = SystemClock.elapsedRealtime()
        frameMsS[slot] = now
        vidS[slot] = b.getInt("vid"); pidS[slot] = b.getInt("pid")
        var dd = ddSlot[slot]
        if (dd <= 0) {
            dd = p.claimBridgeSlot()
            if (dd <= 0) return                      // all DroidDeck player slots are taken
            ddSlot[slot] = dd
        }
        val mask = b.getInt("mask")
        val ax = floatArrayOf(
            stick(b.getInt("lx")), stick(b.getInt("ly")), stick(b.getInt("rx")), stick(b.getInt("ry")),
            b.getInt("l2") / 255f, b.getInt("r2") / 255f,
        )
        val buttonsChanged = mask != maskS[slot]
        val axesChanged = !ax.contentEquals(axesS[slot])
        if (buttonsChanged || (axesChanged && now - applyMsS[slot] >= MIN_AXIS_INTERVAL_MS)) {
            maskS[slot] = mask
            ax.copyInto(axesS[slot])
            applyMsS[slot] = now
            p.applyExternalSlot(dd, { s: PadState -> fill(s, mask, ax) }, buttonsChanged)
        }
        val g = b.getFloatArray("gyro")
        val a = b.getFloatArray("accel")
        if (g != null && a != null) {
            FakeInputWriter.writeMotion(dd, shorts(a, ACCEL_LSB_PER_G, ACCEL_SIGN), shorts(g, GYRO_LSB_PER_DPS * GYRO_GAIN, GYRO_SIGN))
        }
    }

    private fun axisS(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    /** The DualSense as its own full-featured DroidDeck controller (separate Steam entry). */
    private fun onSeparateFrame(b: Bundle) {
        val p = pad ?: return
        val now = SystemClock.elapsedRealtime()
        sepMs = now
        sepVid = b.getInt("vid"); sepPid = b.getInt("pid")
        if (sepSlot <= 0) {
            sepSlot = p.claimBridgeSlot()
            if (sepSlot <= 0) return                 // no free DroidDeck slot
            lastSepControls = -1
        }
        val mask = b.getInt("mask")
        val ax = floatArrayOf(
            stick(b.getInt("lx")), stick(b.getInt("ly")), stick(b.getInt("rx")), stick(b.getInt("ry")),
            b.getInt("l2") / 255f, b.getInt("r2") / 255f,
        )
        val buttonsChanged = mask != sepMask
        val axesChanged = !ax.contentEquals(sepAxes)
        if (buttonsChanged || (axesChanged && now - sepApplyMs >= MIN_AXIS_INTERVAL_MS)) {
            sepMask = mask
            ax.copyInto(sepAxes)
            sepApplyMs = now
            p.applyExternalSlot(sepSlot, { s: PadState -> fill(s, mask, ax) }, buttonsChanged)
        }

        // back buttons + trackpads + clicks -> this slot's Deck controls
        var controls = 0
        if (((mask shr 19) and 1) != 0) controls = controls or DeckControls.L4
        if (((mask shr 20) and 1) != 0) controls = controls or DeckControls.R4
        if (((mask shr 21) and 1) != 0) controls = controls or DeckControls.L5
        if (((mask shr 22) and 1) != 0) controls = controls or DeckControls.R5
        val pads = ShortArray(4)
        val padL = b.getFloatArray("padL")
        val padR = b.getFloatArray("padR")
        if (padL != null && padL.size >= 3 && padL[0] > 0.5f) {
            val xy = orientPad(padL[1], padL[2]); controls = controls or DC_LEFT_TOUCH
            pads[0] = axisS(xy[0]); pads[1] = axisS(xy[1])
        }
        if (padR != null && padR.size >= 3 && padR[0] > 0.5f) {
            val xy = orientPad(padR[1], padR[2]); controls = controls or DC_RIGHT_TOUCH
            pads[2] = axisS(xy[0]); pads[3] = axisS(xy[1])
        }
        val pressure = ShortArray(2)
        when (b.getInt("padClick", -1)) {
            0 -> { controls = controls or DC_LEFT_CLICK; pressure[0] = Short.MAX_VALUE }
            1 -> { controls = controls or DC_RIGHT_CLICK; pressure[1] = Short.MAX_VALUE }
        }
        if (controls != lastSepControls || !pads.contentEquals(lastSepPads) || !pressure.contentEquals(lastSepPressure)) {
            FakeInputWriter.writeDeckControls(sepSlot, controls, pads, pressure)
            lastSepControls = controls
            pads.copyInto(lastSepPads)
            pressure.copyInto(lastSepPressure)
        }

        val g = b.getFloatArray("gyro")
        val a = b.getFloatArray("accel")
        if (g != null && a != null) {
            FakeInputWriter.writeMotion(sepSlot, shorts(a, ACCEL_LSB_PER_G, ACCEL_SIGN), shorts(g, GYRO_LSB_PER_DPS * GYRO_GAIN, GYRO_SIGN))
        }
    }

    private fun orientPad(x0: Float, y0: Float): FloatArray {
        var x = x0; var y = y0
        if (TOUCH_SWAP) { val t = x; x = y; y = t }
        if (TOUCH_INV_X) x = -x
        if (TOUCH_INV_Y) y = -y
        return floatArrayOf(x, y)
    }

    @Synchronized private fun releaseSep() {
        val p = pad
        if (p != null && sepSlot > 0) {
            FakeInputWriter.writeDeckControls(sepSlot, 0, ShortArray(4), ShortArray(2))
            p.applyExternalSlot(sepSlot, { s: PadState -> fill(s, 0, FloatArray(6)) }, false)
            p.releaseBridgeSlot(sepSlot)
        }
        sepSlot = -1; sepMs = 0L; sepVid = 0; sepPid = 0; sepMask = 0; sepAxes.fill(0f)
        lastSepControls = -1; lastSepPads.fill(0); lastSepPressure.fill(0)
    }

    /**
     * Switch the emulated controller type exactly the way DroidDeck's own QAM does: first show the
     * right hidraw node (creates /sys/class/hidraw symlink, removes the others) so Steam can enumerate
     * it, THEN flip the shared pad_type. Without showPad() setPadType alone only changes the report
     * format, so Steam never sees the new device and keeps the Steam Deck pad.
     */
    private fun applyPadType(pt: Int) {
        try { com.droiddeck.launcher.session.SteamDeckPad.showPad(pt) } catch (t: Throwable) { }
        FakeInputWriter.setPadType(pt)
    }

    @Synchronized private fun onFrame(b: Bundle) {
        if (pad == null) return
        val slot = b.getInt("slot", 0).coerceIn(0, SLOTS - 1)
        if (slot != 0) { onExtraFrame(slot, b); return }
        val pt = b.getInt("ptype", 0)
        if (hookupPadHidden) hookupPadHidden = false   // a bridged gamepad needs DroidDeck's pad again
        if (pt != lastPadType) { lastPadType = pt; applyPadType(pt) }  // Deck / DualSense / DS4
        val p = pad ?: return
        frameMsS[0] = SystemClock.elapsedRealtime()
        vidS[0] = b.getInt("vid"); pidS[0] = b.getInt("pid")
        val mask = b.getInt("mask")
        val ax = floatArrayOf(
            stick(b.getInt("lx")), stick(b.getInt("ly")), stick(b.getInt("rx")), stick(b.getInt("ry")),
            b.getInt("l2") / 255f, b.getInt("r2") / 255f,
        )
        val now = SystemClock.elapsedRealtime()
        val prev = lastMask
        val buttonsChanged = mask != prev
        val axesChanged = !ax.contentEquals(lastAxes)
        if (buttonsChanged || (axesChanged && now - lastApplyMs >= MIN_AXIS_INTERVAL_MS)) {
            lastMask = mask
            ax.copyInto(lastAxes)
            lastApplyMs = now
            p.applyExternal({ s: PadState -> fill(s, mask, ax) }, true)
            if (buttonsChanged) {
                val diff = mask xor prev
                for ((bit, grip) in GRIPS) if (((diff shr bit) and 1) != 0) {
                    DeckControls.setGrip(grip, ((mask shr bit) and 1) != 0)
                }
                if (MAP_TOUCHPAD && !b.containsKey("padL") && ((diff shr 13) and 1) != 0) {
                    if (((mask shr 13) and 1) != 0) {
                        val sides = sidesOf(b.getIntArray("touch"))
                        clickSide = if (sides == 1) 0 else 1   // only left finger -> left click, else right
                        DeckControls.setClick(clickSide == 1, true)
                    } else if (clickSide >= 0) {
                        DeckControls.setClick(clickSide == 1, false)
                        clickSide = -1
                    }
                }
            }
        }
        if (MAP_TOUCHPAD && !decoupledTouch(b)) touch(b.getIntArray("touch"))
        val g = b.getFloatArray("gyro")
        val a = b.getFloatArray("accel")
        if (g != null && a != null) {
            FakeInputWriter.writeMotion(0, shorts(a, ACCEL_LSB_PER_G, ACCEL_SIGN), shorts(g, GYRO_LSB_PER_DPS * GYRO_GAIN, GYRO_SIGN))
        }
    }

    private fun fill(s: PadState, mask: Int, ax: FloatArray) {
        for ((bit, btn) in BUTTONS) s.press(btn, ((mask shr bit) and 1) != 0)
        s.up = ((mask shr 15) and 1) != 0
        s.down = ((mask shr 16) and 1) != 0
        s.left = ((mask shr 17) and 1) != 0
        s.right = ((mask shr 18) and 1) != 0
        s.leftX = ax[0]; s.leftY = ax[1]; s.rightX = ax[2]; s.rightY = ax[3]
        s.leftTrigger = ax[4]; s.rightTrigger = ax[5]
    }

    /**
     * Newer bridge builds split the touchpad themselves (see the bridge's Touchpad layout setting):
     * padL / padR = [down, x, y] with x and y already in -1..1 for that trackpad, padClick = -1 / 0 (left) / 1 (right).
     * @return false when the bridge does not send this data (older bridge), so the caller falls back to raw fingers.
     */
    private fun decoupledTouch(b: Bundle): Boolean {
        val l = b.getFloatArray("padL") ?: return false
        val r = b.getFloatArray("padR") ?: return false
        if (l.size < 3 || r.size < 3) return false
        decoupledPad(false, l)
        decoupledPad(true, r)
        val c = b.getInt("padClick", -1)
        if (c != clickSide) {
            if (clickSide >= 0) DeckControls.setClick(clickSide == 1, false)
            if (c >= 0) DeckControls.setClick(c == 1, true)
            clickSide = c
        }
        return true
    }

    private fun decoupledPad(right: Boolean, p: FloatArray) {
        val slot = if (right) 1 else 0
        val touching = p[0] > 0.5f
        val key = if (touching) (((p[1] * 1023f).toInt() and 0x7FF) shl 11) or ((p[2] * 1023f).toInt() and 0x7FF) else -1
        if (key == lastTouchKey[slot]) return
        lastTouchKey[slot] = key
        if (touching) {
            var x = p[1]; var y = p[2]
            if (TOUCH_SWAP) { val t = x; x = y; y = t }
            if (TOUCH_INV_X) x = -x
            if (TOUCH_INV_Y) y = -y
            DeckControls.setPad(right, true, x, y)
        } else DeckControls.setPad(right, false)
    }

    /** Bit 0 = a finger on the left half, bit 1 = a finger on the right half. */
    private fun sidesOf(t: IntArray?): Int {
        var r = 0
        var i = 0
        while (t != null && i + 2 < t.size) { r = r or (if (t[i + 1] < TOUCH_HALF) 1 else 2); i += 3 }
        return r
    }

    private fun touch(t: IntArray?) {
        var left: IntArray? = null
        var right: IntArray? = null
        var i = 0
        while (t != null && i + 2 < t.size) {
            val pt = intArrayOf(t[i + 1], t[i + 2])
            if (pt[0] < TOUCH_HALF) { if (left == null) left = pt } else { if (right == null) right = pt }
            i += 3
        }
        pad(false, left)
        pad(true, right)
    }

    private fun pad(right: Boolean, pt: IntArray?) {
        val slot = if (right) 1 else 0
        val key = if (pt != null) ((pt[0] shr 4) shl 16) or (pt[1] shr 4) else -1
        if (key == lastTouchKey[slot]) return
        lastTouchKey[slot] = key
        if (pt == null) { DeckControls.setPad(right, false); return }
        val local = if (right) pt[0] - TOUCH_HALF else pt[0]
        DeckControls.setPad(right, true, local / TOUCH_HALF.toFloat() * 2f - 1f, pt[1] / TOUCH_H * 2f - 1f)
    }

    private fun shorts(v: FloatArray, scale: Float, sign: FloatArray) =
        ShortArray(3) { (v[it] * scale * sign[it]).coerceIn(-32768f, 32767f).toInt().toShort() }

    /** Neutral state: called when the bridge disconnects so nothing stays stuck. */
    @Synchronized private fun release() {
        frameMsS[0] = 0L
        if (lastPadType != 0 && !hookupPadHidden) { lastPadType = 0; applyPadType(0) }
        updateHookupPad()
        for (k in 1 until SLOTS) padGone(k)
        val p = pad ?: return
        lastMask = 0
        lastAxes.fill(0f)
        lastTouchKey.fill(-1)
        clickSide = -1
        p.applyExternal({ s: PadState -> fill(s, 0, FloatArray(6)) }, false)
        for (grip in GRIPS.values) DeckControls.setGrip(grip, false)
        if (MAP_TOUCHPAD) {
            DeckControls.setPad(false, false); DeckControls.setPad(true, false)
            DeckControls.setClick(false, false); DeckControls.setClick(true, false)
        }
    }
}

// ==== FrameMailbox (self-contained: tested on its own) ====
/**
 * "Latest wins" queue of controller frames per slot, filled on Binder threads and emptied by the worker thread.
 * A frame whose digital part (buttons, trackpad touches / click: [sigOf]) equals the newest waiting one replaces
 * it -- only the newest analog state matters -- while every change of the digital part is kept, so no tap is lost.
 * A frame that arrives already older than [staleNs] without a digital change is dropped (a backlog upstream).
 */
internal class FrameMailbox<T>(
    slots: Int,
    private val staleNs: Long,
    private val maxPending: Int,
    private val sigOf: (T) -> Long,
    private val sentNsOf: (T) -> Long,
    private val nowNs: () -> Long,
) {
    private val pending = Array(slots) { ArrayDeque<T>() }
    private val queued = BooleanArray(slots)
    private val offeredSig = LongArray(slots) { Long.MIN_VALUE }
    private var dropped = 0

    /** @return true when the caller must schedule [take] for this slot (it wasn't scheduled yet). */
    @Synchronized
    fun offer(slot: Int, frame: T): Boolean {
        val sig = sigOf(frame)
        val sent = sentNsOf(frame)
        if (sent != 0L && nowNs() - sent > staleNs && sig == offeredSig[slot]) { dropped++; return false }
        offeredSig[slot] = sig
        val q = pending[slot]
        val last = q.lastOrNull()
        if (last != null && sigOf(last) == sig) q.removeAt(q.size - 1)
        q.addLast(frame)
        while (q.size > maxPending) q.removeAt(0)
        if (queued[slot]) return false
        queued[slot] = true
        return true
    }

    /** Everything waiting for this slot, oldest first (usually one frame). */
    @Synchronized
    fun take(slot: Int): List<T> {
        val l = pending[slot].toList()
        pending[slot].clear()
        queued[slot] = false
        return l
    }

    @Synchronized
    fun takeDropped(): Int = dropped.also { dropped = 0 }
}
