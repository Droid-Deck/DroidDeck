package com.droiddeck.launcher.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val TOOL_STEPS = 12

/** Top-left, as canvas fractions, for a new [width] x [height] control in [area]: where it covers the fewest controls, then farthest from them. */
internal fun freeSurfaceSpot(elements: List<SurfaceBounds>, width: Float, height: Float, area: SurfaceBounds = WHOLE_SURFACE): Pair<Float, Float> {
    val minX = area.left
    val minY = area.top
    val maxX = (area.right - width).coerceAtLeast(minX)
    val maxY = (area.bottom - height).coerceAtLeast(minY)
    fun covered(b: SurfaceBounds, x: Float, y: Float) =
        max(0f, min(x + width, b.right) - max(x, b.left)) * max(0f, min(y + height, b.bottom) - max(y, b.top))
    fun clearance(b: SurfaceBounds, x: Float, y: Float) =
        hypot(max(0f, max(b.left - (x + width), x - b.right)), max(0f, max(b.top - (y + height), y - b.bottom)))
    val xs = (0..TOOL_STEPS).map { minX + (maxX - minX) * it / TOOL_STEPS } + elements.flatMap { listOf(it.right, it.left - width) }.map { it.coerceIn(minX, maxX) }
    val ys = (0..TOOL_STEPS).map { minY + (maxY - minY) * it / TOOL_STEPS } + elements.flatMap { listOf(it.bottom, it.top - height) }.map { it.coerceIn(minY, maxY) }
    val candidates = xs.distinct().flatMap { x -> ys.distinct().map { y -> x to y } }
    return candidates.minWith(compareBy<Pair<Float, Float>>({ (x, y) -> elements.sumOf { covered(it, x, y).toDouble() } },
        { (x, y) -> -(elements.minOfOrNull { clearance(it, x, y) } ?: 0f) },
        { (x, y) -> hypot(x - (minX + maxX) / 2, y - (minY + maxY) / 2) }))
}

/** Top-left corners, in pixels, of the Size and Opacity sliders. */
internal data class SelectionSliderSpots(val size: Pair<Float, Float>, val opacity: Pair<Float, Float>)

/**
 * Size goes above [outline] and Opacity below it, each centred on it and [gap] off its edge. With no room above both go below, Size
 * first; with no room below both go above, Opacity nearest. When neither works they stack beside it on the roomier side, and over it
 * as a last resort. A slider that would cover one of [corners] (the delete badge, resize handles and editor menu) moves sideways off it when it
 * can, or else steps further out. All values are pixels.
 */
internal fun placeSelectionSliders(width: Float, height: Float, sliderWidth: Float, sliderHeight: Float, margin: Float, gap: Float,
    outline: SurfaceBounds, corners: List<SurfaceBounds> = emptyList()): SelectionSliderSpots {
    val maxX = (width - sliderWidth - margin).coerceAtLeast(margin)
    val maxY = (height - sliderHeight - margin).coerceAtLeast(margin)
    val centre = (outline.centerX - sliderWidth / 2).coerceIn(margin, maxX)
    fun hits(x: Float, y: Float) = corners.filter { it.left < x + sliderWidth && x < it.right && it.top < y + sliderHeight && y < it.bottom }
    fun clearX(y: Float) = (listOf(centre) + corners.flatMap { listOf(it.right + gap, it.left - gap - sliderWidth) }.map { it.coerceIn(margin, maxX) })
        .filter { abs(it - centre) <= sliderWidth / 2 && hits(it, y).isEmpty() }.minByOrNull { abs(it - centre) }
    /** A slider whose top would be [from] above the selection, moved up past any corners it covers; null when it leaves the screen. */
    fun above(from: Float): Pair<Float, Float>? {
        var y = from
        repeat(corners.size + 1) {
            if (y < margin) return null
            clearX(y)?.let { return it to y }
            y = hits(centre, y).minOf { it.top } - gap - sliderHeight
        }
        return null
    }
    fun below(from: Float): Pair<Float, Float>? {
        var y = from
        repeat(corners.size + 1) {
            if (y > maxY) return null
            clearX(y)?.let { return it to y }
            y = hits(centre, y).maxOf { it.bottom } + gap
        }
        return null
    }
    val up = above(outline.top - gap - sliderHeight)
    val down = below(outline.bottom + gap)
    if (up != null && down != null) return SelectionSliderSpots(up, down)
    if (up == null) down?.let { first -> below(first.second + sliderHeight + gap)?.let { return SelectionSliderSpots(first, it) } }
    if (down == null) up?.let { first -> above(first.second - gap - sliderHeight)?.let { return SelectionSliderSpots(it, first) } }
    val stack = 2 * sliderHeight + gap
    val top = (outline.centerY - stack / 2).coerceIn(margin, (height - stack - margin).coerceAtLeast(margin))
    val rows = listOf(top, top + sliderHeight + gap)
    fun clear(x: Float) = rows.all { hits(x, it).isEmpty() }
    fun beside(toRight: Boolean): Float? {
        val x = if (toRight) outline.right + gap else outline.left - gap - sliderWidth
        val covered = rows.flatMap { hits(x, it) }.ifEmpty { return x }
        return (if (toRight) covered.maxOf { it.right } + gap else covered.minOf { it.left } - gap - sliderWidth).coerceIn(margin, maxX).takeIf(::clear)
    }
    val left = outline.left - margin
    val right = width - margin - outline.right
    val x = listOf(right >= left, right < left).filter { (if (it) right else left) >= sliderWidth + gap }.firstNotNullOfOrNull(::beside)
        ?: (listOf(centre) + corners.flatMap { listOf(it.right + gap, it.left - gap - sliderWidth) }.map { it.coerceIn(margin, maxX) })
            .filter(::clear).minByOrNull { abs(it - centre) }
        ?: centre
    return SelectionSliderSpots(x to top, x to top + sliderHeight + gap)
}
