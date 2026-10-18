package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt
import com.droiddeck.launcher.gpu.HeadPose

class VitureSdkTest {
    @Test fun spatialIdentityUsesTheFullSdkDigestAndRejectsMissingValues() {
        assertNull(VitureSpatialIdentity.fromSerialHash(null))
        assertNull(VitureSpatialIdentity.fromSerialHash(ByteArray(32)))
        assertNull(VitureSpatialIdentity.fromSerialHash(ByteArray(31) { 1 }))
        val digest=ByteArray(32) { it.toByte() }
        val id=VitureSpatialIdentity.fromSerialHash(digest)
        assertEquals("viture:sn-sha256:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",id)
        assertEquals(id,VitureSpatialIdentity.fromSerialHash(digest.copyOf()))
        assertNotEquals(id,VitureSpatialIdentity.fromSerialHash(digest.copyOf().also { it[31]=0xff.toByte() }))
    }
    @Test fun pro2UsesItsOwnSdkTransportAndDisplayLimits() {
        val m = VitureProtocol.sdkModel("Pro 2")!!
        assertTrue(m.needsSdk)
        assertEquals(VitureProtocol.Family.PRO2, m.family)
        assertEquals(0x34, m.mode(120, false))
        assertEquals(0x32, m.mode(90, true))
        assertFalse(m.sbs90)
        assertFalse(m.hasSunblock)
        assertEquals(8, m.volumeMax)
        assertEquals(50f, m.diagonalFovDeg, 0f)
    }

    @Test fun ultraUsesSdkWith1200pAnd90HzStereo() {
        val m = VitureProtocol.sdkModel("Luma Ultra")!!
        assertTrue(m.needsSdk)
        assertEquals(0x44, m.mode(120, false))
        assertEquals(0x42, m.mode(60, true))
        assertEquals(0x45, m.mode(90, true))
        assertTrue(m.hasSunblock)
    }

    @Test fun gen2DoesNotImplyBeastCapabilities() {
        val beast = VitureProtocol.sdkModel("Beast")!!
        assertTrue(beast.needsSdk)
        assertEquals(15, beast.volumeMax)
        assertTrue(beast.hasSunblock)
        assertFalse(VitureProtocol.sdkModel("Pro")!!.needsSdk)
        assertNull(VitureProtocol.sdkModel("Unknown"))
        assertNull(VitureProtocol.sdkModel("Mobile Dock"))
    }

    @Test fun otherGen2DevicesPreserveSdkNwuAxesAndChangeComponentOrder() {
        val a = sqrt(0.5f)
        assertArrayEquals(floatArrayOf(0f, 0f, a, a),
            VitureSdkPose.toGlasses(floatArrayOf(a, 0f, 0f, a), false), 0.00001f)
    }

    @Test fun pro2AndCarinaSelectOpenGlButOtherGen2ModelsKeepTheirExistingBasis() {
        assertTrue(VitureSdkPose.usesOpenGlAxes(false, VitureProtocol.Family.PRO2))
        assertTrue(VitureSdkPose.usesOpenGlAxes(true, VitureProtocol.Family.ULTRA))
        assertFalse(VitureSdkPose.usesOpenGlAxes(false, VitureProtocol.Family.BEAST))
        assertFalse(VitureSdkPose.usesOpenGlAxes(false, VitureProtocol.Family.PRO))
        assertFalse(VitureSdkPose.usesOpenGlAxes(false, null))
    }

    @Test fun pro2YawPitchAndRollReachTheCorrectRendererAxes() {
        val openGl = VitureSdkPose.usesOpenGlAxes(false, VitureProtocol.sdkModel("Pro 2")!!.family)
        fun screen(wxyz: FloatArray): FloatArray {
            val q = VitureSdkPose.toGlasses(wxyz, openGl)!!
            return HeadPose.fromGlasses(q[0], q[1], q[2], q[3])
        }
        val ref = HeadPose.recenter(screen(floatArrayOf(1f, 0f, 0f, 0f)))
        val s = kotlin.math.sin(Math.PI / 12).toFloat()
        val c = kotlin.math.cos(Math.PI / 12).toFloat()
        for ((axis, expected) in listOf(
            2 to floatArrayOf(-0.5f, 0f, -0.8660254f), // Observed left turn: positive Y.
            1 to floatArrayOf(0f, 0.5f, -0.8660254f),  // Observed upward look: positive X.
            3 to floatArrayOf(-0.5f, 0.8660254f, 0f),
        )) {
            val q = floatArrayOf(c, 0f, 0f, 0f).apply { this[axis] = s }
            val view = HeadPose.relative(ref, screen(q))
            val direction = if (axis == 3) floatArrayOf(0f, 1f, 0f) else floatArrayOf(0f, 0f, -1f)
            assertArrayEquals(expected, HeadPose.apply(view, direction), 0.001f)
        }
    }

    @Test fun ultraOpenGlAxesMatchExistingGlassesHeadTracker() {
        val a = sqrt(0.5f)
        // OpenGL Y yaw maps to NWU Z; OpenGL X pitch maps to negative NWU Y.
        assertArrayEquals(floatArrayOf(0f, 0f, a, a),
            VitureSdkPose.toGlasses(floatArrayOf(a, 0f, a, 0f), true), 0.00001f)
        assertArrayEquals(floatArrayOf(0f, -a, 0f, a),
            VitureSdkPose.toGlasses(floatArrayOf(a, a, 0f, 0f), true), 0.00001f)
        assertArrayEquals(floatArrayOf(-a, 0f, 0f, a),
            VitureSdkPose.toGlasses(floatArrayOf(a, 0f, 0f, a), true), 0.00001f)
    }

    @Test fun invalidOrUninitializedSdkPosesNeverReachTheRenderer() {
        for (q in listOf(FloatArray(4), floatArrayOf(Float.NaN, 0f, 0f, 0f),
            floatArrayOf(Float.POSITIVE_INFINITY, 0f, 0f, 0f), floatArrayOf(3f, 0f, 0f, 0f), FloatArray(7))) {
            assertNull(VitureSdkPose.toGlasses(q, false))
            assertNull(VitureSdkPose.toGlasses(q, true))
        }
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f),
            VitureSdkPose.toGlasses(floatArrayOf(1.1f, 0f, 0f, 0f), true), 0.00001f)
    }
}
