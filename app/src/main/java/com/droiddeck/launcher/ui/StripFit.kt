package com.droiddeck.launcher.ui

/**
 * Which items of a one-line strip are shown in [maxWidth]: items are added in [priorities] order (lower first,
 * then left to right) while their widths, with [spacing] between neighbours, still fit; the first one that does
 * not fit ends the line, so a number never jumps ahead of a more important one. The result is in item order.
 */
internal fun fitByPriority(widths: IntArray, priorities: IntArray, spacing: Int, maxWidth: Int): BooleanArray {
    val keep = BooleanArray(widths.size)
    var used = 0
    var count = 0
    for (i in widths.indices.sortedWith(compareBy({ priorities[it] }, { it }))) {
        val add = widths[i] + if (count > 0) spacing else 0
        if (used + add > maxWidth) break
        keep[i] = true
        used += add
        count++
    }
    return keep
}
