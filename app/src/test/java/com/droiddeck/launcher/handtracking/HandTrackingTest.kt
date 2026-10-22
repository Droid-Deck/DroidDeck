package com.droiddeck.launcher.handtracking

import android.Manifest
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HandTrackingTest {
    private val both = HandTracking.Settings(sides = setOf(TrackingCameras.REAR, TrackingCameras.FRONT))

    @Test fun theQuestionComesOnceWhenAHeadsetConnects() {
        assertTrue(HandTracking.shouldAsk(both, headset = true))
        assertFalse(HandTracking.shouldAsk(both, headset = false))
        assertFalse(HandTracking.shouldAsk(both.copy(asked = true), headset = true))
        assertFalse(HandTracking.shouldAsk(HandTracking.Settings(), headset = true))
    }

    @Test fun answersAreRemembered() {
        val context = RuntimeEnvironment.getApplication()
        HandTracking.allow(context, TrackingCameras.FRONT)
        var s = HandTracking.load(context)
        assertTrue(s.asked)
        assertTrue(s.enabled)
        assertEquals(TrackingCameras.FRONT, s.side)
        assertFalse(s.permitted)
        HandTracking.allow(context, null)
        assertEquals(TrackingCameras.FRONT, HandTracking.load(context).side)
        HandTracking.decline(context)
        s = HandTracking.load(context)
        assertTrue(s.asked)
        assertFalse(s.enabled)
        HandTracking.setEnabled(context, true)
        HandTracking.setSide(context, TrackingCameras.REAR)
        s = HandTracking.load(context)
        assertTrue(s.enabled)
        assertEquals(TrackingCameras.REAR, s.side)
    }

    @Test fun trackingNeedsThePermissionACameraAndTheTrackingPack() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(both.copy(enabled = true, downloaded = true).ready)
        assertFalse(both.copy(enabled = true, permitted = true).ready)
        assertTrue(both.copy(enabled = true, permitted = true, downloaded = true).ready)
        assertFalse(HandTracking.Settings(enabled = true, permitted = true, downloaded = true).ready)
        shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
        HandTracking.refreshPermission(context)
        assertTrue(HandTracking.settings.value.permitted)
    }
}
