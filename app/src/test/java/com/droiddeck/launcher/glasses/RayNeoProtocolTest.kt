package com.droiddeck.launcher.glasses

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class RayNeoProtocolTest {
    private fun sensor(tick: Long = 1234L): ByteArray = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0, 0x99.toByte()); put(1, 0x65); put(2, 64)
        putFloat(4, 1f); putFloat(8, 9.81f); putFloat(12, -2f)
        putFloat(16, 30f); putFloat(20, -60f); putFloat(24, 90f)
        putFloat(28, 25f); putFloat(32, 2f); putFloat(36, 3f)
        putInt(40, tick.toInt()); putFloat(44, 1f); putFloat(48, 42f); putFloat(52, 4f)
    }.array()

    @Test fun decodesScaffoldLayoutAndUnsignedTick() {
        val p = RayNeoProtocol.classifyPacket(sensor(0xfffffff0L)) as RayNeoPacket.Sensor
        assertArrayEquals(floatArrayOf(1f, 9.81f, -2f), p.sample.accelMps2, 0f)
        assertArrayEquals(floatArrayOf(30f, -60f, 90f), p.sample.gyroDps, 0f)
        assertArrayEquals(floatArrayOf(2f, 3f, 4f), p.sample.magnet, 0f)
        assertEquals(0xfffffff0L, p.sample.deviceTick100us)
        assertEquals(25f, p.sample.temperatureC, 0f)
        assertEquals(42f, p.sample.light, 0f)
    }

    @Test fun rejectsTruncationBadHeaderAndNonfiniteMotion() {
        assertSame(RayNeoPacket.Unknown, RayNeoProtocol.classifyPacket(sensor().copyOf(40)))
        assertSame(RayNeoPacket.Unknown, RayNeoProtocol.classifyPacket(sensor().apply { this[0] = 0 }))
        assertSame(RayNeoPacket.Unknown, RayNeoProtocol.classifyPacket(sensor().apply { this[2] = 56 }))
        val nan = sensor().apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putFloat(16, Float.NaN) }
        assertSame(RayNeoPacket.Unknown, RayNeoProtocol.classifyPacket(nan))
    }

    @Test fun assemblesFragmentedAndCoalescedFramesAfterNoise() {
        val a = RayNeoPacketAssembler()
        val packet = sensor()
        val bytes = byteArrayOf(1, 2, 0x99.toByte(), 0, 0) + packet + packet
        a.append(bytes.copyOf(20), 20)
        assertNull(a.nextPacket())
        val rest = bytes.copyOfRange(20, bytes.size)
        a.append(rest, rest.size)
        assertArrayEquals(packet, a.nextPacket())
        assertArrayEquals(packet, a.nextPacket())
        assertNull(a.nextPacket())
    }

    @Test fun commandAndDeviceInfoMatchScaffold() {
        assertArrayEquals(byteArrayOf(0x66, 1, 0) + ByteArray(61), RayNeoProtocol.buildCommandPacket(1, 0, 64))
        val info = ByteArray(64).apply {
            this[0] = 0x99.toByte(); this[1] = 0xc8.toByte(); this[2] = 64
            this[8] = 0; this[21] = 0x3a; this[43] = 1
        }
        assertEquals(RayNeoDeviceInfo(0x3a, true), RayNeoProtocol.parseDeviceInfo(info))
        assertNull(RayNeoProtocol.parseDeviceInfo(info.copyOf(21)))
        info[8] = 1
        assertNull(RayNeoProtocol.parseDeviceInfo(info))
    }

    @Test fun discoveryDoesNotClaimEveryTclOrBridgeDevice() {
        assertNotNull(RayNeoModel.model(0x1bbb, 0xaf50, null))
        for (name in listOf("RayNeo Air 2", "RayNeo Air 2s", "RayNeo Air 3", "RayNeo Air 4 Pro", "RayNeo GT")) {
            assertEquals(name, RayNeoModel.model(0x22d9, 1, name)?.name)
            assertTrue(Glasses.isGlassesDisplayName(name))
        }
        assertNull(RayNeoModel.model(0x1bbb, 1, "TCL Phone"))
        assertNull(RayNeoModel.model(0x22d9, 1, "USB bridge"))
        assertNull(RayNeoModel.model(0x1234, 0xaf50, "RayNeo Air 3"))
        assertFalse(Glasses.isGlassesDisplayName("TCL TV"))
        assertTrue(Glasses.isGlassesDisplayName("VITURE Pro"))
    }

    @Test fun gtIdentityWorksWithoutProductNameAndPreservesMaxName() {
        assertEquals("RayNeo GT series", RayNeoModel.model(0x3941, 0xaf50, null)?.name)
        assertEquals("RayNeo GT series", RayNeoModel.model(0x3941, 0xaf50, "USB device")?.name)
        for (name in listOf("RayNeo GT", "RayNeo GT MAX")) {
            val model = RayNeoModel.model(0x3941, 0xaf50, name)!!
            assertEquals(name, model.name)
            assertEquals(6, model.mode(60, true))
            assertEquals(7, model.mode(120, false))
            assertTrue(Glasses.isGlassesDisplayName(name))
        }
        assertNull(RayNeoModel.model(0x3941, 0x1234, "RayNeo GT"))
        assertNull(RayNeoModel.model(0x1234, 0xaf50, "RayNeo GT MAX"))
    }

    @Test fun gtNotificationsAndControlAcksAreNotDeviceInfo() {
        for (cmd in listOf(0x08, 6, 7, 0x50)) {
            val raw = ByteArray(64).apply {
                this[0] = 0x99.toByte(); this[1] = 0xc8.toByte(); this[2] = 64
                this[8] = cmd.toByte()
            }
            assertEquals(cmd, (RayNeoProtocol.classifyPacket(raw) as RayNeoPacket.Response).cmd)
            assertNull(RayNeoProtocol.parseDeviceInfo(raw))
        }
    }

    private fun sample(tick: Long, yawDps: Float = 0f) = RayNeoSensorSample(
        floatArrayOf(0f, 9.81f, 0f), floatArrayOf(0f, yawDps, 0f), 0f,
        FloatArray(3), 0f, 0f, tick,
    )

    @Test fun stationaryPoseIsFiniteAndYawUsesVitureUpAxis() {
        val stationary = RayNeoOrientationFilter(0f)
        repeat(100) { assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), stationary.update(sample(it * 100L)), 1e-6f) }
        val filter = RayNeoOrientationFilter(0f)
        var q = FloatArray(4)
        repeat(100) { q = filter.update(sample(it * 100L, 90f)) }
        assertEquals(1f, sqrt(q.sumOf { (it * it).toDouble() }).toFloat(), 1e-5f)
        val pose = RayNeoOrientationFilter.glassesPose(q)
        assertEquals(0f, pose[0], 1e-5f); assertEquals(0f, pose[1], 1e-5f)
        assertEquals(sqrt(0.5f), pose[2], 0.01f); assertEquals(sqrt(0.5f), pose[3], 0.01f)
    }

    @Test fun tickWrapDoesNotIntroduceOrientationJump() {
        val filter = RayNeoOrientationFilter(0f)
        filter.update(sample(0xfffffff0L, 90f))
        val q = filter.update(sample(84, 90f)) // 100 ticks = 10ms across uint32 rollover.
        assertEquals(kotlin.math.sin(Math.PI * 0.005).toFloat(), q[2], 1e-4f)
    }
}
