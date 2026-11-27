package com.droiddeck.launcher.input

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class InputControlGroup(val controls: Set<InputControl>) {
    DPAD(setOf(InputControl.UP, InputControl.RIGHT, InputControl.DOWN, InputControl.LEFT)),
    FACE(setOf(InputControl.A, InputControl.B, InputControl.X, InputControl.Y)),
    LEFT_SHOULDER(setOf(InputControl.LB, InputControl.LT)),
    RIGHT_SHOULDER(setOf(InputControl.RB, InputControl.RT)),
    LEFT_STICK(setOf(InputControl.LS)),
    RIGHT_STICK(setOf(InputControl.RS));

    companion object {
        // Stock gamepad controls and sets added from the Groups tab form groups; other added controls stay on their own.
        // An added set names its group in its saved key, "<GROUP>:<set id>".
        fun of(e: InputSurfaceElement): InputControlGroup? =
            if (e.isStockControl) entries.firstOrNull { e.control in it.controls }
            else entries.firstOrNull { e.group.startsWith("${it.name}:") && e.group.length > it.name.length + 1 }

        fun newSetKey(group: InputControlGroup) = "${group.name}:${newInputId()}"
    }
}

private val InputSurfaceElement.isStockControl get() = id == "legacy:${control.id}"

internal fun InputSurfaceElement.groupKey(): String? =
    InputControlGroup.of(this)?.let { if (isStockControl) it.name else group }

data class SurfaceBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
}

private const val MIN_SIZE = .03f
val WHOLE_SURFACE = SurfaceBounds(0f, 0f, 1f, 1f)

fun List<InputSurfaceElement>.boundsOf(ids: Collection<String>): SurfaceBounds? {
    val members = filter { it.id in ids }.ifEmpty { return null }
    return SurfaceBounds(members.minOf { it.x }, members.minOf { it.y }, members.maxOf { it.x + it.width }, members.maxOf { it.y + it.height })
}

fun List<InputSurfaceElement>.groupOf(id: String): List<String> {
    val key = firstOrNull { it.id == id }?.groupKey() ?: return listOf(id)
    return filter { it.groupKey() == key }.map { it.id }
}

fun List<InputSurfaceElement>.selectedGroup(selection: List<String>): InputControlGroup? {
    if (selection.size < 2) return null
    val group = firstOrNull { it.id == selection.first() }?.let(InputControlGroup::of) ?: return null
    return group.takeIf { groupOf(selection.first()).toSet() == selection.toSet() }
}

fun List<InputSurfaceElement>.pressSelection(current: List<String>, hit: String?): List<String> {
    if (hit == null) return emptyList()
    val group = groupOf(hit)
    return when {
        current.toSet() == group.toSet() || current.size > 1 && hit in current -> current
        current.size == 1 && current.first() in group -> listOf(hit)
        else -> group
    }
}

/** [under] lists every control under the tap, topmost first; tapping the one selected control again selects the next one down. */
fun List<InputSurfaceElement>.tapSelection(before: List<String>, hit: String?, under: List<String> = listOfNotNull(hit)): List<String> {
    if (hit != null && before.size > 1 && hit in before) return listOf(hit)
    if (hit != null && before == listOf(hit) && under.size > 1) return pressSelection(emptyList(), under[(under.indexOf(hit) + 1) % under.size])
    return pressSelection(before, hit)
}

private fun List<InputSurfaceElement>.keepsAspect(ids: Collection<String>) = any { it.id in ids && it.kind == InputElementKind.STICK }

private fun List<InputSurfaceElement>.place(ids: Collection<String>, from: SurfaceBounds, to: SurfaceBounds): List<InputSurfaceElement> {
    val sx = to.width / from.width
    val sy = to.height / from.height
    return map { e ->
        if (e.id !in ids) e
        else e.copy(x = to.left + (e.x - from.left) * sx, y = to.top + (e.y - from.top) * sy, width = e.width * sx, height = e.height * sy).bounded()
    }
}

fun List<InputSurfaceElement>.moveSelection(ids: Collection<String>, dx: Float, dy: Float, area: SurfaceBounds = WHOLE_SURFACE): List<InputSurfaceElement> {
    val b = boundsOf(ids) ?: return this
    val x = dx.coerceIn(area.left - b.left, max(area.left - b.left, area.right - b.right))
    val y = dy.coerceIn(area.top - b.top, max(area.top - b.top, area.bottom - b.bottom))
    return place(ids, b, SurfaceBounds(b.left + x, b.top + y, b.right + x, b.bottom + y))
}

