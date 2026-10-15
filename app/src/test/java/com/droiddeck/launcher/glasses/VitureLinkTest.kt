package com.droiddeck.launcher.glasses

import android.content.Context
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Parcelable
import android.util.Log
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [VitureLinkTest.QueuedConnection::class])
class VitureLinkTest {
    private val frames = LinkedBlockingQueue<VitureProtocol.Frame>()
    private val imu = LinkedBlockingQueue<ByteArray>()
    private var link: VitureLink? = null

    private val listener = object : VitureLink.Listener {
        override fun onFrame(frame: VitureProtocol.Frame) { frames += frame }
        override fun onImu(payload: ByteArray, timeNs: Long) { imu += payload }
        override fun onLost() {}
    }

    @Before fun prepare() {
        incoming.clear()
        ShadowLog.clear()
    }

    @After fun close() { link?.close() }

    @Test fun framesWithABadChecksumAreDroppedAndCounted() {
        val usb = RuntimeEnvironment.getApplication().getSystemService(Context.USB_SERVICE) as UsbManager
        val l = VitureLink.open(usb, glasses(), listener)
        assertNotNull(l)
        link = l
        val report = packet(VitureProtocol.MAGIC_REPORT, VitureProtocol.EVENT_BRIGHTNESS, byteArrayOf(6))
        val pose = ByteArray(36) { it.toByte() }
        val imuPacket = packet(VitureProtocol.MAGIC_IMU, 0, pose)
        feed(MCU_IN, corrupt(report), report)
        feed(IMU_IN, corrupt(imuPacket), imuPacket)

        val frame = frames.poll(2, TimeUnit.SECONDS)
        assertNotNull(frame)
        assertEquals(VitureProtocol.EVENT_BRIGHTNESS, frame!!.msgId)
        assertArrayEquals(byteArrayOf(6), frame.payload)
        assertArrayEquals(pose, imu.poll(2, TimeUnit.SECONDS))
        assertNull(frames.poll(200, TimeUnit.MILLISECONDS))
        assertNull(imu.poll(0, TimeUnit.MILLISECONDS))
        val transport: GlassesLink = l!!
        assertEquals(2L, transport.badFrames)
        assertEquals(1, ShadowLog.getLogsForTag("VitureLink").count { it.type == Log.WARN })
    }

    @Test fun bothReadersDiscardCorruptReportsAndAcceptTheNextValidPacket() {
        for (imuEndpoint in listOf(false, true)) {
            checkPackets(VitureProtocol.MAGIC_REPORT, imuEndpoint, expectImu = false)
        }
    }

    @Test fun imuReaderDiscardsCorruptPoseAndAcceptsTheNextValidPacket() {
        checkPackets(VitureProtocol.MAGIC_IMU, imuEndpoint = true, expectImu = true)
    }

