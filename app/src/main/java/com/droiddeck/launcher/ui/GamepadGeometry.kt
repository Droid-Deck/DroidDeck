package com.droiddeck.launcher.ui

import kotlin.math.min

/**
 * Where the unfolded screen's gamepad puts each control, for a panel of a given size: plain
 * numbers, so the layout is tested without a device (GamepadGeometryTest).
 *
 * Each thumb pivots at its bottom corner; its main control (a stick or trackpad) sits where it
 * rests, diagonally in from the corner, and the right side mirrors the left.
 *
 * A wide strip (a foldable held flat as a tablet): the second control - the d-pad, the face
 * buttons - a slide inward along the arc, the shoulders above, and View, Steam, QAM and Menu
 * between the arcs. The reach R is the strip's height, or a third of its width.
 *
 * A tall panel (held upright): the second control a slide up the arc instead, the shoulders above
 * that, View, Steam, QAM and Menu between the two sticks, and Panels - the Deck controls' other
 * tabs - between the shoulders. Each side's column S is half the width, or as much as the height
 * holds. A wide strip has no room for Panels and leaves it out.
 */
object GamepadGeometry {
    enum class Slot {
        LEFT_MAIN, LEFT_SECOND, RIGHT_MAIN, RIGHT_SECOND,
        LEFT_OUTER_SHOULDER, LEFT_INNER_SHOULDER, RIGHT_INNER_SHOULDER, RIGHT_OUTER_SHOULDER,
        VIEW, STEAM, QAM, MENU,
        /** The Deck controls' other tabs: on a tall panel only. */
        PANELS,
    }

    /** A control's box: its centre and size, in pixels. */
    data class Box(val cx: Float, val cy: Float, val width: Float, val height: Float) {
        val left get() = cx - width / 2f
        val top get() = cy - height / 2f
        val right get() = cx + width / 2f
        val bottom get() = cy + height / 2f

        fun overlaps(other: Box): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }

    /** The middle buttons, in a row in this order; two by two as on a Deck, View and Menu above Steam and QAM. */
    private val middle = listOf(Slot.VIEW, Slot.STEAM, Slot.QAM, Slot.MENU)
    private val twoByTwo = mapOf(Slot.VIEW to 0, Slot.MENU to 1, Slot.STEAM to 2, Slot.QAM to 3)

    fun layout(w: Float, h: Float, density: Float): Map<Slot, Box> {
        if (w <= 0f || h <= 0f) return emptyMap()
        val pad = 8 * density
        val buttonH = 40 * density
        // A row needs its buttons comfortable; two by two will take them a little narrower.
        val rowButton = 64 * density
        val gridButton = 48 * density
        val gutter = 8 * density
        val boxes = LinkedHashMap<Slot, Box>()
        fun mirror(x: Float, right: Boolean) = if (right) w - x else x
        fun pair(left: Slot, right: Slot, x: Float, cy: Float, bw: Float, bh: Float) {
            boxes[left] = Box(mirror(x, false), cy, bw, bh)
            boxes[right] = Box(mirror(x, true), cy, bw, bh)
        }
        fun middleGrid(columns: Int, span: Float, top: Float) {
            val each = (span / columns).coerceAtMost(110 * density)
            for ((order, slot) in middle.withIndex()) {
                val index = if (columns == 2) twoByTwo.getValue(slot) else order
                val x = w / 2f + (index % columns - (columns - 1) / 2f) * each
                boxes[slot] = Box(x, top + buttonH / 2f + (index / columns) * (buttonH + 10 * density), each - gutter, buttonH)
            }
        }
        if (h < w * 0.45f) {
            val reach = min(h - pad, w * 0.33f)
            val main = reach * 0.66f
            val second = reach * 0.54f
            val mainTop = h - reach * 0.40f - main / 2f
            val shoulderW = reach * 0.36f
            val shoulderH = (reach * 0.16f).coerceAtLeast(buttonH)
            // Above the main controls, never over them, and inside the panel.
            val shoulderY = min(h - reach * 0.94f + shoulderH / 2f, mainTop - gutter - shoulderH / 2f)
                .coerceAtLeast(pad + shoulderH / 2f)
            pair(Slot.LEFT_MAIN, Slot.RIGHT_MAIN, reach * 0.40f, h - reach * 0.40f, main, main)
            pair(Slot.LEFT_SECOND, Slot.RIGHT_SECOND, reach * 1.03f, h - reach * 0.30f, second, second)
            pair(Slot.LEFT_OUTER_SHOULDER, Slot.RIGHT_OUTER_SHOULDER, reach * 0.22f, shoulderY, shoulderW, shoulderH)
            pair(Slot.LEFT_INNER_SHOULDER, Slot.RIGHT_INNER_SHOULDER, reach * 0.62f, shoulderY, shoulderW, shoulderH)
            // Between the arcs: in a row if they fit, else two by two, else one above another.
            val gap = w - 2 * reach * 1.32f
            val columns = when {
                gap >= middle.size * rowButton -> middle.size
                gap >= 2 * gridButton -> 2
                else -> 1
            }
            middleGrid(columns, gap - 2 * gutter, shoulderY - buttonH / 2f)
        } else {
            val side = min(w * 0.5f, h * 0.70f)
            val main = side * 0.62f
            val second = side * 0.46f
            val secondCy = h - side * 0.97f
            val shoulderW = side * 0.34f
            val shoulderH = (side * 0.13f).coerceAtLeast(buttonH)
            val shoulderY = (secondCy - second / 2f - gutter - shoulderH / 2f).coerceAtLeast(pad + shoulderH / 2f)
            pair(Slot.LEFT_MAIN, Slot.RIGHT_MAIN, side * 0.40f, h - side * 0.40f, main, main)
            pair(Slot.LEFT_SECOND, Slot.RIGHT_SECOND, side * 0.74f, secondCy, second, second)
            pair(Slot.LEFT_OUTER_SHOULDER, Slot.RIGHT_OUTER_SHOULDER, side * 0.20f, shoulderY, shoulderW, shoulderH)
            pair(Slot.LEFT_INNER_SHOULDER, Slot.RIGHT_INNER_SHOULDER, side * 0.57f, shoulderY, shoulderW, shoulderH)
            // Between the two sticks at the bottom: two by two, or one above another.
            val gap = w - 2 * (side * 0.40f + main / 2f)
            val columns = if (gap >= 2 * gridButton) 2 else 1
            val rows = (middle.size + columns - 1) / columns
            middleGrid(columns, gap - 2 * gutter, h - pad - rows * buttonH - (rows - 1) * 10 * density)
            // Between the inner shoulders, where it fits.
            val panelsW = min(112 * density, w - 2 * (side * 0.57f + shoulderW / 2f) - 2 * gutter)
            if (panelsW >= buttonH) boxes[Slot.PANELS] = Box(w / 2f, shoulderY, panelsW, buttonH)
        }
        return boxes
    }
}
