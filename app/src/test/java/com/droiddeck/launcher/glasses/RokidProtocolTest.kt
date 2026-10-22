package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.gpu.HeadPose
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RokidProtocolTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun matrix(q: FloatArray) = HeadPose.fromGlasses(q[0], q[1], q[2], q[3])

    @Test fun identityRequiresRokidProductString() {
        assertEquals("Rokid Max", RokidModel.model(0x04d2, 0x162f, "Rokid Max")?.name)
        assertEquals("Rokid Air", RokidModel.model(0x04d2, 0x162f, "Rokid Air")?.name)
        assertNull(RokidModel.model(0x04d2, 0x162f, "USB Keyboard"))
        assertNull(RokidModel.model(0x04d2, 0x162f, null))
        assertNull(RokidModel.model(0x04d2, 0x1234, "Rokid Max"))
        assertTrue(RokidModel.isDisplay("Rokid Max"))
    }

    @Test fun maxModesFollowRefreshAndStereoResolution() {
        val max = RokidModel.model(0x04d2, 0x162f, "Rokid Max")!!
        assertEquals(RokidModel.MODE_2D_60, max.mode(60, false, null))
        assertEquals(RokidModel.MODE_2D_120, max.mode(120, false, null))
        assertEquals(RokidModel.MODE_2D_60, max.mode(90, false, null))
        assertEquals(listOf(60, 120), max.rates2d)
        assertEquals(RokidModel.MODE_SBS_1200_60, max.mode(60, true, null))
        assertEquals(RokidModel.MODE_SBS_1200_90, max.mode(90, true, null))
        assertEquals(RokidModel.MODE_SBS_1080_60, max.mode(90, true, GlassesResolution(1920, 1080)))
        assertTrue(max.sbs90)
        assertFalse(max.hasSunblock)
    }

    @Test fun airStaysOnSixtyHertzModes() {
        val air = RokidModel.model(0x04d2, 0x162f, "Rokid Air")!!
        assertEquals(RokidModel.MODE_2D_60, air.mode(120, false, null))
        assertEquals(RokidModel.MODE_SBS_1080_60, air.mode(90, true, null))
        assertFalse(air.sbs90)
        assertEquals(listOf(60), air.rates2d)
    }

    @Test fun brightnessStepsRoundTripTheDevicePercent() {
        assertEquals(8, RokidModel.brightnessStep(80))
        assertEquals(10, RokidModel.brightnessStep(100))
        assertEquals(3, RokidModel.brightnessStep(32))
        assertEquals(0, RokidModel.brightnessStep(0))
        assertEquals(30, RokidModel.brightnessRaw(3))
        assertEquals(100, RokidModel.brightnessRaw(12))
    }

    @Test fun capturedMaxCombinedReportParsesAccelGyroAndBrightness() {
        val packet = hex("11d09b3e3700000000bb9eb03f9147f0c06e86c340306a43bc9c227b3ad3e6aebb000055c29a99abc1cdccfac10000000000000000000000000000503c000000")
        val sample = RokidProtocol.Parser().parse(packet)!!
        assertEquals(0x373e9bd0L, sample.timeNs)
        assertEquals(1.380f, sample.accel.x, 0.001f)
        assertEquals(-7.509f, sample.accel.y, 0.001f)
        assertEquals(6.110f, sample.accel.z, 0.001f)
        assertEquals(-0.0119f, sample.gyro.x, 0.0001f)
        assertEquals(80, sample.brightness)
        assertNull(RokidProtocol.Parser().parse(packet.copyOf(32)))
    }

    @Test fun airSeparateReportsPairOnSharedTimestamp() {
        fun report(type: Int, time: Long, v: Vec3) = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, 4); put(1, type.toByte()); putLong(9, time)
            putFloat(21, v.x); putFloat(25, v.y); putFloat(29, v.z)
        }.array()
        val parser = RokidProtocol.Parser()
        assertNull(parser.parse(report(1, 500, Vec3(0f, 9.81f, 0f))))
        val sample = parser.parse(report(2, 500, Vec3(0.1f, 0f, 0f)))!!
        assertEquals(500_000L, sample.timeNs)
        assertEquals(9.81f, sample.accel.y, 0f)
        assertEquals(0.1f, sample.gyro.x, 0f)
        assertNull(sample.brightness)
        assertNull(parser.parse(report(2, 600, Vec3(0f, 0f, 0f))))
    }

    @Test fun capturedRestingGravityStartsUpright() {
        val pose = ImuFusion().update(1_000_000, Vec3(0.01f, 9.58f, -2.20f), Vec3(0f, 0f, 0f))!!
        assertTrue(kotlin.math.abs(pose[3]) > 0.97f)
    }

    @Test fun capturedLeftTurnSignLooksLeft() {
        val fusion = ImuFusion()
        val first = matrix(fusion.update(1_000_000, Vec3(0f, 9.81f, 0f), Vec3(0f, 0f, 0f))!!)
        var last = FloatArray(4)
        repeat(1000) {
            last = fusion.update((it + 2) * 1_000_000L, Vec3(0f, 9.81f, 0f), Vec3(0f, (Math.PI / 6).toFloat(), 0f))!!
        }
        val view = HeadPose.relative(HeadPose.recenter(first), matrix(last))
        assertArrayEquals(floatArrayOf(-0.5f, 0f, -0.8660254f), HeadPose.apply(view, floatArrayOf(0f, 0f, -1f)), 0.01f)
    }
}
