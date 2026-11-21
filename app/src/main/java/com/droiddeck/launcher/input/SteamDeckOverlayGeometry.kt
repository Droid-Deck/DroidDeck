package com.droiddeck.launcher.input

import android.graphics.Rect
import android.util.DisplayMetrics
import kotlin.math.max
import kotlin.math.min

internal class SteamDeckOverlayGeometry(
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

    fun place(elements: List<InputSurfaceElement>, rearButtons: Boolean, trackpads: Boolean): List<InputSurfaceElement> {
        val x = { ref: Float -> anchor(ref, 1600f, w, safe.left, safe.right) }
        val y = { ref: Float -> anchor(ref, 900f, h, safe.top, safe.bottom) }
        val leftEdge = safe.left + 52f * density
        val keyboardX = max(x(136f), leftEdge + 46f * unit)
        val hasKeyboard = elements.any { it.id == KEYBOARD }
        val guideEdge = if (hasKeyboard) keyboardX + 46f * unit + 8f * density else leftEdge
        val middle = (w + safe.left - safe.right) / 2f
        val positions = mapOf(
            InputControl.LT to Box(x(140f), y(66f), 88f, 34f),
            InputControl.LB to Box(x(140f), y(148f), 88f, 30f),
            InputControl.RT to Box(x(1460f), y(66f), 88f, 34f),
            InputControl.RB to Box(x(1460f), y(148f), 88f, 30f),
            InputControl.Y to Box(x(1375f), y(345f) - 96f * unit, 58f),
            InputControl.X to Box(x(1375f) - 96f * unit, y(345f), 58f),
            InputControl.B to Box(x(1375f) + 96f * unit, y(345f), 58f),
            InputControl.A to Box(x(1375f), y(345f) + 96f * unit, 58f),
            InputControl.UP to Box(x(220f), y(345f) - 102.5f * unit, 55.5f, 47.5f),
            InputControl.RIGHT to Box(x(220f) + 102.5f * unit, y(345f), 47.5f, 55.5f),
            InputControl.DOWN to Box(x(220f), y(345f) + 102.5f * unit, 55.5f, 47.5f),
            InputControl.LEFT to Box(x(220f) - 102.5f * unit, y(345f), 47.5f, 55.5f),
            InputControl.LS to Box(x(290f), y(640f), 96f),
            InputControl.RS to Box(x(1310f), y(640f), 96f),
            InputControl.SELECT to Box(middle - 140f * unit, y(850f), 46f, 26f),
            InputControl.START to Box(middle + 140f * unit, y(850f), 46f, 26f),
            InputControl.GUIDE to Box(max(x(370f), guideEdge + 66f * unit), y(852f), 66f, 30f),
            InputControl.QAM to Box(x(1230f), y(852f), 66f, 30f),
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
                element.id == KEYBOARD -> Box(keyboardX, y(848f), 46f).place(element)
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

    private inner class Box(val cx: Float, val cy: Float, val hw: Float, val hh: Float = hw) {
        fun place(element: InputSurfaceElement): InputSurfaceElement {
            val width = max(hw * 2f * unit, w * .03f)
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
