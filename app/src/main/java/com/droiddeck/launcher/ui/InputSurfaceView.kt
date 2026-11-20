package com.droiddeck.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import android.view.MotionEvent
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
import com.droiddeck.launcher.input.KitLayout
import com.droiddeck.launcher.input.OnScreenLayout
import com.droiddeck.launcher.input.SavedInputLayout
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.PadKit
import com.droiddeck.launcher.input.pcKeyLabel
import com.droiddeck.launcher.session.SessionPrefs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

@SuppressLint("ViewConstructor")
class InputSurfaceView(
    context: Context,
    private val pad: PadBridge? = null,
    private var customLayout: List<InputSurfaceElement> = emptyList(),
    val inputRole: InputRole = InputRole.SECONDARY,
    private var surfaceStyle: InputSurfaceStyle = InputSurfaceStyle(),
    private val haptics: Boolean = inputRole != InputRole.PRIMARY,
    kit: PadKit = PadKit.current(context),
) : FrameLayout(context) {
    var kit: PadKit = kit
        set(value) { if (field != value) { field = value; customCanvas?.let { c -> for (i in 0 until c.childCount) c.getChildAt(i).invalidate() } } }
    private val art = PadArt(context)
    private val content = FrameLayout(context)
    private val elementSources = ArrayList<InputSurfaceSource>()
    private var customCanvas: ViewGroup? = null
    private val owners = HashMap<Int, View>()
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

    fun updateStyle(style: InputSurfaceStyle) {
        if (surfaceStyle == style) return
        val relayout = surfaceStyle.hidden != style.hidden
        surfaceStyle = style
        if (relayout) updateCustomLayout(requestedLayout)
        customCanvas?.let { canvas -> for (i in 0 until canvas.childCount) styleElement(canvas.getChildAt(i)) }
    }

    private fun styleElement(view: View) {
        view.alpha = surfaceStyle.opacity / 100f * ((view.tag as? InputSurfaceElement)?.opacity ?: 1f)
        view.invalidate()
    }

    private var generated: SavedInputLayout? = null

    /** Shows [layout]; a layout that has no elements of its own gets the virtual controller's defaults at this view's size. */
    fun showLayout(layout: SavedInputLayout?) {
        generated = layout?.takeIf(OnScreenLayout::generated)
        kit = PadKit.current(context)
        updateStyle(layout?.style ?: InputSurfaceStyle())
        updateCustomLayout(generated?.let { OnScreenLayout.elements(context, it, width, height, android.graphics.Rect(), null) } ?: layout?.elements.orEmpty())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val layout = generated ?: return
        post { if (generated === layout) updateCustomLayout(OnScreenLayout.elements(context, layout, width, height, android.graphics.Rect(), null)) }
    }

    fun updateCustomLayout(requested: List<InputSurfaceElement>) {
        requestedLayout = requested
        val layout = surfaceStyle.shown(requested)
        val sameElements = customLayout.map { it.copy(x = 0f, y = 0f, width = 1f, height = 1f) } == layout.map { it.copy(x = 0f, y = 0f, width = 1f, height = 1f) }
        customLayout = layout
        if (sameElements) customCanvas?.requestLayout() else {
            elementSources.forEach { it.close() }; elementSources.clear()
            owners.clear(); content.removeAllViews(); renderCustom(pad)
        }
    }

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
            }
        }
        canvas.clipChildren = customLayout.none(::adaptive)
        for (element in customLayout) {
            val source = pad?.let { InputSurfaceSource(it, inputRole, element).also { s -> elementSources += s } }
            canvas.addView(when (element.kind) {
                InputElementKind.STICK -> StickView(context, element, source, element.floating, element.hideWhenIdle && pad != null)
                InputElementKind.TRACKPAD -> TrackpadView(context, source)
                InputElementKind.DPAD -> DirectionPad(context, element, source)
                else -> HoldButton(context, element, source)
            }.apply { tag = element; contentDescription = element.name; styleElement(this); source?.onStateChanged = { postInvalidate() } })
        }
        content.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        customCanvas = canvas
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
        protected val density = resources.displayMetrics.density
        protected val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = TEXT
        }
    }

    private inner class HoldButton(context: Context, private val element: InputSurfaceElement, private val source: InputSurfaceSource?) : Surface(context) {
        private var down = false
        private val pointers = HashSet<Int>()
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

        override fun onDraw(canvas: Canvas) {
            val lit = if (element.toggle) source?.latched == true else down
            val w = width.toFloat(); val h = height.toFloat()
            val c = element.control
            val native = element.id == "legacy:${c.id}" && element.showLabel && element.icon == 0 && bitmap == null &&
                (element.name == c.label || element.name == kit.name(c)) && (c !in FACE || element.buttonShape == InputButtonShape.CIRCLE)
            when {
                element.id == KitLayout.KEYBOARD_ID && element.icon == InputButtonIcons.KEYBOARD -> art.system(canvas, w, h, PadArt.Shape.ROUND, PadArt.Icon.KEYBOARD, lit)
                native && c in FACE -> art.face(canvas, w / 2f, h / 2f, min(w, h) / 2f - art.dp(2f), c, kit, lit)
                native && c in SHOULDERS -> art.shoulder(canvas, w, h, c, kit, lit)
                native && c in GRIPS -> art.grip(canvas, w, h, c.label, lit)
                native && (c == InputControl.L3 || c == InputControl.R3) -> art.small(canvas, w, h, c.label, lit)
                native && c in SYSTEM -> systemLook(c).let { (shape, icon) -> art.system(canvas, w, h, shape, icon, lit) }
                else -> custom(canvas, w, h, lit)
            }
        }

        private fun systemLook(c: InputControl): Pair<PadArt.Shape, PadArt.Icon> = when {
            kit.steam -> when (c) {
                InputControl.SELECT -> PadArt.Shape.CHIP to PadArt.Icon.VIEW
                InputControl.START -> PadArt.Shape.CHIP to PadArt.Icon.MENU
                InputControl.GUIDE -> PadArt.Shape.PILL to PadArt.Icon.STEAM
                else -> PadArt.Shape.PILL to PadArt.Icon.QAM
            }
            kit.playStation -> when (c) {
                InputControl.SELECT -> PadArt.Shape.PILL to if (kit == PadKit.DUALSHOCK) PadArt.Icon.SHARE else PadArt.Icon.CREATE
                InputControl.START -> PadArt.Shape.PILL to if (kit == PadKit.DUALSHOCK) PadArt.Icon.OPTIONS else PadArt.Icon.MENU
                InputControl.GUIDE -> PadArt.Shape.ROUND to PadArt.Icon.HOME
                else -> PadArt.Shape.ROUND to PadArt.Icon.QAM
            }
            else -> PadArt.Shape.ROUND to when (c) {
                InputControl.SELECT -> PadArt.Icon.BACK
                InputControl.START -> PadArt.Icon.START
                InputControl.GUIDE -> PadArt.Icon.HOME
                else -> PadArt.Icon.QAM
            }
        }

        private fun custom(canvas: Canvas, w: Float, h: Float, lit: Boolean) {
            val inset = art.dp(2f)
            art.glass(canvas, when (element.buttonShape) {
                InputButtonShape.CIRCLE -> art.circle(w / 2f, h / 2f, min(w, h) / 2f - inset)
                InputButtonShape.PILL -> art.roundRect(inset, inset, w - inset, h - inset, min(w, h) / 2f)
                InputButtonShape.RECTANGLE -> art.roundRect(inset, inset, w - inset, h - inset, art.dp(4f))
                InputButtonShape.ROUNDED -> art.roundRect(inset, inset, w - inset, h - inset, min(w, h) * .22f)
            }, lit)
            labelPaint.color = if (lit) PadArt.ON_ACCENT else PadArt.INK
            bitmap?.let {
                val scale = min(w * .84f / it.width, h * .84f / it.height)
                imageRect.set(w / 2f - it.width * scale / 2, (h - it.height * scale) / 2, w / 2f + it.width * scale / 2, (h + it.height * scale) / 2)
                canvas.drawBitmap(it, null, imageRect, labelPaint)
                return
            }
            if (element.icon != 0 && InputButtonIcons.draw(canvas, element.icon, w / 2f, h / 2f, min(w, h) * .6f, labelPaint, symbol)) return
            if (element.showLabel) art.text(canvas, element.name, w / 2f, h / 2f, min(h * .34f, 26 * density), labelPaint.color, w * .82f)
        }

        fun press(pointer: Int, pressed: Boolean, cancelled: Boolean = false) {
            if (pressed) pointers.add(pointer) else pointers.remove(pointer)
            set(pointers.isNotEmpty(), cancelled)
        }

        fun cancel() { pointers.clear(); set(false, cancelled = true); source?.release() }

        override fun onDetachedFromWindow() { cancel(); super.onDetachedFromWindow() }

        private fun set(pressed: Boolean, cancelled: Boolean = false) {
            if (down == pressed) return
            down = pressed
            if (pressed) feedback(this, HapticFeedbackConstants.VIRTUAL_KEY)
            if (!pressed && cancelled) source?.cancel() else source?.update(if (pressed) 1f else 0f)
            invalidate()
        }
    }

    private inner class TrackpadView(context: Context, private val source: InputSurfaceSource? = null) : Surface(context) {
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

        override fun onDraw(canvas: Canvas) {
            art.trackpad(canvas, width.toFloat(), height.toFloat(), touching || clicked, fingerX, fingerY)
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

        override fun onDetachedFromWindow() { mouse.cancel(); source?.release(); super.onDetachedFromWindow() }

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
        val adaptive get() = adaptive(element)

        private fun radius() = (min(width, height) / 2f - 3 * density).coerceAtLeast(1f)

        fun reach() = radius() * STICK_REACH

        fun captures(x: Float, y: Float): Boolean {
            val cx = centreX()
            val dx = when (element.control) { InputControl.LS -> maxOf(0f, x - cx); InputControl.RS -> minOf(0f, x - cx); else -> x - cx }
            val limit = radius() * ADAPTIVE_REACH
            return dx * dx + (y - height / 2f) * (y - height / 2f) <= limit * limit
        }

        fun centreX() = width / 2f

        override fun onDraw(canvas: Canvas) {
            if (hideWhenIdle && !touching) return
            val cx = if (touching) originX else centreX()
            val cy = if (touching) originY else height / 2f
            val r = radius()
            val travel = r * KNOB_TRAVEL / reach().coerceAtLeast(1f)
            art.stick(canvas, cx, cy, r, kit, knobX * travel, knobY * travel, touching || clicking)
            if (!element.showLabel) return
            labelPaint.color = PadArt.INK
            drawDirectionLabels(canvas, source, false, cx, cy, r * .66f, r * .34f, labelPaint)
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
                    feedback(this, HapticFeedbackConstants.VIRTUAL_KEY)
                    if (lastUp != 0L && event.eventTime - lastUp < CLICK_MS) setClick(true)
                    follow(event.x, event.y, r, force = true)
                }
                MotionEvent.ACTION_MOVE -> follow(event.getX(0), event.getY(0), r)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
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
            super.onDetachedFromWindow()
        }
    }

    private inner class DirectionPad(context: Context, private val element: InputSurfaceElement, private val source: InputSurfaceSource? = null) : View(context) {
        private var held = 0
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = TEXT }

        override fun onDraw(canvas: Canvas) {
            val labels = element.showLabel && directionLabels(context, source?.directions, true).isNotEmpty()
            art.dpad(canvas, width.toFloat(), height.toFloat(), kit, held, chevrons = !labels)
            if (!labels) return
            val half = min(width, height) / 2f
            drawDirectionLabels(canvas, source, true, width / 2f, height / 2f, half * .68f, half * .32f, label)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) { source?.cancel(); held = 0; invalidate(); return true }
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

            invalidate()
        }

        override fun onDetachedFromWindow() {
            set(0)
            super.onDetachedFromWindow()
        }
    }

    companion object {
        private const val CLICK_MS = 300L
        private const val STICK_TAP_MS = 200L
        private const val STICK_REACH = .62f
        private const val KNOB_TRAVEL = .54f
        private const val HIT_SLACK = 0.25f
        private const val ADAPTIVE_CLEARANCE_PX = 5f
        private const val ADAPTIVE_REACH = 1.71f
        private const val CORNER_PILL = 22f
        internal val SURFACE = Color.rgb(0x0A, 0x0B, 0x0D)
        internal val LINE = Color.rgb(0x26, 0x29, 0x2E)
        internal val TEXT = Color.rgb(0xF2, 0xF4, 0xF7)
        private val FACE = setOf(InputControl.A, InputControl.B, InputControl.X, InputControl.Y)
        private val SHOULDERS = setOf(InputControl.LB, InputControl.RB, InputControl.LT, InputControl.RT)
        private val GRIPS = setOf(InputControl.L4, InputControl.L5, InputControl.R4, InputControl.R5)
        private val SYSTEM = setOf(InputControl.SELECT, InputControl.START, InputControl.GUIDE, InputControl.QAM)

        private fun adaptive(element: InputSurfaceElement) = element.kind == InputElementKind.STICK && element.floating

        private val CARDINAL = listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f)
        private val DIAGONAL = listOf(.7071f to -.7071f, .7071f to .7071f, -.7071f to .7071f, -.7071f to -.7071f)

        internal fun directionLabels(context: Context, action: InputAction.Directions?, cardinalOnly: Boolean): List<Pair<Pair<Float, Float>, String>> {
            if (action == null) return emptyList()
            val codes = listOf(action.up, action.right, action.down, action.left).zip(CARDINAL) +
                if (cardinalOnly) emptyList() else action.diagonals.orEmpty().zip(DIAGONAL)
            return codes.filter { it.first != 0 }.map { (code, unit) -> unit to pcKeyLabel(context, code) }
        }

        private fun View.drawDirectionLabels(canvas: Canvas, source: InputSurfaceSource?, cardinalOnly: Boolean, cx: Float, cy: Float,
            distance: Float, box: Float, paint: Paint): Boolean {
            val labels = directionLabels(context, source?.directions, cardinalOnly)
            paint.style = Paint.Style.FILL
            for ((unit, text) in labels) {
                paint.textSize = box * .6f
                val measured = paint.measureText(text)
                if (measured > box) paint.textSize *= box / measured
                canvas.drawText(text, cx + unit.first * distance, cy + unit.second * distance - (paint.ascent() + paint.descent()) / 2f, paint)
            }
            return labels.isNotEmpty()
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
