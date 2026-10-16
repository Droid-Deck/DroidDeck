package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.wayland.WaylandCompositor
import org.json.JSONObject
import kotlin.math.sqrt

data class Cardboard(
    val enabled: Boolean = false,
    val source: Int = SOURCE_SCREEN,
    val viewer: String = Viewer.CARDBOARD_V2.id,
    val ipdMm: Int = 64,
    val screenWidthDm: Int = 40,
    val screenDistanceDm: Int = 30,
    val headTracking: Boolean = true,
) {
    val lens: Viewer get() = Viewer.byId(viewer)

    fun push(panelWidthMm: Float, panelHeightMm: Float) {
        val l = lens
        WaylandCompositor.nativeSetCardboard(enabled, source, ipdMm / 1000f, screenDistanceDm / 10f,
            screenWidthDm / 10f, l.k1, l.k2, fovDeg(l, panelHeightMm), lensInset(panelWidthMm, l.lensSeparationMm), l.chroma)
    }

    fun encode(): JSONObject = JSONObject()
        .put("enabled", enabled).put("source", source).put("viewer", viewer).put("ipdMm", ipdMm)
        .put("screenWidthDm", screenWidthDm).put("screenDistanceDm", screenDistanceDm).put("headTracking", headTracking)

    enum class Viewer(val id: String, val k1: Float, val k2: Float, val screenToLensMm: Float,
                      val lensSeparationMm: Float, val chroma: Float, val fallbackFovDeg: Float) {
        CARDBOARD_V2("cardboard2", 0.34f, 0.55f, 39.3f, 63.9f, 0.006f, 90f),
        CARDBOARD_V1("cardboard1", 0.441f, 0.156f, 42f, 60f, 0.004f, 80f),
        NONE("none", 0f, 0f, 0f, 0f, 0f, 60f);

        companion object {
            fun byId(id: String): Viewer = entries.firstOrNull { it.id == id } ?: CARDBOARD_V2
        }
    }

    companion object {
        const val SOURCE_SCREEN = 0
        const val SOURCE_SBS = 1

        val OFF = Cardboard()

        fun decode(json: JSONObject): Cardboard = Cardboard(
            enabled = json.optBoolean("enabled", false),
            source = if (json.optInt("source", SOURCE_SCREEN) == SOURCE_SBS) SOURCE_SBS else SOURCE_SCREEN,
            viewer = Viewer.byId(json.optString("viewer", Viewer.CARDBOARD_V2.id)).id,
            ipdMm = json.optInt("ipdMm", 64).coerceIn(50, 80),
            screenWidthDm = json.optInt("screenWidthDm", 40).coerceIn(5, 200),
            screenDistanceDm = json.optInt("screenDistanceDm", 30).coerceIn(5, 200),
            headTracking = json.optBoolean("headTracking", true),
        )

        fun fovDeg(viewer: Viewer, panelHeightMm: Float): Float {
            if (panelHeightMm <= 0f || viewer.screenToLensMm <= 0f) return viewer.fallbackFovDeg
            val half = Math.toDegrees(kotlin.math.atan((panelHeightMm / 2f / viewer.screenToLensMm).toDouble())).toFloat()
            return (2f * half).coerceIn(30f, 140f)
        }

        fun lensInset(panelWidthMm: Float, lensSeparationMm: Float): Float {
            if (panelWidthMm <= 0f || lensSeparationMm <= 0f) return 0f
            val inset = (panelWidthMm / 4f - lensSeparationMm / 2f) / (panelWidthMm / 2f)
            return inset.coerceIn(-0.25f, 0.25f)
        }
    }
}

object HeadPose {
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    fun fromQuaternion(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val n = sqrt(x * x + y * y + z * z + w * w).takeIf { it > 1e-6f } ?: 1f
        val qx = x / n; val qy = y / n; val qz = z / n; val qw = w / n
        return floatArrayOf(
            1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
            2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
            2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy),
        )
    }

    fun quaternionFromRotationVector(v: FloatArray): FloatArray {
        val x = v[0]; val y = v[1]; val z = v[2]
        val w = if (v.size >= 4) v[3] else sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f))
        return floatArrayOf(x, y, z, w)
    }

    fun toScreen(deviceToWorld: FloatArray, rotation: Int): FloatArray {
        val a = when (rotation and 3) {
            1 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
            2 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
            3 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            else -> IDENTITY
        }
        return multiply(deviceToWorld, a)
    }

    private const val FLAT_SIN = 0.866f

    class Reference(val basis: FloatArray, val tilt: FloatArray)

    fun recenter(screenToWorld: FloatArray): Reference {
        val fz = -screenToWorld[8]
        val flat = kotlin.math.abs(fz) > FLAT_SIN
        var fx = if (flat) screenToWorld[1] else -screenToWorld[2]
        var fy = if (flat) screenToWorld[4] else -screenToWorld[5]
        val len = sqrt(fx * fx + fy * fy).takeIf { it > 1e-4f } ?: run { fx = 0f; fy = 1f; 1f }
        fx /= len; fy /= len
        val basis = floatArrayOf(
            fy, 0f, -fx,
            -fx, 0f, -fy,
            0f, 1f, 0f,
        )
        val tilt = if (flat) multiply(transpose(screenToWorld), basis) else IDENTITY
        return Reference(basis, tilt)
    }

    fun relative(ref: Reference, screenToWorld: FloatArray): FloatArray =
        multiply(multiply(transpose(ref.basis), screenToWorld), ref.tilt)

    fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun transpose(m: FloatArray): FloatArray = floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

    fun apply(m: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )
}
