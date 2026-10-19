package com.droiddeck.launcher.input

object DeckControls {
    const val L4 = 1
    const val R4 = 2
    const val L5 = 4
    const val R5 = 8
    private const val LEFT_TOUCH = 16
    private const val RIGHT_TOUCH = 32
    private const val LEFT_CLICK = 64
    private const val RIGHT_CLICK = 128
    const val MUTE = 256
    const val FULL_TOUCHPAD = 512

    @Volatile var externalImuActive: Boolean = false

    private const val SLOT = 0

    private var controls = 0
    private val pads = ShortArray(4)
    private val pressure = ShortArray(2)

    @Synchronized
    fun setGrip(bit: Int, down: Boolean) {
        set(bit, down)
        publish()
    }

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

    @Synchronized
    fun setDualSenseTouch(x0: Short, y0: Short, down0: Boolean, x1: Short, y1: Short, down1: Boolean, click: Boolean) {
        set(LEFT_TOUCH, down0)
        set(RIGHT_TOUCH, down1)
        set(FULL_TOUCHPAD, down0 || down1)
        set(LEFT_CLICK or RIGHT_CLICK, click)
        pads[0] = if (down0) x0 else 0
        pads[1] = if (down0) y0 else 0
        pads[2] = if (down1) x1 else 0
        pads[3] = if (down1) y1 else 0
        pressure[0] = if (click) Short.MAX_VALUE else 0
        pressure[1] = 0
        publish()
    }

    @Synchronized
    fun setDualSenseExtras(mute: Boolean, l4: Boolean, r4: Boolean, l5: Boolean, r5: Boolean) {
        set(MUTE, mute)
        set(L4, l4)
        set(R4, r4)
        set(L5, l5)
        set(R5, r5)
        publish()
    }

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

    private fun publish() = FakeInputWriter.writeDeckControls(SLOT, controls, pads, pressure)
}
