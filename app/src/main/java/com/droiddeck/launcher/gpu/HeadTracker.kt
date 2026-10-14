package com.droiddeck.launcher.gpu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.droiddeck.launcher.wayland.WaylandCompositor

class HeadTracker(context: Context, private val rotation: () -> Int) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var thread: HandlerThread? = null
    @Volatile private var reference: HeadPose.Reference? = null
    @Volatile private var recenterPending = true
    @Volatile private var latest: FloatArray? = null

    val available: Boolean get() = sensor != null
    val sensorName: String get() = sensor?.let { "${it.name} (${if (it.type == Sensor.TYPE_GAME_ROTATION_VECTOR) "game rotation vector" else "rotation vector"})" } ?: "none"

    fun start() {
        val s = sensor ?: run {
            WaylandCompositor.nativeLog("cardboard", "no rotation sensor: the virtual screen stays head-locked")
            WaylandCompositor.nativeSetCardboardPose(HeadPose.IDENTITY, false)
            return
        }
        if (thread != null) return
        recenterPending = true
        val t = HandlerThread("droiddeck-headtracker").also { it.start() }
        thread = t
        sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, Handler(t.looper))
        WaylandCompositor.nativeLog("cardboard", "head tracking on: $sensorName")
    }

    fun stop() {
        val t = thread ?: return
        sensors.unregisterListener(this)
        t.quitSafely()
        thread = null
        WaylandCompositor.nativeSetCardboardPose(HeadPose.IDENTITY, false)
    }

    fun recenter() {
        recenterPending = true
        latest?.let { apply(it) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val q = HeadPose.quaternionFromRotationVector(event.values)
        val m = HeadPose.toScreen(HeadPose.fromQuaternion(q[0], q[1], q[2], q[3]), rotation())
        latest = m
        apply(m)
    }

    private fun apply(screenToWorld: FloatArray) {
        val ref = reference.takeUnless { recenterPending } ?: HeadPose.recenter(screenToWorld).also {
            reference = it
            recenterPending = false
        }
        WaylandCompositor.nativeSetCardboardPose(HeadPose.relative(ref, screenToWorld), true)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("DroidDeck", "head tracker accuracy $accuracy")
    }
}
