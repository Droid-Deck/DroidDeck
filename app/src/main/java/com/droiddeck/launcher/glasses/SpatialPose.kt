package com.droiddeck.launcher.glasses

import kotlin.math.*

data class SpatialVector(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    operator fun plus(b: SpatialVector) = SpatialVector(x + b.x, y + b.y, z + b.z)
    operator fun minus(b: SpatialVector) = SpatialVector(x - b.x, y - b.y, z - b.z)
    operator fun times(s: Float) = SpatialVector(x * s, y * s, z * s)
    fun dot(b: SpatialVector) = x * b.x + y * b.y + z * b.z
    fun cross(b: SpatialVector) = SpatialVector(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)
    fun length() = sqrt(dot(this))
    fun normalized() = length().takeIf { it > 1e-6f }?.let { this * (1f / it) }
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
    fun array() = floatArrayOf(x, y, z)
}

data class SpatialRotation(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val w: Float = 1f) {
    fun normalized(): SpatialRotation? {
        val n = sqrt(x*x + y*y + z*z + w*w)
        return if (n.isFinite() && n in 0.5f..1.5f) SpatialRotation(x/n, y/n, z/n, w/n) else null
    }
    fun inverse() = SpatialRotation(-x, -y, -z, w)
    operator fun times(b: SpatialRotation) = SpatialRotation(
        w*b.x + x*b.w + y*b.z - z*b.y, w*b.y - x*b.z + y*b.w + z*b.x,
        w*b.z + x*b.y - y*b.x + z*b.w, w*b.w - x*b.x - y*b.y - z*b.z)
    fun apply(v: SpatialVector): SpatialVector {
        val q = SpatialVector(x, y, z)
        val t = q.cross(v) * 2f
        return v + t * w + q.cross(t)
    }
    fun matrix() = floatArrayOf(
        1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w),
        2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w),
        2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y))
    fun yaw(): SpatialRotation {
        val forward = apply(SpatialVector(0f, 0f, -1f))
        val a = if (forward.x*forward.x + forward.z*forward.z < 1e-8f) {
            val right = apply(SpatialVector(x = 1f))
            atan2(-right.z, right.x) / 2
        } else atan2(-forward.x, -forward.z) / 2
        return SpatialRotation(y = sin(a), w = cos(a))
    }
    companion object {
        fun fromBasis(right: SpatialVector, up: SpatialVector, back: SpatialVector): SpatialRotation {
            val m = floatArrayOf(right.x, up.x, back.x, right.y, up.y, back.y, right.z, up.z, back.z)
            val trace = m[0] + m[4] + m[8]
            val q = if (trace > 0) {
                val s = sqrt(trace + 1) * 2
                SpatialRotation((m[7]-m[5])/s, (m[2]-m[6])/s, (m[3]-m[1])/s, s/4)
            } else {
                val i = if (m[0] > m[4] && m[0] > m[8]) 0 else if (m[4] > m[8]) 1 else 2
                val j = (i+1)%3; val k = (i+2)%3
                val s = sqrt(1 + m[i*3+i] - m[j*3+j] - m[k*3+k]) * 2
                val v = FloatArray(3)
                v[i] = s/4; v[j] = (m[j*3+i]+m[i*3+j])/s; v[k] = (m[k*3+i]+m[i*3+k])/s
                SpatialRotation(v[0], v[1], v[2], (m[k*3+j]-m[j*3+k])/s)
            }
            return q.normalized() ?: SpatialRotation()
        }
    }
}

data class SpatialPose(val position: SpatialVector = SpatialVector(), val rotation: SpatialRotation = SpatialRotation()) {
    fun inverse(): SpatialPose {
        val r = rotation.inverse()
        return SpatialPose(r.apply(position * -1f), r)
    }
    operator fun times(b: SpatialPose) = SpatialPose(position + rotation.apply(b.position), rotation * b.rotation)
    companion object {
        fun fromSdk(v: FloatArray): SpatialPose? {
            if (v.size != 7 || v.any { !it.isFinite() }) return null
            val q = SpatialRotation(v[4], v[5], v[6], v[3]).normalized() ?: return null
            return SpatialPose(SpatialVector(v[0], v[1], v[2]), q)
        }
    }
}

