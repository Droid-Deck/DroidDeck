package com.droiddeck.launcher.glasses

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [RayNeoLinkTest.Connection::class])
@LooperMode(LooperMode.Mode.PAUSED)
class RayNeoLinkTest {
    @Test fun handshakeWarmupPoseAndShutdownUseOnlyRayNeoWireCommands() = exerciseLink(false)

    @Test fun gtControlTrafficDoesNotBreakHandshakeOrTracking() = exerciseLink(true)
    @Test fun nonMaxBoardSelectsItsProfileBeforeTrackingStarts() = exerciseLink(true, true)

    private fun exerciseLink(gtTraffic: Boolean, nonMax: Boolean = false) {
        fun endpoint(address: Int) = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            from(Int::class.javaPrimitiveType, address), from(Int::class.javaPrimitiveType, 3),
            from(Int::class.javaPrimitiveType, 64), from(Int::class.javaPrimitiveType, 1))
        val iface = Shadow.newInstanceOf(UsbInterface::class.java)
        val conn = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
        val io = Shadow.extract<Connection>(conn)
        io.gtTraffic = gtTraffic
        io.boardId = if (nonMax) 0x40 else if (gtTraffic) 0x41 else 0
        lateinit var link: RayNeoLink
        var ready = false
        var pose: FloatArray? = null
        var lost = false
        var loadedBoard = -1
        var reportedBoard = -1
        val listener = object : RayNeoLink.Listener {
            override fun onDeviceInfo(boardId: Int, calibrationSaved: Boolean) {
                reportedBoard = boardId
                assertFalse(calibrationSaved)
            }
            override fun onReady() {
                assertEquals(io.boardId, loadedBoard)
                assertEquals(io.boardId, reportedBoard)
                ready = true
                if (gtTraffic) {
                    assertTrue(link.send(VitureProtocol.MSG_DISPLAY, byteArrayOf(6)))
                    assertTrue(link.send(VitureProtocol.MSG_DISPLAY, byteArrayOf(7)))
                }
            }
            override fun onPose(raw: ByteArray, xyzw: FloatArray, timeNs: Long) {
                pose = xyzw
                link.close()
            }
            override fun onLost() { lost = true }
        }
        val ctor = RayNeoLink::class.java.declaredConstructors.first { it.parameterCount == 5 }.apply { isAccessible = true }
        val loadCalibration: (Int) -> RayNeoCalibration? = { board -> loadedBoard = board; null }
        link = ctor.newInstance(conn, RayNeoEndpointSelection(iface, endpoint(0x81), endpoint(0x01)), listener, gtTraffic, loadCalibration) as RayNeoLink
        ReflectionHelpers.setField(link, "worker", Thread.currentThread())
        assertFalse(link.send(VitureProtocol.MSG_BRIGHTNESS, byteArrayOf(8)))
        assertFalse(link.send(VitureProtocol.MSG_FILM, byteArrayOf(1)))
        assertFalse(link.send(VitureProtocol.MSG_IMU_RATE, byteArrayOf(3)))
        assertTrue(io.commands.isEmpty())
        assertTrue(link.send(VitureProtocol.MSG_IMU, byteArrayOf(1)))
        RayNeoLink::class.java.getDeclaredMethod("run").apply { isAccessible = true }.invoke(link)
        assertTrue(ready)
        assertFalse(lost)
        assertEquals(if (gtTraffic) listOf(0, 6, 7, 1, 2) else listOf(0, 1, 2), io.commands)
        assertTrue(io.samples >= 200)
        assertTrue(io.closed)
        assertTrue(io.released)
        assertNotNull(pose)
        assertEquals(0f, pose!![0], 1e-5f)
        assertEquals(0f, pose!![1], 1e-5f)
        assertTrue(pose!![2] > 0)
    }

    @Implements(UsbDeviceConnection::class)
    class Connection {
        val commands = mutableListOf<Int>()
        private val responses = ArrayDeque<ByteArray>()
        var samples = 0
        var closed = false
        var released = false
        var gtTraffic = false
        var boardId = 0
        private var streaming = false

        @Implementation fun bulkTransfer(ep: UsbEndpoint, bytes: ByteArray, count: Int, timeout: Int): Int {
            if (ep.direction == 0) {
                check(bytes[0] == 0x66.toByte() && count == 64)
                val cmd = bytes[1].toInt() and 0xff
                commands += cmd
                if (gtTraffic && cmd in listOf(0, 1)) {
                    // Notifications and unrelated ACKs can precede the requested response.
                    for (other in listOf(0x08, 6, 7, 0x50)) {
                        responses += ByteArray(64).apply {
                            this[0] = 0x99.toByte(); this[1] = 0xc8.toByte(); this[2] = 64
                            this[8] = other.toByte()
                        }
                    }
                }
                responses += ByteArray(64).apply {
                    this[0] = 0x99.toByte(); this[1] = 0xc8.toByte(); this[2] = 64
                    this[8] = cmd.toByte()
                    if (cmd == 0) this[21] = boardId.toByte()
                }
                if (cmd == 1) streaming = true
                if (cmd == 2) streaming = false
                return count
            }
            ShadowSystemClock.advanceBy(Duration.ofMillis(10))
            val packet = if (responses.isNotEmpty()) responses.removeFirst() else {
                check(streaming)
                check(++samples < 500) { "Warmup never delivered a pose" }
                ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put(0, 0x99.toByte()); put(1, 0x65); put(2, 64)
                    if (gtTraffic && boardId == 0x40) {
                        val s = kotlin.math.sin(Math.toRadians(25.0)).toFloat()
                        val c = kotlin.math.cos(Math.toRadians(25.0)).toFloat()
                        putFloat(8, 9.81f * c); putFloat(12, 9.81f * s)
                        putFloat(20, 90f * c); putFloat(24, 90f * s)
                    } else if (gtTraffic) {
                        val s = kotlin.math.sin(Math.toRadians(35.0)).toFloat()
                        val c = kotlin.math.cos(Math.toRadians(35.0)).toFloat()
                        putFloat(4, 9.81f * s); putFloat(12, 9.81f * c)
                        putFloat(16, 90f * s); putFloat(24, 90f * c)
                    } else {
                        putFloat(8, 9.81f); putFloat(20, 90f)
                    }
                    putInt(40, samples * 100)
                }.array()
            }
            packet.copyInto(bytes)
            return packet.size
        }
        @Implementation fun releaseInterface(iface: UsbInterface): Boolean { released = true; return true }
        @Implementation fun close() { closed = true }
    }
}
