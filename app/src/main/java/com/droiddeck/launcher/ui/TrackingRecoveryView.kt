package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.droiddeck.launcher.R
import com.droiddeck.launcher.glasses.TrackingRecovery
import com.droiddeck.launcher.session.SessionPrefs

internal class TrackingRecoveryView(context: Context) : View(context) {
    private var recovery: TrackingRecovery? = null
    private var stereo = false
    private var palette = Themes.current(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var blocks = emptyList<StaticLayout>()

    init {
        visibility = GONE
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun update(state: TrackingRecovery?, stereo: Boolean, palette: Palette = Themes.current(context)) {
        if (recovery == state && this.stereo == stereo && this.palette == palette) return
        recovery = state
        this.stereo = stereo
        this.palette = palette
        visibility = if (state == null) GONE else VISIBLE
        blocks = state?.let {
            val (title, body, help) = when (it) {
                TrackingRecovery.DISCONNECTED -> Triple(R.string.tracking_recovery_disconnected, R.string.tracking_recovery_connect, R.string.tracking_recovery_usb_help)
                TrackingRecovery.LOST -> Triple(R.string.tracking_recovery_lost, R.string.tracking_recovery_look, R.string.tracking_recovery_restart_help)
                TrackingRecovery.RECENTER -> Triple(R.string.tracking_recovery_recenter, R.string.tracking_recovery_recenter_steps, R.string.tracking_recovery_recenter_help)
                TrackingRecovery.STABILIZING -> Triple(R.string.tracking_recovery_stabilizing, R.string.tracking_recovery_hold, R.string.tracking_recovery_automatic)
            }
            val text = listOf(context.getString(R.string.glasses_head_tracking), context.getString(title), context.getString(body), context.getString(help))
            contentDescription = text.joinToString(". ")
            text.mapIndexed { index, value ->
                val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = floatArrayOf(16f, 38f, 24f, 19f)[index]
                    color = (when (index) { 0 -> palette.primary; 3 -> palette.onSurfaceVariant; else -> palette.onBackground }).toArgb()
                    typeface = if (index < 2) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
                }
                StaticLayout.Builder.obtain(value, 0, value.length, ink, 660)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(4f, 1f).build()
            }
        } ?: emptyList()
        if (state == null) contentDescription = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (recovery == null) return
        canvas.drawColor(palette.background.toArgb())
        val eyes = if (stereo) 2 else 1
        val eyeWidth = width.toFloat() / eyes
        val gaps = floatArrayOf(14f, 24f, 32f, 0f)
        val cardHeight = blocks.sumOf { it.height } + gaps.sum() + 80f
        val scale = minOf(eyeWidth / 860f, height / (cardHeight + 120f))
        repeat(eyes) { eye ->
            val saved = canvas.save()
            canvas.clipRect(eye * eyeWidth, 0f, (eye + 1) * eyeWidth, height.toFloat())
            canvas.translate(eye * eyeWidth + (eyeWidth - 740f * scale) / 2f, (height - cardHeight * scale) / 2f)
            canvas.scale(scale, scale)
            paint.style = Paint.Style.FILL; paint.color = palette.surface.toArgb()
            canvas.drawRoundRect(0f, 0f, 740f, cardHeight, 24f, 24f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f; paint.color = palette.line.toArgb()
            canvas.drawRoundRect(0f, 0f, 740f, cardHeight, 24f, 24f, paint)
            canvas.translate(40f, 40f)
            blocks.forEachIndexed { index, block ->
                block.draw(canvas)
                canvas.translate(0f, block.height + gaps[index])
            }
            canvas.restoreToCount(saved)
        }
    }
}
