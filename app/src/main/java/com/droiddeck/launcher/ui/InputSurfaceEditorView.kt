package com.droiddeck.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.droiddeck.launcher.input.InputSurfaceElement
import com.droiddeck.launcher.input.InputSurfaceStyle
import com.droiddeck.launcher.input.snapSurfaceElement
import kotlin.math.abs

internal class InputSurfaceEditorView(context: Context) : FrameLayout(context) {
    var elements: List<InputSurfaceElement> = emptyList()
        set(value) { field = value; preview.updateCustomLayout(value); invalidate() }
    var style = InputSurfaceStyle()
        set(value) { field = value; preview.updateStyle(value) }
    var snap = true
        set(value) { field = value; if (!value) clearGuides() }
    private var guideX: Float? = null
    private var guideY: Float? = null
    var selected: String? = null
        set(value) { field = value; invalidate() }
    var onSelect: (String?) -> Unit = {}
    var onBeginEdit: () -> Unit = {}
    var onChange: (InputSurfaceElement) -> Unit = {}
    private val preview = InputSurfaceView(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 159, 255); strokeWidth = 2 * resources.displayMetrics.density }
    private val radius = 14 * resources.displayMetrics.density
    private var original: InputSurfaceElement? = null
    private var downX = 0f
    private var downY = 0f
    private var corner = 0
    private var dragging = false

    init {
        setBackgroundColor(Color.rgb(10, 11, 13))
        addView(preview, LayoutParams(-1, -1))
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        paint.style = Paint.Style.STROKE
        guideX?.let { canvas.drawLine(width * it, 0f, width * it, height.toFloat(), paint) }
        guideY?.let { canvas.drawLine(0f, height * it, width.toFloat(), height * it, paint) }
        val e = elements.firstOrNull { it.id == selected } ?: return
        val l = width * e.x; val t = height * e.y; val r = l + width * e.width; val b = t + height * e.height
        paint.style = Paint.Style.STROKE; canvas.drawRect(l, t, r, b, paint)
        paint.style = Paint.Style.FILL
        for (x in listOf(l, r)) for (y in listOf(t, b)) canvas.drawCircle(x, y, radius / 3, paint)
    }

    private fun clearGuides() { guideX = null; guideY = null; invalidate() }

    override fun onInterceptTouchEvent(event: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || width == 0 || height == 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearGuides()
                downX = event.x; downY = event.y; dragging = false; corner = 0
                val current = elements.firstOrNull { it.id == selected }
                if (current != null) {
                    val left = width * current.x; val top = height * current.y
                    val right = left + width * current.width; val bottom = top + height * current.height
                    val horizontal = if (abs(downX - left) < radius) 1 else if (abs(downX - right) < radius) 2 else 0
                    val vertical = if (abs(downY - top) < radius) 4 else if (abs(downY - bottom) < radius) 8 else 0
                    if (horizontal != 0 && vertical != 0) corner = horizontal or vertical
                }
                original = if (corner != 0) current else elements.asReversed().firstOrNull {
                    downX / width in it.x..(it.x + it.width) && downY / height in it.y..(it.y + it.height)
                }
                selected = original?.id; onSelect(selected)
            }
            MotionEvent.ACTION_MOVE -> {
                val e = original ?: return true
                if (!dragging && abs(event.x - downX) + abs(event.y - downY) > ViewConfiguration.get(context).scaledTouchSlop) {
                    dragging = true; onBeginEdit()
                }
                if (!dragging) return true
                val dx = (event.x - downX) / width; val dy = (event.y - downY) / height
                val next = if (corner == 0) e.copy(x = (e.x + dx).coerceIn(0f, 1f - e.width), y = (e.y + dy).coerceIn(0f, 1f - e.height))
                    else {
                        val left = if (corner and 1 != 0) (e.x + dx).coerceIn(0f, e.x + e.width - .03f) else e.x
                        val top = if (corner and 4 != 0) (e.y + dy).coerceIn(0f, e.y + e.height - .03f) else e.y
                        val right = if (corner and 2 != 0) (e.x + e.width + dx).coerceIn(left + .03f, 1f) else e.x + e.width
                        val bottom = if (corner and 8 != 0) (e.y + e.height + dy).coerceIn(top + .03f, 1f) else e.y + e.height
                        e.copy(x = left, y = top, width = right - left, height = bottom - top).bounded()
                    }
                val aligned = if (snap) snapSurfaceElement(next, elements.filter { it.id != e.id }, 9 * resources.displayMetrics.density / width,
                    9 * resources.displayMetrics.density / height, corner) else null
                if (aligned != null && (aligned.x != null && aligned.x != guideX || aligned.y != null && aligned.y != guideY)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                guideX = aligned?.x; guideY = aligned?.y
                onChange(aligned?.element ?: next)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { original = null; dragging = false; clearGuides() }
        }
        return true
    }
}
