package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Places a virtual controller's default on-screen controls. Each kit is drawn on a 1600 × 900 reference screen; controls keep
 * their distance from the nearest edge, so wider screens spread them apart instead of stretching them. Clusters such as the face
 * buttons are placed by their centre so they move as one, and the row along the bottom-left edge always starts after DroidDeck's
 * corner button.
 */
object KitLayout {
    const val DPAD_ID = "kit:dpad"
    const val KEYBOARD_ID = "builtin:keyboard"

    /** Whether [id] names one of a kit's own controls rather than a key or control the player added. */
    fun isKit(id: String) = id.startsWith("legacy:") || id == DPAD_ID || id == KEYBOARD_ID

    /** The control a kit id stands for in a layout that keeps no kit positions, so bindings saved for it stay valid. */
    fun element(id: String): InputSurfaceElement? = when {
        id == DPAD_ID -> InputSurfaceElement(InputControl.LS, 0f, 0f, .1f, .1f, id, InputElementKind.DPAD)
        id == KEYBOARD_ID -> InputSurfaceElement(InputControl.A, 0f, 0f, .1f, .1f, id)
        id.startsWith("legacy:") -> InputControl.entries.firstOrNull { "legacy:${it.id}" == id }
            ?.let { InputSurfaceElement(it, 0f, 0f, .1f, .1f, id, InputElementKind.from(it)) }
        else -> null
    }

    /** Every kit control that has a click, across all virtual controllers. */
    val clickIds = listOf(InputControl.LS, InputControl.RS, InputControl.LEFT_PAD, InputControl.RIGHT_PAD).map { "legacy:${it.id}" }
    private const val REF_W = 1600f
    private const val REF_H = 900f
    /** The widest pair of side controls on any kit: a larger size never pushes the two halves, or the rows, into each other. */
    private const val MIN_SPAN = 1200f
    private const val MAX_HEIGHT_MM = 80f
    private const val CORNER_DP = 52f
    private const val ROW_DP = 60f
    private const val BUTTON_DP = 44f
    private const val GAP_DP = 8f

    private class Spec(val control: InputControl, val cx: Float, val cy: Float, val hw: Float, val hh: Float = hw, val id: String = "legacy:${control.id}",
        val kind: InputElementKind = InputElementKind.from(control), val shape: InputButtonShape = InputButtonShape.CIRCLE, val icon: Int = 0, val name: String = control.label,
        val ax: Float = cx, val ay: Float = cy)

    private fun shoulders() = listOf(
        Spec(InputControl.LT, 140f, 66f, 88f, 34f, shape = InputButtonShape.PILL), Spec(InputControl.LB, 140f, 148f, 88f, 28f, shape = InputButtonShape.PILL),
        Spec(InputControl.RT, 1460f, 66f, 88f, 34f, shape = InputButtonShape.PILL), Spec(InputControl.RB, 1460f, 148f, 88f, 28f, shape = InputButtonShape.PILL))

    private fun face(cx: Float, cy: Float, r: Float, off: Float) = listOf(
        Spec(InputControl.Y, cx, cy - off, r, ax = cx, ay = cy), Spec(InputControl.X, cx - off, cy, r, ax = cx, ay = cy),
        Spec(InputControl.B, cx + off, cy, r, ax = cx, ay = cy), Spec(InputControl.A, cx, cy + off, r, ax = cx, ay = cy))

    private fun dpad(cx: Float, cy: Float, half: Float) =
        Spec(InputControl.LS, cx, cy, half, id = DPAD_ID, kind = InputElementKind.DPAD, shape = InputButtonShape.ROUNDED, name = "D-pad")

    private fun keyboard(cx: Float) = Spec(InputControl.A, cx, 848f, 46f, id = KEYBOARD_ID, icon = InputButtonIcons.KEYBOARD, name = "Keyboard")

