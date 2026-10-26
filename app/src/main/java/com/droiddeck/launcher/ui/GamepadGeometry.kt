package com.droiddeck.launcher.ui

import kotlin.math.min

object GamepadGeometry {
    enum class Slot {
        LEFT_MAIN, LEFT_SECOND, RIGHT_MAIN, RIGHT_SECOND,
        LEFT_OUTER_SHOULDER, LEFT_INNER_SHOULDER, RIGHT_INNER_SHOULDER, RIGHT_OUTER_SHOULDER,
        VIEW, STEAM, QAM, MENU,
        PANELS,
        L4, L5, R5, R4,
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

    fun layout(w: Float, h: Float, density: Float, portraitDock: Boolean = false): Map<Slot, Box> {
        if (w <= 0f || h <= 0f) return emptyMap()
        if (portraitDock && h >= w * 0.9f && w >= 300 * density && h >= 360 * density) {
            return portraitLayout(w, h, density)
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

    private fun portraitLayout(w: Float, h: Float, density: Float): Map<Slot, Box> {
        val pad = 8 * density
        val gap = 8 * density
        val buttonH = 44 * density
        val scale = min(1f, (h - 2 * pad - 4 * gap - 3 * buttonH) / (w * 0.7f))
        val main = w * 0.4f * scale
        val second = w * 0.3f * scale
        val mainY = h - pad - main / 2f
        val secondY = mainY - main / 2f - gap - second / 2f
        val shoulderY = secondY - second / 2f - gap - buttonH / 2f
        val boxes = linkedMapOf(
            Slot.LEFT_MAIN to Box(w * 0.25f, mainY, main, main),
            Slot.RIGHT_MAIN to Box(w * 0.75f, mainY, main, main),
            Slot.LEFT_SECOND to Box(w * 0.25f, secondY, second, second),
            Slot.RIGHT_SECOND to Box(w * 0.75f, secondY, second, second),
        )
        val utilityW = (w - 2 * pad - 3 * gap) / 4f
        for ((i, slot) in middle.withIndex()) {
            boxes[slot] = Box(pad + utilityW / 2f + i * (utilityW + gap), pad + buttonH / 2f, utilityW, buttonH)
        }
        val gripTop = pad + buttonH + gap
        val gripH = min(64 * density, shoulderY - buttonH / 2f - gap - gripTop)
        for ((i, slot) in listOf(Slot.L4, Slot.L5, Slot.R5, Slot.R4).withIndex()) {
            boxes[slot] = Box(pad + utilityW / 2f + i * (utilityW + gap), gripTop + gripH / 2f, utilityW, gripH)
        }
        val panelsW = min(112 * density, w * 0.22f)
        val shoulderW = (w - 2 * pad - 4 * gap - panelsW) / 4f
        boxes[Slot.PANELS] = Box(w / 2f, shoulderY, panelsW, buttonH)
        boxes[Slot.LEFT_OUTER_SHOULDER] = Box(pad + shoulderW / 2f, shoulderY, shoulderW, buttonH)
        boxes[Slot.LEFT_INNER_SHOULDER] = Box(pad + shoulderW * 1.5f + gap, shoulderY, shoulderW, buttonH)
        boxes[Slot.RIGHT_INNER_SHOULDER] = Box(w - pad - shoulderW * 1.5f - gap, shoulderY, shoulderW, buttonH)
        boxes[Slot.RIGHT_OUTER_SHOULDER] = Box(w - pad - shoulderW / 2f, shoulderY, shoulderW, buttonH)
        return boxes
    }
}
