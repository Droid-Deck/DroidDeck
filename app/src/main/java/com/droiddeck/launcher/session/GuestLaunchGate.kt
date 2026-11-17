package com.droiddeck.launcher.session

internal class GuestLaunchGate {
    private val lock = Object()
    private var pending = 0

    fun begin() = synchronized(lock) { pending++ }

    fun end() = synchronized(lock) {
        pending--
        lock.notifyAll()
    }

    fun await() = synchronized(lock) {
        while (pending > 0) lock.wait()
    }
}
