package com.droiddeck.launcher.input

import android.graphics.Rect
import com.droiddeck.launcher.input.GamepadGeometry.Box
import kotlin.math.min

internal class ControllerPortraitGeometry(
    private val width: Float,
    private val height: Float,
    private val safe: Rect,
    private val density: Float,
) {
    fun place(
        elements: List<InputSurfaceElement>, kit: PadKit, docked: Boolean, scale: Float,
        rearButtons: Boolean, trackpads: Boolean,
    ): List<InputSurfaceElement> {
        val w = width - safe.left - safe.right
        val h = height - safe.top - safe.bottom
        if (w <= 0 || h <= 0) return elements
        val presented = elements.toMutableList()
        val buttons = mutableMapOf<String, Box>()
        val rear = presented.filter { it.control in REAR && isStock(it) }
        val pads = presented.filter { it.control.isTrackpad && isStock(it) }
        if (!docked && kit.steam) {
            if (rearButtons) REAR.forEach { addOptional(presented, it) } else presented.removeAll(rear)
            if (trackpads) {
                addOptional(presented, InputControl.LEFT_PAD)
                addOptional(presented, InputControl.RIGHT_PAD)
            } else presented.removeAll(pads)
        }
        fun put(control: InputControl, box: Box) { buttons["legacy:${control.id}"] = box }
        val reserve = if (docked) 0f else min(60f * density, h * .15f)
        val panel = if (docked) h else min(min(h * .60f, w * 1.15f) * scale, h - reserve)
        val top = height - safe.bottom - reserve - panel
        val unit = min(density, min(w / 275f, panel / 300f))
        val pad = 8f * unit
        val gap = 8f * unit
        val buttonHeight = 44f * unit
        val shoulders = if (docked) listOf(InputControl.LT, InputControl.LB, InputControl.SELECT, InputControl.START, InputControl.RB, InputControl.RT)
            else listOf(InputControl.LT, InputControl.LB, InputControl.RB, InputControl.RT)
        fun row(controls: List<InputControl>, y: Float, rowHeight: Float, spacing: Float) {
            val cell = (w - 2f * pad - (controls.size - 1) * spacing) / controls.size
            controls.forEachIndexed { index, control ->
                put(control, Box(safe.left + pad + cell / 2f + index * (cell + spacing), y, cell, rowHeight))
            }
        }
        row(shoulders, top + pad + buttonHeight / 2f, buttonHeight, 4f * unit)
        var bodyTop = top + pad + buttonHeight + gap
        if (presented.any { it.control in REAR && isStock(it) }) {
            val rearHeight = min(56f * unit, (panel - bodyTop + top) * .12f).coerceAtLeast(buttonHeight)
            row(REAR, bodyTop + rearHeight / 2f, rearHeight, gap)
            bodyTop += rearHeight + gap
        }
        val floor = height - safe.bottom - reserve - pad
        val bodyHeight = floor - bodyTop - gap
        if (bodyHeight <= 0f) return elements
        val hasLeftPad = presented.any { it.control == InputControl.LEFT_PAD && isStock(it) }
        val hasRightPad = presented.any { it.control == InputControl.RIGHT_PAD && isStock(it) }
        val hasLeftStick = presented.any { it.control == InputControl.LS && isStock(it) }
        val hasRightStick = presented.any { it.control == InputControl.RS && isStock(it) }
        val four = hasLeftPad && hasLeftStick || hasRightPad && hasRightStick
        val main = min(w * .46f, bodyHeight * .53f)
        val second = min(w * .42f, bodyHeight - main)
        val mainY = floor - main / 2f
        val secondY = bodyTop + second / 2f
        val left = safe.left + w * .25f
        val right = safe.left + w * .75f
        if (four) {
            val col = (w - 2f * pad - 3f * gap) / 4f
            val stick = min(col, main)
            put(InputControl.LEFT_PAD, Box(safe.left + pad + col * .5f, mainY, col, main))
            put(InputControl.LS, Box(safe.left + pad + col * 1.5f + gap, mainY, stick, stick))
            put(InputControl.RS, Box(safe.left + w - pad - col * 1.5f - gap, mainY, stick, stick))
            put(InputControl.RIGHT_PAD, Box(safe.left + w - pad - col * .5f, mainY, col, main))
        } else {
            put(if (hasLeftPad) InputControl.LEFT_PAD else InputControl.LS, Box(left, mainY, main, main))
            put(if (hasRightPad) InputControl.RIGHT_PAD else InputControl.RS, Box(right, mainY, main, main))
        }
        val radius = second / 2f
        val armWidth = radius * if (kit.dpad == DpadStyle.CROSS) .37f else .25f
        val inner = if (kit.dpad == DpadStyle.ARROWS) radius * .14f else armWidth
        val depth = radius - inner
        val distance = (radius + inner) / 2f
        put(InputControl.UP, Box(left, secondY - distance, armWidth * 2f, depth))
        put(InputControl.RIGHT, Box(left + distance, secondY, depth, armWidth * 2f))
        put(InputControl.DOWN, Box(left, secondY + distance, armWidth * 2f, depth))
        put(InputControl.LEFT, Box(left - distance, secondY, depth, armWidth * 2f))
        val face = second * .34f
        val spread = second * .30f
        put(InputControl.Y, Box(right, secondY - spread, face, face))
        put(InputControl.X, Box(right - spread, secondY, face, face))
        put(InputControl.B, Box(right + spread, secondY, face, face))
        put(InputControl.A, Box(right, secondY + spread, face, face))
        if (!docked) {
            val ids = listOf(KEYBOARD, "legacy:guide", "legacy:select", "legacy:start", "legacy:qam")
                .filter { id -> presented.any { it.id == id } }
            val inset = min(52f * density, w * .17f)
            val rowWidth = (w - inset - pad - gap * (ids.size - 1)) / ids.size.coerceAtLeast(1)
            val rowHeight = min(reserve * .8f, 44f * unit)
            ids.forEachIndexed { i, id ->
                val center = safe.left + inset + rowWidth / 2f + i * (rowWidth + gap)
                buttons[id] = Box(center, height - safe.bottom - reserve / 2f, rowWidth, rowHeight)
            }
        }
        return presented.map { element ->
            val box = buttons[element.id]
            if (box == null) element else element.copy(
                x = box.left / width, y = box.top / height, width = box.width / width, height = box.height / height,
            ).bounded()
        }
    }

    private fun isStock(element: InputSurfaceElement) = element.id == "legacy:${element.control.id}"

    private fun addOptional(elements: MutableList<InputSurfaceElement>, control: InputControl) {
        if (elements.none { it.control == control && isStock(it) }) {
            elements += InputSurfaceElement(control, 0f, 0f, 0f, 0f, floating = false,
                buttonShape = InputButtonShape.PILL, showLabel = !control.isTrackpad)
        }
    }

    private companion object {
        const val KEYBOARD = "builtin:keyboard"
        val REAR = listOf(InputControl.L4, InputControl.L5, InputControl.R5, InputControl.R4)
    }
}
