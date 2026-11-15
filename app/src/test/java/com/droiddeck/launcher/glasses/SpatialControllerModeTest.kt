package com.droiddeck.launcher.glasses

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SpatialControllerModeTest {
    private fun snapshot(): ByteBuffer = (SpatialTracking::class.java.getDeclaredField("buffer")
        .apply { isAccessible = true }.get(null) as ByteBuffer).duplicate().order(ByteOrder.LITTLE_ENDIAN)

    @Before fun outputOnGlasses() = SpatialTracking.setOutputUsesGlasses(true)

    @After fun cleanUp() {
        SpatialTracking.setSteadyHandAim(false)
        SpatialTracking.setControllers(false, false)
        SpatialTracking.disconnect()
    }

    @Test fun joyConModeStartsStereoWithoutSelectingGestureControllersAndPersistsAcrossLoss() {
        SpatialTracking.connect(0, 1, null, true, 1.6f, false, true)
        assertTrue(SpatialTracking.handsEnabled)
        assertEquals(4, snapshot().getInt(4))
        assertEquals(5, snapshot().getInt(32))
        SpatialTracking.disconnect()
        assertFalse(SpatialTracking.handsEnabled)
        assertEquals(5, snapshot().getInt(32))
        assertEquals(0, snapshot().getInt(76))
        SpatialTracking.setControllers(false, false)
        assertEquals(1, snapshot().getInt(32))
    }

    @Test fun steadyAimIsOptInAndOnlyAppliesToBareHands() {
        assertFalse(GlassesSettings().steadyHandAim)
        SpatialTracking.connect(0, 4, null, true, 1.6f, true)
        assertEquals(3, snapshot().getInt(32))
        SpatialTracking.setSteadyHandAim(true)
        assertEquals(11, snapshot().getInt(32))
        SpatialTracking.setControllers(false, true)
        assertEquals(5, snapshot().getInt(32))
        SpatialTracking.setControllers(true, false)
        assertEquals(11, snapshot().getInt(32))
        SpatialTracking.setSteadyHandAim(false)
        assertEquals(3, snapshot().getInt(32))
        SpatialTracking.connect(0, 5, null, true, 1.6f, true, steadyAim = true)
        assertEquals(11, snapshot().getInt(32))
        SpatialTracking.useLegacyTracking()
        assertEquals(0, snapshot().getInt(32))
    }

    @Test fun openingOtherGlassesClearsTheRetainedUltraMode() {
        SpatialTracking.connect(0, 3, null, true, 1.6f, false, true)
        SpatialTracking.disconnect()
        assertEquals(5, snapshot().getInt(32))
        SpatialTracking.useLegacyTracking()
        assertEquals(0, snapshot().getInt(32))
        assertFalse(SpatialTracking.enabled)
        assertFalse(SpatialTracking.handsEnabled)
    }

    @Test fun switchingControllerModesClearsOldStereoObservations() {
        SpatialTracking.connect(0, 2, null, true, 1.6f, true)
        assertEquals(3, snapshot().getInt(32))
        SpatialTracking.setControllers(false, true)
        assertTrue(SpatialTracking.handsEnabled)
        assertEquals(5, snapshot().getInt(32))
        SpatialTracking.setControllers(true, false)
        assertEquals(3, snapshot().getInt(32))
        SpatialTracking.setControllers(false, false)
        assertFalse(SpatialTracking.handsEnabled)
        assertEquals(1, snapshot().getInt(32))
    }
}
