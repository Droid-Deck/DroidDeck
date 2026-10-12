package com.droiddeck.launcher.ui

/** Vertical controller moves keep the same column, including across lazy item boundaries. */
internal fun storeGridNeighbor(index: Int, count: Int, columns: Int, direction: Int): Int? {
    if (index !in 0 until count || columns < 1 || direction !in listOf(-1, 1)) return null
    return (index + columns * direction).takeIf { it in 0 until count }
}
