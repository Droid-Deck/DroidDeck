package com.droiddeck.launcher.input

import kotlin.math.min

object GamepadGeometry {
    enum class Slot {
        LEFT_MAIN, LEFT_SECOND, RIGHT_MAIN, RIGHT_SECOND,
        LEFT_OUTER_SHOULDER, LEFT_INNER_SHOULDER, RIGHT_INNER_SHOULDER, RIGHT_OUTER_SHOULDER,
        VIEW, STEAM, QAM, MENU,
        PANELS,
        L4, L5, R5, R4,
        LEFT_TOUCH, RIGHT_TOUCH,
    }

    data class Box(val cx: Float, val cy: Float, val width: Float, val height: Float) {
        val left get() = cx - width / 2f
        val top get() = cy - height / 2f
        val right get() = cx + width / 2f
        val bottom get() = cy + height / 2f

        fun overlaps(other: Box): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }

    private val middle = listOf(Slot.VIEW, Slot.STEAM, Slot.QAM, Slot.MENU)
    private val twoByTwo = mapOf(Slot.VIEW to 0, Slot.MENU to 1, Slot.STEAM to 2, Slot.QAM to 3)

    fun tall(w: Float, h: Float): Boolean = h >= w * 0.45f

    fun layout(w: Float, h: Float, density: Float, portraitDock: Boolean = false, grips: Boolean = true, upright: Boolean = false,
        trackpads: Boolean = false): Map<Slot, Box> {
        if (w <= 0f || h <= 0f) return emptyMap()
        if (upright || portraitDock && h >= w * 0.6f && w >= 300 * density && h >= 300 * density) {
            return portraitLayout(w, h, density, grips, trackpads)
        }
        val pad = 8 * density
        val buttonH = 40 * density
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
        if (!tall(w, h)) {
            val reach = min(h - pad, w * 0.33f)
            val main = reach * 0.66f
            val second = reach * 0.54f
            val mainTop = h - reach * 0.40f - main / 2f
            val shoulderW = reach * 0.36f
            val shoulderH = (reach * 0.16f).coerceAtLeast(buttonH)
            val shoulderY = min(h - reach * 0.94f + shoulderH / 2f, mainTop - gutter - shoulderH / 2f)
                .coerceAtLeast(pad + shoulderH / 2f)
            pair(Slot.LEFT_MAIN, Slot.RIGHT_MAIN, reach * 0.40f, h - reach * 0.40f, main, main)
            pair(Slot.LEFT_SECOND, Slot.RIGHT_SECOND, reach * 1.03f, h - reach * 0.30f, second, second)
            pair(Slot.LEFT_OUTER_SHOULDER, Slot.RIGHT_OUTER_SHOULDER, reach * 0.22f, shoulderY, shoulderW, shoulderH)
            pair(Slot.LEFT_INNER_SHOULDER, Slot.RIGHT_INNER_SHOULDER, reach * 0.62f, shoulderY, shoulderW, shoulderH)
            val gap = w - 2 * reach * 1.32f
            val columns = when {
                gap >= middle.size * rowButton -> middle.size
                gap >= 2 * gridButton -> 2
                else -> 1
            }
            middleGrid(columns, gap - 2 * gutter, shoulderY - buttonH / 2f)
        } else {
            val side = min(min(w * 0.5f, h * 0.70f), (w - 2 * gridButton - 2 * gutter) / 1.42f)
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
            val gap = w - 2 * (side * 0.40f + main / 2f)
            val columns = if (gap >= 2 * gridButton) 2 else 1
            val rows = (middle.size + columns - 1) / columns
            middleGrid(columns, gap - 2 * gutter, h - pad - rows * buttonH - (rows - 1) * 10 * density)
            val panelsW = min(112 * density, w - 2 * (side * 0.57f + shoulderW / 2f) - 2 * gutter)
            if (panelsW >= buttonH) boxes[Slot.PANELS] = Box(w / 2f, shoulderY, panelsW, buttonH)
        }
        return boxes
    }

    /**
     * The panel under a portrait game: shoulders with View and Menu across the top, the back grips under them when the kit has
     * them, then the d-pad and face buttons and, along the bottom edge, the sticks or trackpads, each as large as the panel allows.
     */
    private fun portraitLayout(w: Float, h: Float, density: Float, grips: Boolean, trackpads: Boolean): Map<Slot, Box> {
        val pad = 8 * density
        val gap = 8 * density
        val buttonH = 44 * density
        val boxes = LinkedHashMap<Slot, Box>()
        val topGap = 4 * density
        val topW = (w - 2 * pad - 5 * topGap) / 6f
        val top = listOf(Slot.LEFT_OUTER_SHOULDER, Slot.LEFT_INNER_SHOULDER, Slot.VIEW, Slot.MENU, Slot.RIGHT_INNER_SHOULDER, Slot.RIGHT_OUTER_SHOULDER)
        for ((i, slot) in top.withIndex()) boxes[slot] = Box(pad + topW / 2f + i * (topW + topGap), pad + buttonH / 2f, topW, buttonH)
        var areaTop = pad + buttonH + gap
        if (grips) {
            val gripW = (w - 2 * pad - 3 * gap) / 4f
            val gripH = min(56 * density, (h - areaTop) * 0.12f).coerceAtLeast(buttonH)
            for ((i, slot) in listOf(Slot.L4, Slot.L5, Slot.R5, Slot.R4).withIndex())
                boxes[slot] = Box(pad + gripW / 2f + i * (gripW + gap), areaTop + gripH / 2f, gripW, gripH)
            areaTop += gripH + gap
        }
        val area = h - pad - areaTop - gap
        if (trackpads) {
            val second = min(w * 0.42f, area * 0.5f)
            val rowH = area - second
            val col = (w - 2 * pad - 3 * gap) / 4f
            val stick = min(col, rowH)
            val rowY = h - pad - rowH / 2f
            val secondY = areaTop + second / 2f
            boxes[Slot.LEFT_SECOND] = Box(w * 0.25f, secondY, second, second)
            boxes[Slot.RIGHT_SECOND] = Box(w * 0.75f, secondY, second, second)
            boxes[Slot.LEFT_TOUCH] = Box(pad + col / 2f, rowY, col, rowH)
            boxes[Slot.LEFT_MAIN] = Box(pad + col * 1.5f + gap, rowY, stick, stick)
            boxes[Slot.RIGHT_MAIN] = Box(w - pad - col * 1.5f - gap, rowY, stick, stick)
            boxes[Slot.RIGHT_TOUCH] = Box(w - pad - col / 2f, rowY, col, rowH)
            return boxes
        }
        val main = min(w * 0.46f, area * 0.53f)
        val second = min(w * 0.42f, area - main)
        val secondY = areaTop + second / 2f
        val mainY = h - pad - main / 2f
        boxes[Slot.LEFT_SECOND] = Box(w * 0.25f, secondY, second, second)
        boxes[Slot.RIGHT_SECOND] = Box(w * 0.75f, secondY, second, second)
        boxes[Slot.LEFT_MAIN] = Box(w * 0.25f, mainY, main, main)
        boxes[Slot.RIGHT_MAIN] = Box(w * 0.75f, mainY, main, main)
        return boxes
    }
}
