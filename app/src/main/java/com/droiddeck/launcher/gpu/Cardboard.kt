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
     * Hands the settings to the compositor. [panelWidthMm] x [panelHeightMm] is the physical size of
     * the panel the eyes are drawn on (0 = unknown). The width decides how far each lens centre sits
     * from its viewport's centre, so the two pictures line up with the viewer's lenses; the height,
     * seen through the lens from its focal distance, is the field of view the distortion works in.
     */
    fun push(panelWidthMm: Float, panelHeightMm: Float) {
        val l = lens
        WaylandCompositor.nativeSetCardboard(enabled, source, ipdMm / 1000f, screenDistanceDm / 10f,
            screenWidthDm / 10f, l.k1, l.k2, fovDeg(l, panelHeightMm), lensInset(panelWidthMm, l.lensSeparationMm), l.chroma)
    }

    fun encode(): JSONObject = JSONObject()
        .put("enabled", enabled).put("source", source).put("viewer", viewer).put("ipdMm", ipdMm)
        .put("screenWidthDm", screenWidthDm).put("screenDistanceDm", screenDistanceDm).put("headTracking", headTracking)

    /**
     * A lens the eyes can be drawn for: its distortion (Cardboard viewer profile coefficients, in
     * tangent units at the panel), how far it sits from the panel, and how far apart the two are.
     * [fallbackFovDeg] is the eye's field of view when the panel's size is unknown, or with no lens.
     */
    enum class Viewer(val id: String, val k1: Float, val k2: Float, val screenToLensMm: Float,
                      val lensSeparationMm: Float, val chroma: Float, val fallbackFovDeg: Float) {
        /** Google Cardboard (2015): the common plastic and cardboard viewers. */
        CARDBOARD_V2("cardboard2", 0.34f, 0.55f, 39.3f, 63.9f, 0.006f, 90f),
        /** The original 2014 Cardboard: narrower, gentler lenses. */
        CARDBOARD_V1("cardboard1", 0.441f, 0.156f, 42f, 60f, 0.004f, 80f),
        /** No lens: side-by-side 3D glasses, a 3D TV, or just looking at the stereo pair. */
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

        /**
         * One eye's vertical field of view on the panel side of the lens: half the panel's height
         * seen from the lens's focal distance. The lens then spreads it wider (the distortion).
         */
        fun fovDeg(viewer: Viewer, panelHeightMm: Float): Float {
            if (panelHeightMm <= 0f || viewer.screenToLensMm <= 0f) return viewer.fallbackFovDeg
            val half = Math.toDegrees(kotlin.math.atan((panelHeightMm / 2f / viewer.screenToLensMm).toDouble())).toFloat()
            return (2f * half).coerceIn(30f, 140f)
        }

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
     * Device -> screen axes for a Display rotation (Surface.ROTATION_* as 0..3): the returned matrix
     * maps screen to world, given the device's own device -> world matrix. The same transform as
     * input/PadMotion's and SensorManager.remapCoordinateSystem's (ROTATION_90 = AXIS_Y, AXIS_MINUS_X:
     * the device's x is the screen's y, its y the screen's -x).
     */
    fun toScreen(deviceToWorld: FloatArray, rotation: Int): FloatArray {
        // Columns of A: where each screen axis points in device coordinates.
        val a = when (rotation and 3) {
            1 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)   // screen x = -device y, y = device x
            2 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)  // screen x = -device x, y = -device y
            3 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)   // screen x = device y, y = -device x
            else -> IDENTITY
        }
        return multiply(deviceToWorld, a)
    }

    /** sin(60 degrees): a view further from level than this is "held flat" for recentring. */
    private const val FLAT_SIN = 0.866f

    /**
     * What a recentre locks to. The pose the compositor gets is basis^T * screenToWorld * tilt:
     *  - [basis]: a level frame (columns right, up, back in world) facing the recentred heading. Every
     *    rotation is measured in it, so turning about the real vertical always pans the view;
     *  - [tilt]: the device's own tilt at the recentre, taken out so that pose starts straight ahead.
     *    Identity when the device was held upright: the horizon then stays the true one.
     */
    class Reference(val basis: FloatArray, val tilt: FloatArray)

    /**
     * Recentre on the current orientation. Held upright (the screen within 60 degrees of vertical, as
     * in a viewer), only the heading resets: forward is where the screen faces. Held flat (lying back,
     * or the device on a table or in the hands like a tray), forward is where the top of the screen
     * points and the tilt resets too; turning at the hips still pans, tipping the device still tilts.
     */
    fun recenter(screenToWorld: FloatArray): Reference {
        val fz = -screenToWorld[8]
        val flat = kotlin.math.abs(fz) > FLAT_SIN
        // Upright: the screen's -z. Flat: the screen's +y (its top edge), which is level then.
        var fx = if (flat) screenToWorld[1] else -screenToWorld[2]
        var fy = if (flat) screenToWorld[4] else -screenToWorld[5]
        val len = sqrt(fx * fx + fy * fy).takeIf { it > 1e-4f } ?: run { fx = 0f; fy = 1f; 1f }
        fx /= len; fy /= len
        // right = forward x up(0,0,1) = (fy, -fx, 0); up = (0,0,1); back = -forward.
        val basis = floatArrayOf(
            fy, 0f, -fx,
            -fx, 0f, -fy,
            0f, 1f, 0f,
        )
        val tilt = if (flat) multiply(transpose(screenToWorld), basis) else IDENTITY
        return Reference(basis, tilt)
    }

    /** head -> recentred world: what the compositor takes. */
    fun relative(ref: Reference, screenToWorld: FloatArray): FloatArray =
        multiply(multiply(transpose(ref.basis), screenToWorld), ref.tilt)

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
