package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Rect
import kotlin.math.*

internal class LegacyGamepadLayout(private val context: Context, private val width: Int, private val height: Int) {
    private val resources get() = context.resources
    private val settings = ControllerPrefs.read(context)
    private val safe = Rect()
    private var fit = 1f
    private class Control(val id: String, val group: String, val stick: Int, val radiusDp: Float, val offsetXDp: Float, val offsetYDp: Float) {
        var radius = 0f
        var cx = 0f
        var cy = 0f
        val halfW get() = if (id in shoulderIds) radius * SHOULDER_WIDTH else radius
        val halfH get() = if (id in shoulderIds) radius * SHOULDER_HEIGHT else radius
    }
    private val controls = listOf(
        Control("up", "dpad", -1, 30f, 0f, -50f),
        Control("right", "dpad", -1, 30f, 50f, 0f),
        Control("down", "dpad", -1, 30f, 0f, 50f),
        Control("left", "dpad", -1, 30f, -50f, 0f),
        Control("ls", "ls", 0, 48f, 0f, 0f),
        Control("rs", "rs", 1, 48f, 0f, 0f),
        Control("a", "face", -1, 30f, 0f, 50f),
        Control("b", "face", -1, 30f, 50f, 0f),
        Control("x", "face", -1, 30f, -50f, 0f),
        Control("y", "face", -1, 30f, 0f, -50f),
        Control("lb", "lb", -1, 24f, 0f, 0f),
        Control("rb", "rb", -1, 24f, 0f, 0f),
        Control("lt", "lt", -1, 24f, 0f, 0f),
        Control("rt", "rt", -1, 24f, 0f, 0f),
        Control("select", "select", -1, 20f, 0f, 0f),
        Control("start", "start", -1, 20f, 0f, 0f),
        Control("guide", "guide", -1, 22f, 0f, 0f),
        Control("qam", "qam", -1, 22f, 0f, 0f),
    )

    private val groups = controls.map { it.group }.distinct()

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun scaled(value: Float) = dp(value) * settings.size / 100f * fit

    private fun fitScale(): Float {
        val metrics = resources.displayMetrics
        val heightMm = (height - safe.top - safe.bottom) / pxPerMm(metrics.ydpi)
        val faceMm = dp(60f) / pxPerMm(metrics.xdpi)
        val floor = (MIN_FACE_MM / faceMm).coerceAtMost(1f)
        return (heightMm / FULL_SIZE_HEIGHT_MM).coerceIn(floor, 1f)
    }

    private fun pxPerMm(dpi: Float): Float {
        val nominal = resources.displayMetrics.densityDpi.toFloat()
        return (if (dpi > nominal * 0.6f && dpi < nominal * 1.6f) dpi else nominal) / 25.4f
    }

    private fun placeAuto(w: Float, h: Float) {
        val metrics = resources.displayMetrics
        val mmX = pxPerMm(metrics.xdpi)
        val mmY = pxPerMm(metrics.ydpi)
        val usableW = w - safe.left - safe.right
        val usableH = h - safe.top - safe.bottom
        val inboard = (0.17f * usableW / mmX).coerceIn(24f, 40f) * mmX
        val lift = (0.285f * usableH / mmY).coerceIn(22f, 45f) * mmY
        val gap = dp(12f)
        val floor = BOTTOM_MARGIN_MM * mmY
        val drop = max(drop("dpad", "ls", gap), drop("rs", "face", gap))
        val restY = min(h - safe.bottom - lift, h - safe.bottom - floor - drop)
        put("ls", safe.left + inboard, restY)
        put("face", w - safe.right - inboard, restY)
        besideBelow("dpad", "ls", 1f, gap, floor)
        besideBelow("rs", "face", -1f, gap, floor)
        val phone = usableH / mmY < PHONE_MAX_HEIGHT_MM
        val shoulderY = if (phone) safe.top + floor + halfH("lb")
            else min(centre("ls").second - extent("ls"), centre("face").second - extent("face")) - gap - halfH("lb")
        shoulders("ls", "lb", "lt", 1f, gap, shoulderY)
        shoulders("face", "rb", "rt", -1f, gap, shoulderY)
        val bottom = h - safe.bottom - floor - extent("guide")
        val spread = extent("guide") + gap / 2f
        put("guide", w / 2f - spread, bottom)
        put("qam", w / 2f + spread, bottom)
        val inner = max(centre("dpad").first + extent("dpad"), w - centre("rs").first + extent("rs")) + gap + extent("select")
        put("select", inner, restY)
        put("start", w - inner, restY)
        if (phone) {
            val top = max(centre("lb").first + extent("lb"), w - centre("rb").first + extent("rb")) + gap * 2.5f + extent("select")
            put("select", top, shoulderY)
            put("start", w - top, shoulderY)
        }
        if (crowded("select", gap) || crowded("start", gap)) {
            above("select", "dpad", gap)
            val (sx, sy) = centre("select")
            put("start", w - sx, sy)
            if (crowded("start", gap)) above("start", "rs", gap)
        }
        if (crowded("guide", gap, gap, "qam") || crowded("qam", gap, gap, "guide")) {
            val row = centre("select").second
            put("guide", w / 2f - spread, row)
            put("qam", w / 2f + spread, row)
            if (crowded("guide", gap, gap, "qam") || crowded("qam", gap, gap, "guide")) {
                val top = safe.top + dp(8f) + extent("guide")
                put("guide", w / 2f - spread, top)
                put("qam", w / 2f + spread, top)
            }
        }
    }

