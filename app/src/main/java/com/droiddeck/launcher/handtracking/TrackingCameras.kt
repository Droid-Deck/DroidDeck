package com.droiddeck.launcher.handtracking

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.droiddeck.launcher.glasses.Vec3
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

internal class TrackingCamera(
    val id: String,
    val front: Boolean,
    val orientation: Int,
    val focalPixels: Float,
    val activeWidth: Int,
    val activeHeight: Int,
    val minZoom: Float,
    val realtime: Boolean,
    val fps: Pair<Int, Int>? = null,
) {
    val fovDegrees: Float get() = Math.toDegrees(2.0 * atan(activeWidth / 2.0 / focalPixels)).toFloat()

    fun zoom(): Float {
        if (minZoom >= 1f) return 1f
        val needed = (tan(Math.toRadians(fovDegrees / 2.0)) / tan(Math.toRadians(MAX_FOV / 2.0))).toFloat()
        return min(1f, max(minZoom, needed))
    }

    fun intrinsics(bufferWidth: Int, bufferHeight: Int, zoom: Float, turns: Int): Intrinsics {
        val aspect = bufferWidth.toFloat() / bufferHeight
        val region = min(activeWidth.toFloat(), activeHeight * aspect) / zoom
        val f = focalPixels * bufferWidth / region
        val quarter = turns and 1 == 1
        val w = if (quarter) bufferHeight else bufferWidth
        val h = if (quarter) bufferWidth else bufferHeight
        return Intrinsics(f, f, w / 2f, h / 2f, w, h)
    }

    companion object {
        const val MAX_FOV = 100.0
    }
}

internal object TrackingCameras {
    const val REAR = "rear"
    const val FRONT = "front"
    private const val MAX_FPS = 30

    fun list(context: Context): List<TrackingCamera> {
        val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        return ids.mapNotNull { id -> runCatching { describe(id, manager.getCameraCharacteristics(id)) }.getOrNull() }
    }

    private fun describe(id: String, c: CameraCharacteristics): TrackingCamera? {
        val facing = c.get(CameraCharacteristics.LENS_FACING) ?: return null
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return null
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) return null
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val calibrated = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.firstOrNull()?.takeIf { it > 1f }
        val focalPixels = calibrated ?: (focal * pixels.width / physical.width)
        val minZoom = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f else 1f
        val realtime = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val fps = steadyFps(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.lower to it.upper }.orEmpty())
        return TrackingCamera(id, facing == CameraCharacteristics.LENS_FACING_FRONT, c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            focalPixels, active.width(), active.height(), minZoom, realtime, fps)
    }

    fun steadyFps(ranges: List<Pair<Int, Int>>): Pair<Int, Int>? =
        ranges.filter { it.second <= MAX_FPS }.maxWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenByDescending { it.second })

    fun sides(cameras: List<TrackingCamera>): Set<String> = cameras.mapTo(LinkedHashSet()) { if (it.front) FRONT else REAR }

    fun pick(cameras: List<TrackingCamera>, side: String?): TrackingCamera? {
        val wanted = cameras.filter { side == null || (it.front == (side == FRONT)) }.ifEmpty { cameras }
        val preferred = if (side == null) wanted.filter { !it.front }.ifEmpty { wanted } else wanted
        return preferred.maxWithOrNull(compareBy<TrackingCamera> { min(widest(it), TrackingCamera.MAX_FOV.toFloat()) }
            .thenBy { it.activeWidth.toLong() * it.activeHeight })
    }

    private fun widest(c: TrackingCamera): Float =
        Math.toDegrees(2.0 * atan(tan(Math.toRadians(c.fovDegrees / 2.0)) / c.zoom())).toFloat()
}

internal object CameraGeometry {
    fun axes(front: Boolean, orientation: Int, turns: Int): Pair<Vec3, Vec3> {
        val t = Math.toRadians(orientation.toDouble())
        val c = Math.cos(t).toFloat()
        val s = Math.sin(t).toFloat()
        var u = if (front) Vec3(-c, -s, 0f) else Vec3(c, -s, 0f)
        var v = if (front) Vec3(s, -c, 0f) else Vec3(-s, -c, 0f)
        repeat(Math.floorMod(turns, 4)) {
            val nu = v * -1f
            v = u
            u = nu
        }
        return u to v
    }

    fun upInCamera(front: Boolean, orientation: Int, turns: Int, up: Vec3): Vec3 {
        val (u, v) = axes(front, orientation, turns)
        val n = up.normalized()
        return Vec3(dot(n, u), dot(n, v), dot(n, u.cross(v)))
    }

    fun uprightTurns(front: Boolean, orientation: Int, up: Vec3): Int =
        (0..3).minBy { turns -> dot(up.normalized(), axes(front, orientation, turns).second) }

    fun placement(front: Boolean, up: Vec3): Placement {
        val n = up.normalized()
        if (n.length() < 0.5f) return Placement.UNKNOWN
        if (n.z > FLAT) return Placement.FACE_UP
        if (n.z < -FLAT) return Placement.FACE_DOWN
        val elevation = Math.toDegrees(asin((if (front) n.z else -n.z).toDouble().coerceIn(-1.0, 1.0)))
        return when {
            elevation > STEEP -> Placement.POINTS_UP
            elevation < -STEEP -> Placement.POINTS_DOWN
            else -> Placement.STANDING
        }
    }

    private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z

    private const val FLAT = 0.9f
    private const val STEEP = 45.0
}

internal enum class Placement { UNKNOWN, STANDING, FACE_UP, FACE_DOWN, POINTS_UP, POINTS_DOWN }
