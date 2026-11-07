package com.droiddeck.xr

import java.util.IdentityHashMap

class XrActivityVisibility {
    private val owners = IdentityHashMap<Any, Boolean>()

    @Synchronized
    fun update(owner: Any, visible: Boolean, publish: (Boolean) -> Unit) {
        if (visible) owners[owner] = true else owners.remove(owner)
        publish(owners.isNotEmpty())
    }
}