    private fun drop(group: String, anchor: String, gap: Float): Float =
        (extent(anchor) + extent(group) + gap) * sin(Math.toRadians(DROP_DEGREES)).toFloat() + extent(group)

    private fun besideBelow(group: String, anchor: String, inward: Float, gap: Float, margin: Float) {
        val (ax, ay) = centre(anchor)
        val reach = extent(anchor) + extent(group) + gap
        val floor = height - safe.bottom - margin - extent(group)
        val dy = min(reach * sin(Math.toRadians(DROP_DEGREES)).toFloat(), floor - ay)
        val dx = sqrt(max(0f, reach * reach - dy * dy))
        put(group, ax + inward * dx, ay + dy)
    }

    private fun shoulders(anchor: String, bumper: String, trigger: String, inward: Float, gap: Float, y: Float) {
        val (ax, _) = centre(anchor)
        val spread = extent(bumper) + gap / 2f
        put(bumper, ax + inward * spread, y)
        put(trigger, ax - inward * spread, y)
    }

    private fun above(group: String, anchor: String, gap: Float) {
        val (ax, ay) = centre(anchor)
        put(group, ax, ay - extent(anchor) - gap - extent(group))
    }

    private fun crowded(group: String, gap: Float, space: Float = gap / 2f, except: String? = null): Boolean {
        val (x, y) = centre(group)
        return groups.any { other ->
            if (other == group || other == except) return@any false
            val (ox, oy) = centre(other)
            val dx = x - ox
            val dy = y - oy
            sqrt(dx * dx + dy * dy) < extent(group) + extent(other) + space
        }
    }

    private fun members(group: String) = controls.filter { it.group == group }

    private fun centre(group: String): Pair<Float, Float> {
        val first = members(group).first()
        return (first.cx - scaled(first.offsetXDp)) to (first.cy - scaled(first.offsetYDp))
    }

    private fun extent(group: String): Float = members(group).maxOf { control ->
        sqrt(scaled(control.offsetXDp).let { it * it } + scaled(control.offsetYDp).let { it * it }) + control.halfW
    }

    private fun halfH(group: String): Float = members(group).maxOf { abs(scaled(it.offsetYDp)) + it.halfH }

    private fun put(group: String, x: Float, y: Float) {
        for (control in members(group)) {
            control.cx = x + scaled(control.offsetXDp)
            control.cy = y + scaled(control.offsetYDp)
        }
    }

    private fun clamp(group: String) {
        val list = members(group)
        val margin = dp(4f)
        val minX = list.minOf { it.cx - it.halfW } - safe.left - margin
        val maxX = list.maxOf { it.cx + it.halfW } - (width - safe.right - margin)
        val minY = list.minOf { it.cy - it.halfH } - safe.top - margin
        val maxY = list.maxOf { it.cy + it.halfH } - (height - safe.bottom - margin)
        val dx = if (minX < 0) -minX else if (maxX > 0) -maxX else 0f
        val dy = if (minY < 0) -minY else if (maxY > 0) -maxY else 0f
        if (dx == 0f && dy == 0f) return
        for (control in list) { control.cx += dx; control.cy += dy }
    }


    fun elements(): List<InputSurfaceElement> {
        fit = fitScale()
        controls.forEach { it.radius = scaled(it.radiusDp) }
        placeAuto(width.toFloat(), height.toFloat())
        for ((group, position) in ControllerPrefs.layout(context, width, height)) {
            if (group in groups && position.first.isFinite() && position.second.isFinite()) put(group, position.first * width, position.second * height)
        }
        groups.forEach(::clamp)
        val result = controls.filter {
            settings.mapping[it.id] != ControllerPrefs.OFF && (it.id != "guide" || settings.steamButton) && (it.id != "qam" || settings.qamButton)
        }.map { c ->
            InputSurfaceElement(requireNotNull(InputControl.fromId(c.id)), (c.cx - c.halfW) / width, (c.cy - c.halfH) / height,
                2 * c.halfW / width, 2 * c.halfH / height, floating = settings.adaptiveSticks, hideWhenIdle = c.stick >= 0 && settings.adaptiveSticks,
                buttonShape = if (c.id in shoulderIds) InputButtonShape.PILL else InputButtonShape.CIRCLE).let(::widenStick).bounded()
        }.toMutableList()
        if (settings.keyboardButton) result += keyboard()
        return result
    }

