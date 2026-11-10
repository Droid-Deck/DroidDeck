package com.droiddeck.launcher.input

class PointerButtonState(private val send: (Owner, Int, Boolean) -> Unit) {
    enum class Owner { MOUSE, TOUCHPAD, SIMULATED }
    private val held = mutableMapOf<Int, MutableSet<Owner>>()
    private val routes = mutableMapOf<Int, Owner>()

    fun set(owner: Owner, code: Int, down: Boolean) {
        val owners = if (down) held.getOrPut(code) { mutableSetOf() } else held[code] ?: return
        if (down) {
            if (!owners.add(owner)) return
            if (owners.size == 1) {
                routes[code] = owner
                send(owner, code, true)
            }
        } else {
            if (!owners.remove(owner)) return
            if (owners.isEmpty()) {
                held.remove(code)
                routes.remove(code)?.let { send(it, code, false) }
            }
        }
    }

    fun release(owner: Owner) {
        held.keys.toList().forEach { set(owner, it, false) }
    }

    fun releaseAll() {
        Owner.entries.forEach(::release)
    }
}
