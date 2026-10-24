package com.droiddeck.xrdiagnostics

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.scenecore.InputEvent

class JetpackProvider(private val activity: ComponentActivity, val session: Session) {
    var headEnabled = false
        private set
    var handsEnabled = false
        private set
    var status = "not configured"
        private set
    var rightPinchEvents = 0
        private set
    private val pinch = floatArrayOf(0f, 0f)
    private val eventReceivedNs = longArrayOf(0, 0)

    fun configure() {
        val messages = mutableListOf<String>()
        headEnabled = false
        handsEnabled = false
        try {
            if (granted(HEAD)) {
                headEnabled = session.configure(Config.Builder(session.config)
                    .setDeviceTracking(DeviceTrackingMode.SPATIAL).build()) is SessionConfigureSuccess
                messages += "ArDevice SPATIAL configured=$headEnabled"
            } else messages += "Head unavailable: HEAD_TRACKING denied"
            if (granted(HAND)) {
                handsEnabled = session.configure(Config.Builder(session.config)
                    .setHandTracking(HandTrackingMode.BOTH).build()) is SessionConfigureSuccess
                messages += "Hand PALM configured=$handsEnabled"
            } else messages += "Raw joints unavailable: HAND_TRACKING not granted"
        } catch (e: Exception) {
            messages += "Configuration unavailable: ${e.javaClass.simpleName}: ${e.message}"
        }
        status = messages.joinToString("; ")
    }

    fun event(event: InputEvent) {
        if (event.source != InputEvent.Source.HANDS) return
        val side = when (event.pointerType) {
            InputEvent.Pointer.LEFT -> 0
            InputEvent.Pointer.RIGHT -> 1
            else -> return
        }
        eventReceivedNs[side] = System.nanoTime()
        when (event.action) {
            InputEvent.Action.DOWN -> { pinch[side] = 1f; if (side == 1) rightPinchEvents++ }
            InputEvent.Action.UP, InputEvent.Action.CANCEL -> pinch[side] = 0f
        }
    }

    fun release() { pinch.fill(0f); eventReceivedNs.fill(0) }

    fun values(now: Long): FloatArray {
        if (headEnabled && !granted(HEAD)) {
            headEnabled = false; release(); status += "; HEAD_TRACKING revoked"
        }
        if (handsEnabled && !granted(HAND)) {
            handsEnabled = false; release(); status += "; HAND_TRACKING revoked"
        }
        val values = FloatArray(30)
        values[24] = 0f; values[25] = Float.NaN
        values[26] = 0f; values[27] = Float.NaN
        // The event timestamp is opaque here. Only callback receipt time uses System.nanoTime.
        for (side in 0..1) {
            if (eventReceivedNs[side] > 0 && now - eventReceivedNs[side] > 2_000_000_000L) pinch[side] = 0f
            values[24 + 2 * side] = pinch[side]
            values[28 + side] = 1f
        }
        val frameAge = session.state.value.timeMark.elapsedNow().inWholeNanoseconds
        if (frameAge !in 0..250_000_000L) return values
        if (headEnabled && granted(HEAD)) {
            val state = ArDevice.getInstance(session).state.value
            putPose(values, 0, state.devicePose, state.trackingState == TrackingState.TRACKING)
        }
        if (handsEnabled && granted(HAND)) {
            listOf(Hand.left(session), Hand.right(session)).forEachIndexed { side, hand ->
                val state = hand.state.value
                state.handJoints[HandJointType.PALM]?.let {
                    putPose(values, 8 + 8 * side, it, state.trackingState == TrackingState.TRACKING)
                }
            }
        }
        return values
    }

    private fun granted(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val HEAD = "android.permission.HEAD_TRACKING"
        const val HAND = "android.permission.HAND_TRACKING"
        fun putPose(values: FloatArray, offset: Int, pose: Pose, tracked: Boolean) {
            if (!tracked) return
            values[offset] = pose.translation.x; values[offset + 1] = pose.translation.y; values[offset + 2] = pose.translation.z
            values[offset + 3] = pose.rotation.x; values[offset + 4] = pose.rotation.y
            values[offset + 5] = pose.rotation.z; values[offset + 6] = pose.rotation.w
            values[offset + 7] = 15f
        }
    }
}