    private fun keyboard(): InputSurfaceElement {
        val radius = dp(KEYBOARD_RADIUS_DP)
        val cx = safe.left + dp(KEYBOARD_X_DP)
        val cy = height - safe.bottom - dp(KEYBOARD_Y_DP)
        return InputSurfaceElement(InputControl.A, (cx - radius) / width, (cy - radius) / height, 2 * radius / width, 2 * radius / height,
            KEYBOARD_ID, name = "Keyboard", icon = InputButtonIcons.KEYBOARD, buttonShape = InputButtonShape.CIRCLE).bounded()
    }

    fun restyle(elements: List<InputSurfaceElement>): List<InputSurfaceElement> {
        val dpad = elements.filter { it.id == "legacy:${it.control.id}" && it.control in DPAD && it.buttonShape == InputButtonShape.ROUNDED }
        val centreX = dpad.map { (it.x + it.width / 2f) * width }.average().toFloat()
        val centreY = dpad.map { (it.y + it.height / 2f) * height }.average().toFloat()
        val resize = untouchedDpad(dpad, centreX, centreY)
        return elements.map { e ->
            when {
                e.id == KEYBOARD_ID && e.icon == 0 && e.name == "Keyboard" && e.buttonShape == InputButtonShape.ROUNDED &&
                    e.x == .02f && e.y == .9f && e.width == .12f && e.height == .08f -> keyboard()
                e.id == "legacy:${e.control.id}" && e.buttonShape == InputButtonShape.ROUNDED -> {
                    val round = e.copy(buttonShape = if (e.control.id in shoulderIds) InputButtonShape.PILL else InputButtonShape.CIRCLE)
                    (if (resize && e.control in DPAD) resizeDpad(round, centreX, centreY) else widenStick(round)).bounded()
                }
                else -> e
            }
        }
    }

    private fun untouchedDpad(dpad: List<InputSurfaceElement>, centreX: Float, centreY: Float): Boolean {
        if (dpad.size != DPAD.size) return false
        val radius = dpad[0].width * width / 2f
        val tolerance = 1.5f
        val offset = radius * LEGACY_DPAD_OFFSET_DP / LEGACY_DPAD_RADIUS_DP
        return dpad.all { e ->
            val old = controls.first { it.id == e.control.id }
            val dx = (e.x + e.width / 2f) * width - centreX
            val dy = (e.y + e.height / 2f) * height - centreY
            abs(e.width * width / 2f - radius) <= tolerance && abs(e.height * height / 2f - radius) <= tolerance &&
                abs(dx - offset * sign(old.offsetXDp)) <= tolerance && abs(dy - offset * sign(old.offsetYDp)) <= tolerance
        }
    }

    private fun resizeDpad(e: InputSurfaceElement, centreX: Float, centreY: Float): InputSurfaceElement {
        val old = controls.first { it.id == e.control.id }
        val radius = e.width * width / 2f * old.radiusDp / LEGACY_DPAD_RADIUS_DP
        val cx = centreX + ((e.x + e.width / 2f) * width - centreX) * abs(old.offsetXDp + old.offsetYDp) / LEGACY_DPAD_OFFSET_DP
        val cy = centreY + ((e.y + e.height / 2f) * height - centreY) * abs(old.offsetXDp + old.offsetYDp) / LEGACY_DPAD_OFFSET_DP
        return e.copy(x = (cx - radius) / width, y = (cy - radius) / height, width = 2 * radius / width, height = 2 * radius / height)
    }

    private fun widenStick(e: InputSurfaceElement): InputSurfaceElement {
        val extra = e.width * (STICK_REACH - 1f)
        return when (e.control) {
            InputControl.LS -> (e.x - extra).coerceAtLeast(0f).let { x -> e.copy(x = x, width = e.x + e.width - x) }
            InputControl.RS -> e.copy(width = (e.width + extra).coerceAtMost(1f - e.x))
            else -> e
        }
    }
    private companion object {
        const val MIN_FACE_MM = 9.5f
        const val FULL_SIZE_HEIGHT_MM = 85f
        const val BOTTOM_MARGIN_MM = 5f
        const val DROP_DEGREES = 35.0
        const val PHONE_MAX_HEIGHT_MM = 90f
        const val SHOULDER_WIDTH = 1.45f
        const val SHOULDER_HEIGHT = 0.8f
        val shoulderIds = setOf("lb", "rb", "lt", "rt")
        const val STICK_REACH = 1.25f
        const val LEGACY_DPAD_RADIUS_DP = 26f
        const val LEGACY_DPAD_OFFSET_DP = 52f
        val DPAD = setOf(InputControl.UP, InputControl.RIGHT, InputControl.DOWN, InputControl.LEFT)
        const val KEYBOARD_ID = "builtin:keyboard"
        const val KEYBOARD_RADIUS_DP = 18f
        const val KEYBOARD_X_DP = 90f
        const val KEYBOARD_Y_DP = 34f
    }
}
