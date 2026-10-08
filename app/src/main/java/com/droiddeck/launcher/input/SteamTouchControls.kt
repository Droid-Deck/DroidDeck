package com.droiddeck.launcher.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import com.droiddeck.launcher.input.SteamTouchConfig.Element
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Steam's touch controls, drawn by the app.
 *
 * The layout is Steam's: the game's touch config for the action set the client says is active
 * (SteamTouchDevice.State, SteamTouchConfig), placed where the config's layout puts each control or
 * where Steam Link would by default, and only the controls the config binds. Touches become the
 * touch controller's input report; the client does the rest - bindings, action sets, the virtual
 * pad the game reads. Each control shows its binding's icon, label and colours, as Steam keeps them
 * (SteamTouchBindings). [startEditing] moves, resizes and hides controls, picks their icons and the
 * layout's colour, and saves all of it into the game's touch config, as Steam Link and Steam's
 * configurator do.
 */
@SuppressLint("ViewConstructor")
class SteamTouchControls(
    context: Context,
    val device: SteamTouchDevice,
    private val onMenu: () -> Unit,
    private val onKeyboard: () -> Unit,
) : View(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()

    private var config = SteamTouchConfig.Config(null, null, null, emptyMap())
    private var configApp = -1
    private var appId = 0
    private var actionSet = 0
    private var layers: List<Int> = emptyList()
    private var actionSeq = -1
    private var opened = 0
    private var elements: List<Element> = emptyList()
    private var tint = Color.WHITE
    private var alphaScale = 0.45f

    // What each control is bound to (its icon, label, colours), per element type; the D-pad has four.
    private class Visual(val ref: SteamTouchBindings.Ref?, val binding: SteamTouchBindings.Binding?)
    private var visuals: Map<Int, List<Visual>> = emptyMap()

    // Touch state.
    private class Finger(val element: Element?, var x: Float, var y: Float, val toolbar: Int = -1)
    private val fingers = HashMap<Int, Finger>()

    // Editing.
    private var editing = false
    private var editElements: MutableList<Element> = mutableListOf()
    private var hidden: MutableList<Element> = mutableListOf()
    private var selected = -1
    private var dragOffset = 0f to 0f
    private var pinchStart = 0f
    private var pinchScale = 1f
    private var saving = false
    // Icon and colour edits not saved yet.
    private val pendingBindings = HashMap<SteamTouchBindings.Ref, SteamTouchBindings.Binding>()
    private var pendingColor: FloatArray? = null
    private var selectedArm = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    private val poll = object : Runnable {
        override fun run() {
            refreshState()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(poll)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(poll)
        releaseAll()
        super.onDetachedFromWindow()
    }

    /** Follows what the client tells the device: a new app loads its config, a new action set or
     *  layer redraws. */
    private fun refreshState() {
        val state = device.state()
        val app = if (state.appId == 0) BIG_PICTURE else state.appId
        // The client reads the config afresh when the controller (re)connects: so do we.
        val reconnected = state.opened > 0 && opened == 0
        opened = state.opened
        if (app != configApp || reconnected) {
            configApp = app
            reload(app)
        }
        if (state.actionSeq != actionSeq) {
            actionSeq = state.actionSeq
            actionSet = state.actionSet
            layers = state.layers
            appId = app
            rebuild()
        }
    }

    private fun reload(app: Int) {
        loader.execute {
            val loaded = SteamTouchConfig.load(context, app)
            handler.post {
                if (app != configApp) return@post
                config = loaded
                appId = app
                rebuild()
            }
        }
    }

    /** Re-reads the config for the current app (after a save). */
    fun reloadConfig() {
        configApp = -1
        refreshState()
    }

    private fun rebuild() {
        elements = SteamTouchConfig.elementsFor(config, actionSet, layers)
        applyColor(pendingColor ?: SteamTouchConfig.layoutColor(config, actionSet))
        rebuildVisuals()
        if (!editing) {
            Log.i(TAG, "steam touch: app $appId, action set $actionSet, layers $layers: ${elements.size} controls")
            releaseAll()
        }
        invalidate()
    }

    private fun applyColor(color: FloatArray?) {
        tint = if (color != null) Color.rgb((color[0] * 255).toInt(), (color[1] * 255).toInt(), (color[2] * 255).toInt()) else Color.WHITE
        alphaScale = color?.get(3)?.coerceIn(0.15f, 1f) ?: 0.45f
    }

    /** Each control's bindings for the action set and layers in use, with edits not saved yet. */
    private fun rebuildVisuals() {
        val mappings = config.mappings
        val preset = SteamTouchConfig.layoutIdOf(actionSet) - 1
        val types = (elements + editElements).map { it.type }.toSet()
        visuals = if (mappings == null) emptyMap() else types.associateWith { type ->
            SteamTouchBindings.refsFor(mappings, type, preset, layers).map { ref ->
                Visual(ref, ref?.let { pendingBindings[it] ?: SteamTouchBindings.bindingAt(mappings, it) })
            }
        }
        val icons = visuals.values.flatten().mapNotNull { it.binding?.icon?.takeIf(String::isNotEmpty) }.toSet()
        if (icons.isEmpty()) return
        val app = appId
        loader.execute {
            icons.forEach { SteamTouchBindings.icon(context, app, it, ICON_PX) }
            handler.post { invalidate() }
        }
    }

    // ---- Geometry ----

    private fun unit() = min(width / 1280f, height / 720f)

    private fun radius(e: Element): Float = baseRadius(e.type) * e.xScale * unit()

    private fun baseRadius(type: Int) = when (type) {
        SteamTouchConfig.DPAD -> 100f
        SteamTouchConfig.JOYSTICK_LEFT, SteamTouchConfig.JOYSTICK_RIGHT -> 105f
        SteamTouchConfig.TRACKPAD_LEFT, SteamTouchConfig.TRACKPAD_RIGHT, SteamTouchConfig.TRACKPAD_CENTER -> 115f
        SteamTouchConfig.A, SteamTouchConfig.B, SteamTouchConfig.X, SteamTouchConfig.Y -> 40f
        SteamTouchConfig.TRIGGER_LEFT, SteamTouchConfig.TRIGGER_RIGHT, SteamTouchConfig.BUMPER_LEFT, SteamTouchConfig.BUMPER_RIGHT -> 38f
        else -> 32f
    }

    private fun cx(e: Element) = e.x * width
    private fun cy(e: Element) = e.y * height

    private fun hit(list: List<Element>, x: Float, y: Float): Element? =
        list.filter { hypot(x - cx(it), y - cy(it)) <= radius(it) * 1.15f }
            .minByOrNull { hypot(x - cx(it), y - cy(it)) / radius(it) }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        val shown = if (editing) editElements else elements
        shown.forEach { draw(canvas, it, editing && editElements.indexOf(it) == selected) }
        if (editing) drawToolbar(canvas)
    }

    private fun pressedTypes(): Set<Int> = fingers.values.mapNotNull { it.element?.type }.toSet()

    private fun draw(canvas: Canvas, e: Element, isSelected: Boolean) {
        val x = cx(e)
        val y = cy(e)
        val r = radius(e)
        val pressed = !editing && e.type in pressedTypes()
        val baseAlpha = (255 * alphaScale).toInt()
        fill.color = Color.argb(if (pressed) min(255, baseAlpha + 90) else baseAlpha / 2, 20, 24, 30)
        stroke.color = if (isSelected) Color.rgb(102, 192, 244) else Color.argb(min(255, baseAlpha + 60), Color.red(tint), Color.green(tint), Color.blue(tint))
        stroke.strokeWidth = if (isSelected) 4f * unit() + 2f else 2f * unit() + 1f
        text.color = Color.argb(min(255, baseAlpha + 110), 255, 255, 255)
        text.textSize = r * 0.7f
        when (e.type) {
            SteamTouchConfig.DPAD -> drawDpad(canvas, e, x, y, r)
            SteamTouchConfig.JOYSTICK_LEFT, SteamTouchConfig.JOYSTICK_RIGHT -> {
                canvas.drawCircle(x, y, r, fill)
                canvas.drawCircle(x, y, r, stroke)
                val f = fingers.values.firstOrNull { it.element?.type == e.type }
                var kx = x
                var ky = y
                if (f != null && !editing) {
                    val d = hypot(f.x - x, f.y - y)
                    val k = if (d > r) r / d else 1f
                    kx = x + (f.x - x) * k
                    ky = y + (f.y - y) * k
                }
                fill.color = Color.argb(min(255, baseAlpha + 40), 60, 66, 76)
                canvas.drawCircle(kx, ky, r * 0.42f, fill)
                canvas.drawCircle(kx, ky, r * 0.42f, stroke)
            }
            SteamTouchConfig.TRACKPAD_LEFT, SteamTouchConfig.TRACKPAD_RIGHT, SteamTouchConfig.TRACKPAD_CENTER -> {
                val rect = RectF(x - r, y - r, x + r, y + r)
                canvas.drawRoundRect(rect, r * 0.25f, r * 0.25f, fill)
                canvas.drawRoundRect(rect, r * 0.25f, r * 0.25f, stroke)
            }
            else -> drawFace(canvas, e.type, visuals[e.type]?.firstOrNull()?.binding, x, y, r, baseAlpha, pressed)
        }
    }

    /**
     * A button: its binding's icon on a disc of its background colour, as Steam draws binding icons;
     * else its label; else a glyph for what it presses; else the button's own name.
     */
    private fun drawFace(canvas: Canvas, type: Int, binding: SteamTouchBindings.Binding?, x: Float, y: Float, r: Float,
                         baseAlpha: Int, pressed: Boolean) {
        val icon = binding?.icon?.takeIf { it.isNotEmpty() }?.let(::iconOrLoad)
        if (icon != null) {
            val alpha = min(255, baseAlpha + if (pressed) 150 else 90)
            val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = parseColor(binding.background, SteamTouchBindings.DEFAULT_BACKGROUND)
                this.alpha = alpha
            }
            canvas.drawCircle(x, y, r, disc)
            val s = r * 0.62f
            canvas.drawBitmap(tinted(binding.icon, icon, binding.foreground), null, RectF(x - s, y - s, x + s, y + s),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha })
            canvas.drawCircle(x, y, r, stroke)
            return
        }
        canvas.drawCircle(x, y, r, fill)
        canvas.drawCircle(x, y, r, stroke)
        val own = label(type)
        val shown = binding?.label?.takeIf { it.isNotEmpty() } ?: SteamTouchBindings.glyph(binding) ?: own
        text.textSize = when {
            shown.length <= 2 -> r * 0.7f
            shown.length <= 4 -> r * 0.42f
            else -> r * 0.3f
        }
        if (shown == own) LABEL_COLORS[type]?.let { text.color = it }
        val line = if (shown.length > 10) shown.take(9) + "…" else shown
        canvas.drawText(line, x, y - (text.descent() + text.ascent()) / 2, text)
    }

    private val iconsLoading = HashSet<String>()

    // Icons already in their foreground colour. Drawn plain: a colour filter on the icon bitmap
    // itself stopped drawing on the hardware canvas once a dialog had been over the view.
    private val tintedIcons = android.util.LruCache<String, android.graphics.Bitmap>(48)

    private fun tinted(name: String, icon: android.graphics.Bitmap, foreground: String): android.graphics.Bitmap {
        val key = "$name|$foreground|${icon.width}"
        tintedIcons.get(key)?.let { return it }
        val out = android.graphics.Bitmap.createBitmap(icon.width, icon.height, android.graphics.Bitmap.Config.ARGB_8888)
        SteamTouchIconPicker.drawSteamIcon(Canvas(out), icon, RectF(0f, 0f, icon.width.toFloat(), icon.height.toFloat()),
            parseColor(foreground, SteamTouchBindings.DEFAULT_FOREGROUND), Paint(Paint.FILTER_BITMAP_FLAG))
        tintedIcons.put(key, out)
        return out
    }

    /** An icon from the cache, or null while it is (re)loaded; the view redraws when it comes in. */
    private fun iconOrLoad(name: String): android.graphics.Bitmap? {
        SteamTouchBindings.cachedIcon(appId, name, ICON_PX)?.let { return it }
        if (iconsLoading.add(name)) {
            val app = appId
            loader.execute {
                SteamTouchBindings.icon(context, app, name, ICON_PX)
                handler.post { iconsLoading.remove(name); invalidate() }
            }
        }
        return null
    }

    private fun parseColor(hex: String, fallback: String) =
        try { Color.parseColor(hex.ifEmpty { fallback }) } catch (_: IllegalArgumentException) { Color.parseColor(fallback) }

    private fun drawDpad(canvas: Canvas, e: Element, x: Float, y: Float, r: Float) {
        val arm = r * 0.36f
        val dirs = if (editing) 0 else dpadBits(e)
        val arms = listOf(DPAD_UP to (0f to -1f), DPAD_DOWN to (0f to 1f), DPAD_LEFT to (-1f to 0f), DPAD_RIGHT to (1f to 0f))
        val armVisuals = visuals[SteamTouchConfig.DPAD].orEmpty()
        val isSelected = editing && editElements.indexOf(e) == selected
        for ((armIndex, armEntry) in arms.withIndex()) {
            val (bit, d) = armEntry
            val (dx, dy) = d
            val cxArm = x + dx * r * 0.6f
            val cyArm = y + dy * r * 0.6f
            val rect = RectF(cxArm - if (dx == 0f) arm else r * 0.4f, cyArm - if (dy == 0f) arm else r * 0.4f,
                cxArm + if (dx == 0f) arm else r * 0.4f, cyArm + if (dy == 0f) arm else r * 0.4f)
            val old = fill.color
            val oldStroke = stroke.color
            if (dirs and bit != 0L) fill.color = Color.argb(200, 20, 24, 30)
            if (isSelected && armIndex == selectedArm) stroke.color = Color.rgb(255, 200, 60)
            val binding = armVisuals.getOrNull(armIndex)?.binding
            val icon = binding?.icon?.takeIf { it.isNotEmpty() }?.let(::iconOrLoad)
            if (icon != null) {
                val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = parseColor(binding.background, SteamTouchBindings.DEFAULT_BACKGROUND)
                    alpha = min(255, (255 * alphaScale).toInt() + if (dirs and bit != 0L) 150 else 90)
                }
                canvas.drawRoundRect(rect, arm * 0.4f, arm * 0.4f, disc)
                val s = min(rect.width(), rect.height()) * 0.42f
                canvas.drawBitmap(tinted(binding.icon, icon, binding.foreground), null,
                    RectF(rect.centerX() - s, rect.centerY() - s, rect.centerX() + s, rect.centerY() + s),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = disc.alpha })
                canvas.drawRoundRect(rect, arm * 0.4f, arm * 0.4f, stroke)
                fill.color = old
                stroke.color = oldStroke
                continue
            }
            canvas.drawRoundRect(rect, arm * 0.4f, arm * 0.4f, fill)
            canvas.drawRoundRect(rect, arm * 0.4f, arm * 0.4f, stroke)
            fill.color = old
            stroke.color = oldStroke
            val tri = Path()
            val s = arm * 0.5f
            val tx = cxArm + dx * r * 0.08f
            val ty = cyArm + dy * r * 0.08f
            if (dx == 0f) { tri.moveTo(tx, ty + dy * s); tri.lineTo(tx - s, ty - dy * s); tri.lineTo(tx + s, ty - dy * s) }
            else { tri.moveTo(tx + dx * s, ty); tri.lineTo(tx - dx * s, ty - s); tri.lineTo(tx - dx * s, ty + s) }
            tri.close()
            val tp = Paint(text).apply { style = Paint.Style.FILL }
            canvas.drawPath(tri, tp)
        }
    }

    private fun label(type: Int) = when (type) {
        SteamTouchConfig.A -> "A"
        SteamTouchConfig.B -> "B"
        SteamTouchConfig.X -> "X"
        SteamTouchConfig.Y -> "Y"
        SteamTouchConfig.STEAM -> "STEAM"
        SteamTouchConfig.THUMB -> "…"
        SteamTouchConfig.KEYBOARD -> "⌨"
        SteamTouchConfig.SELECT -> "⧉"
        SteamTouchConfig.START -> "☰"
        SteamTouchConfig.BUMPER_LEFT -> "LB"
        SteamTouchConfig.BUMPER_RIGHT -> "RB"
        SteamTouchConfig.TRIGGER_LEFT -> "LT"
        SteamTouchConfig.TRIGGER_RIGHT -> "RT"
        SteamTouchConfig.JOYSTICK_LEFT_BUTTON -> "L3"
        SteamTouchConfig.JOYSTICK_RIGHT_BUTTON -> "R3"
        SteamTouchConfig.MACRO_1_FINGER -> "1F"
        SteamTouchConfig.MACRO_2_FINGER -> "2F"
        in SteamTouchConfig.MACRO_0..SteamTouchConfig.MACRO_0 + 7 -> "M${type - SteamTouchConfig.MACRO_0 + 1}"
        else -> "?"
    }

    // ---- The report ----

    private fun dpadBits(e: Element): Long {
        var bits = 0L
        fingers.values.filter { it.element?.type == SteamTouchConfig.DPAD }.forEach { f ->
            val dx = f.x - cx(e)
            val dy = f.y - cy(e)
            if (hypot(dx, dy) < radius(e) * 0.22f) return@forEach
            val angle = Math.toDegrees(atan2(-dy, dx).toDouble()).let { if (it < 0) it + 360 else it }
            // Eight sectors: each direction covers 67.5°, diagonals press two.
            if (angle < 67.5 || angle > 292.5) bits = bits or DPAD_RIGHT
            if (angle in 22.5..157.5) bits = bits or DPAD_UP
            if (angle in 112.5..247.5) bits = bits or DPAD_LEFT
            if (angle in 202.5..337.5) bits = bits or DPAD_DOWN
        }
        return bits
    }

    private fun publish() {
        var buttons = 0L
        val sticks = ShortArray(4)
        val pads = ShortArray(6)
        for (f in fingers.values) {
            val e = f.element ?: continue
            val r = radius(e)
            when (e.type) {
                SteamTouchConfig.DPAD -> buttons = buttons or dpadBits(e)
                SteamTouchConfig.JOYSTICK_LEFT, SteamTouchConfig.JOYSTICK_RIGHT -> {
                    val right = e.type == SteamTouchConfig.JOYSTICK_RIGHT
                    var dx = (f.x - cx(e)) / r
                    var dy = (f.y - cy(e)) / r
                    val d = hypot(dx, dy)
                    if (d > 1f) { dx /= d; dy /= d }
                    sticks[if (right) 2 else 0] = (dx * 32767).toInt().coerceIn(-32767, 32767).toShort()
                    sticks[if (right) 3 else 1] = (-dy * 32767).toInt().coerceIn(-32767, 32767).toShort()
                    buttons = buttons or if (right) STICK_RIGHT_TOUCHED else STICK_LEFT_TOUCHED
                }
                SteamTouchConfig.TRACKPAD_CENTER, SteamTouchConfig.TRACKPAD_LEFT, SteamTouchConfig.TRACKPAD_RIGHT -> {
                    val index = e.type - SteamTouchConfig.TRACKPAD_CENTER // centre, left, right
                    val nx = ((f.x - (cx(e) - r)) / (2 * r)).coerceIn(0f, 1f)
                    val ny = ((f.y - (cy(e) - r)) / (2 * r)).coerceIn(0f, 1f)
                    pads[2 * index] = (((nx * 65535).toInt().coerceIn(0, 65535)) xor 0x8000).toShort()
                    pads[2 * index + 1] = (((ny * 65535).toInt().coerceIn(0, 65535)) xor 0x7fff).toShort()
                    buttons = buttons or TRACKPAD_TOUCHED[index]
                }
                else -> BUTTON_BITS[e.type]?.let { buttons = buttons or it }
            }
        }
        device.setControls(buttons, sticks, pads)
        invalidate()
    }

    fun releaseAll() {
        fingers.clear()
        device.setControls(0L, ShortArray(4), ShortArray(6))
        invalidate()
    }

    // ---- Touch ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editing) return onEditTouch(event)
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(index)
                val y = event.getY(index)
                val e = hit(elements, x, y) ?: return event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
                fingers[event.getPointerId(index)] = Finger(e, x, y)
                requestUnbufferedDispatch(event)
                publish()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val f = fingers[event.getPointerId(i)] ?: continue
                    f.x = event.getX(i)
                    f.y = event.getY(i)
                    // A finger on a button can slide onto another, as on Steam Link.
                    val e = f.element ?: continue
                    if (BUTTON_BITS.containsKey(e.type)) {
                        val over = hit(elements, f.x, f.y)
                        if (over != null && over !== e && BUTTON_BITS.containsKey(over.type))
                            fingers[event.getPointerId(i)] = Finger(over, f.x, f.y)
                    }
                }
                publish()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = event.getPointerId(index)
                val f = fingers.remove(id)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) fingers.clear()
                val e = f?.element
                if (event.actionMasked != MotionEvent.ACTION_CANCEL && e != null && hypot(event.getX(index) - cx(e), event.getY(index) - cy(e)) <= radius(e) * 1.3f) {
                    when (e.type) {
                        SteamTouchConfig.THUMB -> onMenu()
                        SteamTouchConfig.KEYBOARD -> onKeyboard()
                    }
                }
                publish()
                return true
            }
        }
        return true
    }

    // ---- Editing ----

    val isEditing get() = editing

    /** Moves, resizes and hides the current action set's controls; Save writes them to the game's
     *  touch config. */
    fun startEditing() {
        releaseAll()
        editing = true
        val available = config.availableFor(actionSet, layers)
        editElements = available.mapNotNull { type ->
            elements.firstOrNull { it.type == type } ?: SteamTouchConfig.defaultElement(type, available)
        }.sortedBy { it.type }.toMutableList()
        hidden = available.filter { type -> editElements.none { it.type == type } }
            .mapNotNull { SteamTouchConfig.defaultElement(it, available)?.copy(visible = false) }.toMutableList()
        selected = -1
        pendingBindings.clear()
        pendingColor = null
        rebuildVisuals()
        invalidate()
    }

    private fun stopEditing() {
        editing = false
        selected = -1
        pendingBindings.clear()
        pendingColor = null
        rebuild()
    }

    private val toolbarLabels = listOf("−", "+", "Hide", "Icon", "Colour", "Reset", "Cancel", "Save")

    private fun toolbarRects(): List<RectF> {
        val u = max(unit(), 0.6f)
        val w = 112f * u
        val h = 56f * u
        val gap = 12f * u
        val total = toolbarLabels.size * w + (toolbarLabels.size - 1) * gap
        val left = (width - total) / 2
        val top = height * 0.16f
        return toolbarLabels.indices.map { i -> RectF(left + i * (w + gap), top, left + i * (w + gap) + w, top + h) }
    }

    private fun drawToolbar(canvas: Canvas) {
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val rects = toolbarRects()
        val title = Paint(text).apply { color = Color.WHITE; textSize = 26f * max(unit(), 0.6f) }
        canvas.drawText(if (saving) "Saving…" else "Editing touch layout for ${if (appId == BIG_PICTURE) "Steam" else "app $appId"} - drag, pinch, or pick a control's icon",
            width / 2f, rects[0].top - 18f * unit(), title)
        rects.forEachIndexed { i, r ->
            val enabled = i > 3 || selected >= 0
            bar.color = when (i) {
                7 -> Color.rgb(26, 159, 255)
                else -> Color.argb(if (enabled) 230 else 120, 50, 56, 66)
            }
            canvas.drawRoundRect(r, 10f, 10f, bar)
            val t = Paint(text).apply { color = Color.WHITE; textSize = r.height() * 0.42f }
            canvas.drawText(toolbarLabels[i], r.centerX(), r.centerY() - (t.descent() + t.ascent()) / 2, t)
        }
    }

    private fun onEditTouch(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val x = event.getX(index)
        val y = event.getY(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tool = toolbarRects().indexOfFirst { it.contains(x, y) }
                if (tool >= 0) {
                    toolbar(tool)
                    return true
                }
                val e = hit(editElements, x, y)
                selected = if (e != null) editElements.indexOf(e) else -1
                if (e != null) dragOffset = (x - cx(e)) to (y - cy(e))
                // The D-pad's icons are per direction: a tap picks the one under the finger.
                if (e?.type == SteamTouchConfig.DPAD) {
                    val dx = x - cx(e)
                    val dy = y - cy(e)
                    selectedArm = if (abs(dx) > abs(dy)) (if (dx < 0) 2 else 3) else (if (dy < 0) 0 else 1)
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (selected >= 0 && event.pointerCount == 2) {
                pinchStart = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                pinchScale = editElements[selected].xScale
            }
            MotionEvent.ACTION_MOVE -> if (selected >= 0) {
                val e = editElements[selected]
                if (event.pointerCount >= 2 && pinchStart > 0f) {
                    val d = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                    val s = (pinchScale * d / pinchStart).coerceIn(0.4f, 3f)
                    editElements[selected] = e.copy(xScale = s, yScale = s)
                } else if (event.pointerCount == 1) {
                    val nx = ((event.getX(0) - dragOffset.first) / width).coerceIn(0f, 1f)
                    val ny = ((event.getY(0) - dragOffset.second) / height).coerceIn(0f, 1f)
                    editElements[selected] = e.copy(x = nx, y = ny)
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> pinchStart = 0f
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pinchStart = 0f
        }
        return true
    }

    private fun toolbar(tool: Int) {
        if (saving) return
        when (tool) {
            0, 1 -> if (selected >= 0) {
                val e = editElements[selected]
                val s = (e.xScale * if (tool == 0) 0.85f else 1.18f).coerceIn(0.4f, 3f)
                editElements[selected] = e.copy(xScale = s, yScale = s)
            }
            2 -> if (selected >= 0) {
                hidden += editElements.removeAt(selected).copy(visible = false)
                selected = -1
            }
            3 -> if (selected >= 0) pickIcon(editElements[selected])
            4 -> pickColor()
            5 -> {
                val available = config.availableFor(actionSet, layers)
                editElements = available.mapNotNull { SteamTouchConfig.defaultElement(it, available) }.sortedBy { it.type }.toMutableList()
                hidden.clear()
                selected = -1
            }
            6 -> stopEditing()
            7 -> save()
        }
        invalidate()
    }

    /** The selected control's icon, label and colours (a D-pad direction: the one tapped). */
    private fun pickIcon(e: Element) {
        val list = visuals[e.type].orEmpty()
        val visual = list.getOrNull(if (e.type == SteamTouchConfig.DPAD) selectedArm else 0)
        val ref = visual?.ref
        val binding = visual?.binding
        if (ref == null || binding == null) {
            Toast.makeText(context, "This control has no binding in Steam to put an icon on", Toast.LENGTH_SHORT).show()
            return
        }
        val name = if (e.type == SteamTouchConfig.DPAD) "D-pad " + listOf("up", "down", "left", "right")[selectedArm] else label(e.type)
        val host = parent as? android.view.ViewGroup ?: return
        SteamTouchIconPicker(context, host, appId, "Icon for $name", binding) { picked ->
            pendingBindings[ref] = picked
            rebuildVisuals()
            invalidate()
        }.show()
    }

    /** The layout's colour and opacity, as Steam Link's colour picker sets them. */
    private fun pickColor() {
        val current = pendingColor ?: SteamTouchConfig.layoutColor(config, actionSet) ?: floatArrayOf(1f, 1f, 1f, 0.45f)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        var chosen = current.copyOf()
        val root = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val swatchRow = android.widget.LinearLayout(context)
        SteamTouchBindings.PALETTE.forEach { hex ->
            val c = Color.parseColor(hex)
            swatchRow.addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c); setStroke(dp(1), Color.GRAY) }
                setOnClickListener {
                    chosen = floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, chosen[3])
                    pendingColor = chosen
                    applyColor(chosen)
                    invalidate()
                }
            }, android.widget.LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(6) })
        }
        root.addView(android.widget.TextView(context).apply { text = "Colour" })
        root.addView(android.widget.HorizontalScrollView(context).apply { addView(swatchRow) })
        root.addView(android.widget.TextView(context).apply { text = "Opacity"; setPadding(0, dp(12), 0, 0) })
        val opacityRow = android.widget.LinearLayout(context)
        listOf(20, 35, 50, 65, 80, 100).forEach { percent ->
            opacityRow.addView(android.widget.Button(context).apply {
                text = "$percent%"
                setOnClickListener {
                    chosen = floatArrayOf(chosen[0], chosen[1], chosen[2], percent / 100f)
                    pendingColor = chosen
                    applyColor(chosen)
                    invalidate()
                }
            })
        }
        root.addView(android.widget.HorizontalScrollView(context).apply { addView(opacityRow) })
        val host = parent as? android.view.ViewGroup ?: return
        SteamTouchIconPicker.showPanel(host, "Touch layout colour", root, listOf("Done" to {}))
    }

    private fun save() {
        saving = true
        invalidate()
        val app = appId
        val set = actionSet
        val cfg = config
        val layout = editElements.map { it.copy(visible = true) } + hidden.filter { h -> editElements.none { it.type == h.type } }
        val color = pendingColor
        // A picked icon replaces the label, icon and colours of every binding of that input; what
        // the binding does stays as it is.
        val bindings = pendingBindings.mapValues { (_, picked) ->
            { old: SteamTouchBindings.Binding ->
                old.copy(label = picked.label, icon = picked.icon, foreground = picked.foreground, background = picked.background)
            }
        }
        loader.execute {
            val file = try {
                SteamTouchConfig.saveLayout(context, app, cfg, set, layout, color, bindings)
            } catch (e: Exception) {
                Log.w(TAG, "steam touch: saving failed: $e")
                null
            }
            handler.post {
                saving = false
                if (file == null) {
                    Toast.makeText(context, "Could not save the touch layout", Toast.LENGTH_SHORT).show()
                    invalidate()
                    return@post
                }
                Toast.makeText(context, "Touch layout saved to Steam", Toast.LENGTH_SHORT).show()
                onSaved()
                stopEditing()
                reloadConfig()
            }
        }
    }

    /** Called after a save: the client reads the config again when the controller reconnects. */
    var onSaved: () -> Unit = {}

    companion object {
        private const val TAG = "SteamTouch"
        private const val POLL_MS = 150L
        const val BIG_PICTURE = 769
        private const val ICON_PX = 128

        private const val DPAD_UP = 1L shl 8
        private const val DPAD_RIGHT = 1L shl 9
        private const val DPAD_LEFT = 1L shl 10
        private const val DPAD_DOWN = 1L shl 11
        private const val STICK_LEFT_TOUCHED = 1L shl 46
        private const val STICK_RIGHT_TOUCHED = 1L shl 47
        private val TRACKPAD_TOUCHED = longArrayOf(1L shl 27, 1L shl 19, 1L shl 20)

        /** Steam Link's button bits (CVirtualController's table, by element type). */
        private val BUTTON_BITS: Map<Int, Long> = buildMap {
            put(SteamTouchConfig.STEAM, 1L shl 13)
            put(SteamTouchConfig.JOYSTICK_LEFT_BUTTON, 1L shl 22)
            put(SteamTouchConfig.JOYSTICK_RIGHT_BUTTON, 1L shl 26)
            put(SteamTouchConfig.A, 1L shl 7)
            put(SteamTouchConfig.B, 1L shl 5)
            put(SteamTouchConfig.X, 1L shl 6)
            put(SteamTouchConfig.Y, 1L shl 4)
            put(SteamTouchConfig.SELECT, 1L shl 12)
            put(SteamTouchConfig.START, 1L shl 14)
            put(SteamTouchConfig.TRIGGER_LEFT, 1L shl 1)
            put(SteamTouchConfig.TRIGGER_RIGHT, 1L shl 0)
            put(SteamTouchConfig.BUMPER_LEFT, 1L shl 3)
            put(SteamTouchConfig.BUMPER_RIGHT, 1L shl 2)
            for (i in 0 until 8) put(SteamTouchConfig.MACRO_0 + i, 1L shl (32 + i))
            put(SteamTouchConfig.MACRO_1_FINGER, 1L shl 48)
            put(SteamTouchConfig.MACRO_2_FINGER, 1L shl 49)
            // Not bits: the menu and the keyboard are the app's own.
            put(SteamTouchConfig.THUMB, 0L)
            put(SteamTouchConfig.KEYBOARD, 0L)
        }

        private val LABEL_COLORS = mapOf(
            SteamTouchConfig.A to Color.rgb(96, 200, 90),
            SteamTouchConfig.B to Color.rgb(230, 80, 70),
            SteamTouchConfig.X to Color.rgb(70, 140, 240),
            SteamTouchConfig.Y to Color.rgb(240, 200, 60),
        )
    }
}
