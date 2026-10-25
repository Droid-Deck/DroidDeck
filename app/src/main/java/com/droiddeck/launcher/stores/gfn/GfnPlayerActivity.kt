package com.droiddeck.launcher.stores.gfn

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Plays one GeForce NOW game: books a seat, waits in the queue, then streams onto a SurfaceView. */
class GfnPlayerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_APP_ID = "appId"
        const val EXTRA_VARIANT_ID = "variantId"
        const val EXTRA_TITLE = "title"
        private const val TAG = "GfnPlayer"
        private const val DEADZONE = 0.15f
    }

    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private var streamer: GfnStreamer? = null
    @Volatile private var session: JSONObject? = null
    @Volatile private var closed = false
    @Volatile private var capabilities: JSONObject? = null
    private val helloWait = java.util.concurrent.CompletableFuture<JSONObject>()
    private val pads = Pads()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = GfnStreamer.setSurface(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) = GfnStreamer.setSurface(null)
        })
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })
        val appId = intent.getStringExtra(EXTRA_APP_ID).orEmpty()
        val variantId = intent.getStringExtra(EXTRA_VARIANT_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        show("Starting $title…")
        worker.execute {
            runCatching { play(appId, variantId, title) }.onFailure {
                Log.w(TAG, "could not start: ${it.message}")
                show(it.message ?: "GeForce NOW could not start this game.")
            }
        }
    }

    private fun play(appId: String, variantId: String, title: String) {
        val engine = GfnStreamer(::onStreamerMessage)
        streamer = engine
        engine.send("hello", JSONObject().put("protocolVersion", 7))
        val caps = helloWait.get(10, java.util.concurrent.TimeUnit.SECONDS)
        capabilities = caps

        val inspect = GfnCore.request(this, "catalog.launch.inspect", JSONObject().put("appId", appId).put("variantId", variantId))
        val decision = inspect.optJSONObject("decision")
        if (decision?.optString("status") != "ready") {
            show(decision?.optString("message").orEmpty().ifEmpty { "This game can't be started with this account." })
            return
        }
        show("Requesting a GeForce NOW seat…")
        var current = GfnCore.request(this, "session.create", JSONObject()
            .put("appId", variantId)
            .put("variantId", variantId)
            .put("catalogAppId", appId)
            .put("scope", inspect.opt("scope"))
            .put("title", title)
            .put("runtimeCapabilities", caps), timeoutMs = 60_000)
        current = current.optJSONObject("session") ?: current
        session = current
        while (!closed && !ready(current)) {
            val position = current.optInt("queuePosition", 0)
            show(if (position > 0) "In the queue: position $position" else "Preparing your seat…")
            Thread.sleep(2_000)
            val polled = GfnCore.request(this, "session.poll", JSONObject()
                .put("sessionId", current.opt("sessionId"))
                .put("streamingBaseUrl", current.opt("streamingBaseUrl")), timeoutMs = 35_000)
            current = polled.optJSONObject("session") ?: polled
            session = current
            if (current.optString("phase") == "failed") {
                show("GeForce NOW could not prepare this session.")
                return
            }
        }
        if (closed) return
        show("Connecting…")
        val prepared = GfnCore.request(this, "streamer.prepare",
            JSONObject().put("session", current).put("runtimeCapabilities", caps))
        prepared.optJSONObject("session")?.let { session = it }
        val context = prepared.optJSONObject("context") ?: error("GeForce NOW returned no stream context")
        engine.send("start", JSONObject().put("context", context).put("microphoneEnabled", false))
    }

    private fun ready(session: JSONObject): Boolean {
        val phase = session.optString("phase")
        return phase == "ready" || phase == "streaming" || session.optInt("status", 0) >= 2
    }

    private fun onStreamerMessage(message: JSONObject) {
        when (message.optString("type")) {
            "ready" -> message.optJSONObject("capabilities")?.let { helloWait.complete(it) }
            "error" -> {
                Log.w(TAG, "streamer error ${message.optString("code")}: ${message.optString("message")}")
                show(message.optString("message").ifEmpty { "The stream stopped." })
            }
            "status" -> when (message.optString("event")) {
                "first-frame" -> runOnUiThread { status.visibility = android.view.View.GONE }
            }
            "stopped" -> show("The stream ended.")
        }
    }

    private fun show(text: String) = runOnUiThread {
        status.text = text
        status.visibility = android.view.View.VISIBLE
    }

    private fun confirmLeave() {
        AlertDialog.Builder(this)
            .setTitle("Leave the game?")
            .setMessage("This ends your GeForce NOW session.")
            .setPositiveButton("Leave") { _, _ -> finish() }
            .setNegativeButton("Keep playing", null)
            .show()
    }

    override fun onDestroy() {
        closed = true
        val engine = streamer
        val active = session
        streamer = null
        Thread({
            runCatching { engine?.send("stop") }
            if (active != null) {
                runCatching {
                    GfnCore.request(applicationContext, "session.stop", JSONObject()
                        .put("sessionId", active.opt("sessionId"))
                        .put("streamingBaseUrl", active.opt("streamingBaseUrl")))
                }.onFailure { Log.w(TAG, "session stop: ${it.message}") }
            }
            engine?.close()
        }, "gfn-leave").start()
        worker.shutdownNow()
        super.onDestroy()
    }

    // --- controllers: Android gamepads in XInput layout, the same mapping OpenNOW uses ---

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            val bit = buttonBit(event.keyCode)
            if (bit != 0) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) pads.press(event.deviceId, bit, true)
                else if (event.action == KeyEvent.ACTION_UP) pads.press(event.deviceId, bit, false)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            pads.move(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun buttonBit(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> 0x1000
        KeyEvent.KEYCODE_BUTTON_B -> 0x2000
        KeyEvent.KEYCODE_BUTTON_X -> 0x4000
        KeyEvent.KEYCODE_BUTTON_Y -> 0x8000
        KeyEvent.KEYCODE_BUTTON_SELECT -> 0x0020
        KeyEvent.KEYCODE_BUTTON_START -> 0x0010
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 0x0040
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 0x0080
        KeyEvent.KEYCODE_BUTTON_L1 -> 0x0100
        KeyEvent.KEYCODE_BUTTON_R1 -> 0x0200
        KeyEvent.KEYCODE_DPAD_UP -> 0x0001
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x0002
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x0004
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x0008
        else -> 0
    }

    private inner class Pads {
        private inner class Pad(val slot: Int) {
            var buttons = 0
            var hatButtons = 0
            var lt = 0
            var rt = 0
            var lx = 0
            var ly = 0
            var rx = 0
            var ry = 0
        }

        private val bySlot = arrayOfNulls<Pad>(4)
        private val byDevice = HashMap<Int, Pad>()

        @Synchronized
        private fun pad(deviceId: Int): Pad? = byDevice[deviceId] ?: run {
            val free = (0 until 4).firstOrNull { bySlot[it] == null } ?: return null
            Pad(free).also { bySlot[free] = it; byDevice[deviceId] = it }
        }

        private fun connected(): Int = (0 until 4).filter { bySlot[it] != null }.fold(0) { bits, slot -> bits or (1 shl slot) or (1 shl (slot + 8)) }

        fun press(deviceId: Int, bit: Int, down: Boolean) {
            val pad = pad(deviceId) ?: return
            pad.buttons = if (down) pad.buttons or bit else pad.buttons and bit.inv()
            send(pad)
        }

        fun move(event: MotionEvent) {
            val pad = pad(event.deviceId) ?: return
            val (lx, ly) = stick(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
            val rightX = event.getAxisValue(MotionEvent.AXIS_Z).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_RX)
            val rightY = event.getAxisValue(MotionEvent.AXIS_RZ).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_RY)
            val (rx, ry) = stick(rightX, rightY)
            pad.lx = lx; pad.ly = ly; pad.rx = rx; pad.ry = ry
            pad.lt = trigger(maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)))
            pad.rt = trigger(maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)))
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            pad.hatButtons = (if (hatY < -0.5f) 0x0001 else 0) or (if (hatY > 0.5f) 0x0002 else 0) or
                (if (hatX < -0.5f) 0x0004 else 0) or (if (hatX > 0.5f) 0x0008 else 0)
            send(pad)
        }

        /** Radial deadzone, Y flipped so up is positive (XInput). */
        private fun stick(x: Float, y: Float): Pair<Int, Int> {
            val magnitude = hypot(x, y)
            if (magnitude < DEADZONE) return 0 to 0
            val scaled = ((magnitude - DEADZONE) / (1f - DEADZONE)).coerceIn(0f, 1f) / magnitude
            return (x * scaled * 32767f).roundToInt() to (-y * scaled * 32767f).roundToInt()
        }

        private fun trigger(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()

        private fun send(pad: Pad) {
            streamer?.gamepad(pad.slot, connected(), pad.buttons or pad.hatButtons, pad.lt, pad.rt, pad.lx, pad.ly, pad.rx, pad.ry)
        }
    }
}
