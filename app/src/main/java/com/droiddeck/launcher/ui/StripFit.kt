package com.droiddeck.launcher.ui

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
