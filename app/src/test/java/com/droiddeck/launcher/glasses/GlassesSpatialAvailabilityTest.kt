package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test

class GlassesSpatialAvailabilityTest {
    private val ultra = GlassesState(connected = true, controllable = true, supportsSpatial = true)
    private val selected = GlassesSettings(positionalTracking = true, handControllers = true)

    @Test fun sdkConfirmedConnectedUltraOwnsSpatialControllerInput() {
        assertTrue(ultra.spatialConnected)
        assertTrue(ultra.usesSpatialControllers(selected))
        assertTrue(ultra.usesSpatialControllers(selected.copy(handControllers = false, joyConTracking = true)))
        assertFalse(ultra.usesSpatialControllers(selected.copy(positionalTracking = false)))
        assertFalse(ultra.usesSpatialControllers(selected.copy(handControllers = false)))
    }

    @Test fun savedUltraPreferencesDoNotSelectSpatialInputForOtherGlasses() {
        for (name in listOf("VITURE Luma Ultra", "VITURE Luma Pro", "VITURE Pro", "XREAL", "RayNeo")) {
            val other = ultra.copy(model = name, supportsSpatial = false)
            assertFalse(name, other.spatialConnected)
            assertFalse(name, other.usesSpatialControllers(selected))
            assertFalse(name, other.usesSpatialControllers(selected.copy(handControllers = false, joyConTracking = true)))
        }
    }

    @Test fun disconnectedOrUncontrollableUltraCannotClaimSpatialInput() {
        for (state in listOf(ultra.copy(connected = false), ultra.copy(controllable = false), GlassesState())) {
            assertFalse(state.spatialConnected)
            assertFalse(state.usesSpatialControllers(selected))
        }
    }
}