data class SpatialSample(val pose: SpatialPose, val timeNs: Long, val generation: Long, val valid: Boolean)
data class SpatialHand(val pose: SpatialPose = SpatialPose(), val timeNs: Long = 0, val valid: Boolean = false,
                       val trigger: Float = 0f, val grip: Boolean = false,
                       val triggerValid: Boolean = valid, val gripValid: Boolean = valid) {
    fun flags() = if (!valid) 0 else 1 or (if (triggerValid) 2 else 0) or (if (gripValid) 4 else 0)
}

class SpatialSession {
    var generation: Long = 0; private set
    var originGeneration: Long = 0; private set
    var trackingFrameGeneration: Long = 0; private set
    var needsRecenter = false; private set
    val recenterPending: Boolean get() = pendingRecenter != null
    private var pendingRecenter: Boolean? = null
    private var recoveryAnchor: SpatialSample? = null
    private var head: SpatialSample? = null
    private var lastStable: SpatialSample? = null
    private var vrOrigin: SpatialPose? = null
    private var screenOrigin: SpatialPose? = null
    private var hands = arrayOf(SpatialHand(), SpatialHand())
    private val gestureEpochs = IntArray(4)
    private val gestureEligible = BooleanArray(4)
    private var inputsAfterNs = 0L
    private var height = 1.6f
    private val history = ArrayDeque<SpatialSample>()

