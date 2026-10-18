package com.droiddeck.launcher.glasses

import kotlin.math.*

internal class HandGestures(private val rightHand: Boolean) {
    private var grip = false
    private var last: SpatialHand? = null
    var lastOutcome = HandObservationOutcome.LANDMARK_COUNT_INVALID; private set
    fun reset() { grip = false; last = null }
    fun resetAfterRejection(outcome: HandObservationOutcome) {
        if (outcome != HandObservationOutcome.PALM_MOTION_REJECTED) reset()
    }
    private fun reject(outcome: HandObservationOutcome): SpatialHand? { lastOutcome = outcome; return null }
    fun estimate(points: List<SpatialVector?>, worldFromCamera: SpatialPose, time: Long,
                 leftImage: List<SpatialVector>? = null, rightImage: List<SpatialVector>? = null): SpatialHand? {
        if (points.size != 21) return reject(HandObservationOutcome.LANDMARK_COUNT_INVALID)
        val p = points.map { it?.takeIf(SpatialVector::finite) }
        val wrist = p[0] ?: return reject(HandObservationOutcome.PALM_LANDMARK_MISSING)
        val index = p[5] ?: return reject(HandObservationOutcome.PALM_LANDMARK_MISSING)
        val middle = p[9] ?: return reject(HandObservationOutcome.PALM_LANDMARK_MISSING)
        val ring = p[13] ?: return reject(HandObservationOutcome.PALM_LANDMARK_MISSING)
        val little = p[17] ?: return reject(HandObservationOutcome.PALM_LANDMARK_MISSING)
        val width = (index-little).length()
        if (width !in 0.035f..0.13f) return reject(HandObservationOutcome.PALM_WIDTH_INVALID)
        val forward = (middle-wrist).normalized() ?: return reject(HandObservationOutcome.PALM_BASIS_INVALID)
        val lateral = (if (rightHand) little-index else index-little).normalized() ?: return reject(HandObservationOutcome.PALM_BASIS_INVALID)
        val up = lateral.cross(forward).normalized() ?: return reject(HandObservationOutcome.PALM_BASIS_INVALID)
        val right = forward.cross(up).normalized() ?: return reject(HandObservationOutcome.PALM_BASIS_INVALID)
        val palm = (wrist+index+middle+ring+little) * 0.2f
        val pose = worldFromCamera * SpatialPose(palm,SpatialRotation.fromBasis(right,up,forward * -1f))
        last?.let { old ->
            val dt=(time-old.timeNs)/1e9f
            if (dt>0 && dt<0.2f && (pose.position-old.pose.position).length()>0.08f+dt*3f)
                return reject(HandObservationOutcome.PALM_MOTION_REJECTED)
        }
        val stereo = if (leftImage != null || rightImage != null)
            StereoFingerGestures.estimate(leftImage,rightImage,grip) else null
        val triggerValid = stereo?.triggerValid ?: (p[4] != null && p[8] != null)
        val trigger = stereo?.trigger ?: if (triggerValid) ((0.55f-(p[4]!!-p[8]!!).length()/width)/0.35f).coerceIn(0f,1f) else 0f
        val gripValid = stereo?.gripValid ?: listOf(12,16,20).all { p[it] != null }
        val closed = if (stereo == null && gripValid) listOf(12 to 9,16 to 13,20 to 17).count { (tip,base) ->
            (p[tip]!!-wrist).length() < (p[base]!!-wrist).length()*1.35f
        } else 0
        grip=stereo?.grip ?: (gripValid && if(grip) closed>=2 else closed==3)
        lastOutcome = HandObservationOutcome.ACCEPTED
        return SpatialHand(pose,time,true,trigger,grip,triggerValid,gripValid).also { last=it }
    }
}
