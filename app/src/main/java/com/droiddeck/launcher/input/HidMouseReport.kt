package com.droiddeck.launcher.input

/** Where a USB mouse's input report keeps its buttons, motion and wheel, as its HID report descriptor lays them out. */
class HidMouseReport(
    val reportId: Int,
    private val buttons: List<Field?>,
    private val x: Field,
    private val y: Field,
    private val wheel: Field?,
) {
    class Field(private val bit: Int, private val size: Int, private val signed: Boolean) {
        fun read(report: ByteArray, length: Int, base: Int): Int {
            var value = 0
            for (i in 0 until minOf(size, 32)) {
                val at = base + bit + i
                if (at / 8 >= length) break
                if ((report[at / 8].toInt() shr (at % 8)) and 1 != 0) value = value or (1 shl i)
            }
            return if (signed && size in 1..31 && value and (1 shl (size - 1)) != 0) value - (1 shl size) else value
        }
    }

    /** [buttons] has bit 0 set for the first button. */
    data class State(val buttons: Int, val dx: Int, val dy: Int, val wheel: Int)

    /** Returns null for reports with another ID, such as a receiver's media keys. */
    fun decode(report: ByteArray, length: Int): State? {
        val base = if (reportId == 0) 0 else {
            if (length < 1 || (report[0].toInt() and 0xff) != reportId) return null
            8
        }
        var pressed = 0
        buttons.forEachIndexed { i, field -> if (field != null && field.read(report, length, base) != 0) pressed = pressed or (1 shl i) }
        return State(pressed, x.read(report, length, base), y.read(report, length, base), wheel?.read(report, length, base) ?: 0)
    }

    companion object {
        /** The report every boot mouse sends after SET_PROTOCOL(boot): three buttons, then X and Y as signed bytes. */
        val BOOT = HidMouseReport(0, List(3) { Field(it, 1, false) }, Field(8, 8, true), Field(16, 8, true), null)

        private const val BUTTON_PAGE = 0x09
        private const val MOUSE = 0x00010002
        private const val X = 0x00010030
        private const val Y = 0x00010031
        private const val WHEEL = 0x00010038
        private const val MAX_BUTTONS = 8
        private const val MAX_FIELD_BITS = 32
        private const val MAX_REPORT_COUNT = 4096

        private class Global(var usagePage: Int = 0, var logicalMin: Int = 0, var reportSize: Int = 0,
                             var reportCount: Int = 0, var reportId: Int = 0) {
            fun copy() = Global(usagePage, logicalMin, reportSize, reportCount, reportId)
        }

        private class Placed(val reportId: Int, val field: Field)

        /** Returns null if [descriptor] has no mouse collection with X and Y in one report. */
        fun parse(descriptor: ByteArray): HidMouseReport? {
            var global = Global()
            val pushed = ArrayDeque<Global>()
            val usages = mutableListOf<Int>()
            var usageMin = -1
            var usageMax = -1
            val offsets = HashMap<Int, Int>()
            var depth = 0
            var mouseDepth = -1
            var mouseDone = false
            val placed = HashMap<Int, Placed>()

            fun usage(index: Int): Int = when {
                usages.isNotEmpty() -> usages.getOrElse(index) { usages.last() }
                usageMin >= 0 && usageMax >= usageMin -> minOf(usageMin + index, usageMax)
                else -> -1
            }

            var i = 0
            while (i < descriptor.size) {
                val prefix = descriptor[i].toInt() and 0xff
                if (prefix == 0xfe) {
                    if (i + 1 >= descriptor.size) break
                    i += 3 + (descriptor[i + 1].toInt() and 0xff)
                    continue
                }
                val size = intArrayOf(0, 1, 2, 4)[prefix and 3]
                if (i + 1 + size > descriptor.size) break
                var data = 0
                for (b in 0 until size) data = data or ((descriptor[i + 1 + b].toInt() and 0xff) shl (8 * b))
                val signedData = when (size) {
                    1 -> data.toByte().toInt()
                    2 -> data.toShort().toInt()
                    else -> data
                }
                val tag = prefix shr 4
                when ((prefix shr 2) and 3) {
                    0 -> {
                        when (tag) {
                            0x8 -> {
                                if (global.reportSize !in 0..MAX_FIELD_BITS || global.reportCount !in 0..MAX_REPORT_COUNT) return null
                                val start = offsets[global.reportId] ?: 0
                                val isData = data and 1 == 0
                                val isVariable = data and 2 != 0
                                if (mouseDepth >= 0 && !mouseDone && isData && isVariable) {
                                    for (n in 0 until global.reportCount) {
                                        val u = usage(n)
                                        if (u < 0) break
                                        val field = Placed(global.reportId, Field(start + n * global.reportSize, global.reportSize, global.logicalMin < 0))
                                        val button = if (u ushr 16 == BUTTON_PAGE) u and 0xffff else 0
                                        when {
                                            u == X || u == Y || u == WHEEL -> placed.putIfAbsent(u, field)
                                            button in 1..MAX_BUTTONS -> placed.putIfAbsent(u, field)
                                        }
                                    }
                                }
                                offsets[global.reportId] = start + global.reportSize * global.reportCount
                            }
                            0xa -> {
                                depth++
                                if (mouseDepth < 0 && data == 1 && usage(0) == MOUSE) mouseDepth = depth
                            }
                            0xc -> {
                                if (depth == mouseDepth) mouseDone = true
                                depth--
                            }
                        }
                        usages.clear()
                        usageMin = -1
                        usageMax = -1
                    }
                    1 -> when (tag) {
                        0x0 -> global.usagePage = data
                        0x1 -> global.logicalMin = signedData
                        0x7 -> global.reportSize = data
                        0x8 -> global.reportId = data
                        0x9 -> global.reportCount = data
                        0xa -> pushed.addLast(global.copy())
                        0xb -> global = pushed.removeLastOrNull() ?: global
                    }
                    2 -> {
                        val full = if (size == 4) data else (global.usagePage shl 16) or data
                        when (tag) {
                            0x0 -> usages += full
                            0x1 -> usageMin = full
                            0x2 -> usageMax = full
                        }
                    }
                }
                i += 1 + size
            }

            val x = placed[X] ?: return null
            val y = placed[Y]?.takeIf { it.reportId == x.reportId } ?: return null
            val id = x.reportId
            val buttons = (1..MAX_BUTTONS).map { n -> placed[(BUTTON_PAGE shl 16) or n]?.takeIf { it.reportId == id }?.field }
            return HidMouseReport(id, buttons.dropLastWhile { it == null }, x.field, y.field,
                placed[WHEEL]?.takeIf { it.reportId == id }?.field)
        }
    }
}
