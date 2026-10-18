package com.droiddeck.launcher.glasses

internal class GlassesModeState(private val timeoutMs: Long) {
    enum class Confirmation { NONE, WAITING, CONFIRMED, FAILED }
    data class Device(val productId: Int, val serial: String?)

    private var deadline: Long? = null
    private var device: Device? = null
    private var attached = false
    private var reconnectDeadline: Long? = null
    private var vrOwned = false
    var restorePending = false
        private set
    val pendingStereo: Boolean get() = deadline != null
    val nextCheckAt: Long? get() = listOfNotNull(deadline, reconnectDeadline).minOrNull()

    fun updateDevice(current: Device?, now: Long): Boolean {
        if (reconnectDeadline?.let { now >= it } == true) abandon()
        if (current == null) {
            if (attached) reconnectDeadline = now + timeoutMs
            attached = false
            return false
        }
        val previous = device
        val changed = previous == null || previous.productId != current.productId ||
            (previous.serial != null && current.serial != null && previous.serial != current.serial)
        if (changed) abandon()
        device = if (!changed && current.serial == null) previous else current
        attached = true
        reconnectDeadline = null
        return changed
    }

    private fun abandon() {
        deadline = null
        reconnectDeadline = null
        vrOwned = false
        restorePending = false
    }

    fun manual() {
        vrOwned = false
        restorePending = false
    }

    fun startVr() {
        vrOwned = true
        restorePending = false
    }

    fun stopVr(): Boolean {
        if (vrOwned) {
            vrOwned = false
            restorePending = true
        }
        return restorePending
    }

    fun sent(stereo: Boolean, now: Long) {
        deadline = if (stereo) now + timeoutMs else null
        if (!stereo) restorePending = false
    }

    fun cancelPending() {
        deadline = null
    }

    fun observe(stereo: Boolean, now: Long): Confirmation {
        val until = deadline ?: return Confirmation.NONE
        if (stereo) {
            deadline = null
            return Confirmation.CONFIRMED
        }
        if (now < until) return Confirmation.WAITING
        if (!attached) {
            abandon()
            return Confirmation.NONE
        }
        deadline = null
        vrOwned = false
        restorePending = true
        return Confirmation.FAILED
    }
}
