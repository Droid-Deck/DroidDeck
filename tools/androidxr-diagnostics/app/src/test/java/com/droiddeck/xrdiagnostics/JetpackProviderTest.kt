package com.droiddeck.xrdiagnostics

import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.arcore.TrackingState
import androidx.xr.arcore.testing.ArCoreTestRule
import androidx.xr.runtime.Config
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class JetpackProviderTest {
    @get:Rule val arCore = ArCoreTestRule()

    @Test fun injectedPalmsStaySeparateAndHiddenHandStaysUnavailable() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        shadowOf(activity.application).grantPermissions(JetpackProvider.HAND)
        controller.create().start().resume()
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session = (Session.create(activity, coroutineContext = dispatcher) as SessionCreateSuccess).session
            session.configure(Config.Builder(session.config).setHandTracking(HandTrackingMode.BOTH).build())
            arCore.leftHandTester.isVisible = true
            arCore.rightHandTester.isVisible = true
            arCore.leftHandTester.handJointMap = mapOf(HandJointType.PALM to Pose(Vector3(-.25f, 1f, -.5f)))
            arCore.rightHandTester.handJointMap = mapOf(HandJointType.PALM to Pose(Vector3(.4f, 1.2f, -.7f)))
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            val values = FloatArray(30)
            listOf(Hand.left(session), Hand.right(session)).forEachIndexed { side, hand ->
                val state = hand.state.value
                JetpackProvider.putPose(values, 8 + 8 * side, state.handJoints.getValue(HandJointType.PALM),
                    state.trackingState == TrackingState.TRACKING)
            }
            assertEquals(-.25f, values[8], 0f)
            assertEquals(.4f, values[16], 0f)
            arCore.leftHandTester.isVisible = false
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            val hidden = FloatArray(30)
            val state = Hand.left(session).state.value
            JetpackProvider.putPose(hidden, 8, Pose(), state.trackingState == TrackingState.TRACKING)
            assertEquals(0f, hidden[15], 0f)
        } finally { controller.pause().stop().destroy() }
    }
}
