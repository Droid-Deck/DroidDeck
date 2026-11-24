package com.droiddeck.launcher.session

class ContentCadence {
    var rate = 0
        private set
    private var candidate = 0
    private var streak = 0
    private var misses = 0
    private val reengage = HashMap<Int, Int>()

    fun note(fps: Int, panelRates: IntArray): Boolean {
        val snapped = snap(fps, panelRates)
        if (rate != 0) {
            if (snapped == rate) {
                misses = 0
                return false
            }
            if (++misses < RELEASE_SAMPLES) return false
            reengage[rate] = minOf((reengage[rate] ?: ENGAGE_SAMPLES) * 2, MAX_ENGAGE_SAMPLES)
            rate = 0
            misses = 0
            candidate = snapped
            streak = if (snapped > 0) 1 else 0
            return true
        }
        if (snapped == 0 || snapped != candidate) {
            candidate = snapped
            streak = if (snapped > 0) 1 else 0
            return false
        }
        if (++streak < (reengage[snapped] ?: ENGAGE_SAMPLES)) return false
        rate = snapped
        misses = 0
        return true
    }

    fun reset(): Boolean {
        val engaged = rate != 0
        rate = 0
        candidate = 0
        streak = 0
        misses = 0
        return engaged
    }

    companion object {
        const val ENGAGE_SAMPLES = 3
        const val RELEASE_SAMPLES = 3
        const val MAX_ENGAGE_SAMPLES = 30
        private const val MIN_FPS = 20
        private const val MAX_DIVISOR = 6

        fun snap(fps: Int, panelRates: IntArray): Int {
            if (fps < MIN_FPS) return 0
            var best = 0
            for (hz in panelRates) for (divisor in 1..MAX_DIVISOR) {
                if (hz % divisor != 0) continue
                val step = hz / divisor
                val miss = Math.abs(step - fps)
                if (miss <= 1 && (best == 0 || miss < Math.abs(best - fps) || (miss == Math.abs(best - fps) && step > best))) best = step
            }
            return best
        }
    }
}
