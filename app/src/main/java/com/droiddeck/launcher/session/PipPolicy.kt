package com.droiddeck.launcher.session

object PipPolicy {
    fun eligible(running: Boolean, frameSeen: Boolean, phase: SessionPhase, suspended: Boolean, automatic: Boolean): Boolean =
        running && frameSeen && phase in setOf(SessionPhase.READY, SessionPhase.SUSPENDED) &&
            (!automatic || !suspended)

    fun aspect(width: Int, height: Int): Pair<Int, Int> = when {
        width <= 0 || height <= 0 -> 16 to 9
        width.toDouble() / height > 2.39 -> 239 to 100
        height.toDouble() / width > 2.39 -> 100 to 239
        else -> width to height
    }

    fun outputSize(width: Int, height: Int, outputWidth: Int, outputHeight: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0 || outputWidth <= 0 || outputHeight <= 0) return null
        val scale = minOf(width.toFloat() / outputWidth, height.toFloat() / outputHeight)
        val fittedWidth = ((outputWidth * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val fittedHeight = ((outputHeight * scale).toInt() and 1.inv()).coerceAtLeast(2)
        return fittedWidth to fittedHeight
    }
}
