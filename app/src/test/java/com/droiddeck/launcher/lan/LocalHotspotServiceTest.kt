package com.droiddeck.launcher.lan

import android.Manifest
import android.app.Application
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import com.droiddeck.launcher.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = Application::class, shadows = [LanWifiShadow::class, LanReservationShadow::class, LanSoftApShadow::class])
class LocalHotspotServiceTest {
    private lateinit var controller: org.robolectric.android.controller.ServiceController<LocalHotspotService>
    private val service get() = controller.get()

    @Before fun prepare() {
        LocalLan.hotspotState.value = HotspotState()
        LanWifiShadow.callback = null
        LanWifiShadow.requests = 0
        LanWifiShadow.unsupported = false
        LanReservationShadow.closes = 0
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        controller = Robolectric.buildService(LocalHotspotService::class.java).create()
    }

    @After fun cleanUp() {
        controller.destroy()
        LocalLan.hotspotState.value = HotspotState()
    }

    private fun start() = service.onStartCommand(Intent(), 0, 1)

    @Test fun repeatedStartsShareOneRequestAndDestroyReleasesTheReservation() {
        start()
        start()
        assertEquals(1, LanWifiShadow.requests)
        assertTrue(LocalLan.hotspot.value.starting)
        LanWifiShadow.callback!!.onStarted(Shadow.newInstanceOf(WifiManager.LocalOnlyHotspotReservation::class.java))
        assertEquals("DroidDeck-test", LocalLan.hotspot.value.ssid)
        assertEquals("test-password", LocalLan.hotspot.value.password)
        start()
        assertEquals(1, LanWifiShadow.requests)
        service.onDestroy()
        assertEquals(1, LanReservationShadow.closes)
        assertNull(LocalLan.hotspot.value.password)
    }

    @Test fun aLateReservationAfterShutdownIsClosedWithoutPublishingCredentials() {
        start()
        service.onDestroy()
        LanWifiShadow.callback!!.onStarted(Shadow.newInstanceOf(WifiManager.LocalOnlyHotspotReservation::class.java))
        assertEquals(1, LanReservationShadow.closes)
        assertNull(LocalLan.hotspot.value.ssid)
        assertFalse(LocalLan.hotspot.value.starting)
    }

    @Test fun failureExplainsIncompatibleTethering() {
        start()
        LanWifiShadow.callback!!.onFailed(WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE)
        assertEquals(R.string.lan_incompatible, LocalLan.hotspot.value.error)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun missingPermissionDoesNotRequestWifi() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        start()
        assertEquals(0, LanWifiShadow.requests)
        assertEquals(R.string.lan_permission_needed, LocalLan.hotspot.value.error)
    }

    @Test fun disabledLocationDoesNotRequestWifi() {
        shadowOf(service.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        start()
        assertEquals(0, LanWifiShadow.requests)
        assertEquals(R.string.lan_location_needed, LocalLan.hotspot.value.error)
    }

    @Test fun unsupportedHardwareStopsCleanly() {
        LanWifiShadow.unsupported = true
        start()
        assertEquals(R.string.lan_failed, LocalLan.hotspot.value.error)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}

@Implements(WifiManager::class)
class LanWifiShadow : ShadowWifiManager() {
    @Implementation
    fun startLocalOnlyHotspot(callback: WifiManager.LocalOnlyHotspotCallback, handler: Handler?) {
        if (unsupported) throw UnsupportedOperationException()
        requests++
        Companion.callback = callback
    }

    companion object {
        var callback: WifiManager.LocalOnlyHotspotCallback? = null
        var requests = 0
        var unsupported = false
    }
}

@Implements(WifiManager.LocalOnlyHotspotReservation::class)
class LanReservationShadow {
    @Implementation fun close() { closes++ }

    @Implementation(maxSdk = 29)
    fun getWifiConfiguration() = WifiConfiguration().apply { SSID = "DroidDeck-test"; preSharedKey = "test-password" }

    @Implementation(minSdk = 30)
    fun getSoftApConfiguration(): SoftApConfiguration = Shadow.newInstanceOf(SoftApConfiguration::class.java)

    companion object { var closes = 0 }
}

@Implements(SoftApConfiguration::class, minSdk = 30)
class LanSoftApShadow {
    @Implementation fun getSsid() = "DroidDeck-test"
    @Implementation fun getPassphrase() = "test-password"
}