    @Synchronized fun reset(connection: Long, eyeHeight: Float, requireSetup: Boolean = false) {
        generation = connection; height = eyeHeight.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.5f) ?: 1.6f
        originGeneration++; trackingFrameGeneration++; head = null; lastStable = null; vrOrigin = null; screenOrigin = null
        history.clear()
        pendingRecenter = null; recoveryAnchor = null
        hands = arrayOf(SpatialHand(), SpatialHand()); needsRecenter = requireSetup
        gestureEpochs.fill(0); gestureEligible.fill(false)
        inputsAfterNs = 0L
    }
    @Synchronized fun accept(sample: SpatialSample): Boolean {
        if (sample.generation != generation || sample.timeNs <= (head?.timeNs ?: 0)) return false
        val previous = head
        if (previous != null && sample.timeNs-previous.timeNs > 100_000_000L) invalidateHeadInputs(sample.timeNs)
        val rotation = sample.pose.rotation.normalized()
        val current = sample.copy(pose = sample.pose.copy(rotation = rotation ?: SpatialRotation()),
            valid = sample.valid && sample.pose.position.finite() && rotation != null)
        head = current
        if (!current.valid) {
            recoveryAnchor = null; invalidateHeadInputs(current.timeNs); return false
        }
        lastStable?.let {
            val d = (current.pose.position - it.pose.position).length()
            val a = current.pose.rotation; val b = it.pose.rotation
            if (!needsRecenter && (d > 0.5f || abs(a.x*b.x+a.y*b.y+a.z*b.z+a.w*b.w) < 0.9238795f)) {
                needsRecenter = true; trackingFrameGeneration++
            }
        }
        pendingRecenter?.let { screen ->
            val anchor = recoveryAnchor
            val a = current.pose.rotation; val b = anchor?.pose?.rotation ?: a
            if (anchor == null || previous?.valid != true || current.timeNs-previous.timeNs > 100_000_000L ||
                (current.pose.position-anchor.pose.position).length() > 0.05f ||
                abs(a.x*b.x+a.y*b.y+a.z*b.z+a.w*b.w) < 0.9961947f) {
                recoveryAnchor = current
            } else if (current.timeNs-anchor.timeNs >= 250_000_000L) {
                applyRecenter(screen, current)
            }
        }
        if (needsRecenter || recenterPending) { invalidateHeadInputs(current.timeNs); return false }
        lastStable = current
        history.addLast(current)
        while (history.size > 512) history.removeFirst()
        if (vrOrigin == null) vrOrigin = SpatialPose(current.pose.position - SpatialVector(y = height), current.pose.rotation.yaw())
        if (screenOrigin == null) screenOrigin = current.pose
        return true
    }
    @Synchronized fun setFloor(eyeHeight: Float, now: Long = System.nanoTime()) {
        if (!eyeHeight.isFinite()) return
        height = eyeHeight.coerceIn(0.5f, 2.5f)
        val p = head?.takeIf { it.valid && now-it.timeNs in 0..100_000_000L }?.pose ?: return
        vrOrigin = SpatialPose(p.position - SpatialVector(y = height), p.rotation.yaw())
        if (needsRecenter) { history.clear(); screenOrigin = p }
        lastStable = head; needsRecenter = false; pendingRecenter = null; recoveryAnchor = null
        originGeneration++; invalidateHeadInputs(now)
    }
    @Synchronized fun recenter(screen: Boolean, now: Long = System.nanoTime()) {
        val current = head?.takeIf { it.valid && now-it.timeNs in 0..100_000_000L }
        if (current == null || needsRecenter || recenterPending) {
            if (pendingRecenter == null) recoveryAnchor = null
            pendingRecenter = screen
            invalidateHeadInputs(now)
            return
        }
        applyRecenter(screen, current)
    }
    private fun applyRecenter(screen: Boolean, current: SpatialSample) {
        val p = current.pose
        if (vrOrigin == null) vrOrigin = SpatialPose(p.position - SpatialVector(y = height), p.rotation.yaw())
        if (screenOrigin == null) screenOrigin = p
        if (needsRecenter) {
            vrOrigin = SpatialPose(p.position - SpatialVector(y = height), p.rotation.yaw())
            screenOrigin = p; originGeneration++; history.clear()
        }
        if (screen) screenOrigin = p else {
            val floor = vrOrigin?.position?.y ?: (p.position.y-height)
            vrOrigin = SpatialPose(SpatialVector(p.position.x, floor, p.position.z), p.rotation.yaw())
            originGeneration++
        }
        lastStable = current; needsRecenter = false; pendingRecenter = null; recoveryAnchor = null
        invalidateHeadInputs(current.timeNs)
    }
    @Synchronized fun view(screen: Boolean): SpatialPose? = lastStable?.pose?.let {
        (if (screen) screenOrigin else vrOrigin)?.inverse()?.times(it)
    }
    @Synchronized fun latest() = head
    @Synchronized fun poseAt(time: Long, connection: Long): SpatialPose? {
        if (connection != generation || needsRecenter || recenterPending) return null
        return history.minByOrNull { abs(it.timeNs-time) }?.takeIf { abs(it.timeNs-time) <= 12_000_000L }?.pose
    }
    @Synchronized fun valid(now: Long) = !needsRecenter && !recenterPending && head?.let { it.valid && now-it.timeNs in 0..100_000_000L } == true
    @Synchronized internal fun isCurrentTrackingFrame(connection: Long, frame: Long, clock: () -> Long = System::nanoTime): Boolean =
        connection == generation && frame == trackingFrameGeneration && valid(clock())
    @Synchronized fun setHands(connection: Long, left: SpatialHand, right: SpatialHand) {
        if (connection != generation || needsRecenter || recenterPending) return
        val next = arrayOf(left, right)
        for (index in 0..1) {
            val oldTime = hands[index].timeNs
            val hand = next[index]
            if (oldTime > 0 && (hand.timeNs < oldTime || hand.timeNs-oldTime > HandObservation.MAX_AGE_NS))
                invalidateInputs(index)
            updateInputs(index, hand, head?.valid == true && usableHand(hand) && hand.timeNs > inputsAfterNs)
        }
        hands = next
    }
    @Synchronized fun hand(index: Int, now: Long): SpatialHand {
        val h = hands[index]
        val origin = vrOrigin
        val current = origin != null && usableHand(h) && valid(now) && now-h.timeNs in 0..HandObservation.MAX_AGE_NS
        updateInputs(index, h, current && h.timeNs > inputsAfterNs)
        return if (origin == null) SpatialHand() else h.copy(pose = origin.inverse() * h.pose, valid = current,
            triggerValid = h.triggerValid && gestureEligible[index*2], gripValid = h.gripValid && gestureEligible[index*2+1])
    }
    @Synchronized fun inputEpochs(): Int {
        var packed = 0
        for (index in gestureEpochs.indices) packed = packed or (gestureEpochs[index] shl (index*8))
        return packed
    }
    private fun usableHand(hand: SpatialHand): Boolean {
        val q = hand.pose.rotation
        val norm = q.x*q.x+q.y*q.y+q.z*q.z+q.w*q.w
        return hand.valid && hand.timeNs > 0 && hand.pose.position.finite() && norm > 0.99f && norm < 1.01f
    }
    private fun updateInputs(index: Int, hand: SpatialHand, valid: Boolean) {
        updateInput(index*2, valid && hand.triggerValid && hand.trigger.isFinite() && hand.trigger in 0f..1f)
        updateInput(index*2+1, valid && hand.gripValid)
    }
    private fun updateInput(index: Int, eligible: Boolean) {
        if (gestureEligible[index] && !eligible) gestureEpochs[index] = (gestureEpochs[index]+1) and 0xff
        gestureEligible[index] = eligible
    }
    private fun invalidateInputs(index: Int? = null) {
        if (index == null) for (gesture in gestureEligible.indices) updateInput(gesture, false)
        else { updateInput(index*2, false); updateInput(index*2+1, false) }
    }
    private fun invalidateHeadInputs(time: Long) {
        inputsAfterNs = maxOf(inputsAfterNs, time)
        hands = arrayOf(SpatialHand(), SpatialHand())
        invalidateInputs()
    }
}