    private fun specs(kit: PadKit): List<Spec> = when {
        kit.steam -> shoulders() + listOf(
            Spec(InputControl.L4, 30f, 470f, 30f, 58f, shape = InputButtonShape.ROUNDED), Spec(InputControl.L5, 30f, 590f, 30f, 58f, shape = InputButtonShape.ROUNDED),
            Spec(InputControl.R4, 1570f, 470f, 30f, 58f, shape = InputButtonShape.ROUNDED), Spec(InputControl.R5, 1570f, 590f, 30f, 58f, shape = InputButtonShape.ROUNDED),
            dpad(215f, 330f, 112f)) + face(1385f, 330f, 56f, 92f) + listOf(
            Spec(InputControl.LS, 290f, 610f, 96f), Spec(InputControl.RS, 1310f, 610f, 96f),
            Spec(InputControl.LEFT_PAD, 480f, 345f, 95f, shape = InputButtonShape.ROUNDED), Spec(InputControl.RIGHT_PAD, 1120f, 345f, 95f, shape = InputButtonShape.ROUNDED),
            Spec(InputControl.SELECT, 660f, 150f, 46f, 26f, shape = InputButtonShape.PILL), Spec(InputControl.START, 940f, 150f, 46f, 26f, shape = InputButtonShape.PILL),
            Spec(InputControl.GUIDE, 370f, 852f, 66f, 30f, shape = InputButtonShape.PILL), Spec(InputControl.QAM, 1230f, 852f, 66f, 30f, shape = InputButtonShape.PILL),
            keyboard(136f))
        kit.playStation -> shoulders() + listOf(dpad(250f, 395f, 125f)) + face(1350f, 395f, 56f, 92f) + listOf(
            Spec(InputControl.LS, 565f, 690f, 100f), Spec(InputControl.RS, 1035f, 690f, 100f),
            Spec(InputControl.L3, 430f, 590f, 42f, ax = 565f, ay = 690f), Spec(InputControl.R3, 1170f, 590f, 42f, ax = 1035f, ay = 690f),
            Spec(InputControl.SELECT, 640f, 150f, 50f, 30f, shape = InputButtonShape.PILL), Spec(InputControl.START, 960f, 150f, 50f, 30f, shape = InputButtonShape.PILL),
            Spec(InputControl.GUIDE, 800f, 250f, 46f), Spec(InputControl.QAM, 220f, 848f, 46f), keyboard(136f))
        else -> shoulders() + listOf(Spec(InputControl.LS, 235f, 400f, 104f), dpad(470f, 675f, 118f)) + face(1375f, 400f, 58f, 94f) + listOf(
            Spec(InputControl.RS, 1130f, 690f, 104f), Spec(InputControl.L3, 385f, 290f, 42f, ax = 235f, ay = 400f), Spec(InputControl.R3, 1290f, 790f, 42f, ax = 1130f, ay = 690f),
            Spec(InputControl.SELECT, 690f, 162f, 38f), Spec(InputControl.GUIDE, 800f, 162f, 54f), Spec(InputControl.START, 910f, 162f, 38f),
            Spec(InputControl.QAM, 220f, 848f, 46f), keyboard(136f))
    }

    /** The default controls for [kit] on a [width] × [height] screen. */
    fun elements(context: Context, kit: PadKit, width: Int, height: Int, safe: Rect = Rect(), scale: Float = 1f): List<InputSurfaceElement> {
        if (width <= 0 || height <= 0) return emptyList()
        if (height > width) return portrait(context, kit, width, height, safe, scale)
        val w = width.toFloat(); val h = height.toFloat()
        val unit = (min(w / REF_W, h / REF_H) * cap(context, min(width, height)) * scale).coerceAtMost(min(w / MIN_SPAN, h / REF_H))
        fun x(ref: Float) = when {
            ref < REF_W / 3f -> safe.left + ref * unit
            ref > REF_W * 2f / 3f -> w - safe.right - (REF_W - ref) * unit
            else -> w / 2f + (ref - REF_W / 2f) * unit
        }
        fun y(ref: Float) = when {
            ref < REF_H / 3f -> safe.top + ref * unit
            ref > REF_H * 2f / 3f -> h - safe.bottom - (REF_H - ref) * unit
            else -> h / 2f + (ref - REF_H / 2f) * unit
        }
        val density = context.resources.displayMetrics.density
        var edge = safe.left + CORNER_DP * density
        val specs = specs(kit)
        val row = specs.filter { it.cy > REF_H * .88f && it.cx < REF_W / 3f }.sortedBy { it.cx }.associateWith { s ->
            val hw = s.hw * unit
            max(x(s.cx), edge + hw).also { edge = it + hw + GAP_DP * density }
        }
        return specs.map { s ->
            val cx = row[s] ?: (x(s.ax) + (s.cx - s.ax) * unit); val cy = y(s.ay) + (s.cy - s.ay) * unit; val hw = s.hw * unit; val hh = s.hh * unit
            InputSurfaceElement(s.control, (cx - hw) / w, (cy - hh) / h, 2 * hw / w, 2 * hh / h, s.id, s.kind, if (s.name == s.control.label) kit.name(s.control) else s.name,
                floating = s.kind == InputElementKind.STICK, buttonShape = s.shape, icon = s.icon).bounded()
        }
    }

