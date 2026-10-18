package com.droiddeck.launcher.input

import android.content.Context
import android.hardware.BatteryState
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.CombinedVibration
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.VibrationEffect
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import java.io.File
import kotlin.math.min
import kotlin.math.sqrt

@RequiresApi(31)
class JoyConVr(
    private val context: Context,
    private val path: File,
    private val onDevicesChanged: (taken: List<Int>, released: List<Int>) -> Unit,
) {
    private val thread = HandlerThread("joycon-vr", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var owned = IntArray(0)
    private var wanted = false
    private var closed = false
    private var retries = 0
    private var shm: JoyConShm? = null
    private val hands = arrayOfNulls<Hand>(2)
    private val retry = Runnable { apply() }

    fun setActive(on: Boolean) {
        worker.post {
            if (closed || wanted == on) return@post
            wanted = on
            retries = 0
            apply()
        }
    }

    fun refresh() {
        worker.post {
            if (closed) return@post
            retries = 0
            apply()
        }
    }

    fun owns(deviceId: Int): Boolean = owned.contains(deviceId)

    val holding: Boolean get() = owned.isNotEmpty()

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!owns(event.deviceId)) return false
        if (event.repeatCount > 0) return true
        val down = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return true
        }
        val id = event.deviceId
        val scanCode = event.scanCode
        worker.post { hand(id)?.key(scanCode, down) }
        return true
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!owns(event.deviceId)) return false
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return true
        val device = event.device ?: return true
        val side = JoyCon.side(device)
        if (side < 0) return true
        val axes = JoyCon.stickAxes(side, device, event.source)
        val x = event.getAxisValue(axes[0])
        val y = event.getAxisValue(axes[1])
        val id = event.deviceId
        worker.post { hand(id)?.stick(x, -y) }
        return true
    }

    fun releaseButtons() {
        worker.post { hands.forEach { it?.release() } }
    }

    fun recenter() {
        worker.post { hands.forEach { it?.recenter() } }
    }

    fun close() {
        worker.post {
            closed = true
            wanted = false
            apply()
            shm?.close()
            shm = null
        }
        thread.quitSafely()
    }

    private fun hand(id: Int): Hand? = hands.firstOrNull { it?.device?.id == id }

    private fun apply() {
        worker.removeCallbacks(retry)
        val found = arrayOfNulls<InputDevice>(2)
        var waiting = false
        if (wanted && !closed) {
            for (id in InputDevice.getDeviceIds()) {
                val device = InputDevice.getDevice(id) ?: continue
                val side = JoyCon.side(device)
                if (side < 0 || found[side] != null) continue
                if (hasMotion(device)) found[side] = device else waiting = true
            }
        }
        val before = owned
        for (side in 0..1) {
            val current = hands[side]
            val device = found[side]
            if (current != null && current.device.id == device?.id) continue
            current?.stop()
            hands[side] = null
            if (device != null) {
                hands[side] = start(side, device)
                if (hands[side] == null) waiting = true
            }
        }
        val now = hands.mapNotNull { it?.device?.id }.toIntArray()
        owned = now
        if (closed) JoyCon.clearVrDevices(this)
        else JoyCon.setVrDevices(this, IntArray(2) { hands[it]?.device?.id ?: PadBridge.NO_CONTROLLER })
        if (!closed) {
            val taken = now.filter { it !in before }
            val released = before.filter { it !in now }
            if (taken.isNotEmpty() || released.isNotEmpty()) main.post { onDevicesChanged(taken, released) }
        }
        if (wanted && waiting && retries < MAX_RETRIES) {
            retries++
            worker.postDelayed(retry, RETRY_MS)
        }
    }

    private fun hasMotion(device: InputDevice): Boolean =
        runCatching {
            val sensors = device.sensorManager
            sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null && sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        }.getOrDefault(false)

    private fun start(side: Int, device: InputDevice): Hand? {
        val out = shm ?: JoyConShm.open(path)?.also { shm = it } ?: return null
        val sensors = runCatching { device.sensorManager }.getOrNull() ?: return null
        val gyro = runCatching { sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }.getOrNull() ?: return null
        val accel = runCatching { sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }.getOrNull() ?: return null
        val hand = Hand(side, device, sensors, out)
        val listening = runCatching {
            sensors.registerListener(hand, accel, SensorManager.SENSOR_DELAY_FASTEST, worker) &&
                sensors.registerListener(hand, gyro, SensorManager.SENSOR_DELAY_FASTEST, worker)
        }.onFailure { Log.w(TAG, "Joy-Con motion unavailable for \"${device.name}\"", it) }.getOrDefault(false)
        if (!listening) {
            runCatching { sensors.unregisterListener(hand) }
            return null
        }
        Log.i(TAG, "${SIDES[side]} Joy-Con \"${device.name}\" (id ${device.id}) is a VR controller")
        return hand
    }

    private inner class Hand(
        val side: Int,
        val device: InputDevice,
        private val sensors: SensorManager,
        private val out: JoyConShm,
    ) : SensorEventListener {
        private val filter = JoyConOrientation()
        private val motion = JoyConMotion()
        private val calibration = JoyConAccelCalibration().apply { restore(JoyConCalibrations.load(context, device.descriptor)) }
        private val reports = JoyConReports()
        private val accel = FloatArray(3)
        private var accelStamp = 0L
        private var haveAccel = false
        private val pressed = HashSet<Int>()
        private var stickX = 0f
        private var stickY = 0f
        private var systemDown = false
        private var systemTap = false
        private var held = false
        private var recenter = 0
        private var battery = -1f
        private var charging = false
        private var batteryAt = 0L
        private var sampleAt = 0L
        private var live = true
        private val hold = Runnable {
            if (!systemDown || held) return@Runnable
            held = true
            recenter()
        }
        private val tapEnd = Runnable {
            systemTap = false
            publish()
        }
        private val flush = Runnable { flushReport() }

        override fun onSensorChanged(event: SensorEvent) {
            if (!live) return
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    if (event.timestamp == accelStamp) return
                    accelStamp = event.timestamp
                    event.values.copyInto(accel, 0, 0, 3)
                    haveAccel = true
                }
                Sensor.TYPE_GYROSCOPE -> {
                    if (!haveAccel) return
                    reports.add(accel, event.values, System.nanoTime())
                    worker.removeCallbacks(flush)
                    if (reports.size >= JoyConReports.SAMPLES_PER_REPORT) flushReport() else worker.postDelayed(flush, FLUSH_MS)
                }
            }
        }

        private fun flushReport() {
            val samples = reports.flush()
            if (samples.isEmpty() || !live) return
            for (sample in samples) {
                val raw = JoyConOrientation.axes(sample.accel)
                val corrected = calibration.correct(raw)
                filter.update(corrected, JoyConOrientation.axes(sample.gyro), sample.dt)
                if (!filter.ready) continue
                motion.update(filter.rotation, corrected, filter.rate, calibration.ready, sample.dt)
                if (motion.resting && calibration.addResting(raw)) JoyConCalibrations.save(context, device.descriptor, calibration.saved())
            }
            val now = System.nanoTime()
            sampleAt = now
            if (now - batteryAt > BATTERY_NS) refreshBattery(now)
            publish()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        fun key(scanCode: Int, down: Boolean) {
            if (!live) return
            val bit = JoyCon.vrButton(side, scanCode)
            if (bit == 0) return
            if (bit == JoyCon.VR_SYSTEM) {
                system(down)
                return
            }
            if (down) pressed += scanCode else pressed -= scanCode
            publish()
        }

        fun stick(x: Float, y: Float) {
            if (!live) return
            val length = sqrt(x * x + y * y)
            if (length < STICK_DEADZONE) {
                stickX = 0f
                stickY = 0f
            } else {
                val scale = min((length - STICK_DEADZONE) / (1f - STICK_DEADZONE), 1f) / length
                stickX = x * scale
                stickY = y * scale
            }
            publish()
        }

        fun release() {
            if (!live) return
            pressed.clear()
            stickX = 0f
            stickY = 0f
            systemDown = false
            systemTap = false
            held = false
            worker.removeCallbacks(hold)
            worker.removeCallbacks(tapEnd)
            publish()
        }

        fun recenter() {
            if (!live) return
            recenter++
            confirm()
            publish()
        }

        fun stop() {
            live = false
            runCatching { sensors.unregisterListener(this) }
            worker.removeCallbacks(hold)
            worker.removeCallbacks(tapEnd)
            worker.removeCallbacks(flush)
            out.write(side, JoyConShm.Hand(connected = false))
            Log.i(TAG, "${SIDES[side]} Joy-Con \"${device.name}\" (id ${device.id}) is a game pad again")
        }

        private fun system(down: Boolean) {
            if (down == systemDown) return
            systemDown = down
            if (down) {
                held = false
                worker.postDelayed(hold, HOLD_MS)
                return
            }
            worker.removeCallbacks(hold)
            if (held) return
            systemTap = true
            publish()
            worker.removeCallbacks(tapEnd)
            worker.postDelayed(tapEnd, TAP_MS)
        }

        private fun buttons(): Int {
            var bits = if (systemTap) JoyCon.VR_SYSTEM else 0
            for (scanCode in pressed) bits = bits or JoyCon.vrButton(side, scanCode)
            return bits
        }

        private fun publish() {
            out.write(side, JoyConShm.Hand(
                connected = filter.ready,
                charging = charging,
                buttons = buttons(),
                rotation = filter.rotation,
                rate = filter.rate,
                displacement = motion.displacement,
                velocity = motion.velocity,
                inertial = motion.inertial,
                stickX = stickX,
                stickY = stickY,
                battery = battery,
                recenter = recenter,
                timeNs = sampleAt,
            ))
        }

        private fun refreshBattery(now: Long) {
            batteryAt = now
            val state = runCatching { device.batteryState }.getOrNull() ?: return
            battery = if (state.isPresent && !state.capacity.isNaN()) state.capacity else -1f
            charging = state.isPresent && state.status == BatteryState.STATUS_CHARGING
        }

        private fun confirm() {
            if (!ControllerPrefs.rumbleEnabled(context)) return
            runCatching {
                device.vibratorManager.vibrate(CombinedVibration.createParallel(
                    VibrationEffect.createOneShot(CONFIRM_MS, VibrationEffect.DEFAULT_AMPLITUDE)))
            }
        }
    }

    companion object {
        const val FILE = JoyConShm.FILE
        private const val TAG = "JoyConVr"
        private val SIDES = arrayOf("left", "right")
        private const val MAX_RETRIES = 6
        private const val RETRY_MS = 500L
        private const val HOLD_MS = 800L
        private const val TAP_MS = 120L
        private const val CONFIRM_MS = 80L
        private const val BATTERY_NS = 30_000_000_000L
        private const val STICK_DEADZONE = 0.12f
        private const val FLUSH_MS = 3L
    }
}
