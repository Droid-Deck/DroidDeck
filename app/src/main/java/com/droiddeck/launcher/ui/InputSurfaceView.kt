package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.droiddeck.launcher.input.InputRole
import com.droiddeck.launcher.input.InputControl
import com.droiddeck.launcher.input.InputSurfaceElement
import com.droiddeck.launcher.input.InputSurfaceStyle
import com.droiddeck.launcher.input.pointerSubset
import com.droiddeck.launcher.input.InputSurfaceSource
import com.droiddeck.launcher.input.InputElementKind
import com.droiddeck.launcher.input.InputButtonIcons
import com.droiddeck.launcher.input.InputButtonShape
import java.io.File
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.session.SessionPrefs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

@SuppressLint("ViewConstructor")
class InputSurfaceView(
    context: Context,
    private val pad: PadBridge? = null,
    private var customLayout: List<InputSurfaceElement> = emptyList(),
    private val inputRole: InputRole = InputRole.SECONDARY,
    private var surfaceStyle: InputSurfaceStyle = InputSurfaceStyle(),
) : FrameLayout(context) {
    private val content = FrameLayout(context)
    private val elementSources = ArrayList<InputSurfaceSource>()
    private var customCanvas: ViewGroup? = null
    init { addView(content, LayoutParams(-1, -1)); renderCustom(pad) }

    var onTouchMiss: ((MotionEvent) -> Unit)? = null
    private var forwardedPointers = 0

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) forwardedPointers = 0
        if (onTouchMiss == null) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            val index = event.actionIndex
            if (!takesTouchAt(event.getX(index), event.getY(index))) forwardedPointers = forwardedPointers or (1 shl event.getPointerId(index))
            else if (SessionPrefs.lowLatency(context)) requestUnbufferedDispatch(event)
        }
        if (forwardedPointers == 0) return super.dispatchTouchEvent(event)
        var all = 0
        for (index in 0 until event.pointerCount) all = all or (1 shl event.getPointerId(index))
        event.pointerSubset(all and forwardedPointers.inv())?.let { split ->
            try { super.dispatchTouchEvent(split) } finally { split.recycle() }
        }
        event.pointerSubset(all and forwardedPointers)?.let { split ->
            try { onTouchMiss?.invoke(split) } finally { split.recycle() }
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) forwardedPointers = forwardedPointers and (1 shl event.getPointerId(event.actionIndex)).inv()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) forwardedPointers = 0
        return true
    }

    fun updateStyle(style: InputSurfaceStyle) {
        if (surfaceStyle == style) return
        surfaceStyle = style
        customCanvas?.let { canvas -> for (i in 0 until canvas.childCount) styleElement(canvas.getChildAt(i)) }
    }

    private fun styleElement(view: View) {
        view.alpha = surfaceStyle.opacity / 100f * ((view.tag as? InputSurfaceElement)?.opacity ?: 1f)
        (view as? Surface)?.tint = surfaceStyle.tint
        view.invalidate()
    }

    fun updateCustomLayout(layout: List<InputSurfaceElement>) {
        val sameElements = customLayout.map { it.copy(x = 0f, y = 0f, width = 1f, height = 1f) } == layout.map { it.copy(x = 0f, y = 0f, width = 1f, height = 1f) }
        customLayout = layout
        if (sameElements) customCanvas?.requestLayout() else {
            elementSources.forEach { it.close() }; elementSources.clear()
            content.removeAllViews(); renderCustom(pad)
        }
    }

    fun releaseInputs() {
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
        forwardedPointers = 0
        elementSources.forEach { it.release() }
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
        for (element in customLayout) {
            val source = pad?.let { InputSurfaceSource(it, inputRole, element).also { s -> elementSources += s } }
            canvas.addView(when (element.kind) {
                InputElementKind.STICK -> StickView(context, source, element.floating, element.hideWhenIdle && pad != null)
                InputElementKind.TRACKPAD -> TrackpadView(context, element.control == InputControl.RIGHT_PAD, source = source)
                InputElementKind.DPAD -> DirectionPad(context, source)
                else -> HoldButton(context, element, source)
            }.apply { tag = element; contentDescription = element.name; styleElement(this); source?.onStateChanged = { postInvalidate() } })
        }
        content.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        customCanvas = canvas
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (pad != null && elementSources.isEmpty()) {
            content.removeAllViews()
            renderCustom(pad)
        }
    }

    override fun onDetachedFromWindow() {
        elementSources.forEach { it.close() }
        elementSources.clear()
        super.onDetachedFromWindow()
    }

    fun takesTouchAt(x: Float, y: Float): Boolean = controlUnder(this, x, y)

    private fun controlUnder(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != VISIBLE || x < 0f || y < 0f || x >= view.width || y >= view.height) return false
        if (view.isClickable || view is HorizontalScrollView || view is Surface || view is DirectionPad ||
            view is androidx.compose.ui.platform.ComposeView) return true
        val group = view as? ViewGroup ?: return false
        return (group.childCount - 1 downTo 0).any { i ->
            val child = group.getChildAt(i)
            controlUnder(child, x + group.scrollX - child.x, y + group.scrollY - child.y)
        }
    }

    private abstract class Surface(context: Context) : View(context) {
        var tint: Int? = null
        protected val density = resources.displayMetrics.density
        protected val rect = RectF()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        protected val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        protected val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = TEXT_DIM
        }

        protected fun drawSurface(canvas: Canvas, lit: Boolean, shape: InputButtonShape = InputButtonShape.ROUNDED) {
            val inset = density
            rect.set(inset, inset, width - inset, height - inset)
            val radius = when (shape) { InputButtonShape.CIRCLE, InputButtonShape.PILL -> min(width, height) / 2f; InputButtonShape.RECTANGLE -> 2 * density; else -> CORNER * density }
            fill.color = tint?.let { color -> (color and 0x00ffffff) or ((if (lit) 140 else 48) shl 24) } ?: if (lit) ACCENT_WASH else SURFACE
            canvas.drawRoundRect(rect, radius, radius, fill)
            stroke.color = tint?.let { color -> (color and 0x00ffffff) or ((if (lit) 255 else 160) shl 24) } ?: if (lit) ACCENT else LINE
            stroke.strokeWidth = (if (lit) 2f else 1f) * density
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }
    }

    private class HoldButton(context: Context, private val element: InputSurfaceElement, private val source: InputSurfaceSource?) : Surface(context) {
        private var down = false
        private val symbol = InputButtonIcons.drawable(context, element.icon)
        private val bitmap = element.image.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && it != "." && it != ".." }?.let { name ->
            val file = File(File(context.filesDir, "screenkeys"), name)
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
            drawSurface(canvas, lit, element.buttonShape)
            val cx = width / 2f
            labelPaint.color = if (lit) ACCENT else TEXT
            bitmap?.let {
                val scale = min(width * .84f / it.width, height * .84f / it.height)
                imageRect.set(cx - it.width * scale / 2, (height - it.height * scale) / 2,
                    cx + it.width * scale / 2, (height + it.height * scale) / 2)
                canvas.drawBitmap(it, null, imageRect, labelPaint)
                return
            }
            if (element.icon != 0 && InputButtonIcons.draw(canvas, element.icon, cx, height / 2f, min(width, height) * .68f, labelPaint, symbol)) return
            if (!element.showLabel) return
            labelPaint.textSize = min(height * .3f, 30 * density)
            val labelWidth = labelPaint.measureText(element.name)
            if (labelWidth > width * .84f) labelPaint.textSize *= width * .84f / labelWidth
            canvas.drawText(element.name, cx, height / 2f + labelPaint.textSize * .35f, labelPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> set(true)
                MotionEvent.ACTION_UP -> set(false)
                MotionEvent.ACTION_CANCEL -> { set(false); source?.release() }
            }
            return true
        }

        override fun onDetachedFromWindow() { set(false); source?.release(); super.onDetachedFromWindow() }

        private fun set(pressed: Boolean) {
            if (down == pressed) return
            down = pressed
            if (pressed) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            source?.update(if (pressed) 1f else 0f)
            invalidate()
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
            override fun onLongPress() { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        }).apply { bounds = RectF(-1e9f, -1e9f, 1e9f, 1e9f) }

        private var touching = false
        private var clicked = false
        private var fingerX = 0f
        private var fingerY = 0f
        private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = GUIDE }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            drawSurface(canvas, clicked)
            val cx = width / 2f
            val cy = height / 2f
            guide.strokeWidth = density
            val reach = min(width, height) / 2f - 24 * density
            canvas.drawCircle(cx, cy, reach, guide)
            canvas.drawCircle(cx, cy, reach / 2f, guide)
            canvas.drawLine(cx - reach, cy, cx + reach, cy, guide)
            canvas.drawLine(cx, cy - reach, cx, cy + reach, guide)
            captionPaint.textSize = 12 * density
            captionPaint.color = if (touching) ACCENT else TEXT_DIM
            if (captioned) canvas.drawText((contentDescription?.toString() ?: if (right) context.getString(R.string.deck_right_trackpad) else context.getString(R.string.deck_left_trackpad)).uppercase(), cx, 28 * density, captionPaint)
            if (touching) {
                val radius = 56 * density
                glow.shader = RadialGradient(fingerX, fingerY, radius, ACCENT_GLOW, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawCircle(fingerX, fingerY, radius, glow)
                canvas.drawCircle(fingerX, fingerY, (if (clicked) 16 else 12) * density, dot)
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
            if (down) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            source?.click(down)
            invalidate()
        }
    }

    private inner class StickView(context: Context, private val source: InputSurfaceSource? = null, private val floating: Boolean = true, private val hideWhenIdle: Boolean = false) : Surface(context) {
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
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun reach() = min(width, height) * 0.30f

        override fun onDraw(canvas: Canvas) {
            if (hideWhenIdle && !touching) return
            drawSurface(canvas, clicking)
            val cx = if (touching) originX else width / 2f
            val cy = if (touching) originY else height / 2f
            ring.strokeWidth = 2 * density
            ring.color = if (touching) ACCENT else LINE
            canvas.drawCircle(cx, cy, reach(), ring)
            knob.color = if (touching) ACCENT else KNOB
            canvas.drawCircle(cx + knobX, cy + knobY, reach() * 0.45f, knob)
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
                    originX = if (floating) event.x else width / 2f
                    originY = if (floating) event.y else height / 2f
                    touching = true
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
            if (down) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            source?.click(down)
        }

        override fun onDetachedFromWindow() {
            setClick(false)
            if (knobX != 0f || knobY != 0f) move(0f, 0f, 1f)
            super.onDetachedFromWindow()
        }
    }

    private inner class DirectionPad(context: Context, private val source: InputSurfaceSource? = null) : View(context) {
        private val density = resources.displayMetrics.density
        private var held = 0
        private val arm = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val size = min(width, height) * 0.86f
            val cx = width / 2f
            val cy = height / 2f
            val half = size / 2f
            val thick = size / 3f / 2f
            fun armRect(bit: Int, l: Float, t: Float, r: Float, b: Float) {
                arm.color = if (held and bit != 0) ACCENT else KNOB
                rect.set(l, t, r, b)
                canvas.drawRoundRect(rect, 8 * density, 8 * density, arm)
            }
            armRect(1, cx - thick, cy - half, cx + thick, cy - thick)
            armRect(4, cx - thick, cy + thick, cx + thick, cy + half)
            armRect(8, cx - half, cy - thick, cx - thick, cy + thick)
            armRect(2, cx + thick, cy - thick, cx + half, cy + thick)
            arm.color = KNOB
            canvas.drawRect(cx - thick, cy - thick, cx + thick, cy + thick, arm)
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
            if (bits and held.inv() != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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


        private const val CORNER = 20f
        private const val CORNER_PILL = 22f
        private val ACCENT = Color.rgb(0x1A, 0x9F, 0xFF)
        private val ACCENT_WASH = Color.argb(0x2E, 0x1A, 0x9F, 0xFF)
        private val ACCENT_GLOW = Color.argb(0x66, 0x1A, 0x9F, 0xFF)
        private val ON_ACCENT = Color.rgb(0x03, 0x11, 0x1F)
        private val SURFACE = Color.rgb(0x0A, 0x0B, 0x0D)
        private val LINE = Color.rgb(0x26, 0x29, 0x2E)
        private val GUIDE = Color.rgb(0x16, 0x18, 0x1B)
        private val TEXT = Color.rgb(0xF2, 0xF4, 0xF7)
        private val TEXT_DIM = Color.rgb(0x7A, 0x80, 0x8A)
        private val KNOB = Color.rgb(0x2A, 0x2D, 0x33)

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