    /**
     * A portrait surface gets the same pad as the panel under the game, in the lower part of the screen, with the keyboard,
     * Steam, Quick Access and stick-click buttons in a row along the bottom edge that starts after DroidDeck's corner button.
     */
    private fun portrait(context: Context, kit: PadKit, width: Int, height: Int, safe: Rect, scale: Float): List<InputSurfaceElement> {
        val w = width.toFloat(); val h = height.toFloat()
        val density = context.resources.displayMetrics.density
        val row = ROW_DP * density
        val inner = (w - safe.left - safe.right).coerceAtLeast(1f)
        val area = (min(h * .6f, inner * 1.15f) * scale).coerceAtMost(h - safe.top - safe.bottom - row).coerceAtLeast(1f)
        val top = h - safe.bottom - row - area
        val pad = dock(context, kit, inner.toInt(), area.toInt(), true, InputControl.LS, InputControl.RS, upright = true).map { e ->
            e.copy(x = (safe.left + e.x * inner) / w, y = (top + e.y * area) / h, width = e.width * inner / w, height = e.height * area / h,
                floating = e.kind == InputElementKind.STICK)
        }
        val specs = specs(kit)
        fun spec(control: InputControl, id: String = "legacy:${control.id}") = specs.firstOrNull { it.control == control && it.id == id }
        val gap = GAP_DP * density
        val leftRow = listOfNotNull(spec(InputControl.A, KEYBOARD_ID), spec(InputControl.GUIDE).takeIf { kit.steam }, spec(InputControl.L3))
        val rightRow = listOfNotNull(spec(InputControl.QAM), spec(InputControl.GUIDE).takeIf { !kit.steam }, spec(InputControl.R3))
        val start = safe.left + CORNER_DP * density
        val span = (w - safe.right - gap - start - gap * (leftRow.size + rightRow.size - 1)).coerceAtLeast(1f)
        val size = min(BUTTON_DP * density, span / (leftRow + rightRow).sumOf { (it.hw / it.hh).toDouble() }.toFloat())
        val cy = h - safe.bottom - row / 2f
        val placed = mutableListOf<InputSurfaceElement>()
        fun put(s: Spec, cx: Float) {
            val bw = size * s.hw / s.hh
            placed += InputSurfaceElement(s.control, (cx - bw / 2) / w, (cy - size / 2) / h, bw / w, size / h, s.id, s.kind,
                if (s.name == s.control.label) kit.name(s.control) else s.name, buttonShape = s.shape, icon = s.icon)
        }
        var left = start
        for (s in leftRow) {
            val bw = size * s.hw / s.hh
            put(s, left + bw / 2); left += bw + gap
        }
        var right = w - safe.right - gap
        for (s in rightRow) {
            val bw = size * s.hw / s.hh
            put(s, right - bw / 2); right -= bw + gap
        }
        return pad + placed
    }

