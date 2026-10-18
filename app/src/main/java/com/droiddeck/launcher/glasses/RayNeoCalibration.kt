package com.droiddeck.launcher.glasses

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class CalibrationPhase { IDLE, SETTLING, COLLECTING, MOVING, TURN_OVER, SAVED, FAILED }

data class GlassesCalibrationState(
    val supported: Boolean = false,
    val saved: Boolean = false,
    val phase: CalibrationPhase = CalibrationPhase.IDLE,
    val progress: Int = 0,
) {
    val running: Boolean get() = phase in setOf(CalibrationPhase.SETTLING, CalibrationPhase.COLLECTING,
        CalibrationPhase.MOVING, CalibrationPhase.TURN_OVER)
}

internal data class RayNeoCalibration(val gyroBiasDps: Vec3, val level: Quaternion) {
    fun encode(): String = JSONObject().put("version", 1)
        .put("bias", JSONArray(listOf(gyroBiasDps.x, gyroBiasDps.y, gyroBiasDps.z)))
        .put("level", JSONArray(listOf(level.w, level.x, level.y, level.z))).toString()

    companion object {
        fun decode(json: String?): RayNeoCalibration? = runCatching {
            val obj = JSONObject(json ?: return null)
            require(obj.getInt("version") == 1)
            val b = obj.getJSONArray("bias"); val q = obj.getJSONArray("level")
            require(b.length() == 3 && q.length() == 4)
            val bias = Vec3(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat())
            val level = Quaternion(q.getDouble(0).toFloat(), q.getDouble(1).toFloat(), q.getDouble(2).toFloat(), q.getDouble(3).toFloat())
            require(listOf(bias.x, bias.y, bias.z, level.w, level.x, level.y, level.z).all { it.isFinite() })
            require(bias.length() <= 6f && level.w in 0.96f..1.001f)
            val norm = level.w * level.w + level.x * level.x + level.y * level.y + level.z * level.z
            require(norm in 0.99f..1.01f)
            RayNeoCalibration(bias, level.normalized())
        }.getOrNull()
    }
}

internal class RayNeoCalibrationStore(private val prefs: SharedPreferences) {
    companion object {
        // GT and GT Max can share USB identity fields. Keep the non-Max mounting
        // correction separate from existing Max profiles and their legacy keys.
        fun profileKey(base: String, gtUsb: Boolean, boardId: Int): String =
            if (gtUsb && boardId == RayNeoProtocol.BOARD_GT) "$base:gt-standard-v1" else base
    }
    // A serial isolates physical units. Devices without one share their model profile.
    fun key(vid: Int, pid: Int, serial: String?, modelName: String = ""): String =
        "rayneo-cal-v1:$vid:$pid:${serial?.takeIf { it.isNotBlank() } ?: "model:$modelName"}"
    fun load(key: String): RayNeoCalibration? = RayNeoCalibration.decode(prefs.getString(key, null))
    fun save(key: String, value: RayNeoCalibration?) {
        if (value == null) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, value.encode()).apply()
    }
}

/** One upside-down stationary pose estimates gyro bias and tilt, never yaw or accelerometer scale. */
internal class RayNeoCalibrationCollector(private val axes: RayNeoSensorAxes, private val beganMs: Long) {
    var phase = CalibrationPhase.SETTLING; private set
    var progress = 0; private set
    private var windowStart = -1L
    private var lastMs = -1L
    private var count = 0
    private var meanA = Vec3(0f, 0f, 0f)
    private var meanG = Vec3(0f, 0f, 0f)
    private var aVariance = 0f
    private var gVariance = 0f

    private fun clear(next: CalibrationPhase) {
        phase = next; progress = 0; windowStart = -1; count = 0
        meanA = Vec3(0f, 0f, 0f); meanG = meanA; aVariance = 0f; gVariance = 0f
    }

    fun update(sample: RayNeoSensorSample, nowMs: Long): RayNeoCalibration? {
        if (phase == CalibrationPhase.FAILED || phase == CalibrationPhase.SAVED) return null
        if (nowMs - beganMs >= 30_000) { clear(CalibrationPhase.FAILED); return null }
        val gap = lastMs >= 0 && nowMs - lastMs > 250
        lastMs = nowMs
        if (nowMs - beganMs < 3000) return null
        val a = axes.map(Vec3(sample.accelMps2[0], sample.accelMps2[1], sample.accelMps2[2]))
        val g = Vec3(sample.gyroDps[0], sample.gyroDps[1], sample.gyroDps[2])
        if (a.length() !in 8.5f..11f || !g.length().isFinite() || g.length() > 6f || gap) {
            clear(CalibrationPhase.MOVING); return null
        }
        if (a.normalized().y > -0.94f) { clear(CalibrationPhase.TURN_OVER); return null }
        if (windowStart < 0) windowStart = nowMs
        phase = CalibrationPhase.COLLECTING
        count++
        val da = a - meanA; val dg = g - meanG
        meanA += da * (1f / count); meanG += dg * (1f / count)
        fun dot(x: Vec3, y: Vec3) = x.x * y.x + x.y * y.y + x.z * y.z
        aVariance += dot(da, a - meanA); gVariance += dot(dg, g - meanG)
        if (count > 25 && (aVariance / count > 0.04f || gVariance / count > 2.25f)) {
            clear(CalibrationPhase.MOVING); return null
        }
        progress = ((nowMs - windowStart) * 100 / 5000).toInt().coerceIn(0, 99)
        if (nowMs - windowStart < 5000 || count < 200) return null
        val down = meanA.normalized()
        val axis = down.cross(Vec3(0f, -1f, 0f))
        val result = RayNeoCalibration(meanG, Quaternion(1f - down.y, axis.x, axis.y, axis.z).normalized())
        phase = CalibrationPhase.SAVED; progress = 100
        return result
    }
}
