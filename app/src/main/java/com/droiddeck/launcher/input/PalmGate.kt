package com.droiddeck.launcher.input

internal class PalmGate(val log: (String) -> Unit) {
    enum class Pen { OUT, HOVER, CONTACT }

    var enabled = true
    private var pen = Pen.OUT
    private var device = Int.MIN_VALUE
    private var sampleTime = 0L
    private var outTime = NEVER
    private val cancelers = mutableListOf<(Long) -> Int>()

    fun register(cancelFingers: (Long) -> Int) {
        cancelers += cancelFingers
    }

    fun expire(time: Long) {
        if (pen != Pen.HOVER || time - sampleTime < PenStream.HOVER_TIMEOUT_MS) return
        pen = Pen.OUT
        outTime = sampleTime
        log("palm: pen hover timed out")
    }

    fun armed(time: Long): Boolean = enabled && (pen != Pen.OUT || time - outTime < GRACE_MS)

    fun onPen(time: Long, next: Pen, deviceId: Int) {
        expire(time)
        val arming = enabled && !armed(time)
        pen = next
        device = deviceId
        sampleTime = time
        if (!arming) return
        val cancelled = cancelers.sumOf { it(time) }
        if (cancelled > 0) log("palm: pen near, cancelled $cancelled finger(s)")
    }

    fun leave(time: Long) {
        pen = Pen.OUT
        outTime = time
    }

    fun forceOut(now: Long) {
        expire(now)
        if (pen != Pen.OUT) leave(now)
    }

    fun deviceRemoved(deviceId: Int, now: Long) {
        if (deviceId == device) forceOut(now)
    }

    companion object {
        const val GRACE_MS = 400L
        private const val NEVER = Long.MIN_VALUE / 2
    }
}
