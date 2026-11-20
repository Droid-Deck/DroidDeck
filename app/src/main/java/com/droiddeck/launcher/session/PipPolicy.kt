package com.droiddeck.launcher.session

object PipPolicy {
    fun eligible(running: Boolean, frameSeen: Boolean, phase: SessionPhase, suspended: Boolean, automatic: Boolean): Boolean =
        running && frameSeen && phase in setOf(SessionPhase.READY, SessionPhase.SUSPENDED) &&
            (!automatic || !suspended)

    // A front end or shortcut that launched the game brings it back by moving its task to the
    // front, which Android ignores for a task in picture in picture, and draws its resume controls
    // where the picture would sit. Going home suspends the game for it instead.
    fun autoEnter(enabled: Boolean, launchedByAnotherApp: Boolean): Boolean = enabled && !launchedByAnotherApp

    fun aspect(width: Int, height: Int): Pair<Int, Int> = when {
        width <= 0 || height <= 0 -> 16 to 9
        width.toDouble() / height > 2.39 -> 239 to 100
        height.toDouble() / width > 2.39 -> 100 to 239
        else -> width to height
    }
}
