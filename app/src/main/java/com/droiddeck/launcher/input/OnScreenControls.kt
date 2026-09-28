package com.droiddeck.launcher.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@SuppressLint("ViewConstructor")
class OnScreenControls(context: Context, private val pad: PadBridge?, private val editing: Boolean = false) : View(context) {

    private class Control(
        val id: String,
        val group: String,
        val stick: Int,
        val radiusDp: Float,
        val offsetXDp: Float,
        val offsetYDp: Float,
    ) {
        var target = id
        var radius = 0f
        var cx = 0f
        var cy = 0f
        var pressedBy = -1
        var kx = 0f
        var ky = 0f
        var ax = 0f
        var ay = 0f
        var dirty = false
        var clicked = false
        var lastUp = 0L
        val wide = id in shoulderIds
        val halfW get() = if (wide) radius * SHOULDER_WIDTH else radius
        val halfH get() = if (wide) radius * SHOULDER_HEIGHT else radius

        fun contains(x: Float, y: Float, r: Float, padding: Float = 0f): Boolean {
            val dx = x - cx
            val dy = y - cy
            if (wide) return abs(dx) <= halfW + r * 0.25f + padding && abs(dy) <= halfH + r * 0.25f + padding
            val hit = r * 1.25f + padding
            return dx * dx + dy * dy <= hit * hit
        }

        fun drag(x: Float, y: Float) {
            var dx = x - ax
            var dy = y - ay
            val d = sqrt(dx * dx + dy * dy)
            if (d > radius) { dx = dx / d * radius; dy = dy / d * radius }
            kx = dx; ky = dy; dirty = true
        }
    }

