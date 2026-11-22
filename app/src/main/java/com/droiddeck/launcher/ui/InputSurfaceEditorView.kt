package com.droiddeck.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import com.droiddeck.launcher.input.OnScreenLayout
import com.droiddeck.launcher.input.PadKit
import com.droiddeck.launcher.input.SavedInputLayout
import com.droiddeck.launcher.input.InputSurfaceElement
import com.droiddeck.launcher.input.InputSurfaceStyle
import com.droiddeck.launcher.input.dpadPiece
import com.droiddeck.launcher.input.frame
import com.droiddeck.launcher.input.groupOf
import com.droiddeck.launcher.input.snapSurfaceElement
import kotlin.math.abs

internal class InputSurfaceEditorView(context: Context) : FrameLayout(context) {
    var elements: List<InputSurfaceElement> = emptyList()
        set(value) { field = value; refreshElements(); invalidate() }
    var aspect = 1f
        set(value) { field = value; refreshElements(); invalidate() }
    var sourceLayout: SavedInputLayout? = null
        set(value) {
            field = value
            updatePreviewStyle()
            refreshElements()
            invalidate()
        }
    var style = InputSurfaceStyle()
        set(value) { field = value; updatePreviewStyle() }
    var snap = true
        set(value) { field = value; if (!value) clearGuides() }
    private var guideX: Float? = null
    private var guideY: Float? = null
    var selected: String? = null
        set(value) { field = value; invalidate() }
    var onSelect: (String?) -> Unit = {}
    var onBeginEdit: (List<InputSurfaceElement>, Int, Int) -> Unit = { _, _, _ -> }
    var onChange: (List<InputSurfaceElement>) -> Unit = {}
    var onResize: (Int, Int) -> Unit = { _, _ -> }
    var onDisplayed: (List<InputSurfaceElement>) -> Unit = {}
    private val preview = InputSurfaceView(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 159, 255); strokeWidth = 2 * resources.displayMetrics.density }
    private val radius = 14 * resources.displayMetrics.density
    private var original: List<InputSurfaceElement> = emptyList()
    private var downX = 0f
    private var downY = 0f
    private var corner = 0
    private var dragging = false

    private var visibleElements: List<InputSurfaceElement> = emptyList()

    init {
        setBackgroundColor(Color.rgb(10, 11, 13))
        addView(preview, LayoutParams(-1, -1))
    }

    private fun updatePreviewStyle() {
        preview.updateStyle(style, PadKit.forOverlay(context, sourceLayout))
    }

    private var safe = Rect()
    private var frameWidth = 0
    private var frameHeight = 0

    private fun refreshElements() {
        visibleElements = sourceLayout?.let { layout ->
            OnScreenLayout.editorElements(context, layout.copy(elements = elements, aspect = aspect), frameWidth, frameHeight, safe = safe)
        } ?: elements
        preview.updateCustomLayout(visibleElements)
        onDisplayed(visibleElements)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val fresh = OnScreenLayout.safeInsets(insets, false)
        if (fresh != safe) { safe = fresh; refreshElements(); invalidate() }
        return super.onApplyWindowInsets(insets)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (measuredWidth == frameWidth && measuredHeight == frameHeight) return
        frameWidth = measuredWidth; frameHeight = measuredHeight
        refreshElements()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onResize(w, h)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        paint.style = Paint.Style.STROKE
        guideX?.let { canvas.drawLine(width * it, 0f, width * it, height.toFloat(), paint) }
        guideY?.let { canvas.drawLine(0f, height * it, width.toFloat(), height * it, paint) }
        val e = visibleElements.firstOrNull { it.id == selected }?.let { visibleElements.groupOf(it).frame() } ?: return
        val l = width * e.x; val t = height * e.y; val r = l + width * e.width; val b = t + height * e.height
        paint.style = Paint.Style.STROKE; canvas.drawRect(l, t, r, b, paint)
        paint.style = Paint.Style.FILL
        for (x in listOf(l, r)) for (y in listOf(t, b)) canvas.drawCircle(x, y, radius / 3, paint)
    }

    private fun contains(e: InputSurfaceElement, x: Float, y: Float) = x / width in e.x..(e.x + e.width) && y / height in e.y..(e.y + e.height)

    private fun clearGuides() { guideX = null; guideY = null; invalidate() }

    override fun onInterceptTouchEvent(event: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || width == 0 || height == 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearGuides()
                downX = event.x; downY = event.y; dragging = false; corner = 0
                val current = visibleElements.firstOrNull { it.id == selected }?.let { visibleElements.groupOf(it).frame() }
                if (current != null) {
                    val left = width * current.x; val top = height * current.y
                    val right = left + width * current.width; val bottom = top + height * current.height
                    val horizontal = if (abs(downX - left) < radius) 1 else if (abs(downX - right) < radius) 2 else 0
                    val vertical = if (abs(downY - top) < radius) 4 else if (abs(downY - bottom) < radius) 8 else 0
                    if (horizontal != 0 && vertical != 0) corner = horizontal or vertical
                }
                val touched = if (corner != 0) visibleElements.firstOrNull { it.id == selected }
                    else visibleElements.asReversed().firstOrNull { contains(it, downX, downY) }
                    ?: visibleElements.filter { it.dpadPiece }.takeIf { it.isNotEmpty() && contains(it.frame(), downX, downY) }
                        ?.let { pad -> pad.firstOrNull { it.id == selected } ?: pad.first() }
                original = touched?.let { visibleElements.groupOf(it) }.orEmpty()
                selected = touched?.id; onSelect(selected)
            }
            MotionEvent.ACTION_MOVE -> {
                if (original.isEmpty()) return true
                val e = original.frame()
                if (!dragging && abs(event.x - downX) + abs(event.y - downY) > ViewConfiguration.get(context).scaledTouchSlop) {
                    dragging = true; onBeginEdit(visibleElements, width, height)
                }
                if (!dragging) return true
                val dx = (event.x - downX) / width; val dy = (event.y - downY) / height
                val next = if (corner == 0) e.copy(x = (e.x + dx).coerceIn(0f, 1f - e.width), y = (e.y + dy).coerceIn(0f, 1f - e.height))
                    else {
                        val minW = (.03f * e.width / original.minOf { it.width }).coerceIn(.03f, e.width.coerceAtLeast(.03f))
                        val minH = (.03f * e.height / original.minOf { it.height }).coerceIn(.03f, e.height.coerceAtLeast(.03f))
                        val left = if (corner and 1 != 0) (e.x + dx).coerceIn(0f, (e.x + e.width - minW).coerceAtLeast(0f)) else e.x
                        val top = if (corner and 4 != 0) (e.y + dy).coerceIn(0f, (e.y + e.height - minH).coerceAtLeast(0f)) else e.y
                        val right = if (corner and 2 != 0) (e.x + e.width + dx).coerceIn((left + minW).coerceAtMost(1f), 1f) else e.x + e.width
                        val bottom = if (corner and 8 != 0) (e.y + e.height + dy).coerceIn((top + minH).coerceAtMost(1f), 1f) else e.y + e.height
                        e.copy(x = left, y = top, width = right - left, height = bottom - top).bounded()
                    }
                val aligned = if (snap) snapSurfaceElement(next, visibleElements.filter { v -> original.none { it.id == v.id } }, 9 * resources.displayMetrics.density / width,
                    9 * resources.displayMetrics.density / height, corner) else null
                if (aligned != null && (aligned.x != null && aligned.x != guideX || aligned.y != null && aligned.y != guideY)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                guideX = aligned?.x; guideY = aligned?.y
                val f = aligned?.element ?: next
                val sx = f.width / e.width; val sy = f.height / e.height
                onChange(original.map { it.copy(x = f.x + (it.x - e.x) * sx, y = f.y + (it.y - e.y) * sy, width = it.width * sx, height = it.height * sy) })
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { original = emptyList(); dragging = false; clearGuides() }
        }
        return true
    }
}
