package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.gpu.HeadPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesMenuProjectionTest {
    private fun frame(rotation: FloatArray = HeadPose.IDENTITY, position: FloatArray = FloatArray(3)) =
        GlassesMenuPose.Frame(rotation, position, true, false)

    private fun hit(pose: GlassesMenuPose.Frame = frame(), x: Float = 0f, y: Float = 0f, eye: Float = 0f,
                    aspect: Float = 2f) = GlassesMenuProjection.intersect(pose, x, y, eye, 1f, 3f, aspect)

    @Test fun centeredForwardRaySamplesTheTextureCenter() {
        assertEquals(GlassesMenuProjection.Uv(0.5f, 0.5f), hit())
    }

    @Test fun screenTopAndRightRaysUseTopLeftTextureCoordinates() {
        val topRight = hit(x = 1f/3f, y = -1f/6f)!!
        assertEquals(1f, topRight.x, 1e-5f)
        assertEquals(0f, topRight.y, 1e-5f)
    }

    @Test fun eyeOriginsShiftByPhysicalIpdWhileMonoUsesTheCenter() {
        val left = hit(eye = -GlassesMenuSurface.IPD_METERS/2)!!
        val right = hit(eye = GlassesMenuSurface.IPD_METERS/2)!!
        assertEquals(0.484f, left.x, 1e-5f)
        assertEquals(0.516f, right.x, 1e-5f)
        assertEquals(0.5f, hit()!!.x, 0f)
    }

    @Test fun translatedHeadUsesMetresInTheAnchoredScreenFrame() {
        val translated = hit(frame(position = floatArrayOf(0.2f, 0.1f, 0f)))!!
        assertEquals(0.6f, translated.x, 1e-5f)
        assertEquals(0.4f, translated.y, 1e-5f)
    }

    @Test fun rowMajorPositiveYawTurnsTheForwardRayTowardNegativeX() {
        val angle = Math.toRadians(10.0)
        val rotation = SpatialRotation(y = kotlin.math.sin(angle/2).toFloat(), w = kotlin.math.cos(angle/2).toFloat()).matrix()
        assertEquals(0.5f - 1.5f*kotlin.math.tan(angle).toFloat(), hit(frame(rotation))!!.x, 1e-5f)
    }

    @Test fun aRayBehindTheHeadOrOutsideThePlaneHasNoUiHit() {
        assertNull(hit(frame(rotation = SpatialRotation(y = 1f, w = 0f).matrix())))
        assertNull(hit(x = 1f))
        assertNull(hit(frame(position = floatArrayOf(0f, 0f, -4f))))
    }

    @Test fun sourceAspectChangesThePlaneHeightWithoutStretchingItsWidth() {
        assertNotNull(hit(y = 0.2f, aspect = 1f))
        assertNull(hit(y = 0.2f, aspect = 2f))
    }

    @Test fun nonFiniteInputsCannotCreateTextureCoordinates() {
        assertNull(hit(x = Float.NaN))
        assertNull(hit(frame(position = floatArrayOf(Float.POSITIVE_INFINITY, 0f, 0f))))
    }
}

private object GlassesMenuProjection {
    data class Uv(val x: Float, val y: Float)

    // Row-major head-to-anchor rotation; +Y is up, -Z is forward, output UV starts at the top left.
    fun intersect(frame: GlassesMenuPose.Frame, rayX: Float, rayY: Float, eye: Float,
                  halfWidth: Float, distance: Float, aspect: Float): Uv? {
        if (frame.rotation.size != 9 || frame.position.size != 3 || frame.rotation.any { !it.isFinite() } ||
            frame.position.any { !it.isFinite() } || listOf(rayX, rayY, eye, halfWidth, distance, aspect).any { !it.isFinite() } ||
            halfWidth <= 0f || distance <= 0f || aspect <= 0f) return null
        val r = frame.rotation
        val d = FloatArray(3) { row -> r[row*3]*rayX - r[row*3+1]*rayY - r[row*3+2] }
        val o = FloatArray(3) { row -> frame.position[row] + r[row*3]*eye }
        if (d[2] >= -0.0001f) return null
        val t = (-distance-o[2]) / d[2]
        if (t <= 0f) return null
        val u = (o[0]+d[0]*t) / halfWidth * 0.5f + 0.5f
        val v = -(o[1]+d[1]*t) * aspect / halfWidth * 0.5f + 0.5f
        return Uv(u, v).takeIf { u in 0f..1f && v in 0f..1f }
    }
}
