package com.droiddeck.launcher.session

/** Platform-independent eligibility; a service existing alone does not mean it has a picture. */
object PipPolicy {
    fun eligible(running: Boolean, frameSeen: Boolean, phase: SessionPhase, suspended: Boolean, automatic: Boolean): Boolean =
        running && frameSeen && phase in setOf(SessionPhase.READY, SessionPhase.SUSPENDED) &&
            (!automatic || !suspended)

    fun aspect(width: Int, height: Int): Pair<Int, Int> = when {
        width <= 0 || height <= 0 -> 16 to 9
        width.toDouble() / height > 2.39 -> 239 to 100
        height.toDouble() / width > 2.39 -> 100 to 239
        else -> width to height
    }
}
