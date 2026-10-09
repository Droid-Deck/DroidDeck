package com.droiddeck.launcher.input

/**
 * The Steam Deck controller's back grips and trackpads, which a handheld's own pad does not have:
 * the second screen's Deck controls (DeckControlsPanel) drive them, and each change goes into the
 * pad's ring ([FakeInputWriter.writeDeckControls]) for libfakeinput's Deck report.
 *
 * Trackpad positions are -1..1 each way with y up, as on a Deck; a pad touched reports its
 * position, a pad clicked also reports full pressure.
 */
object DeckControls {
    /** Bits of the ring's controls word - MUST match DECK_EXTRA_* in fakeinput_steam.cpp. */
    const val L4 = 1
    const val R4 = 2
    const val L5 = 4
    const val R5 = 8
    private const val LEFT_TOUCH = 16
    private const val RIGHT_TOUCH = 32
    private const val LEFT_CLICK = 64
    private const val RIGHT_CLICK = 128

    /** The pad the Deck controller is made from (PadBridge's slot). */
    private const val SLOT = 0

    private var controls = 0
    /** Grips held on a physical pad's extra buttons (PadBridge), kept apart from the panel's. */
    private var padGrips = 0
    private val pads = ShortArray(4)
    private val pressure = ShortArray(2)

    @Synchronized
    fun setGrip(bit: Int, down: Boolean) {
        set(bit, down)
        publish()
    }

    /** A physical pad's extra button (e.g. a Razer Kishi's L4/R4) holding a back grip. */
    @Synchronized
    fun setPadGrip(bit: Int, down: Boolean) {
        val next = if (down) padGrips or bit else padGrips and bit.inv()
        if (next == padGrips) return
        padGrips = next
        publish()
    }

    /** The physical pad's grips let go (session end, the pad stops feeding the game). */
    @Synchronized
    fun releasePadGrips() {
        if (padGrips == 0) return
        padGrips = 0
        publish()
    }

    /** A finger on (or off) one trackpad at x, y in -1..1, y up. */
    @Synchronized
    fun setPad(right: Boolean, touching: Boolean, x: Float = 0f, y: Float = 0f) {
        val at = if (right) 2 else 0
        set(if (right) RIGHT_TOUCH else LEFT_TOUCH, touching)
        pads[at] = if (touching) axis(x) else 0
        pads[at + 1] = if (touching) axis(y) else 0
        publish()
    }

    @Synchronized
    fun setClick(right: Boolean, down: Boolean) {
        set(if (right) RIGHT_CLICK else LEFT_CLICK, down)
        pressure[if (right) 1 else 0] = if (down) Short.MAX_VALUE else 0
        publish()
    }

    /** Everything let go - for when the panel goes away with a finger still on it. */
    @Synchronized
    fun releaseAll() {
        if (controls == 0 && pads.all { it.toInt() == 0 } && pressure.all { it.toInt() == 0 }) return
        controls = 0
        pads.fill(0)
        pressure.fill(0)
        publish()
    }

    private fun set(bit: Int, on: Boolean) {
        controls = if (on) controls or bit else controls and bit.inv()
    }

    private fun axis(value: Float): Short = (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()

    private fun publish() = FakeInputWriter.writeDeckControls(SLOT, controls or padGrips, pads, pressure)
}