internal data class HandObservation(val timeNs: Long, val connection: Long, val trackingFrame: Long) {
    fun headPose(session: SpatialSession, cameraSynchronized: Boolean, now: Long? = null): SpatialPose? = synchronized(session) {
        val checkedAt=now ?: System.nanoTime()
        if (!cameraSynchronized || timeNs <= 0 || checkedAt-timeNs !in 0..MAX_AGE_NS ||
            !session.isCurrentTrackingFrame(connection, trackingFrame) { checkedAt }) null
        else session.poseAt(timeNs, connection)
    }
    companion object {
        const val MAX_AGE_NS = 200_000_000L
    }
}

internal class CameraClock {
    private val offsets = ArrayDeque<Long>()
    private var lastSource = 0L
    private var lastReceipt = 0L
    fun map(source: Long, receipt: Long): Long? {
        if (source <= 0 || receipt <= 0) {
            offsets.clear();lastSource=0;lastReceipt=0
            return null
        }
        val offset = receipt-source
        val skipped = lastSource > 0 && source-lastSource > 200_000_000L
        val inconsistent = skipped && offsets.minOrNull()?.let { abs(offset-it) > 20_000_000L } == true
        if (source <= lastSource || receipt <= lastReceipt || inconsistent) offsets.clear()
        lastSource = source;lastReceipt = receipt
        offsets.addLast(offset)
        while (offsets.size > 64) offsets.removeFirst()
        if (offsets.size < 16) return null
        val sorted = offsets.sorted()
        if (sorted[sorted.size*9/10]-sorted.first() > 20_000_000L) return null
        return source+sorted.first()
    }
}
