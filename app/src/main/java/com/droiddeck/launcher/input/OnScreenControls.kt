package com.droiddeck.launcher.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import com.droiddeck.launcher.ui.InputSurfaceView

class OnScreenControls(context: Context, pad: PadBridge?) : FrameLayout(context) {
    private val surface = InputSurfaceView(context, pad, inputRole = InputRole.PRIMARY)
    private var layout: SavedInputLayout? = null
    private val quickToggle = QuickToggle(context)
    var isButtonsOnly = false
        private set
    var isQuickHidden = false
        private set
    var onTouchMiss: ((MotionEvent) -> Unit)?
        get() = surface.onTouchMiss
        set(value) { surface.onTouchMiss = value }

    init {
        addView(surface, LayoutParams(-1, -1))
        val size = (QUICK_HIT_DP * 2 * resources.displayMetrics.density).toInt()
        addView(quickToggle, LayoutParams(size, size, Gravity.START or Gravity.BOTTOM))
    }

    fun updateLayout(value: SavedInputLayout?) {
        if (layout == value) return
        releaseAll()
        layout = value
        render()
    }

    fun setButtonsOnly(enabled: Boolean) {
        if (isButtonsOnly == enabled) return
        releaseAll()
        isButtonsOnly = enabled
        isQuickHidden = false
        render()
    }

    fun setQuickHidden(hidden: Boolean) {
        if (isButtonsOnly || isQuickHidden == hidden) return
        releaseAll()
        isQuickHidden = hidden
        render()
    }

    private fun render() {
        val elements = layout?.elements.orEmpty()
        surface.updateCustomLayout(when {
            isButtonsOnly -> elements.filter { it.control == InputControl.GUIDE || it.control == InputControl.QAM || it.id == "builtin:keyboard" }
            isQuickHidden -> elements.filter { it.id == "builtin:keyboard" }
            else -> elements
        })
        val style = layout?.style ?: InputSurfaceStyle()
        surface.updateStyle(style)
        quickToggle.tint = style.tint
        quickToggle.alpha = style.opacity / 100f
        quickToggle.visibility = if (isButtonsOnly || layout == null) GONE else VISIBLE
        quickToggle.invalidate()
    }

    fun takesTouchAt(x: Float, y: Float) = surface.takesTouchAt(x, y) ||
        quickToggle.visibility == VISIBLE && x >= quickToggle.left && x < quickToggle.right && y >= quickToggle.top && y < quickToggle.bottom
    fun releaseAll() = surface.releaseInputs()
    fun reload() { releaseAll(); render() }

    override fun onDetachedFromWindow() { releaseAll(); super.onDetachedFromWindow() }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
        val offset = ((QUICK_CENTER_DP - QUICK_HIT_DP) * resources.displayMetrics.density).toInt()
        val params = quickToggle.layoutParams as LayoutParams
        val left = (cutout?.safeInsetLeft ?: 0) + offset
        val bottom = (cutout?.safeInsetBottom ?: 0) + offset
        if (params.leftMargin != left || params.bottomMargin != bottom) {
            params.leftMargin = left
            params.bottomMargin = bottom
            quickToggle.layoutParams = params
        }
        return super.onApplyWindowInsets(insets)
    }

    private inner class QuickToggle(context: Context) : View(context) {
        var tint = ControllerPrefs.STEAM_BLUE
        private val density = resources.displayMetrics.density
        private var armed = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 22 * density
        }

        override fun onDraw(canvas: Canvas) {
            val x = width / 2f
            val y = height / 2f
            val radius = QUICK_RADIUS_DP * density
            fill.color = InputSurfaceView.shade(tint, 190, 0.12f)
            canvas.drawCircle(x, y, radius, fill)
            stroke.color = InputSurfaceView.light(tint, 150, 0.25f)
            canvas.drawCircle(x, y, radius, stroke)
            text.color = InputSurfaceView.light(tint, 210, 0.75f)
            canvas.drawText(if (isQuickHidden) "+" else "−", x, y + text.textSize * 0.35f, text)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> armed = true
                MotionEvent.ACTION_CANCEL -> armed = false
                MotionEvent.ACTION_UP -> {
                    if (armed && event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        setQuickHidden(!isQuickHidden)
                    }
                    armed = false
                }
            }
            return true
        }
    }

    private companion object {
        const val QUICK_CENTER_DP = 34f
        const val QUICK_HIT_DP = 26f
        const val QUICK_RADIUS_DP = 18f
    }
}
