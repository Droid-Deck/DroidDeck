package com.droiddeck.launcher.session

internal object LinuxWindowPresentation {
    enum class State { WAITING, PREPARED, FAILED }

    fun state(stats: LongArray, size: LinuxWindowSize): State {
        if (stats.size < 12 || stats[0] != size.generation ||
            stats[1] != size.width.toLong() || stats[2] != size.height.toLong()) return State.WAITING
        return when {
            stats[10] < 0 -> State.FAILED
            stats[10] > 0 -> State.PREPARED
            else -> State.WAITING
        }
    }
}
