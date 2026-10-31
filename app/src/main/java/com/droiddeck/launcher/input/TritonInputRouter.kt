package com.droiddeck.launcher.input

import kotlin.math.roundToInt

class TritonInputRouter(private val router: InputRouter) {
    private val channels = InputControl.entries.map { router.channel(InputRole.PHYSICAL, it) }.toTypedArray()
    private var last: ByteArray? = null
    private var buffer = ByteArray(45)
    private val snapshot: () -> Unit = { last?.let { read(it, false) } }

    init { router.beforeProfileChange(snapshot) }

    fun route(data: ByteArray): ByteArray = synchronized(router) {
        if (data.size < 45) return@synchronized data
        last = data
        if (channels.all { it.nativeIdentity() }) return@synchronized data
        if (buffer.size != data.size) buffer = ByteArray(data.size)
        data.copyInto(buffer)
        putBits(buffer, bits(data) and CONTROL_BITS.inv())
        for (offset in 5..27 step 2) putShort(buffer, offset, 0)
        read(data, true)
        buffer
    }

    private fun read(data: ByteArray, emit: Boolean) {
        val bits = bits(data)
        for (control in InputControl.entries) {
            val channel = channels[control.ordinal]
            val offset = offset(control)
            val pair = control.dimensions == 2
            val x = if (offset != 0) axis(data, offset) else if (bits and bit(control) != 0) 1f else 0f
            val y = if (pair) axis(data, offset + 2) * if (control.isTrackpad) 1f else -1f else 0f
            val contact = control.isTrackpad && bits and bit(control) != 0
            if (!emit) { channel.observe(x, y, contact); continue }
            val target = channel.update(x, y, contact) ?: continue
            val targetOffset = offset(target)
            if (targetOffset != 0) {
                val value = shortValue(x)
                if (target.dimensions == 2) {
                    val second = shortValue(y * if (target.isTrackpad) 1f else -1f)
                    val oldX = short(buffer, targetOffset).toLong()
                    val oldY = short(buffer, targetOffset + 2).toLong()
                    if (value.toLong() * value + second.toLong() * second >= oldX * oldX + oldY * oldY) {
                        putShort(buffer, targetOffset, value)
                        putShort(buffer, targetOffset + 2, second)
                    }
                    val touch = if (control.isTrackpad) contact else bits and bit(control) != 0
                    if (touch) putBits(buffer, bits(buffer) or bit(target))
                } else {
                    if (value >= short(buffer, targetOffset)) putShort(buffer, targetOffset, value)
                    if (bits and bit(control) != 0) putBits(buffer, bits(buffer) or bit(target))
                }
            } else if (x > 0.5f) putBits(buffer, bits(buffer) or bit(target))
            if (target == InputControl.LEFT_CLICK || target == InputControl.RIGHT_CLICK) {
                val pressureOffset = if (target == InputControl.LEFT_CLICK) 21 else 27
                val from = if (control == InputControl.LEFT_CLICK) 21 else if (control == InputControl.RIGHT_CLICK) 27 else 0
                if (from != 0) putShort(buffer, pressureOffset, short(data, from))
                else if (x > 0.5f) putShort(buffer, pressureOffset, 32767)
            }
        }
    }

    fun release() = synchronized(router) { channels.forEach { it.release() }; last = null }
    fun close() = synchronized(router) { release(); channels.forEach { it.close() }; router.removeSnapshot(snapshot) }

    private fun bits(data: ByteArray): Int = (data[1].toInt() and 255) or ((data[2].toInt() and 255) shl 8) or
        ((data[3].toInt() and 255) shl 16) or ((data[4].toInt() and 255) shl 24)
    private fun putBits(data: ByteArray, value: Int) { for (i in 0..3) data[i + 1] = (value ushr (i * 8)).toByte() }
    private fun short(data: ByteArray, offset: Int) = ((data[offset].toInt() and 255) or (data[offset + 1].toInt() shl 8)).toShort().toInt()
    private fun axis(data: ByteArray, offset: Int): Float = short(data, offset).let { it / if (it < 0) 32768f else 32767f }
    private fun shortValue(value: Float) = (value * if (value < 0f) 32768f else 32767f).roundToInt().coerceIn(-32768, 32767)
    private fun putShort(data: ByteArray, offset: Int, value: Int) { data[offset] = value.toByte(); data[offset + 1] = (value shr 8).toByte() }

    companion object {
        private const val CONTROL_BITS = 0x0fffffff
        private fun bit(c: InputControl): Int = when (c) {
            InputControl.A -> 0x1; InputControl.B -> 0x2; InputControl.X -> 0x4; InputControl.Y -> 0x8
            InputControl.QAM -> 0x10; InputControl.R3 -> 0x20; InputControl.START -> 0x40
            InputControl.R4 -> 0x80; InputControl.R5 -> 0x100; InputControl.RB -> 0x200
            InputControl.DOWN -> 0x400; InputControl.RIGHT -> 0x800; InputControl.LEFT -> 0x1000; InputControl.UP -> 0x2000
            InputControl.SELECT -> 0x4000; InputControl.L3 -> 0x8000; InputControl.GUIDE -> 0x10000
            InputControl.L4 -> 0x20000; InputControl.L5 -> 0x40000; InputControl.LB -> 0x80000
            InputControl.RS -> 0x100000; InputControl.RIGHT_PAD -> 0x200000; InputControl.RIGHT_CLICK -> 0x400000
            InputControl.RT -> 0x800000; InputControl.LS -> 0x1000000; InputControl.LEFT_PAD -> 0x2000000
            InputControl.LEFT_CLICK -> 0x4000000; InputControl.LT -> 0x8000000
        }
        private fun offset(c: InputControl): Int = when (c) {
            InputControl.LT -> 5; InputControl.RT -> 7; InputControl.LS -> 9; InputControl.RS -> 13
            InputControl.LEFT_PAD -> 17; InputControl.RIGHT_PAD -> 23
            else -> 0
        }
    }
}
