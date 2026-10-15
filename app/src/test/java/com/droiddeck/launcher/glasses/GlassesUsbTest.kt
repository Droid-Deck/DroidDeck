package com.droiddeck.launcher.glasses

import android.app.PendingIntent
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowUsbManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [GlassesUsbTest.RecordingUsbManager::class])
@LooperMode(LooperMode.Mode.PAUSED)
class GlassesUsbTest {
    private lateinit var manager: RecordingUsbManager
    private lateinit var state: MutableStateFlow<GlassesState>
    private fun field(name: String) = Glasses::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        manager = Shadow.extract(usb)
        field("app").set(null, context)
        field("usb").set(null, usb)
        field("displays").set(null, context.getSystemService(Context.DISPLAY_SERVICE))
        field("handler").set(null, Handler(Looper.getMainLooper()))
        field("asked").setBoolean(null, false)
        @Suppress("UNCHECKED_CAST")
        state = field("_state").get(null) as MutableStateFlow<GlassesState>
        state.value = GlassesState()
    }

    @Test fun controllableGlassesTakePriorityOverDockAndUnsupportedGlasses() {
        val dock = device(0x3011)
        val beast = device(0x1201)
        val pro = device(0x101d)
        manager.devices.putAll(listOf(dock, beast, pro).associateBy { it.deviceName })
        refresh()
        assertEquals(listOf(pro), manager.requests)
        assertTrue(state.value.controllable)
        assertEquals(Glasses.Permission.ASKING, state.value.permission)
    }

    @Test fun unsupportedDevicesNeverRequestOrAdvertiseUsbPermission() {
        for (pid in listOf(0x3011, 0xffff, 0x1201)) {
            val device = device(pid)
            manager.devices.clear()
            manager.devices[device.deviceName] = device
            state.value = GlassesState()
            refresh(ask = true)
            assertTrue(manager.requests.isEmpty())
            assertEquals(Glasses.Permission.NONE, state.value.permission)
            manager.granted.add(device.deviceName)
            state.value = GlassesState(permission = Glasses.Permission.ASKING)
            refresh(ask = true)
            assertTrue(manager.requests.isEmpty())
            assertEquals(Glasses.Permission.NONE, state.value.permission)
            assertEquals(pid == 0x1201, state.value.present)
            assertFalse(state.value.controllable)
            assertFalse(state.value.connected)
        }
    }

    @Test fun dockPresenceStillIdentifiesAnUnnamedPresentationDisplay() {
        val hdmi = ReflectionHelpers.getStaticField<Int>(Display::class.java, "TYPE_HDMI")
        val displayId = ShadowDisplayManager.addDisplay("w1920dp-h1080dp", hdmi)
        val displays = RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java)
        val display = displays.getDisplay(displayId)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        assertEquals(displayId, displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).single().displayId)
        assertFalse(display.name.contains("VITURE", ignoreCase = true))
        val dock = device(0x3011)
        manager.devices[dock.deviceName] = dock
        refresh()
        assertEquals(displayId, state.value.displayId)
        assertTrue(manager.requests.isEmpty())
        assertEquals(Glasses.Permission.NONE, state.value.permission)
    }

    @Test fun noSignalStereoFailureIsRememberedPerModel() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("glasses", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val hdmi = ReflectionHelpers.getStaticField<Int>(Display::class.java, "TYPE_HDMI")
        val displayId = ShadowDisplayManager.addDisplay("w640dp-h480dp", hdmi)
        val displays = RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java)
        shadowOf(displays.getDisplay(displayId)).setFlags(Display.FLAG_PRESENTATION)
        val pro = device(0x1019)
        manager.devices[pro.deviceName] = pro
        refresh()
        assertTrue(state.value.noSignal)
        (field("modeState").get(null) as GlassesModeState).sent(true, SystemClock.uptimeMillis())
        ShadowSystemClock.advanceBy(Duration.ofSeconds(21))
        refresh()
        assertEquals(GlassesState.SBS_FAILS, state.value.sbsSupport)
        assertEquals(setOf("1019"), prefs.getStringSet("sbsFailed", null))
        ShadowDisplayManager.removeDisplay(displayId)

        val xr = device(0x101d)
        manager.devices.clear()
        manager.devices[xr.deviceName] = xr
        refresh()
        assertEquals(GlassesState.SBS_UNKNOWN, state.value.sbsSupport)
        manager.devices.clear()
        manager.devices[pro.deviceName] = pro
        refresh()
        assertEquals(GlassesState.SBS_FAILS, state.value.sbsSupport)
        assertFalse(state.value.can3d)
    }

    @Test fun rayNeoRequestsPermissionWithoutClaimingUnrelatedTclDevices() {
        val air = device(0xaf50, 0x1bbb, "RayNeo Air 4 Pro")
        manager.devices[air.deviceName] = air
        refresh(ask = true)
        assertEquals("RayNeo Air 4 Pro", state.value.model)
        assertTrue(state.value.controllable)
        assertFalse(state.value.hardwareControls)
        assertEquals(listOf(air), manager.requests)

        manager.devices.clear()
        manager.devices["phone"] = device(0x1234, 0x1bbb, "TCL Phone")
        manager.requests.clear()
        refresh(ask = true)
        assertFalse(state.value.present)
        assertTrue(manager.requests.isEmpty())
    }

    @Test fun alternateBridgeRequiresRayNeoModelIdentity() {
        val gt = device(0x1234, 0x22d9, "RayNeo GT")
        manager.devices[gt.deviceName] = gt
        refresh(ask = true)
        assertEquals("RayNeo GT", state.value.model)
        assertEquals(listOf(gt), manager.requests)
    }

    @Test fun gtVendorRequestsPermissionAndRetainsMaxProductName() {
        val gt = device(0xaf50, 0x3941, "RayNeo GT MAX")
        manager.devices[gt.deviceName] = gt
        refresh(ask = true)
        assertEquals("RayNeo GT MAX", state.value.model)
        assertTrue(state.value.controllable)
        assertFalse(state.value.hardwareControls)
        assertEquals(listOf(gt), manager.requests)
    }

    @Test fun screen3dofAvailabilityFollowsTheTrackingTransportAcrossBrands() {
        val devices = listOf(device(0x1011), device(0x1019), device(0x1131),
            device(0xaf50, 0x1bbb, "RayNeo Air 4 Pro"),
            device(0xaf50, 0x3941, "RayNeo GT MAX"),
            device(0x043e, 0x3318, "XREAL 1S"))
        try {
            for (device in devices) for (imu in listOf(false, true)) {
                manager.devices.clear()
                manager.devices[device.deviceName] = device
                manager.granted.add(device.deviceName)
                field("linkDevice").set(null, device)
                field("link").set(null, object : GlassesLink {
                    override val hasImu = imu
                    override fun send(msgId: Int, payload: ByteArray) = true
                    override fun close() {}
                })
                refresh()
                assertTrue(state.value.canControl)
                assertEquals(imu, state.value.supportsScreen3dof)
            }
            manager.devices.clear()
            refresh()
            assertFalse(state.value.supportsScreen3dof)
        } finally {
            field("link").set(null, null)
            field("linkDevice").set(null, null)
            (field("handler").get(null) as Handler).removeCallbacksAndMessages(null)
        }
    }

    private fun refresh(ask: Boolean = false) {
        Glasses::class.java.getDeclaredMethod("refresh", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(Glasses, ask)
    }

    @Test fun xreal1SUsesExactIdentityAndExcludesAirOneProAndRecoveryProducts() {
        assertEquals(XrealModel, Glasses.deviceModel(device(0x043e, 0x3318, "XREAL 1S")))
        assertTrue(Glasses.isGlassesDisplayName("XREAL 1S"))
        for (pid in listOf(0x043d, 0x0435, 0x0436, 0x0424, 0xffff)) {
            assertEquals(null, Glasses.deviceModel(device(pid, 0x3318, "XREAL 1S")))
        }
        assertEquals(null, Glasses.deviceModel(device(0x043e, 0x1234, "XREAL 1S")))
    }

    private fun device(pid: Int, vid: Int = VitureProtocol.VENDOR_ID, name: String = "VITURE device"): UsbDevice = ReflectionHelpers.callConstructor(
        UsbDevice::class.java,
        from(String::class.java, "/dev/bus/usb/$pid"),
        from(Int::class.javaPrimitiveType, vid),
        from(Int::class.javaPrimitiveType, pid),
        from(Int::class.javaPrimitiveType, 0),
        from(Int::class.javaPrimitiveType, 0),
        from(Int::class.javaPrimitiveType, 0),
        from(String::class.java, "VITURE"),
        from(String::class.java, name),
        from(String::class.java, "1.0"),
        from(String::class.java, "test-$pid"),
    )

    @Implements(UsbManager::class)
    class RecordingUsbManager : ShadowUsbManager() {
        val devices = linkedMapOf<String, UsbDevice>()
        val granted = mutableSetOf<String>()
        val requests = mutableListOf<UsbDevice>()

        @Implementation override fun getDeviceList(): HashMap<String, UsbDevice> = LinkedHashMap(devices)
        @Implementation override fun hasPermission(device: UsbDevice): Boolean = device.deviceName in granted
        @Implementation fun requestPermission(device: UsbDevice, intent: PendingIntent) { requests += device }
    }
}
