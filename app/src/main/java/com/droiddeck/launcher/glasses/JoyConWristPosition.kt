package com.droiddeck.launcher.glasses

internal class JoyConWristPosition {
    private var last: SpatialHand? = null
    var lastOutcome = HandObservationOutcome.WRIST_LANDMARK_MISSING; private set

    fun reset() { last = null }

    private fun reject(outcome: HandObservationOutcome): SpatialHand? {
        lastOutcome = outcome
        return null
    }

    fun estimate(points: List<SpatialVector?>, worldFromCamera: SpatialPose, time: Long): SpatialHand? {
        val wrist = points.firstOrNull()?.takeIf(SpatialVector::finite)
            ?: return reject(HandObservationOutcome.WRIST_LANDMARK_MISSING)
        val pose = worldFromCamera * SpatialPose(wrist)
        last?.let { old ->
            val dt = (time - old.timeNs) / 1e9f
            if (dt <= 0 || (dt < .2f && (pose.position - old.pose.position).length() > .08f + dt * 3f))
                return reject(HandObservationOutcome.WRIST_MOTION_REJECTED)
        }
        lastOutcome = HandObservationOutcome.ACCEPTED
        return SpatialHand(pose, time, true, triggerValid = false, gripValid = false).also { last = it }
    }
}
