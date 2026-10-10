package com.droiddeck.launcher.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlin.math.roundToInt

/**
 * The handheld's own gyro and accelerometer, as the Steam Deck controller's (SteamDeckPad).
 *
 * The phone is the controller's body, so its motion sensors are the ones a Deck's would be. Android
 * reports them in the device's natural orientation; they are turned to the screen as it is shown
 * (x right, y up, z towards the player - SDL's frame for a gamepad), then to the Deck's own axes
 * and units as SDL's Deck driver undoes them (SDL_hidapi_steamdeck.c): X right, Y away from the
 * player, Z up; 1 g = 16384, 2000 °/s = 32768. The Steam client calibrates the gyro itself.
 *
 * Sensors run only while the session is on screen with the pad a Deck controller, sampled as often
 * as the Deck reports (4 ms); each reading goes straight into the pad's ring
 * ([FakeInputWriter.writeMotion]), where libfakeinput's 4 ms report picks up the latest.
 */
class PadMotion(private val context: Context, private val rotation: () -> Int) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    /** Readings are taken only while this is set; a callback already queued when [stop] ran drops. */
    @Volatile private var active = false
    // Touched only on the worker thread.
    private val accel = ShortArray(3)
    private val gyro = ShortArray(3)

    fun start() {
        if (thread != null || sensors == null) return
        val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (gyroscope == null && accelerometer == null) {
            Log.i(TAG, "pad motion: this device has no gyro or accelerometer")
            return
        }
        val worker = HandlerThread("pad-motion", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        thread = worker
        val handler = Handler(worker.looper)
        active = true
        gyroscope?.let { sensors.registerListener(this, it, SAMPLING_US, handler) }
        accelerometer?.let { sensors.registerListener(this, it, SAMPLING_US, handler) }
        Log.i(TAG, "pad motion: gyro ${gyroscope?.name ?: "none"}, accelerometer ${accelerometer?.name ?: "none"}")
    }

    fun stop() {
        val worker = thread ?: return
        thread = null
        active = false
        sensors?.unregisterListener(this)
        // A still controller, not one frozen mid-turn - published on the worker, after any reading
        // already queued there, so nothing can overwrite it.
        Handler(worker.looper).post {
            gyro.fill(0)
            FakeInputWriter.writeMotion(SLOT, accel, gyro)
            SteamTouchDevice.current?.setMotion(accel, gyro)
        }
        worker.quitSafely()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return
        val values = event.values
        // Natural orientation to the screen's: Android's display-rotation transform.
        val (x, y) = when (rotation()) {
            Surface.ROTATION_90 -> -values[1] to values[0]
            Surface.ROTATION_180 -> -values[0] to -values[1]
            Surface.ROTATION_270 -> values[1] to -values[0]
            else -> values[0] to values[1]
        }
        val z = values[2]
        val (target, scale) = when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> gyro to GYRO_COUNTS_PER_RAD_S
            Sensor.TYPE_ACCELEROMETER -> accel to ACCEL_COUNTS_PER_M_S2
            else -> return
        }
        // Screen frame (right, up, towards the player) to the Deck's (right, away, up).
        target[0] = counts(x * scale)
        target[1] = counts(-z * scale)
        target[2] = counts(y * scale)
        FakeInputWriter.writeMotion(SLOT, accel, gyro)
        SteamTouchDevice.current?.setMotion(accel, gyro)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun counts(value: Float): Short = value.roundToInt().coerceIn(-32768, 32767).toShort()

    companion object {
        private const val TAG = "PadMotion"
        /** The pad the Deck controller is made from (PadBridge's slot). */
        private const val SLOT = 0
        /** The Deck controller's report interval (libfakeinput: DECK_REPORT_INTERVAL_US). */
        private const val SAMPLING_US = 4000
        private const val ACCEL_COUNTS_PER_M_S2 = 16384f / SensorManager.GRAVITY_EARTH
        private const val GYRO_COUNTS_PER_RAD_S = (32768f / 2000f) * (180f / Math.PI.toFloat())
    }
}
