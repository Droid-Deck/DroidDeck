package com.droiddeck.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaliAutoRouteTest {
    @Test
    fun defaultBridgeIsOwnedByAuto() {
        assertEquals(MaliAutoRoute.BRIDGE, MaliAutoRoute.initialOwnership(true, false, false, false))
    }

    @Test
    fun bridgeFallbackIsOwnedByAuto() {
        assertEquals(MaliAutoRoute.BRIDGE, MaliAutoRoute.fallbackOwnership(false, false, false, false, false))
    }

    @Test
    fun explicitBridgeStaysUserOwned() {
        assertEquals(MaliAutoRoute.NONE, MaliAutoRoute.initialOwnership(true, true, false, false))
        assertEquals(MaliAutoRoute.NONE, MaliAutoRoute.fallbackOwnership(false, true, true, false, false))
    }

    @Test
    fun explicitVenusStaysUserOwned() {
        assertEquals(MaliAutoRoute.NONE, MaliAutoRoute.initialOwnership(false, false, true, true))
        assertEquals(MaliAutoRoute.NONE, MaliAutoRoute.fallbackOwnership(true, false, false, true, true))
    }

    @Test
    fun installedMaliPairEndsAutoOwnedRoute() {
        assertTrue(MaliAutoRoute.shouldEnd(MaliAutoRoute.BRIDGE, true, true))
    }

    @Test
    fun installedAdrenoPairDoesNotEndRoute() {
        assertFalse(MaliAutoRoute.shouldEnd(MaliAutoRoute.BRIDGE, false, true))
    }
}
