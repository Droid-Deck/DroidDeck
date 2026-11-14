package com.droiddeck.launcher.input

import kotlin.math.abs

internal data class SurfaceSnapResult(val element: InputSurfaceElement, val x: Float? = null, val y: Float? = null)

internal fun snapSurfaceElement(element: InputSurfaceElement, others: List<InputSurfaceElement>, thresholdX: Float, thresholdY: Float,
    corner: Int = 0): SurfaceSnapResult {
    fun axis(start: Float, size: Float, targets: List<Float>, threshold: Float, low: Boolean, high: Boolean): Triple<Float, Float, Float?> {
        val anchors = when { low -> listOf(0f); high -> listOf(size); else -> listOf(0f, size / 2f, size) }
        val candidate = anchors.flatMap { anchor -> targets.map { target -> Triple(target - start - anchor, target, anchor) } }
            .filter { abs(it.first) <= threshold }.minByOrNull { abs(it.first) } ?: return Triple(start, size, null)
        val offset = candidate.first
        val nextStart = if (high) start else start + offset
        val nextSize = when { low -> size - offset; high -> size + offset; else -> size }
        if (nextStart < 0f || nextSize < .03f || nextStart + nextSize > 1.00001f) return Triple(start, size, null)
        return Triple(nextStart.coerceAtLeast(0f), nextSize, candidate.second)
    }
    val xTargets = listOf(0f, .5f, 1f) + others.flatMap { listOf(it.x, it.x + it.width / 2f, it.x + it.width) }
    val yTargets = listOf(0f, .5f, 1f) + others.flatMap { listOf(it.y, it.y + it.height / 2f, it.y + it.height) }
    val x = axis(element.x, element.width, xTargets, thresholdX, corner and 1 != 0, corner and 2 != 0)
    val y = axis(element.y, element.height, yTargets, thresholdY, corner and 4 != 0, corner and 8 != 0)
    return SurfaceSnapResult(element.copy(x = x.first, y = y.first, width = x.second, height = y.second).bounded(), x.third, y.third)
}