    private val controls = listOf(
        Control("up", "dpad", -1, 26f, 0f, -52f),
        Control("right", "dpad", -1, 26f, 52f, 0f),
        Control("down", "dpad", -1, 26f, 0f, 52f),
        Control("left", "dpad", -1, 26f, -52f, 0f),
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

    private var buttonsOnly = false
    private var settings = ControllerPrefs.read(context)
    private var safe = Rect()
    private var selected: String? = null
    private var grabX = 0f
    private var grabY = 0f
    private var ignoreSaved = false
    private var fit = 1f

    private var idleFill = 0
    private var heldFill = 0
    private var idleStroke = 0
    private var heldStroke = 0
    private var idleText = 0
    private var stickFill = 0
    private var knobFill = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arrow = Path()
    private val box = RectF()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    init {
        applySettings()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun scaled(value: Float) = dp(value) * settings.size / 100f * fit

    private fun fitScale(): Float {
        val metrics = resources.displayMetrics
        val heightMm = (height - safe.top - safe.bottom) / pxPerMm(metrics.ydpi)
        val faceMm = dp(60f) / pxPerMm(metrics.xdpi)
        val floor = (MIN_FACE_MM / faceMm).coerceAtMost(1f)
        return (heightMm / FULL_SIZE_HEIGHT_MM).coerceIn(floor, 1f)
    }

    fun reload() {
        releaseAll()
        settings = ControllerPrefs.read(context)
        applySettings()
        relayout()
    }

    private fun applySettings() {
        for (control in controls) control.target = settings.mapping[control.id] ?: control.id
        val tint = settings.tint
        val alpha = settings.opacity / 100f
        fun shade(a: Int, f: Float) = Color.argb((a * alpha).toInt(), (Color.red(tint) * f).toInt(), (Color.green(tint) * f).toInt(), (Color.blue(tint) * f).toInt())
        fun light(a: Int, f: Float) = Color.argb(
            (a * alpha).toInt(),
            (Color.red(tint) + (255 - Color.red(tint)) * f).toInt(),
            (Color.green(tint) + (255 - Color.green(tint)) * f).toInt(),
            (Color.blue(tint) + (255 - Color.blue(tint)) * f).toInt(),
        )
        idleFill = shade(80, 0.12f)
        heldFill = shade(160, 1f)
        idleStroke = light(150, 0.25f)
        heldStroke = light(230, 0.7f)
        idleText = light(210, 0.75f)
        stickFill = shade(60, 0.12f)
        knobFill = shade(120, 0.35f)
        stroke.strokeWidth = dp(1.5f)
    }

    fun setButtonsOnly(enabled: Boolean) {
        if (buttonsOnly == enabled) return
        releaseAll()
        buttonsOnly = enabled
        relayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
        val fresh = Rect(cutout?.safeInsetLeft ?: 0, cutout?.safeInsetTop ?: 0, cutout?.safeInsetRight ?: 0, cutout?.safeInsetBottom ?: 0)
        if (fresh != safe) { safe = fresh; relayout() }
        return super.onApplyWindowInsets(insets)
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    private fun relayout() {
        if (width <= 0 || height <= 0) return
        fit = if (buttonsOnly && !editing) 1f else fitScale()
        for (control in controls) control.radius = if (buttonsOnly && !editing && (control.id == "guide" || control.id == "qam")) dp(30f) else scaled(control.radiusDp)
        if (buttonsOnly && !editing) {
            val inset = dp(44f)
            put("guide", safe.left + inset, height - safe.bottom - inset)
            put("qam", width - safe.right - inset, height - safe.bottom - inset)
        } else {
            placeAuto(width.toFloat(), height.toFloat())
            if (!ignoreSaved) for ((group, pos) in ControllerPrefs.layout(context, width, height)) {
                if (group in groups) put(group, pos.first * width, pos.second * height)
            }
        }
        groups.forEach { clamp(it) }
        invalidate()
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

    fun saveLayout() {
        if (width <= 0 || height <= 0) return
        if (ignoreSaved) { ControllerPrefs.resetLayout(context, width, height); return }
        ControllerPrefs.setLayout(context, width, height, groups.associateWith { group ->
            val (x, y) = centre(group)
            x / width to y / height
        })
    }

    fun resetLayout() {
        ControllerPrefs.resetLayout(context, width, height)
        ignoreSaved = true
        selected = null
        relayout()
    }

    private fun label(control: Control): String = when (control.target) {
        "ls" -> "L"
        "rs" -> "R"
        "select" -> "⧉"
        "start" -> "☰"
        "guide" -> if (buttonsOnly && !editing) "Steam" else "◉"
        "qam" -> "⋯"
        else -> control.target.uppercase()
    }

    override fun onDraw(canvas: Canvas) {
        for (control in controls) {
            if (!isVisible(control)) continue
            if (control.stick >= 0 && settings.adaptiveSticks && !editing && control.pressedBy == -1) continue
            val held = control.pressedBy != -1
            val radius = control.radius
            if (control.stick >= 0) {
                val bx = if (held) control.ax else control.cx
                val by = if (held) control.ay else control.cy
                fill.color = if (held) heldFill else stickFill
                canvas.drawCircle(bx, by, radius, fill)
                stroke.color = if (held) heldStroke else idleStroke
                canvas.drawCircle(bx, by, radius, stroke)
                val knob = radius * 0.46f
                fill.color = if (held || control.clicked) heldFill else knobFill
                canvas.drawCircle(bx + control.kx, by + control.ky, knob, fill)
                stroke.color = if (held) heldStroke else idleStroke
                canvas.drawCircle(bx + control.kx, by + control.ky, knob, stroke)
                text.color = if (held) Color.WHITE else idleText
                text.textSize = knob * 0.8f
                canvas.drawText(label(control), bx + control.kx, by + control.ky + text.textSize * 0.35f, text)
            } else if (control.wide) {
                box.set(control.cx - control.halfW, control.cy - control.halfH, control.cx + control.halfW, control.cy + control.halfH)
                val corner = control.halfH * 0.55f
                fill.color = if (held) heldFill else idleFill
                canvas.drawRoundRect(box, corner, corner, fill)
                stroke.color = if (held) heldStroke else idleStroke
                canvas.drawRoundRect(box, corner, corner, stroke)
                text.color = if (held) Color.WHITE else idleText
                text.textSize = control.halfH * 0.8f
                canvas.drawText(label(control), control.cx, control.cy + text.textSize * 0.35f, text)
            } else {
                fill.color = if (held) heldFill else idleFill
                canvas.drawCircle(control.cx, control.cy, radius, fill)
                stroke.color = if (held) heldStroke else idleStroke
                canvas.drawCircle(control.cx, control.cy, radius, stroke)
                text.color = if (held) Color.WHITE else idleText
                val direction = directions.indexOf(control.target)
                if (direction >= 0) {
                    drawArrow(canvas, control.cx, control.cy, radius * 0.34f, direction)
                    continue
                }
                val name = label(control)
                text.textSize = if (name.length > 2) dp(11f) else radius * if (name.length == 2) 0.62f else 0.85f
                canvas.drawText(name, control.cx, control.cy + text.textSize * 0.35f, text)
            }
        }
        val group = selected ?: return
        val (x, y) = centre(group)
        stroke.color = heldStroke
        val single = members(group).singleOrNull()
        if (single?.wide == true) {
            val pad = dp(6f)
            box.set(x - single.halfW - pad, y - single.halfH - pad, x + single.halfW + pad, y + single.halfH + pad)
            canvas.drawRoundRect(box, single.halfH, single.halfH, stroke)
        } else canvas.drawCircle(x, y, extent(group) + dp(6f), stroke)
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, size: Float, direction: Int) {
        val (fx, fy) = when (direction) {
            0 -> 0f to -1f
            1 -> 1f to 0f
            2 -> 0f to 1f
            else -> -1f to 0f
        }
        arrow.reset()
        arrow.moveTo(x + fx * size, y + fy * size)
        arrow.lineTo(x - fx * size * 0.7f - fy * size, y - fy * size * 0.7f + fx * size)
        arrow.lineTo(x - fx * size * 0.7f + fy * size, y - fy * size * 0.7f - fx * size)
        arrow.close()
        fill.color = text.color
        canvas.drawPath(arrow, fill)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editing) return onEditTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                val control = controlAt(x, y) ?: adaptiveStickAt(x, y) ?: return false
                if (control.pressedBy != -1) return true
                control.pressedBy = event.getPointerId(index)
                if (control.stick >= 0) {
                    control.clicked = settings.stickClick && control.lastUp > 0L && event.eventTime - control.lastUp < DOUBLE_TAP_MS
                    val adaptive = settings.adaptiveSticks
                    control.ax = if (adaptive) event.getX(index) else control.cx
                    control.ay = if (adaptive) event.getY(index) else control.cy
                    control.drag(event.getX(index), event.getY(index))
                }
                requestUnbufferedDispatch(event)
                apply()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                var changed = false
                for (index in 0 until event.pointerCount) {
                    val pointer = event.getPointerId(index)
                    val x = event.getX(index)
                    val y = event.getY(index)
                    val stick = controls.firstOrNull { it.stick >= 0 && it.pressedBy == pointer }
                    if (stick != null) { stick.drag(x, y); changed = true; continue }
                    val over = controlAt(x, y)?.takeIf { it.stick < 0 }
                    for (control in controls) {
                        if (control.stick < 0 && control.pressedBy == pointer && control !== over) {
                            control.pressedBy = -1
                            changed = true
                        }
                    }
                    if (over != null && over.pressedBy == -1) {
                        over.pressedBy = pointer
                        changed = true
                    }
                }
                if (changed) apply()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val pointer = event.getPointerId(event.actionIndex)
                var changed = false
                for (control in controls) {
                    if (control.pressedBy == pointer || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        if (control.pressedBy != -1) changed = true
                        if (control.stick >= 0 && control.pressedBy != -1) control.lastUp = if (control.clicked || event.actionMasked == MotionEvent.ACTION_CANCEL) 0L else event.eventTime
                        control.pressedBy = -1
                        control.clicked = false
                        if (control.stick >= 0 && (control.kx != 0f || control.ky != 0f)) control.dirty = true
                        control.kx = 0f; control.ky = 0f
                    }
                }
                if (changed) apply()
                return true
            }
        }
        return false
    }

    private fun onEditTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val control = controlAt(event.x, event.y)
                selected = control?.group
                val group = selected ?: run { invalidate(); return false }
                val (x, y) = centre(group)
                grabX = x - event.x
                grabY = y - event.y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val group = selected ?: return true
                ignoreSaved = false
                put(group, event.x + grabX, event.y + grabY)
                clamp(group)
                invalidate()
            }
        }
        return true
    }

    private fun controlAt(x: Float, y: Float): Control? =
        controls.firstOrNull {
            isVisible(it) && (editing || !settings.adaptiveSticks || it.stick < 0) && it.contains(x, y, it.radius)
        }

    private fun adaptiveStickAt(x: Float, y: Float): Control? {
        if (!settings.adaptiveSticks || editing || buttonsOnly) return null
        if (x < safe.left || x >= width - safe.right || y < safe.top || y >= height - safe.bottom) return null
        if (controls.any { it.stick < 0 && isVisible(it) && it.contains(x, y, it.radius, 5f) }) return null
        return controls.filter {
            val dx = x - it.cx
            val dy = y - it.cy
            val reach = it.radius * 1.4f * sqrt(1.5f)
            it.stick >= 0 && isVisible(it) && it.pressedBy == -1 && dx * dx + dy * dy <= reach * reach
        }.minByOrNull {
            val dx = x - it.cx
            val dy = y - it.cy
            dx * dx + dy * dy
        }
    }

    private fun isVisible(control: Control): Boolean = when {
        buttonsOnly && !editing -> control.id == "guide" || control.id == "qam"
        else -> control.target != ControllerPrefs.OFF
    }

    private fun apply() {
        val bridge = pad
        if (bridge == null || editing) { invalidate(); return }
        val qamHeld = controls.any { it.id == "qam" && it.pressedBy != -1 }
        if (buttonsOnly) {
            bridge.setSystemButtons(controls.any { it.id == "guide" && it.pressedBy != -1 }, qamHeld)
        } else {
            bridge.setSystemButtons(false, qamHeld)
            bridge.applyTouch { state ->
                val used = HashSet<String>()
                val held = HashSet<String>()
                for (control in controls) {
                    when {
                        control.stick >= 0 -> {
                            val click = if (control.stick == 0) "l3" else "r3"
                            if (settings.stickClick) used.add(click)
                            if (control.clicked) held.add(click)
                            if (!control.dirty) continue
                            if (control.stick == 0) { state.leftX = control.kx / control.radius; state.leftY = control.ky / control.radius }
                            else { state.rightX = control.kx / control.radius; state.rightY = control.ky / control.radius }
                            control.dirty = false
                        }
                        control.id == "qam" || control.target == ControllerPrefs.OFF -> {}
                        else -> {
                            used.add(control.target)
                            if (control.pressedBy != -1) held.add(control.target)
                        }
                    }
                }
                for (target in used) write(state, target, target in held)
            }
        }
        invalidate()
    }

    private fun write(state: PadState, target: String, down: Boolean) {
        when (target) {
            "a" -> state.press(PadState.A, down)
            "b" -> state.press(PadState.B, down)
            "x" -> state.press(PadState.X, down)
            "y" -> state.press(PadState.Y, down)
            "lb" -> state.press(PadState.LB, down)
            "rb" -> state.press(PadState.RB, down)
            "select" -> state.press(PadState.SELECT, down)
            "start" -> state.press(PadState.START, down)
            "l3" -> state.press(PadState.L3, down)
            "r3" -> state.press(PadState.R3, down)
            "guide" -> state.press(PadState.GUIDE, down)
            "lt" -> state.leftTrigger = if (down) 1f else 0f
            "rt" -> state.rightTrigger = if (down) 1f else 0f
            "up" -> state.up = down
            "right" -> state.right = down
            "down" -> state.down = down
            "left" -> state.left = down
        }
    }

    fun releaseAll() {
        if (controls.none { it.pressedBy != -1 || it.clicked } && !buttonsOnly) return
        controls.forEach {
            it.pressedBy = -1
            it.clicked = false
            it.lastUp = 0L
            if (it.stick >= 0 && (it.kx != 0f || it.ky != 0f)) it.dirty = true
            it.kx = 0f; it.ky = 0f
        }
        apply()
    }

    private companion object {
        const val DOUBLE_TAP_MS = 300L
        const val MIN_FACE_MM = 9.5f
        const val FULL_SIZE_HEIGHT_MM = 85f
        const val BOTTOM_MARGIN_MM = 5f
        const val DROP_DEGREES = 35.0
        const val PHONE_MAX_HEIGHT_MM = 90f
        const val SHOULDER_WIDTH = 1.45f
        const val SHOULDER_HEIGHT = 0.8f
        val shoulderIds = setOf("lb", "rb", "lt", "rt")
        val directions = listOf("up", "right", "down", "left")
    }
}
