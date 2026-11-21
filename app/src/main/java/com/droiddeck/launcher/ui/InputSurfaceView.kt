package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import android.view.MotionEvent
import com.droiddeck.launcher.input.copiedStock
import com.droiddeck.launcher.input.TouchpadGestures
import com.droiddeck.launcher.input.PointerGestures
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.droiddeck.launcher.input.InputRole
import com.droiddeck.launcher.input.InputControl
import com.droiddeck.launcher.input.InputSurfaceElement
import com.droiddeck.launcher.input.InputSurfaceStyle
import com.droiddeck.launcher.input.pointerSubset
import com.droiddeck.launcher.input.InputSurfaceSource
import com.droiddeck.launcher.input.InputElementKind
import com.droiddeck.launcher.input.InputAction
import com.droiddeck.launcher.input.InputButtonIcons
import com.droiddeck.launcher.input.InputButtonShape
import com.droiddeck.launcher.input.InputButtonImages
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.pcKeyLabel
import com.droiddeck.launcher.session.SessionPrefs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

internal inline fun fittedTextSize(preferred: Float, room: Float, floor: Float = 0f, measure: (Float) -> Float): Float {
    val width = measure(preferred)
    return maxOf(floor, if (width > room && width > 0f) preferred * room / width else preferred)
}