    private fun checkPackets(magic: Int, imuEndpoint: Boolean, expectImu: Boolean) {
        val frames = mutableListOf<VitureProtocol.Frame>()
        val poses = mutableListOf<ByteArray>()
        val listener = object : VitureLink.Listener {
            override fun onFrame(frame: VitureProtocol.Frame) { frames += frame }
            override fun onImu(payload: ByteArray, timeNs: Long) { poses += payload }
            override fun onLost() { fail("A corrupt packet must not disconnect the link") }
        }
        val ctor = VitureLink::class.java.getDeclaredConstructor(UsbDeviceConnection::class.java,
            UsbInterface::class.java, UsbEndpoint::class.java, UsbEndpoint::class.java,
            UsbInterface::class.java, UsbEndpoint::class.java, VitureLink.Listener::class.java)
            .apply { isAccessible = true }
        val link = ctor.newInstance(Shadow.newInstanceOf(UsbDeviceConnection::class.java),
            Shadow.newInstanceOf(UsbInterface::class.java), Shadow.newInstanceOf(UsbEndpoint::class.java),
            null, null, null, listener)
        val receive = VitureLink::class.java.getDeclaredMethod("onPacket", ByteArray::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val payload = byteArrayOf(0, 0x32)
        val valid = VitureProtocol.packet(VitureProtocol.MSG_DISPLAY, payload).also { it[1] = magic.toByte() }
        val corrupt = valid.copyOf().also { it[18] = 1 }
        assertFalse(VitureProtocol.parse(corrupt, corrupt.size)!!.crcOk)
        receive.invoke(link, corrupt, corrupt.size, imuEndpoint)
        assertTrue(frames.isEmpty())
        assertTrue(poses.isEmpty())
        receive.invoke(link, valid, valid.size, imuEndpoint)
        assertEquals(if (expectImu) 0 else 1, frames.size)
        assertEquals(if (expectImu) 1 else 0, poses.size)
        assertArrayEquals(payload, if (expectImu) poses.single() else frames.single().payload)
    }

    private fun packet(magic: Int, msgId: Int, payload: ByteArray) =
        VitureProtocol.packet(msgId, payload).also { it[1] = magic.toByte() }

    private fun corrupt(packet: ByteArray) = packet.copyOf().also { it[18] = (it[18].toInt() xor 0x01).toByte() }

    private fun feed(address: Int, vararg packets: ByteArray) {
        val queue = incoming.getOrPut(address) { LinkedBlockingQueue() }
        packets.forEach(queue::put)
    }

    private fun glasses(): UsbDevice {
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
            UsbEndpoint::class.java,
            from(Int::class.javaPrimitiveType, address),
            from(Int::class.javaPrimitiveType, UsbConstants.USB_ENDPOINT_XFER_INT),
            from(Int::class.javaPrimitiveType, VitureProtocol.PACKET_SIZE),
            from(Int::class.javaPrimitiveType, 1),
        )
        fun hid(id: Int, vararg endpoints: UsbEndpoint): UsbInterface = ReflectionHelpers.callConstructor(
            UsbInterface::class.java,
            from(Int::class.javaPrimitiveType, id),
            from(Int::class.javaPrimitiveType, 0),
            from(String::class.java, "hid$id"),
            from(Int::class.javaPrimitiveType, UsbConstants.USB_CLASS_HID),
            from(Int::class.javaPrimitiveType, 0),
            from(Int::class.javaPrimitiveType, 0),
        ).also { hidden(it, "setEndpoints", *endpoints) }
        val config: UsbConfiguration = ReflectionHelpers.callConstructor(
            UsbConfiguration::class.java,
            from(Int::class.javaPrimitiveType, 1),
            from(String::class.java, "default"),
            from(Int::class.javaPrimitiveType, 0),
            from(Int::class.javaPrimitiveType, 250),
        )
        hidden(config, "setInterfaces", hid(0, endpoint(IMU_IN)), hid(1, endpoint(MCU_IN), endpoint(MCU_OUT)))
        return ReflectionHelpers.callConstructor(
            UsbDevice::class.java,
            from(String::class.java, "/dev/bus/usb/001/002"),
            from(Int::class.javaPrimitiveType, VitureProtocol.VENDOR_ID),
            from(Int::class.javaPrimitiveType, 0x101d),
            from(Int::class.javaPrimitiveType, 0),
            from(Int::class.javaPrimitiveType, 0),
            from(Int::class.javaPrimitiveType, 0),
            from(String::class.java, "VITURE"),
            from(String::class.java, "VITURE Pro XR"),
            from(String::class.java, "1.0"),
            from(String::class.java, "test"),
        ).also { hidden(it, "setConfigurations", config) }
    }

    private fun hidden(target: Any, method: String, vararg items: Parcelable) {
        ReflectionHelpers.callInstanceMethod<Unit>(target, method, from(Array<Parcelable>::class.java, arrayOf(*items)))
    }

    @Implements(UsbDeviceConnection::class)
    class QueuedConnection {
        @Implementation fun claimInterface(intf: UsbInterface, force: Boolean) = true
        @Implementation fun releaseInterface(intf: UsbInterface) = true
        @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
            if (endpoint.direction == UsbConstants.USB_DIR_OUT) return length
            val packet = incoming[endpoint.address]?.poll(timeout.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            packet.copyInto(buffer, endIndex = minOf(packet.size, length))
            return minOf(packet.size, length)
        }
    }

    companion object {
        private const val IMU_IN = 0x81
        private const val MCU_IN = 0x82
        private const val MCU_OUT = 0x02
        private val incoming = ConcurrentHashMap<Int, LinkedBlockingQueue<ByteArray>>()
    }
}
