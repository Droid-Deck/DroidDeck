package com.droiddeck.launcher.input

/** The slider range for a selection [size] in percent: the usual range, widened in steps of [step] when the size is already outside it. */
internal fun selectionSizeRange(size: Int, usual: IntRange = 40..250, step: Int = 5) =
    minOf(usual.first, Math.floorDiv(size, step) * step)..maxOf(usual.last, Math.floorDiv(size + step - 1, step) * step)