    /** Controls for the panel below the game, in [GamepadGeometry]'s slots. */
    fun dock(context: Context, kit: PadKit, width: Int, height: Int, portrait: Boolean, left: InputControl, right: InputControl,
        upright: Boolean = false): List<InputSurfaceElement> {
        val w = width.toFloat(); val h = height.toFloat()
        val boxes = GamepadGeometry.layout(w, h, context.resources.displayMetrics.density, portrait, grips = kit.steam, upright = upright,
            trackpads = kit.steam)
        val placed = mutableListOf<InputSurfaceElement>()
        fun put(control: InputControl, box: GamepadGeometry.Box?, shape: InputButtonShape, id: String = "legacy:${control.id}",
            kind: InputElementKind = InputElementKind.from(control), name: String = kit.name(control)) {
            val b = box ?: return
            placed += InputSurfaceElement(control, b.left / w, b.top / h, b.width / w, b.height / h, id, kind, name, floating = false, buttonShape = shape).bounded()
        }
        fun square(box: GamepadGeometry.Box?) = box?.let { val side = min(it.width, it.height); GamepadGeometry.Box(it.cx, it.cy, side, side) }
        val four = GamepadGeometry.Slot.LEFT_TOUCH in boxes
        val leftMain = if (four || !kit.steam && left == InputControl.LEFT_PAD) InputControl.LS else left
        val rightMain = if (four || !kit.steam && right == InputControl.RIGHT_PAD) InputControl.RS else right
        if (four) {
            put(InputControl.LEFT_PAD, boxes[GamepadGeometry.Slot.LEFT_TOUCH], InputButtonShape.ROUNDED)
            put(InputControl.RIGHT_PAD, boxes[GamepadGeometry.Slot.RIGHT_TOUCH], InputButtonShape.ROUNDED)
        }
        put(leftMain, square(boxes[GamepadGeometry.Slot.LEFT_MAIN]), InputButtonShape.CIRCLE)
        put(InputControl.LS, square(boxes[GamepadGeometry.Slot.LEFT_SECOND]), InputButtonShape.ROUNDED, DPAD_ID, InputElementKind.DPAD, "D-pad")
        put(rightMain, square(boxes[GamepadGeometry.Slot.RIGHT_MAIN]), InputButtonShape.CIRCLE)
        square(boxes[GamepadGeometry.Slot.RIGHT_SECOND])?.let { b ->
            val r = b.width * .17f; val off = b.width * .3f
            listOf(InputControl.Y to (0f to -1f), InputControl.B to (1f to 0f), InputControl.A to (0f to 1f), InputControl.X to (-1f to 0f)).forEach { (c, d) ->
                put(c, GamepadGeometry.Box(b.cx + d.first * off, b.cy + d.second * off, 2 * r, 2 * r), InputButtonShape.CIRCLE)
            }
        }
        put(InputControl.LT, boxes[GamepadGeometry.Slot.LEFT_OUTER_SHOULDER], InputButtonShape.PILL)
        put(InputControl.LB, boxes[GamepadGeometry.Slot.LEFT_INNER_SHOULDER], InputButtonShape.PILL)
        put(InputControl.RB, boxes[GamepadGeometry.Slot.RIGHT_INNER_SHOULDER], InputButtonShape.PILL)
        put(InputControl.RT, boxes[GamepadGeometry.Slot.RIGHT_OUTER_SHOULDER], InputButtonShape.PILL)
        put(InputControl.SELECT, boxes[GamepadGeometry.Slot.VIEW], InputButtonShape.PILL)
        put(InputControl.START, boxes[GamepadGeometry.Slot.MENU], InputButtonShape.PILL)
        if (kit.steam) listOf(InputControl.L4 to GamepadGeometry.Slot.L4, InputControl.L5 to GamepadGeometry.Slot.L5,
            InputControl.R5 to GamepadGeometry.Slot.R5, InputControl.R4 to GamepadGeometry.Slot.R4).forEach { (c, slot) -> put(c, boxes[slot], InputButtonShape.ROUNDED) }
        return placed
    }

    private fun cap(context: Context, shortSide: Int): Float {
        val metrics = context.resources.displayMetrics
        val nominal = metrics.densityDpi.toFloat()
        val dpi = metrics.ydpi.takeIf { it > nominal * .6f && it < nominal * 1.6f } ?: nominal
        val mm = shortSide / dpi * 25.4f
        return min(1f, MAX_HEIGHT_MM / max(mm, 1f))
    }
}
