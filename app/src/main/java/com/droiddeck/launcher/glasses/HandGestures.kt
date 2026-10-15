package com.droiddeck.launcher.glasses

import kotlin.math.*

internal class HandGestures {
    private var grip = false
    private var last: SpatialHand? = null
    fun reset() { grip = false; last = null }
    fun estimate(points: List<SpatialVector>, worldFromCamera: SpatialPose, time: Long): SpatialHand? {
        if (points.size != 21 || points.any { !it.finite() }) return null
        val width = (points[5]-points[17]).length()
        if (width !in 0.035f..0.13f) return null
        val forward = (points[9]-points[0]).normalized() ?: return null
        val lateral = (points[5]-points[17]).normalized() ?: return null
        val up = lateral.cross(forward).normalized() ?: return null
        val right = forward.cross(up).normalized() ?: return null
        val palm = (points[0]+points[5]+points[9]+points[13]+points[17]) * 0.2f
        val pose = worldFromCamera * SpatialPose(palm,SpatialRotation.fromBasis(right,up,forward * -1f))
        last?.let { old ->
            val dt=(time-old.timeNs)/1e9f
            if (dt>0 && dt<0.2f && (pose.position-old.pose.position).length()>0.08f+dt*3f) return null
        }
        val pinch=(points[4]-points[8]).length()/width
        val trigger=((0.55f-pinch)/0.35f).coerceIn(0f,1f)
        val closed=listOf(12 to 9,16 to 13,20 to 17).count { (tip,base) ->
            (points[tip]-points[0]).length() < (points[base]-points[0]).length()*1.35f
        }
        grip=if(grip) closed>=2 else closed==3
        return SpatialHand(pose,time,true,trigger,grip).also { last=it }
    }
}
