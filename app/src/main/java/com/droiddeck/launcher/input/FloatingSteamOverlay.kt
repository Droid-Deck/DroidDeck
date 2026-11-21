package com.droiddeck.launcher.input

import android.graphics.Rect
import kotlin.math.min

internal class FloatingSteamOverlay(width: Int, height: Int, safe: Rect, density: Float) {
    private val w = width.toFloat()
    private val h = height.toFloat()
    private val left = safe.left / w
    private val top = safe.top / h
    private val right = 1f - safe.right / w
    private val bottom = 1f - safe.bottom / h
    private val gap = 3f * density
    private val bottomPadding = 14f * density
    private val dp = min(density, min(width, height) / 250f)

    fun place(elements: List<InputSurfaceElement>, lowerShortcuts: Boolean,
        rearButtons: Boolean?, trackpads: Boolean?): List<InputSurfaceElement> {
        val visible = elements.filter { element ->
            when {
                !isStock(element) -> true
                element.control in rear -> rearButtons != false
                element.control.isTrackpad -> trackpads != false
                else -> true
            }
        }
        val lowered = if (lowerShortcuts) lower(visible) else visible
        if (rearButtons != true && trackpads != true) return lowered
        val placed = lowered.toMutableList()
        fun add(control: InputControl, x: Float, y: List<Float>, widthDp: Int, heightDp: Int, shape: InputButtonShape) {
            if (placed.any { it.id == "legacy:${control.id}" }) return
            val element = InputSurfaceElement(control, 0f, 0f, (widthDp * dp / w).coerceAtLeast(.03f),
                (heightDp * dp / h).coerceAtLeast(.03f), floating = false, buttonShape = shape, showLabel = !control.isTrackpad)
            findSpace(element, x, y, placed)?.let(placed::add)
        }
        if (rearButtons == true) {
            add(InputControl.L4, .045f, listOf(.48f, .38f, .58f), 28, 46, InputButtonShape.PILL)
            add(InputControl.L5, .045f, listOf(.62f, .72f, .53f), 28, 46, InputButtonShape.PILL)
            add(InputControl.R4, .955f, listOf(.48f, .38f, .58f), 28, 46, InputButtonShape.PILL)
            add(InputControl.R5, .955f, listOf(.62f, .72f, .53f), 28, 46, InputButtonShape.PILL)
        }
        if (trackpads == true) {
            val rows = listOf(.45f, .36f, .54f, .27f, .63f)
            add(InputControl.LEFT_PAD, .34f, rows, 68, 63, InputButtonShape.ROUNDED)
            add(InputControl.RIGHT_PAD, .66f, rows, 68, 63, InputButtonShape.ROUNDED)
        }
        return placed
    }

    private fun lower(elements: List<InputSurfaceElement>): List<InputSurfaceElement> {
        val row = elements.filter { isStock(it) && it.control in shortcuts }
        val occupied = (elements - row.toSet()).toMutableList()
        val moved = mutableMapOf<String, InputSurfaceElement>()
        for (element in row.sortedBy { shortcuts.getValue(it.control) }) {
            val y = bottom - bottomPadding / h - element.height / 2f
            val position = findSpace(element, shortcuts.getValue(element.control), listOf(y, y - .07f), occupied)
                ?: return elements
            occupied += position
            moved[element.id] = position
        }
        return elements.map { moved[it.id] ?: it }
    }

    private fun findSpace(element: InputSurfaceElement, x: Float, rows: List<Float>,
        occupied: List<InputSurfaceElement>): InputSurfaceElement? {
        val maxX = right - element.width
        val maxY = bottom - element.height
        if (maxX < left || maxY < top) return null
        for (y in rows) {
            for (dx in horizontalOffsets) {
                val candidate = element.copy(
                    x = (x + dx - element.width / 2f).coerceIn(left, maxX),
                    y = (y - element.height / 2f).coerceIn(top, maxY),
                )
                if (occupied.none { overlaps(candidate, it) }) return candidate
            }
        }
        return null
    }

    private fun overlaps(a: InputSurfaceElement, b: InputSurfaceElement): Boolean =
        a.x < b.x + b.width + gap / w && b.x < a.x + a.width + gap / w &&
            a.y < b.y + b.height + gap / h && b.y < a.y + a.height + gap / h

    private fun isStock(element: InputSurfaceElement) = element.id == "legacy:${element.control.id}"

    private companion object {
        val rear = setOf(InputControl.L4, InputControl.L5, InputControl.R4, InputControl.R5)
        val shortcuts = mapOf(InputControl.GUIDE to .23f, InputControl.SELECT to .41f, InputControl.START to .59f, InputControl.QAM to .77f)
        val horizontalOffsets = listOf(0f, -.035f, .035f, -.07f, .07f, -.105f, .105f)
    }
}
