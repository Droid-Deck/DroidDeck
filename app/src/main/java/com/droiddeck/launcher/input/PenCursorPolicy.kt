package com.droiddeck.launcher.input

class PenCursorPolicy {
    data class Overlay(val visible: Boolean, val mouseOwns: Boolean)

    private var before: Overlay? = null
    private var hidden = false

    fun followsPen(tablet: Boolean, overlay: Overlay): Boolean {
        if (before == null) before = overlay
        hidden = tablet
        return !tablet
    }

    fun changed(overlay: Overlay) {
        if (hidden) before = overlay
    }

    fun mouseRemoved() {
        if (before?.mouseOwns == true) before = Overlay(visible = false, mouseOwns = false)
    }

    fun restoreOnLeave(): Overlay? {
        val restore = before.takeIf { hidden }
        before = null
        hidden = false
        return restore
    }
}
