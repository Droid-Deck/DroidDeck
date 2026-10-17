package com.droiddeck.launcher.glasses

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RayNeoEndpointsTest {
    @Test fun gtPrefersInterfaceNumberFiveOverEarlierPairs() {
        val other = iface(1)
        val control = iface(5)
        val selected = RayNeoProtocol.selectEndpoints(device(0x3941, other, control))!!
        assertSame(control, selected.usbInterface)
        assertEquals(0x81, selected.inEndpoint.address)
        assertEquals(0x01, selected.outEndpoint.address)
    }

    @Test fun gtAcceptsUniqueRenumberedPairAndIgnoresSmallButtonReports() {
        val control = iface(0)
        assertSame(control, RayNeoProtocol.selectEndpoints(
            device(0x3941, iface(5, packetSize = 8), control))?.usbInterface)
    }

    @Test fun gtDoesNotGuessBetweenAmbiguousPairsOrUseInputOnlyInterface() {
        assertNull(RayNeoProtocol.selectEndpoints(device(0x3941, iface(0), iface(1))))
        assertNull(RayNeoProtocol.selectEndpoints(device(0x3941, iface(5, output = false))))
        assertNull(RayNeoProtocol.selectEndpoints(device(0x3941, iface(5, packetSize = 8))))
    }

    @Test fun airRetainsItsExistingFirstInterruptPairSelection() {
        val first = iface(1)
        assertSame(first, RayNeoProtocol.selectEndpoints(device(0x1bbb, first, iface(5)))?.usbInterface)
    }

    private fun device(vid: Int, vararg interfaces: UsbInterface): UsbDevice =
        Shadow.newInstanceOf(UsbDevice::class.java).also {
            ReflectionHelpers.setField(it, "mVendorId", vid)
            ReflectionHelpers.setField(it, "mProductId", 0xaf50)
            ReflectionHelpers.setField(it, "mInterfaces", interfaces)
        }

    private fun iface(id: Int, packetSize: Int = 64, output: Boolean = true): UsbInterface =
        Shadow.newInstanceOf(UsbInterface::class.java).also {
            ReflectionHelpers.setField(it, "mId", id)
            val endpoints = if (output) arrayOf(endpoint(0x81, packetSize), endpoint(1, packetSize))
                else arrayOf(endpoint(0x81, packetSize))
            ReflectionHelpers.setField(it, "mEndpoints", endpoints)
        }

    private fun endpoint(address: Int, size: Int) = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
        from(Int::class.javaPrimitiveType, address), from(Int::class.javaPrimitiveType, 3),
        from(Int::class.javaPrimitiveType, size), from(Int::class.javaPrimitiveType, 1))
}