@SuppressLint("ViewConstructor")
class InputSurfaceView(
    context: Context,
    private val pad: PadBridge? = null,
    private var customLayout: List<InputSurfaceElement> = emptyList(),
    val inputRole: InputRole = InputRole.SECONDARY,
    private var surfaceStyle: InputSurfaceStyle = InputSurfaceStyle(),
    private val haptics: Boolean = inputRole != InputRole.PRIMARY,
) : FrameLayout(context) {
    private val content = FrameLayout(context)
    private val elementSources = ArrayList<InputSurfaceSource>()
    private var customCanvas: ViewGroup? = null
    private val owners = HashMap<Int, View>()
    private var steamDeckButtons = false
    private val deckArt by lazy(LazyThreadSafetyMode.NONE) { SteamDeckButtonArt(context) }
    private var deckDpad = emptyList<HoldButton>()
    private val deckDpadBounds = RectF()
    private var requestedLayout = customLayout
    init { customLayout = surfaceStyle.shown(customLayout); addView(content, LayoutParams(-1, -1)); renderCustom(pad) }

    val shownElements: List<InputSurfaceElement> get() = customLayout
    var onTouchMiss: ((MotionEvent) -> Unit)? = null
    private var forwardedPointers = 0

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { forwardedPointers = 0; cancelOwners(event) }
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            val index = event.actionIndex
            if (event.getToolType(index) == MotionEvent.TOOL_TYPE_MOUSE || !takesTouchAt(event.getX(index), event.getY(index)))
                forwardedPointers = forwardedPointers or (1 shl event.getPointerId(index))
            else if (onTouchMiss != null && SessionPrefs.lowLatency(context)) requestUnbufferedDispatch(event)
        }
        if (forwardedPointers == 0) return route(event)
        var all = 0
        for (index in 0 until event.pointerCount) all = all or (1 shl event.getPointerId(index))
        val routed = event.pointerSubset(all and forwardedPointers.inv())?.let { split ->
            try { route(split) } finally { split.recycle() }
        } ?: false
        event.pointerSubset(all and forwardedPointers)?.let { split ->
            try { onTouchMiss?.invoke(split) } finally { split.recycle() }
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) forwardedPointers = forwardedPointers and (1 shl event.getPointerId(event.actionIndex)).inv()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) forwardedPointers = 0
        return onTouchMiss != null || routed
    }

    private fun route(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) { cancelOwners(event); return true }
        val index = event.actionIndex
        val pointer = event.getPointerId(index)
        var taken = true
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val target = elementAt(event.getX(index), event.getY(index))
            taken = target != null
            when {
                target is HoldButton -> { owners[pointer] = target; target.press(pointer, true) }
                target != null && !(target is StickView && target in owners.values) -> owners[pointer] = target
            }
        }
        if (action == MotionEvent.ACTION_MOVE) for (i in 0 until event.pointerCount) slide(event.getPointerId(i), event.getX(i), event.getY(i))
        for (view in owners.values.distinct()) {
            if (view is HoldButton) continue
            var bits = 0
            for ((id, owner) in owners) if (owner === view) bits = bits or (1 shl id)
            event.pointerSubset(bits)?.let { split ->
                try { split.offsetLocation(-originX(view), -originY(view)); view.dispatchTouchEvent(split) } finally { split.recycle() }
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            (owners.remove(pointer) as? HoldButton)?.press(pointer, false, event.flags and MotionEvent.FLAG_CANCELED != 0)
        }
        return taken
    }

    private fun slide(pointer: Int, x: Float, y: Float) {
        val owner = owners[pointer]
        if (owner != null && owner !is HoldButton) return
        val over = elementAt(x, y) as? HoldButton
        if (over === owner) return
        (owner as? HoldButton)?.press(pointer, false, cancelled = true)
        if (over == null) owners.remove(pointer) else { owners[pointer] = over; over.press(pointer, true) }
    }

    private fun cancelOwners(event: MotionEvent) {
        if (owners.isEmpty()) return
        val views = owners.values.distinct()
        owners.clear()
        for (view in views) {
            if (view is HoldButton) { view.cancel(); continue }
            val cancel = MotionEvent.obtain(event.downTime, event.eventTime, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try { view.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
        }
    }

    fun updateStyle(style: InputSurfaceStyle, steamDeckButtons: Boolean = false) {
        if (surfaceStyle == style && this.steamDeckButtons == steamDeckButtons) return
        val relayout = surfaceStyle.removed != style.removed
        surfaceStyle = style
        this.steamDeckButtons = steamDeckButtons
        if (relayout) updateCustomLayout(requestedLayout)
        customCanvas?.let { canvas ->
            for (i in 0 until canvas.childCount) styleElement(canvas.getChildAt(i))
            canvas.invalidate()
        }
    }

    /** The alpha [view], one of this surface's controls, has when nothing animates it. */
    internal fun restingAlpha(view: View) = surfaceStyle.opacity / 100f * ((view.tag as? InputSurfaceElement)?.opacity ?: 1f)

    private fun styleElement(view: View) {
        view.alpha = restingAlpha(view)
        (view as? Surface)?.tint = surfaceStyle.tint
        view.invalidate()
    }

    fun updateCustomLayout(requested: List<InputSurfaceElement>) {
        requestedLayout = requested
        val layout = surfaceStyle.shown(requested)
        if (layout == customLayout) return
        val previous = customLayout
        customLayout = layout
        val canvas = customCanvas
        if (canvas == null || previous.map(::unplaced) != layout.map(::unplaced)) {
            elementSources.forEach { it.close() }; elementSources.clear()
            owners.clear(); content.removeAllViews(); renderCustom(pad)
            return
        }
        for (i in layout.indices) {
            val child = canvas.getChildAt(i)
            child.tag = layout[i]
            if (layout[i].opacity != previous[i].opacity) styleElement(child)
        }
        canvas.requestLayout()
    }

    private fun unplaced(e: InputSurfaceElement) = e.copy(x = 0f, y = 0f, width = 1f, height = 1f, opacity = 1f)

    fun releaseInputs() {
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { cancelOwners(cancel) } finally { cancel.recycle() }
        forwardedPointers = 0
        elementSources.forEach { it.release() }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) releaseInputs()
    }

    private fun renderCustom(pad: PadBridge?) {
        deckDpad = emptyList()
        val canvas = object : ViewGroup(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val layout = customLayout
                val w = MeasureSpec.getSize(widthMeasureSpec)
                val h = MeasureSpec.getSize(heightMeasureSpec)
                setMeasuredDimension(w, h)
                for (i in layout.indices) getChildAt(i).measure(MeasureSpec.makeMeasureSpec((w * layout[i].width).toInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec((h * layout[i].height).toInt(), MeasureSpec.EXACTLY))
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val layout = customLayout
                for (i in layout.indices) {
                    val e = layout[i]
                    val child = getChildAt(i)
                    val x = (width * e.x).toInt(); val y = (height * e.y).toInt()
                    child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                }
                updateDeckDpad(this)
            }

            override fun dispatchDraw(canvas: Canvas) {
                if (steamDeckButtons && deckDpad.isNotEmpty()) {
                    val alpha = (deckDpad.first().alpha * 255f).toInt()
                    val saved = if (alpha == 255) canvas.save()
                        else canvas.saveLayerAlpha(deckDpadBounds, alpha)
                    canvas.translate(deckDpadBounds.left, deckDpadBounds.top)
                    val held = deckDpad.foldIndexed(0) { i, bits, button -> if (button.highlighted) bits or (1 shl i) else bits }
                    deckArt.dpad(canvas, deckDpadBounds.width(), deckDpadBounds.height(), held)
                    canvas.restoreToCount(saved)
                }
                super.dispatchDraw(canvas)
            }
        }
        canvas.clipChildren = false
        for (element in customLayout) {
            val source = pad?.let { InputSurfaceSource(it, inputRole, element).also { s -> elementSources += s } }
            canvas.addView(when (element.kind) {
                InputElementKind.STICK -> StickView(context, element, source, element.floating, element.hideWhenIdle && pad != null)
                InputElementKind.TRACKPAD -> TrackpadView(context, element.control == InputControl.RIGHT_PAD, captioned = element.showLabel, source = source)
                InputElementKind.DPAD -> DirectionPad(context, element, source)
                else -> HoldButton(context, element, source)
            }.apply { tag = element; contentDescription = element.name; styleElement(this); source?.onStateChanged = { postInvalidate() } })
        }
        content.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        customCanvas = canvas
    }

    private fun updateDeckDpad(canvas: ViewGroup) {
        val buttons = DIRECTIONS.mapNotNull { direction ->
            val index = customLayout.indexOfFirst { element ->
                element.control == direction && classic(element) && element.buttonShape == InputButtonShape.CIRCLE &&
                    element.name == direction.label && element.icon == 0 && element.image.isEmpty() &&
                    element.showLabel && !element.toggle && element.opacity == 1f
            }
            canvas.getChildAt(index) as? HoldButton
        }
        deckDpad = emptyList()
        if (buttons.size != DIRECTIONS.size) return
        val (up, right, down, left) = buttons
        val centerX = (left.left + right.right) / 2f
        val centerY = (up.top + down.bottom) / 2f
        if (kotlin.math.abs((up.left + up.right) / 2f - centerX) > 2f ||
            kotlin.math.abs((down.left + down.right) / 2f - centerX) > 2f ||
            kotlin.math.abs((left.top + left.bottom) / 2f - centerY) > 2f ||
            kotlin.math.abs((right.top + right.bottom) / 2f - centerY) > 2f ||
            up.bottom >= down.top || left.right >= right.left) return
        deckDpadBounds.set(left.left.toFloat(), up.top.toFloat(), right.right.toFloat(), down.bottom.toFloat())
        deckDpad = buttons
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (pad != null && elementSources.isEmpty()) {
            owners.clear(); content.removeAllViews()
            renderCustom(pad)
        }
    }

    override fun onDetachedFromWindow() {
        elementSources.forEach { it.close() }
        elementSources.clear()
        super.onDetachedFromWindow()
    }

    fun takesTouchAt(x: Float, y: Float): Boolean = elementAt(x, y) != null

    private fun elementAt(x: Float, y: Float): View? {
        val canvas = customCanvas ?: return null
        if (visibility != VISIBLE || x < 0f || y < 0f || x >= width || y >= height) return null
        val views = (canvas.childCount - 1 downTo 0).map(canvas::getChildAt).filter { it.visibility == VISIBLE }
        views.firstOrNull { !(it is StickView && it.adaptive) && hits(it, x, y, 0f) }?.let { return it }
        views.filter { it is HoldButton && hits(it, x, y, slack(it)) }.minByOrNull { distance(it, x, y) }?.let { return it }
        if (views.any { it is HoldButton && hits(it, x, y, slack(it) + ADAPTIVE_CLEARANCE_PX) }) return null
        return views.filter { it is StickView && it.adaptive && it !in owners.values && (hits(it, x, y, 0f) || it.captures(x - originX(it), y - originY(it))) }
            .minByOrNull { distance(it, x, y) }
    }

    private fun hits(view: View, x: Float, y: Float, slack: Float): Boolean {
        val left = originX(view)
        val top = originY(view)
        if ((view as? HoldButton)?.round == true && slack > 0f) {
            val reach = min(view.width, view.height) / 2f + slack
            return hypot(x - left - view.width / 2f, y - top - view.height / 2f) <= reach
        }
        return x >= left - slack && x < left + view.width + slack && y >= top - slack && y < top + view.height + slack
    }

    private fun slack(view: View) = if ((view as? HoldButton)?.gamepad == true) min(view.width, view.height) / 2f * HIT_SLACK else 0f

    private fun distance(view: View, x: Float, y: Float): Float {
        val cx = originX(view) + ((view as? StickView)?.centreX() ?: (view.width / 2f))
        return hypot(x - cx, y - originY(view) - view.height / 2f)
    }

    private fun originX(view: View): Float {
        var x = 0f
        var at: View = view
        while (at !== this) { x += at.left - ((at.parent as? View)?.scrollX ?: 0); at = at.parent as? View ?: break }
        return x
    }

    private fun originY(view: View): Float {
        var y = 0f
        var at: View = view
        while (at !== this) { y += at.top - ((at.parent as? View)?.scrollY ?: 0); at = at.parent as? View ?: break }
        return y
    }

    private fun feedback(view: View, type: Int) { if (haptics) view.performHapticFeedback(type) }

    private abstract class Surface(context: Context) : View(context) {
        protected val look = ControlLook()
        var tint: Int? = null
            set(value) { field = value; look.tint(value) }
        protected val density = resources.displayMetrics.density
        protected val labelFloor = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MIN_LABEL_SP, resources.displayMetrics)
        protected val rect = RectF()
        private val glowRect = RectF()
        protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        protected val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        protected val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private var labelsFor: InputAction.Directions? = null
        private var labels: List<Pair<Pair<Float, Float>, String>> = emptyList()

        protected fun fitLabel(text: String, preferred: Float, room: Float) {
            labelPaint.textSize = fittedTextSize(preferred, room, labelFloor) { labelPaint.textSize = it; labelPaint.measureText(text) }
        }

        protected fun drawBody(canvas: Canvas, shape: InputButtonShape, press: Float, hot: Float = 0f, latched: Boolean = false) {
            rect.set(density, density, width - density, height - density)
            val round = min(rect.width(), rect.height()) / 2f
            val radius = when (shape) { InputButtonShape.CIRCLE, InputButtonShape.PILL -> round; InputButtonShape.RECTANGLE -> 2 * density; else -> min(CORNER * density, round) }
            drawGlow(canvas, rect, radius, maxOf(ControlLook.GLOW * press, ControlLook.GLOW_HOT * hot))
            fill.color = look.fill(press, hot, latched)
            canvas.drawRoundRect(rect, radius, radius, fill)
            stroke.color = look.stroke(press, hot)
            stroke.strokeWidth = (1.5f + .5f * press) * density
            canvas.drawRoundRect(rect, radius, radius, stroke)
            if (!latched) return
            val inset = 4 * density
            glowRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            val inner = (radius - inset).coerceAtLeast(0f)
            stroke.color = look.accent
            stroke.strokeWidth = 2 * density
            canvas.drawRoundRect(glowRect, inner, inner, stroke)
            val dot = 4.5f * density
            val x = rect.right - width * .04f - dot
            val y = rect.top + height * .04f + dot
            fill.color = ControlLook.BACKDROP
            canvas.drawCircle(x, y, dot + 2 * density, fill)
            fill.color = look.accent
            canvas.drawCircle(x, y, dot, fill)
        }

        protected fun drawGlow(canvas: Canvas, around: RectF, radius: Float, strength: Float) {
            if (strength <= 0f) return
            val step = GLOW_REACH * density / GLOW_RINGS
            glow.strokeWidth = step
            for (i in 0 until GLOW_RINGS) {
                val out = (i + .5f) * step
                val falloff = 1f - i / GLOW_RINGS.toFloat()
                glow.color = ControlLook.alpha(look.accent, strength * falloff * falloff)
                glowRect.set(around.left - out, around.top - out, around.right + out, around.bottom + out)
                canvas.drawRoundRect(glowRect, radius + out, radius + out, glow)
            }
        }

        protected fun drawGlow(canvas: Canvas, x: Float, y: Float, radius: Float, strength: Float, reach: Float) {
            if (strength <= 0f) return
            val step = reach / GLOW_RINGS
            glow.strokeWidth = step
            for (i in 0 until GLOW_RINGS) {
                val falloff = 1f - i / GLOW_RINGS.toFloat()
                glow.color = ControlLook.alpha(look.accent, strength * falloff * falloff)
                canvas.drawCircle(x, y, radius + (i + .5f) * step, glow)
            }
        }

        protected fun drawKeyboard(canvas: Canvas, x: Float, y: Float, color: Int) {
            val u = min(width, height) / KEYBOARD_UNITS
            stroke.color = color
            stroke.strokeWidth = 1.5f * u
            rect.set(x - 10 * u, y - 7 * u, x + 10 * u, y + 7 * u)
            canvas.drawRoundRect(rect, 2 * u, 2 * u, stroke)
            for (row in 0..1) for (col in 0..2) {
                val kx = x + (-6f + col * 5f) * u
                val ky = y + (-3f + row * 4f) * u
                canvas.drawLine(kx, ky, kx + 2 * u, ky, stroke)
            }
            canvas.drawLine(x - 5 * u, y + 4 * u, x + 5 * u, y + 4 * u, stroke)
        }

        protected fun drawDirectionLabels(canvas: Canvas, directions: InputAction.Directions?, cardinalOnly: Boolean, cx: Float, cy: Float,
            distance: Float, box: Float): Boolean {
            if (directions !== labelsFor) { labelsFor = directions; labels = directionLabels(context, directions, cardinalOnly) }
            labelPaint.style = Paint.Style.FILL
            for (i in labels.indices) {
                val (unit, text) = labels[i]
                labelPaint.textSize = fittedTextSize(box * .6f, box, labelFloor) { labelPaint.textSize = it; labelPaint.measureText(text) }
                canvas.drawText(text, cx + unit.first * distance, cy + unit.second * distance - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint)
            }
            return labels.isNotEmpty()
        }
    }

    private inner class HoldButton(context: Context, private val element: InputSurfaceElement, private val source: InputSurfaceSource?) : Surface(context) {
        private var down = false
        private val pointers = HashSet<Int>()
        private val motion = ControlMotion(this, squash = true)
        val round get() = element.buttonShape == InputButtonShape.CIRCLE
        val gamepad get() = round || element.buttonShape == InputButtonShape.PILL
        private val symbol = InputButtonIcons.drawable(context, element.icon)
        private val bitmap = InputButtonImages.file(context, element.image)?.let { file ->
            val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, size)
            val options = BitmapFactory.Options().apply {
                while (size.outWidth / inSampleSize.coerceAtLeast(1) > 512 || size.outHeight / inSampleSize.coerceAtLeast(1) > 512)
                    inSampleSize = inSampleSize.coerceAtLeast(1) * 2
            }
            if (file.isFile) BitmapFactory.decodeFile(file.path, options) else null
        }
        private val imageRect = RectF()
        private val arrow = Path()
        private val glyph = glyph(element.control)
        private val direction = DIRECTIONS.indexOf(element.control)

        val highlighted get() = if (element.toggle) source?.latched == true else down

        override fun onDraw(canvas: Canvas) {
            val press = motion.shown
            if (steamDeckButtons && element.image.isEmpty() && element.showLabel) {
                if (this in deckDpad) return
                val lit = highlighted
                if (element.id == "builtin:keyboard" && element.icon == InputButtonIcons.KEYBOARD) {
                    deckArt.keyboard(canvas, width, height, lit)
                    return
                }
                val native = element.id == "legacy:${element.control.id}" && element.name == element.control.label && element.icon == 0
                if (native && deckArt.button(canvas, width, height, element.control, lit)) return
            }
            drawBody(canvas, element.buttonShape, press, latched = element.toggle && source?.latched == true)
            val classic = classic(element)
            val cx = width / 2f
            val cy = height / 2f
            labelPaint.color = look.ink(press)
            bitmap?.let {
                val scale = min(width * .84f / it.width, height * .84f / it.height)
                imageRect.set(cx - it.width * scale / 2, (height - it.height * scale) / 2,
                    cx + it.width * scale / 2, (height + it.height * scale) / 2)
                canvas.drawBitmap(it, null, imageRect, labelPaint)
                return
            }
            if (classic && element.icon == InputButtonIcons.KEYBOARD) { drawKeyboard(canvas, cx, cy, labelPaint.color); return }
            if (element.icon != 0) {
                if (symbol != null) labelPaint.color = if (press < .5f) look.ink else Color.WHITE
                if (InputButtonIcons.draw(canvas, element.icon, cx, cy, min(width, height) * .68f, labelPaint, symbol)) return
            }
            if (!element.showLabel) return
            labelPaint.style = Paint.Style.FILL
            if (classic && (element.name == element.control.label || element.name == glyph)) {
                val radius = min(width, height) / 2f
                if (direction >= 0) { drawArrow(canvas, arrow, cx, cy, radius * 0.34f, direction, labelPaint); return }
                fitLabel(glyph, when {
                    element.buttonShape == InputButtonShape.PILL -> height / 2f * 0.8f
                    glyph.length > 2 -> radius * 0.5f
                    else -> radius * if (glyph.length == 2) 0.62f else 0.85f
                }, width * .84f)
                canvas.drawText(glyph, cx, cy + labelPaint.textSize * .35f, labelPaint)
                return
            }
            fitLabel(element.name, height * .32f, width * .84f)
            canvas.drawText(element.name, cx, cy + labelPaint.textSize * .35f, labelPaint)
        }

        fun press(pointer: Int, pressed: Boolean, cancelled: Boolean = false) {
            if (pressed) pointers.add(pointer) else pointers.remove(pointer)
            set(pointers.isNotEmpty(), cancelled)
        }

        fun cancel() { pointers.clear(); set(false, cancelled = true); source?.release() }

        override fun onDetachedFromWindow() { cancel(); motion.snap(0f); super.onDetachedFromWindow() }

        private fun set(pressed: Boolean, cancelled: Boolean = false) {
            if (down == pressed) return
            down = pressed
            if (pressed) feedback(this, HapticFeedbackConstants.VIRTUAL_KEY)
            if (!pressed && cancelled) source?.cancel() else source?.update(if (pressed) 1f else 0f)
            motion.press(pressed)
            invalidate()
            if (steamDeckButtons && this in deckDpad) customCanvas?.invalidate()
        }
    }

    private inner class TrackpadView(context: Context, private val right: Boolean, private val captioned: Boolean = true,
        private val source: InputSurfaceSource? = null) : Surface(context) {
        private var gestureX = 0f
        private var gestureY = 0f
        private val mouse = TouchpadGestures(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), object : PointerGestures.Listener {
            override fun onMove(x: Float, y: Float) {
                source?.pointerDelta(2 * (x - gestureX) / width.coerceAtLeast(1), 2 * (y - gestureY) / height.coerceAtLeast(1))
                gestureX = x; gestureY = y
            }
            override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) { source?.gestureButton(button, pressed) }
            override fun onWheel(steps: Int) { source?.gestureWheel(steps) }
            override fun onLongPress() { feedback(this@TrackpadView, HapticFeedbackConstants.LONG_PRESS) }
        }).apply { bounds = RectF(-1e9f, -1e9f, 1e9f, 1e9f) }

        private var touching = false
        private var clicked = false
        private var fingerX = 0f
        private var fingerY = 0f
        private val motion = ControlMotion(this, squash = false)
        private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var caption = ""
        private var captionFrom: CharSequence? = null

        override fun onDraw(canvas: Canvas) {
            if (!captioned) {
                deckArt.trackpad(canvas, width.toFloat(), height.toFloat(), touching || clicked, fingerX, fingerY)
                return
            }
            drawBody(canvas, InputButtonShape.ROUNDED, motion.shown, hot = if (touching) 1f else 0f)
            val cx = width / 2f
            val cy = height / 2f
            guide.strokeWidth = density
            guide.color = ControlLook.alpha(look.ink, TRACKPAD_GUIDE)
            val reach = (min(width, height) / 2f - 24 * density).coerceAtLeast(0f)
            canvas.drawCircle(cx, cy, reach, guide)
            canvas.drawCircle(cx, cy, reach / 2f, guide)
            canvas.drawLine(cx - reach, cy, cx + reach, cy, guide)
            canvas.drawLine(cx, cy - reach, cx, cy + reach, guide)
            if (captioned) {
                val description = contentDescription
                if (caption.isEmpty() || description !== captionFrom) {
                    captionFrom = description
                    caption = (description?.toString() ?: context.getString(if (right) R.string.deck_right_trackpad else R.string.deck_left_trackpad)).uppercase()
                }
                captionPaint.textSize = 12 * density
                captionPaint.color = if (touching) look.accent else ControlLook.alpha(look.ink, TRACKPAD_CAPTION)
                canvas.drawText(caption, cx, 28 * density, captionPaint)
            }
            if (touching) {
                val radius = (if (clicked) 16 else 12) * density
                drawGlow(canvas, fingerX, fingerY, radius, FINGER_GLOW, FINGER_GLOW_REACH * density)
                fill.color = ControlLook.alpha(look.accent, FINGER)
                canvas.drawCircle(fingerX, fingerY, radius, fill)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (source?.mouseGestures == true) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    gestureX = 0f; gestureY = 0f; mouse.place(0f, 0f)
                    source.gestureContact(true)
                }
                touching = event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
                fingerX = event.x; fingerY = event.y
                mouse.onTouch(event)
                if (!touching) source.gestureContact(false)
                invalidate()
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    touch(event.getX(0), event.getY(0))
                    click(event.pointerCount > 1)
                }
                MotionEvent.ACTION_POINTER_DOWN -> click(true)
                MotionEvent.ACTION_POINTER_UP -> {
                    val stays = if (event.actionIndex == 0) 1 else 0
                    touch(event.getX(stays), event.getY(stays))
                    click(event.pointerCount - 1 > 1)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    click(false)
                    touching = false
                    source?.update(0f, 0f, false)
                    invalidate()
                }
            }
            return true
        }

        override fun onDetachedFromWindow() { mouse.cancel(); source?.release(); motion.snap(0f); super.onDetachedFromWindow() }

        private fun touch(x: Float, y: Float) {
            fingerX = x.coerceIn(0f, width.toFloat())
            fingerY = y.coerceIn(0f, height.toFloat())
            touching = true
            val xAxis = (fingerX - width / 2f) / (width / 2f).coerceAtLeast(1f)
            val yAxis = -(fingerY - height / 2f) / (height / 2f).coerceAtLeast(1f)
            source?.update(xAxis, yAxis, true)
            invalidate()
        }

        private fun click(down: Boolean) {
            if (clicked == down) return
            clicked = down
            if (down) feedback(this, HapticFeedbackConstants.VIRTUAL_KEY)
            source?.click(down)
            motion.press(down)
            invalidate()
        }
    }

    private inner class StickView(context: Context, private val element: InputSurfaceElement, private val source: InputSurfaceSource? = null, private val floating: Boolean = true, private val hideWhenIdle: Boolean = false) : Surface(context) {
        private var touching = false
        private var clicking = false
        private var lastUp = 0L
        private var downAt = 0L
        private var downX = 0f
        private var downY = 0f
        private var travel = 0f
        private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var originX = 0f
        private var originY = 0f
        private var knobX = 0f
        private var knobY = 0f
        private val motion = ControlMotion(this, squash = false)
        private val label = if (element.name == element.control.label) glyph(element.control) else element.name

        val adaptive get() = adaptive(element)

        fun reach() = min(width, height) * if (classicReach(element)) CLASSIC_REACH else PANEL_REACH

        fun captures(x: Float, y: Float): Boolean {
            val cx = centreX()
            val dx = if (element.control == InputControl.LS) maxOf(0f, x - cx) else x - cx
            val limit = reach() * ADAPTIVE_REACH
            return dx * dx + (y - height / 2f) * (y - height / 2f) <= limit * limit
        }

        fun centreX() = when {
            !classicReach(element) || width <= height -> width / 2f
            element.control == InputControl.LS -> width - height / 2f
            else -> height / 2f
        }

        override fun onDraw(canvas: Canvas) {
            if (hideWhenIdle && !touching) return
            val cx = if (touching) originX else centreX()
            val cy = if (touching) originY else height / 2f
            val touched = motion.shown
            if (steamDeckButtons && classic(element)) {
                val radius = min(width, height) / 2f - 2f * density
                if (radius > 0f) {
                    deckArt.stick(canvas, cx, cy, radius, knobX, knobY, touching || clicking)
                    if (element.showLabel) {
                        labelPaint.color = TEXT
                        drawDirectionLabels(canvas, source?.directions, false, cx, cy, radius * .66f, radius * .34f)
                    }
                    return
                }
            }
            if (classic(element)) {
                val radius = min(width, height) / 2f - density
                fill.color = ControlLook.GLASS
                canvas.drawCircle(cx, cy, radius, fill)
                drawRing(canvas, cx, cy, radius, touched)
                val knobRadius = radius * 0.46f
                drawKnob(canvas, cx + knobX, cy + knobY, knobRadius, touched)
                if (!element.showLabel) return
                labelPaint.color = look.ink(touched)
                if (drawDirectionLabels(canvas, source?.directions, false, cx, cy, radius * 0.66f, radius * 0.34f)) return
                fitLabel(label, knobRadius * 0.8f, knobRadius * 1.7f)
                canvas.drawText(label, cx + knobX, cy + knobY + labelPaint.textSize * 0.35f, labelPaint)
                return
            }
            drawBody(canvas, InputButtonShape.ROUNDED, if (clicking) 1f else 0f)
            drawRing(canvas, cx, cy, reach(), touched)
            drawKnob(canvas, cx + knobX, cy + knobY, reach() * 0.45f, touched)
            if (!element.showLabel) return
            labelPaint.color = look.ink(touched)
            drawDirectionLabels(canvas, source?.directions, false, cx, cy, reach() * 1.32f, reach() * 0.68f)
        }

        private fun drawRing(canvas: Canvas, x: Float, y: Float, radius: Float, touched: Float) {
            stroke.color = ControlLook.alpha(look.accent, STICK_RING + (STICK_RING_TOUCHED - STICK_RING) * touched)
            stroke.strokeWidth = 1.5f * density
            canvas.drawCircle(x, y, radius, stroke)
        }

        private fun drawKnob(canvas: Canvas, x: Float, y: Float, radius: Float, touched: Float) {
            drawGlow(canvas, x, y, radius, KNOB_GLOW * touched, KNOB_GLOW_REACH * density)
            fill.color = ControlLook.alpha(look.accent, if (clicking) KNOB_CLICKED else KNOB + (KNOB_TOUCHED - KNOB) * touched)
            canvas.drawCircle(x, y, radius, fill)
            stroke.color = ControlLook.alpha(look.accent, KNOB_STROKE + (1f - KNOB_STROKE) * touched)
            stroke.strokeWidth = 2 * density
            canvas.drawCircle(x, y, radius, stroke)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val r = reach().coerceAtLeast(1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = event.eventTime
                    downX = event.x
                    downY = event.y
                    travel = 0f
                    originX = if (floating) event.x else centreX()
                    originY = if (floating) event.y else height / 2f
                    touching = true
                    motion.press(true)
                    feedback(this, HapticFeedbackConstants.VIRTUAL_KEY)
                    if (lastUp != 0L && event.eventTime - lastUp < CLICK_MS) setClick(true)
                    follow(event.x, event.y, r, force = true)
                }
                MotionEvent.ACTION_MOVE -> follow(event.getX(0), event.getY(0), r)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    motion.press(false)
                    val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL || event.flags and MotionEvent.FLAG_CANCELED != 0
                    travel = maxOf(travel, hypot(event.x - downX, event.y - downY))
                    lastUp = if (!clicking && !cancelled && event.eventTime - downAt <= STICK_TAP_MS && travel <= tapSlop) event.eventTime else 0L
                    setClick(false)
                    move(0f, 0f, 1f)
                }
            }
            return true
        }

        private fun follow(x: Float, y: Float, r: Float, force: Boolean = false) {
            travel = maxOf(travel, hypot(x - downX, y - downY))
            var dx = x - originX
            var dy = y - originY
            val length = hypot(dx, dy)
            if (length > r) { dx *= r / length; dy *= r / length }
            if (force || dx != knobX || dy != knobY) move(dx, dy, r)
        }

        private fun move(dx: Float, dy: Float, r: Float) {
            knobX = dx
            knobY = dy
            source?.update(dx / r, dy / r)
            invalidate()
        }

        private fun setClick(down: Boolean) {
            if (clicking == down) return
            clicking = down
            if (down) feedback(this, HapticFeedbackConstants.VIRTUAL_KEY)
            source?.click(down)
        }

        override fun onDetachedFromWindow() {
            setClick(false)
            if (knobX != 0f || knobY != 0f) move(0f, 0f, 1f)
            motion.snap(0f)
            super.onDetachedFromWindow()
        }
    }

    private inner class DirectionPad(context: Context, private val element: InputSurfaceElement, private val source: InputSurfaceSource? = null) : Surface(context) {
        private var held = 0
        private val arms = Array(4) { ControlMotion(this, squash = false) }

        override fun onDraw(canvas: Canvas) {
            val size = min(width, height) * 0.86f
            val cx = width / 2f
            val cy = height / 2f
            val half = size / 2f
            val thick = size / 3f / 2f
            val gap = thick * ARM_GAP
            val disc = min(width, height) / 2f - density
            fill.color = ControlLook.alpha(ControlLook.BACKDROP, DPAD_DISC)
            canvas.drawCircle(cx, cy, disc, fill)
            stroke.color = ControlLook.alpha(look.accent, DPAD_RING)
            stroke.strokeWidth = 1.5f * density
            canvas.drawCircle(cx, cy, disc, stroke)
            drawArm(canvas, 0, cx - thick, cy - half, cx + thick, cy - thick - gap)
            drawArm(canvas, 1, cx + thick + gap, cy - thick, cx + half, cy + thick)
            drawArm(canvas, 2, cx - thick, cy + thick + gap, cx + thick, cy + half)
            drawArm(canvas, 3, cx - half, cy - thick, cx - thick - gap, cy + thick)
            fill.color = ControlLook.alpha(look.accent, DPAD_HUB)
            canvas.drawCircle(cx, cy, size * .06f, fill)
            if (!element.showLabel) return
            labelPaint.color = look.ink
            drawDirectionLabels(canvas, source?.directions, true, cx, cy, (half + thick) / 2f, thick * 1.7f)
        }

        private fun drawArm(canvas: Canvas, index: Int, l: Float, t: Float, r: Float, b: Float) {
            val motion = arms[index]
            val press = motion.shown
            val scale = 1f - ControlMotion.SQUASH * motion.value
            val radius = min(ARM_CORNER * density, min(r - l, b - t) / 2f)
            canvas.save()
            canvas.scale(scale, scale, (l + r) / 2f, (t + b) / 2f)
            rect.set(l, t, r, b)
            drawGlow(canvas, rect, radius, ControlLook.GLOW * press)
            fill.color = look.fill(press)
            canvas.drawRoundRect(rect, radius, radius, fill)
            stroke.color = look.stroke(press)
            stroke.strokeWidth = (1.5f + .5f * press) * density
            canvas.drawRoundRect(rect, radius, radius, stroke)
            canvas.restore()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val bits = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val dx = event.getX(0) - width / 2f
                    val dy = event.getY(0) - height / 2f
                    if (hypot(dx, dy) < min(width, height) * 0.08f) 0 else {
                        val sector = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360 + 22.5) % 360 / 45).toInt()
                        intArrayOf(2, 2 or 4, 4, 4 or 8, 8, 8 or 1, 1, 1 or 2)[sector]
                    }
                }
                else -> 0
            }
            set(bits)
            return true
        }

        private fun set(bits: Int) {
            if (bits == held) return
            if (bits and held.inv() != 0) feedback(this, HapticFeedbackConstants.VIRTUAL_KEY)
            held = bits
            source?.update((if (bits and 2 != 0) 1f else 0f) - (if (bits and 8 != 0) 1f else 0f),
                (if (bits and 4 != 0) 1f else 0f) - (if (bits and 1 != 0) 1f else 0f))
            for (i in arms.indices) arms[i].press(bits and (1 shl i) != 0)
            invalidate()
        }

        override fun onDetachedFromWindow() {
            set(0)
            for (arm in arms) arm.snap(0f)
            super.onDetachedFromWindow()
        }
    }

    companion object {
        private const val CLICK_MS = 300L
        private const val STICK_TAP_MS = 200L
        private const val CLASSIC_REACH = 0.5f
        private const val PANEL_REACH = 0.30f
        private const val HIT_SLACK = 0.25f
        private const val ADAPTIVE_CLEARANCE_PX = 5f
        private val ADAPTIVE_REACH = 1.4f * kotlin.math.sqrt(1.5f)


        private const val CORNER = 20f
        private const val CORNER_PILL = 22f
        internal val SURFACE = Color.rgb(0x0A, 0x0B, 0x0D)
        internal val LINE = Color.rgb(0x26, 0x29, 0x2E)
        internal val TEXT = Color.rgb(0xF2, 0xF4, 0xF7)

        internal const val MIN_LABEL_SP = 10f
        // The stock keyboard button is 36dp across; its drawing is laid out in dp at that size.
        private const val KEYBOARD_UNITS = 36f
        private const val GLOW_RINGS = 6
        private const val GLOW_REACH = 12f
        private const val STICK_RING = .45f
        private const val STICK_RING_TOUCHED = .7f
        private const val KNOB = .25f
        private const val KNOB_TOUCHED = .7f
        private const val KNOB_CLICKED = .85f
        private const val KNOB_STROKE = .9f
        private const val KNOB_GLOW = .55f
        private const val KNOB_GLOW_REACH = 18f
        private const val DPAD_DISC = .4f
        private const val DPAD_RING = .26f
        private const val DPAD_HUB = .22f
        private const val ARM_GAP = .18f
        private const val ARM_CORNER = 8f
        private const val TRACKPAD_GUIDE = .12f
        private const val TRACKPAD_CAPTION = .75f
        private const val FINGER = .7f
        private const val FINGER_GLOW = .4f
        private const val FINGER_GLOW_REACH = 24f

        private fun adaptive(element: InputSurfaceElement) = element.kind == InputElementKind.STICK && element.floating && element.hideWhenIdle

        private fun classic(element: InputSurfaceElement) = element.buttonShape == InputButtonShape.CIRCLE || element.buttonShape == InputButtonShape.PILL

        /** Sticks saved with their own ids keep the panel reach and centre they were laid out with; stock sticks and copies of them take the classic geometry. */
        private fun classicReach(element: InputSurfaceElement) = classic(element) && (copiedStock(element.id) ?: element.id).let { it.startsWith("legacy:") || it == "builtin:keyboard" }

        private val DIRECTIONS = listOf(InputControl.UP, InputControl.RIGHT, InputControl.DOWN, InputControl.LEFT)

        internal fun shade(tint: Int, alpha: Int, f: Float) =
            Color.argb(alpha, (Color.red(tint) * f).toInt(), (Color.green(tint) * f).toInt(), (Color.blue(tint) * f).toInt())

        internal fun light(tint: Int, alpha: Int, f: Float) = Color.argb(alpha, (Color.red(tint) + (255 - Color.red(tint)) * f).toInt(),
            (Color.green(tint) + (255 - Color.green(tint)) * f).toInt(), (Color.blue(tint) + (255 - Color.blue(tint)) * f).toInt())

        private fun glyph(control: InputControl) = when (control) {
            InputControl.LS -> "L"
            InputControl.RS -> "R"
            InputControl.SELECT -> "⧉"
            InputControl.START -> "☰"
            InputControl.GUIDE -> "◉"
            InputControl.QAM -> "⋯"
            else -> control.id.uppercase()
        }

        private val CARDINAL = listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f)
        private val DIAGONAL = listOf(.7071f to -.7071f, .7071f to .7071f, -.7071f to .7071f, -.7071f to -.7071f)

        internal fun directionLabels(context: Context, action: InputAction.Directions?, cardinalOnly: Boolean): List<Pair<Pair<Float, Float>, String>> {
            if (action == null) return emptyList()
            val codes = listOf(action.up, action.right, action.down, action.left).zip(CARDINAL) +
                if (cardinalOnly) emptyList() else action.diagonals.orEmpty().zip(DIAGONAL)
            return codes.filter { it.first != 0 }.map { (code, unit) -> unit to pcKeyLabel(context, code) }
        }

        private fun drawArrow(canvas: Canvas, path: Path, x: Float, y: Float, size: Float, direction: Int, paint: Paint) {
            val fx = when (direction) { 1 -> 1f; 3 -> -1f; else -> 0f }
            val fy = when (direction) { 0 -> -1f; 2 -> 1f; else -> 0f }
            path.rewind()
            path.moveTo(x + fx * size, y + fy * size)
            path.lineTo(x - fx * size * 0.7f - fy * size, y - fy * size * 0.7f + fx * size)
            path.lineTo(x - fx * size * 0.7f + fy * size, y - fy * size * 0.7f - fx * size)
            path.close()
            canvas.drawPath(path, paint)
        }

        internal fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

        internal fun outline(context: Context, radius: Float, fill: Int) = GradientDrawable().apply {
            cornerRadius = radius * context.resources.displayMetrics.density
            setColor(fill)
            setStroke(dp(context, 1), LINE)
        }

        internal fun roundButton(context: Context, label: String, description: String, onClick: () -> Unit, wide: Boolean = false) =
            TextView(context).apply {
                text = label
                contentDescription = description
                textSize = if (wide) 13f else 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = if (wide) 0.08f else 0f
                gravity = Gravity.CENTER
                setTextColor(TEXT)
                background = outline(context, CORNER_PILL, SURFACE)
                setPadding(if (wide) dp(context, 16) else 0, 0, if (wide) dp(context, 16) else 0, 0)
                minWidth = dp(context, 44)
                layoutParams = LinearLayout.LayoutParams(if (wide) LinearLayout.LayoutParams.WRAP_CONTENT else dp(context, 44), dp(context, 44))
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                }
            }

    }
}
