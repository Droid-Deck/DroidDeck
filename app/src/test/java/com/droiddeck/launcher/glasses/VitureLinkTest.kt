package com.droiddeck.launcher.glasses

import android.app.Application
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class VitureLinkTest {
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
}
