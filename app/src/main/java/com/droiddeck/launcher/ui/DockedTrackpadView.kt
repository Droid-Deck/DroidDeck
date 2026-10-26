package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.droiddeck.launcher.R

/**
 * The phone's whole screen while the session runs on an external display: a dark,
 * dimmed touch surface. It never handles touches itself; the session activity does,
 * so the normal touchpad gestures keep working underneath.
 */
class DockedTrackpadView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0x26FFFFFF
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x59FFFFFF
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
    }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 24f * density
        rect.set(inset, inset, width - inset, height - inset)
        val radius = 26f * density
        canvas.drawRoundRect(rect, radius, radius, border)
        canvas.drawText(context.getString(R.string.docked_trackpad_title), width / 2f, height / 2f - 5f * density, title)
        canvas.drawText(context.getString(R.string.docked_trackpad_body), width / 2f, height / 2f + 17f * density, body)
    }
}
