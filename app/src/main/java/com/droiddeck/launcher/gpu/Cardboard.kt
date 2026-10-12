package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.wayland.WaylandCompositor
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Cardboard mode: the compositor draws the session as two lens-distorted eye views side by side
 * (waylandcomp/src/cardboard.c), for a phone or handheld dropped into a Google Cardboard-style
 * viewer. Units are the menu's own:
 *
 *  - [ipdMm]              distance between the eyes, millimetres
 *  - [screenWidthDm]      the virtual screen's width, decimetres (40 = 4 m)
 *  - [screenDistanceDm]   how far away it hangs, decimetres (30 = 3 m)
 *  - [viewer]             a [Viewer] id: the lens it is drawn for
 *
 * [source] SCREEN shows the frame as a flat virtual screen, world-locked by the rotation sensor
 * when [headTracking] is on (3DoF), with real stereo depth from the IPD. SBS takes a frame that is
 * already a left/right pair (SteamVR, SBS 3D games and video) and only adds the lens.
 */
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

    /**
     * Hands the settings to the compositor. [panelWidthMm] is the physical width of the panel the
     * eyes are drawn on (0 = unknown): it decides how far each lens centre sits from its viewport's
     * centre, so the two pictures line up with the viewer's lenses.
     */
    fun push(panelWidthMm: Float) {
        val l = lens
        WaylandCompositor.nativeSetCardboard(enabled, source, ipdMm / 1000f, screenDistanceDm / 10f,
            screenWidthDm / 10f, l.k1, l.k2, l.fovDeg, lensInset(panelWidthMm, l.lensSeparationMm), l.chroma)
    }

    fun encode(): JSONObject = JSONObject()
        .put("enabled", enabled).put("source", source).put("viewer", viewer).put("ipdMm", ipdMm)
        .put("screenWidthDm", screenWidthDm).put("screenDistanceDm", screenDistanceDm).put("headTracking", headTracking)

    /** A lens the eyes can be drawn for: distortion, field of view and how far apart the lenses are. */
    enum class Viewer(val id: String, val k1: Float, val k2: Float, val fovDeg: Float, val lensSeparationMm: Float, val chroma: Float) {
        /** Google Cardboard (2015): the common plastic and cardboard viewers. */
        CARDBOARD_V2("cardboard2", 0.34f, 0.55f, 100f, 63.9f, 0.006f),
        /** The original 2014 Cardboard: narrower, gentler lenses. */
        CARDBOARD_V1("cardboard1", 0.441f, 0.156f, 80f, 60f, 0.004f),
        /** No lens: side-by-side 3D glasses, a 3D TV, or just looking at the stereo pair. */
        NONE("none", 0f, 0f, 60f, 0f, 0f);

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

        /**
         * The lens centre's inset towards the nose, as a fraction of one eye viewport's width. Each
         * eye gets half the panel, so its viewport centre is a quarter of the panel from the middle;
         * the lens centre is half the lens separation from it. 0 when either is unknown.
         */
        fun lensInset(panelWidthMm: Float, lensSeparationMm: Float): Float {
            if (panelWidthMm <= 0f || lensSeparationMm <= 0f) return 0f
            val inset = (panelWidthMm / 4f - lensSeparationMm / 2f) / (panelWidthMm / 2f)
            return inset.coerceIn(-0.25f, 0.25f)
        }
    }
}

/**
 * Head pose maths for Cardboard mode, kept free of Android types so it is unit-tested on the JVM.
 * Matrices are row-major 3x3 FloatArrays. "World" is Android's sensor world (x east, y north,
 * z up); "screen" axes are x right, y up, z out of the panel towards the viewer's eyes.
 */
object HeadPose {
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** Unit quaternion (x, y, z, w) -> rotation matrix: what SensorManager.getRotationMatrixFromVector gives. */
    fun fromQuaternion(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val n = sqrt(x * x + y * y + z * z + w * w).takeIf { it > 1e-6f } ?: 1f
        val qx = x / n; val qy = y / n; val qz = z / n; val qw = w / n
        return floatArrayOf(
            1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
            2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
            2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy),
        )
    }

    /** The rotation vector sensor's values -> (x, y, z, w), computing w when the sensor leaves it out. */
    fun quaternionFromRotationVector(v: FloatArray): FloatArray {
        val x = v[0]; val y = v[1]; val z = v[2]
        val w = if (v.size >= 4) v[3] else sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f))
        return floatArrayOf(x, y, z, w)
    }

    /**
     * Device -> screen axes for a Display rotation (Surface.ROTATION_* as 0..3), the same remap
     * SensorManager.remapCoordinateSystem does for each rotation: the returned matrix maps screen
     * to world, given the device's own device -> world matrix.
     */
    fun toScreen(deviceToWorld: FloatArray, rotation: Int): FloatArray {
        // Columns of A: where each screen axis points in device coordinates.
        val a = when (rotation and 3) {
            1 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)   // x = device y, y = -device x
            2 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)  // x = -device x, y = -device y
            3 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)   // x = -device y, y = device x
            else -> IDENTITY
        }
        return multiply(deviceToWorld, a)
    }

    /**
     * The yaw-only frame a recentre locks to: facing where the viewer looks now, upright. Columns
     * are its right, up and back axes in world coordinates. Pitch and roll stay absolute (gravity
     * keeps the horizon level); only the heading is reset.
     */
    fun recenterBasis(screenToWorld: FloatArray): FloatArray {
        // Forward = the screen's -z in world.
        var fx = -screenToWorld[2]; var fy = -screenToWorld[5]
        var len = sqrt(fx * fx + fy * fy)
        if (len < 1e-3f) {
            // Looking straight up or down: take the heading from the top of the screen instead.
            fx = screenToWorld[1] * (if (screenToWorld[8] > 0f) 1f else -1f)
            fy = screenToWorld[4] * (if (screenToWorld[8] > 0f) 1f else -1f)
            len = sqrt(fx * fx + fy * fy).takeIf { it > 1e-6f } ?: 1f
        }
        fx /= len; fy /= len
        // right = forward x up(0,0,1) = (fy, -fx, 0); up = (0,0,1); back = -forward.
        return floatArrayOf(
            fy, 0f, -fx,
            -fx, 0f, -fy,
            0f, 1f, 0f,
        )
    }

    /** head -> recentred world: what the compositor takes (basis^T * screenToWorld). */
    fun relative(basis: FloatArray, screenToWorld: FloatArray): FloatArray = multiply(transpose(basis), screenToWorld)

    fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun transpose(m: FloatArray): FloatArray = floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

    /** m * v for a 3-vector. */
    fun apply(m: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )
}
