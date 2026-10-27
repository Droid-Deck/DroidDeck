package com.droiddeck.launcher.gpu

object GpuDeviceState {
    private val lock = Any()
    @Volatile private var lost = false

    @JvmStatic fun isLost(): Boolean = lost

    @JvmStatic fun markLost(): Boolean = synchronized(lock) {
        if (lost) false else { lost = true; true }
    }

    // Session-state callers acquire SessionState before this lock; loss callbacks run after it is released.
    fun whileAlive(action: () -> Unit) {
        synchronized(lock) { if (!lost) action() }
    }
}
