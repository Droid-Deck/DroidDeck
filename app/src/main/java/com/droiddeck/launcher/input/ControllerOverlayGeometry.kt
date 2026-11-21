package com.droiddeck.launcher.input

import android.graphics.Rect
import android.util.DisplayMetrics
import kotlin.math.max
import kotlin.math.min

internal class ControllerOverlayGeometry(
    width: Int, height: Int, private val safe: Rect, metrics: DisplayMetrics, scale: Float,
) {
    private val w = width.toFloat()
    private val h = height.toFloat()
    private val density = metrics.density
    private val dpi = metrics.ydpi.takeIf { it in metrics.densityDpi * .6f..metrics.densityDpi * 1.6f }
        ?: metrics.densityDpi.toFloat()
    private val unit = minOf(
        min(w / 1600f, h / 900f) * min(1f, 80f / max(h / dpi * 25.4f, 1f)) * scale,
        (w - safe.left - safe.right).coerceAtLeast(1f) / 1200f,
        (h - safe.top - safe.bottom).coerceAtLeast(1f) / 900f,
    )

    private fun anchor(value: Float, reference: Float, extent: Float, start: Int, end: Int): Float {
        val low = start + value * unit
        val high = extent - end - (reference - value) * unit
        return when {
            value < reference / 3f -> low
            value > reference * 2f / 3f -> high
            else -> (extent / 2f + (value - reference / 2f) * unit).coerceIn(low, max(low, high))
        }
    }

    private fun x(ref: Float) = anchor(ref, 1600f, w, safe.left, safe.right)

    private fun y(ref: Float) = anchor(ref, 900f, h, safe.top, safe.bottom)

    fun place(elements: List<InputSurfaceElement>, kit: PadKit, rearButtons: Boolean, trackpads: Boolean): List<InputSurfaceElement> {
        if (!kit.steam) return placeStandardPad(elements, kit)
        val leftEdge = safe.left + 52f * density
        val middle = (w + safe.left - safe.right) / 2f
        val keyboard = Box(max(x(136f), leftEdge + 46f * unit), y(848f), 46f).takeIf { elements.any { it.id == KEYBOARD } }
        val row = fitRow(listOfNotNull(keyboard,
            Box(max(x(370f), leftEdge + 66f * unit), y(852f), 66f, 30f),
            Box(middle - 140f * unit, y(850f), 46f, 26f),
            Box(middle + 140f * unit, y(850f), 46f, 26f),
            Box(x(1230f), y(852f), 66f, 30f)), leftEdge)
        val (guide, select, start, qam) = row.takeLast(4)
        val positions = shoulders() + face(1375f, 345f, 96f) + mapOf(
            InputControl.UP to Box(x(220f), y(345f) - 102.5f * unit, 55.5f, 47.5f),
            InputControl.RIGHT to Box(x(220f) + 102.5f * unit, y(345f), 47.5f, 55.5f),
            InputControl.DOWN to Box(x(220f), y(345f) + 102.5f * unit, 55.5f, 47.5f),
            InputControl.LEFT to Box(x(220f) - 102.5f * unit, y(345f), 47.5f, 55.5f),
            InputControl.LS to Box(x(290f), y(640f), 96f),
            InputControl.RS to Box(x(1310f), y(640f), 96f),
            InputControl.SELECT to select,
            InputControl.START to start,
            InputControl.GUIDE to guide,
            InputControl.QAM to qam,
        )
        val rear = mapOf(
            InputControl.L4 to Box(x(30f), y(470f), 30f, 58f),
            InputControl.L5 to Box(x(30f), y(590f), 30f, 58f),
            InputControl.R4 to Box(x(1570f), y(470f), 30f, 58f),
            InputControl.R5 to Box(x(1570f), y(590f), 30f, 58f),
        )
        val pads = mapOf(
            InputControl.LEFT_PAD to Box(x(470f), y(360f), 88f),
            InputControl.RIGHT_PAD to Box(x(1130f), y(360f), 88f),
        )
        val optional = rear.filterKeys { rearButtons } + pads.filterKeys { trackpads }
        val placed = elements.mapNotNull { element ->
            when {
                element.id == KEYBOARD -> row.first().place(element)
                element.id != "legacy:${element.control.id}" -> element
                element.control in rear || element.control in pads -> optional[element.control]?.place(element)
                else -> positions[element.control]?.place(element) ?: element
            }
        }
        return placed + optional.mapNotNull { (control, box) ->
            if (placed.any { it.id == "legacy:${control.id}" }) null else box.place(InputSurfaceElement(control,
                0f, 0f, 0f, 0f, floating = false, buttonShape = InputButtonShape.PILL, showLabel = !control.isTrackpad))
        }
    }

    private fun placeStandardPad(elements: List<InputSurfaceElement>, kit: PadKit): List<InputSurfaceElement> {
        val leftEdge = safe.left + 52f * density
        val middle = (w + safe.left - safe.right) / 2f
        val keyboard = Box(max(x(136f), leftEdge + 46f * unit), y(848f), 46f).takeIf { elements.any { it.id == KEYBOARD } }
        val controls = if (kit.playStation) listOf(
            InputControl.QAM to Box(x(220f), y(848f), 46f),
            InputControl.SELECT to Box(middle - 160f * unit, y(850f), 50f, 30f),
            InputControl.GUIDE to Box(middle, y(850f), 46f),
            InputControl.START to Box(middle + 160f * unit, y(850f), 50f, 30f),
        ) else listOf(
            InputControl.QAM to Box(x(220f), y(848f), 46f),
            InputControl.SELECT to Box(middle - 110f * unit, y(850f), 38f),
            InputControl.GUIDE to Box(middle, y(850f), 46f),
            InputControl.START to Box(middle + 110f * unit, y(850f), 38f),
        )
        val row = fitRow(listOfNotNull(keyboard) + controls.map { it.second }, leftEdge)
        val positions = controls.map { it.first }.zip(row.takeLast(controls.size)).toMap() + shoulders() +
            if (kit.playStation) {
                face(1350f, 395f, 96f) + dpad(250f, 395f, 125f, kit.dpad) + mapOf(
                    InputControl.LS to Box(x(565f), y(690f), 100f),
                    InputControl.RS to Box(x(1035f), y(690f), 100f),
                    InputControl.L3 to Box(x(565f) - 135f * unit, y(690f) - 100f * unit, 42f),
                    InputControl.R3 to Box(x(1035f) + 135f * unit, y(690f) - 100f * unit, 42f),
                )
            } else {
                face(1375f, 400f, 94f) + dpad(470f, 675f, 118f, kit.dpad) + mapOf(
                    InputControl.LS to Box(x(235f), y(400f), 104f),
                    InputControl.RS to Box(x(1130f), y(690f), 104f),
                    InputControl.L3 to Box(x(235f) + 150f * unit, y(400f) - 110f * unit, 42f),
                    InputControl.R3 to Box(x(1130f) + 160f * unit, y(690f) + 100f * unit, 42f),
                )
            }
        return elements.map { element ->
            when (element.id) {
                KEYBOARD -> row.first().place(element)
                "legacy:${element.control.id}" -> positions[element.control]?.place(element) ?: element
                else -> element
            }
        }
    }

    private fun shoulders() = mapOf(
        InputControl.LT to Box(x(140f), y(66f), 88f, 34f),
        InputControl.LB to Box(x(140f), y(148f), 88f, 30f),
        InputControl.RT to Box(x(1460f), y(66f), 88f, 34f),
        InputControl.RB to Box(x(1460f), y(148f), 88f, 30f),
    )

    private fun face(cx: Float, cy: Float, offset: Float) = mapOf(
        InputControl.Y to Box(x(cx), y(cy) - offset * unit, 58f),
        InputControl.X to Box(x(cx) - offset * unit, y(cy), 58f),
        InputControl.B to Box(x(cx) + offset * unit, y(cy), 58f),
        InputControl.A to Box(x(cx), y(cy) + offset * unit, 58f),
    )

    private fun dpad(cx: Float, cy: Float, radius: Float, style: DpadStyle): Map<InputControl, Box> {
        val inner = radius * if (style == DpadStyle.DISC) .25f else .14f
        val depth = (radius - inner) / 2f
        val offset = (radius - depth) * unit
        val side = radius * .25f
        return mapOf(
            InputControl.UP to Box(x(cx), y(cy) - offset, side, depth),
            InputControl.RIGHT to Box(x(cx) + offset, y(cy), depth, side),
            InputControl.DOWN to Box(x(cx), y(cy) + offset, side, depth),
            InputControl.LEFT to Box(x(cx) - offset, y(cy), depth, side),
        )
    }

    private fun fitRow(row: List<Box>, start: Float): List<Box> {
        val end = w - safe.right
        val gap = ((end - start - row.fold(0f) { sum, box -> sum + box.width }) / (row.size - 1)).coerceIn(0f, 8f * density)
        var edge = start - gap
        val pushed = row.map { box -> box.at(max(box.cx, edge + gap + box.width / 2f)).also { edge = it.cx + it.width / 2f } }
        edge = end + gap
        return pushed.asReversed().map { box -> box.at(min(box.cx, edge - gap - box.width / 2f)).also { edge = it.cx - it.width / 2f } }.asReversed()
    }

    private inner class Box(val cx: Float, val cy: Float, val hw: Float, val hh: Float = hw) {
        val width get() = max(hw * 2f * unit, w * .03f)

        fun at(cx: Float) = Box(cx, cy, hw, hh)

        fun place(element: InputSurfaceElement): InputSurfaceElement {
            val height = max(hh * 2f * unit, h * .03f)
            return element.copy(
                x = (cx - width / 2f).coerceIn(safe.left.toFloat(), max(safe.left.toFloat(), w - safe.right - width)) / w,
                y = (cy - height / 2f).coerceIn(safe.top.toFloat(), max(safe.top.toFloat(), h - safe.bottom - height)) / h,
                width = width / w, height = height / h,
            ).bounded()
        }
    }

    private companion object {
        const val KEYBOARD = "builtin:keyboard"
    }
}
