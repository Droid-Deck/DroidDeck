package com.droiddeck.launcher.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import com.droiddeck.launcher.R

@SuppressLint("ViewConstructor")
class DesktopKeyboardButton(context: Context, private val onTap: () -> Unit) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
    private val box = RectF()
    private var idleFill = 0
    private var idleStroke = 0
    private var heldStroke = 0
    private var pressed = false

    init {
        contentDescription = context.getString(R.string.second_show_keyboard)
        isClickable = true
        visibility = GONE
        layoutParams = FrameLayout.LayoutParams(dp(SIZE).toInt(), dp(SIZE).toInt(), Gravity.BOTTOM or Gravity.START)
        place(0, 0)
        applyColors()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    fun applyColors() {
        val settings = ControllerPrefs.read(context)
        val tint = settings.tint
        val alpha = settings.opacity / 100f
        fun light(a: Int, f: Float) = Color.argb(
            (a * alpha).toInt(),
            (Color.red(tint) + (255 - Color.red(tint)) * f).toInt(),
            (Color.green(tint) + (255 - Color.green(tint)) * f).toInt(),
            (Color.blue(tint) + (255 - Color.blue(tint)) * f).toInt(),
        )
        idleFill = Color.argb(190, (Color.red(tint) * 0.12f).toInt(), (Color.green(tint) * 0.12f).toInt(), (Color.blue(tint) * 0.12f).toInt())
        idleStroke = light(150, 0.25f)
        heldStroke = light(230, 0.7f)
        invalidate()
    }

    private fun place(left: Int, bottom: Int) {
        val params = layoutParams as FrameLayout.LayoutParams
        val marginStart = left + (dp(CENTER_X) - dp(SIZE) / 2f).toInt()
        val marginBottom = bottom + (dp(CENTER_Y) - dp(SIZE) / 2f).toInt()
        if (params.leftMargin == marginStart && params.bottomMargin == marginBottom) return
        params.leftMargin = marginStart
        params.bottomMargin = marginBottom
        layoutParams = params
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
        place(cutout?.safeInsetLeft ?: 0, cutout?.safeInsetBottom ?: 0)
        return super.onApplyWindowInsets(insets)
    }

    override fun onDraw(canvas: Canvas) {
        val x = width / 2f
        val y = height / 2f
        fill.color = idleFill
        canvas.drawCircle(x, y, dp(18f), fill)
        stroke.color = if (pressed) heldStroke else idleStroke
        canvas.drawCircle(x, y, dp(18f), stroke)
        box.set(x - dp(10f), y - dp(7f), x + dp(10f), y + dp(7f))
        canvas.drawRoundRect(box, dp(2f), dp(2f), stroke)
        for (row in 0..1) for (col in 0..2) {
            val kx = x + dp(-6f + col * 5f)
            val ky = y + dp(-3f + row * 4f)
            canvas.drawLine(kx, ky, kx + dp(2f), ky, stroke)
        }
        canvas.drawLine(x - dp(5f), y + dp(4f), x + dp(5f), y + dp(4f), stroke)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = true; invalidate() }
            MotionEvent.ACTION_UP -> {
                val inside = event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()
                pressed = false
                invalidate()
                if (inside) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = false; invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    private companion object {
        const val SIZE = 52f
        const val CENTER_X = 90f
        const val CENTER_Y = 34f
    }
}