fun List<InputSurfaceElement>.resizeSelection(ids: Collection<String>, corner: Int, target: SurfaceBounds,
    area: SurfaceBounds = WHOLE_SURFACE): List<InputSurfaceElement> {
    val b = boundsOf(ids) ?: return this
    val members = filter { it.id in ids }
    val minX = MIN_SIZE / members.minOf { it.width }
    val minY = MIN_SIZE / members.minOf { it.height }
    val anchorX = if (corner and 1 != 0) b.right else b.left
    val anchorY = if (corner and 4 != 0) b.bottom else b.top
    val roomX = (if (corner and 1 != 0) anchorX - area.left else area.right - anchorX) / b.width
    val roomY = (if (corner and 4 != 0) anchorY - area.top else area.bottom - anchorY) / b.height
    var sx = (target.width / b.width).coerceIn(minX, max(minX, roomX))
    var sy = (target.height / b.height).coerceIn(minY, max(minY, roomY))
    if (keepsAspect(ids)) {
        val s = (if (abs(sx - 1f) >= abs(sy - 1f)) sx else sy).coerceIn(max(minX, minY), max(max(minX, minY), min(roomX, roomY)))
        sx = s; sy = s
    }
    val w = b.width * sx
    val h = b.height * sy
    val left = if (corner and 1 != 0) anchorX - w else anchorX
    val top = if (corner and 4 != 0) anchorY - h else anchorY
    return place(ids, b, SurfaceBounds(left, top, left + w, top + h))
}

fun List<InputSurfaceElement>.scaleSelection(ids: Collection<String>, factor: Float, area: SurfaceBounds = WHOLE_SURFACE): List<InputSurfaceElement> {
    val b = boundsOf(ids) ?: return this
    val members = filter { it.id in ids }
    val smallest = max(MIN_SIZE / members.minOf { it.width }, MIN_SIZE / members.minOf { it.height })
    val largest = min(area.width / b.width, area.height / b.height)
    val f = factor.coerceIn(smallest, max(smallest, largest))
    val w = b.width * f
    val h = b.height * f
    val left = (b.centerX - w / 2f).coerceIn(area.left, max(area.left, area.right - w))
    val top = (b.centerY - h / 2f).coerceIn(area.top, max(area.top, area.bottom - h))
    return place(ids, b, SurfaceBounds(left, top, left + w, top + h))
}

/** Moves controls into [area]; a group moves as a whole so its buttons keep their arrangement. */
fun List<InputSurfaceElement>.keptIn(area: SurfaceBounds): List<InputSurfaceElement> {
    fun back(low: Float, high: Float, min: Float, max: Float) = if (low < min) min - low else if (high > max) max(max - high, min - low) else 0f
    val fitted = map { if (it.width > area.width || it.height > area.height) it.copy(width = min(it.width, area.width), height = min(it.height, area.height)) else it }
    val shift = fitted.groupBy { it.groupKey() ?: it.id }.values.flatMap { members ->
        val dx = back(members.minOf { it.x }, members.maxOf { it.x + it.width }, area.left, area.right)
        val dy = back(members.minOf { it.y }, members.maxOf { it.y + it.height }, area.top, area.bottom)
        members.map { it.id to (dx to dy) }
    }.toMap()
    return fitted.map { e ->
        val (dx, dy) = shift.getValue(e.id)
        // 1f - (1f - width) can round below width, so bound it the way saved layouts are checked.
        e.copy(x = (e.x + dx).coerceIn(area.left, max(area.left, area.right - e.width)), y = (e.y + dy).coerceIn(area.top, max(area.top, area.bottom - e.height))).bounded()
    }
}

fun List<InputSurfaceElement>.sizePercent(ids: Collection<String>, reference: Map<String, InputSurfaceElement>): Float {
    val ratios = filter { it.id in ids }.mapNotNull { e ->
        reference[e.id]?.takeIf { it.width > 0f && it.height > 0f }?.let { sqrt(e.width * e.height / (it.width * it.height)) }
    }
    return if (ratios.isEmpty()) 100f else 100f * ratios.average().toFloat()
}
