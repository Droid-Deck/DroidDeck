package com.droiddeck.launcher.ui

// `split` is the size of a leading section that starts its own rows; 0 means one continuous grid.
internal fun storeGridNeighbor(index: Int, count: Int, columns: Int, direction: Int, split: Int = 0): Int? {
    if (index !in 0 until count || columns < 1 || direction !in listOf(-1, 1)) return null
    if (split !in 1 until count) return (index + columns * direction).takeIf { it in 0 until count }
    val inFirst = index < split
    val start = if (inFirst) 0 else split
    val end = if (inFirst) split else count
    val local = index - start
    val within = start + local + columns * direction
    if (within in start until end) return within
    val column = local % columns
    return when {
        inFirst && direction > 0 -> split + minOf(column, count - split - 1)
        !inFirst && direction < 0 && local < columns -> ((split - 1) / columns) * columns + column.coerceAtMost((split - 1) % columns)
        else -> null
    }
}
