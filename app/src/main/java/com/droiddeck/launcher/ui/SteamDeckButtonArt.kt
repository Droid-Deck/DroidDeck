package com.droiddeck.launcher.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.droiddeck.launcher.input.InputButtonShape
import kotlin.math.min

internal class SteamDeckButtonArt(private val density: Float) {
    private val bounds = RectF()
    private val chevron = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val highlight = LinearGradient(0f, 0f, 0f, 1f, 0x2BFFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val transform = Matrix()

    fun surface(canvas: Canvas, width: Int, height: Int, shape: InputButtonShape, pressed: Boolean) {
        val shortSide = min(width, height)
        if (shortSide <= 0) return
        val inset = min(2f * density, shortSide * .12f)
        bounds.set(inset, inset, width - inset, height - inset)
        val side = min(bounds.width(), bounds.height())
        val radius = if (shape == InputButtonShape.CIRCLE) {
            val half = side / 2f
            bounds.set(width / 2f - half, height / 2f - half, width / 2f + half, height / 2f + half)
            half
        } else side * .28f
        fill.color = if (pressed) 0xEB1A9FFF.toInt() else 0x850E1014.toInt()
        canvas.drawRoundRect(bounds, radius, radius, fill)
        if (!pressed) {
            transform.setScale(1f, bounds.height() * .55f)
            transform.postTranslate(0f, bounds.top)
            highlight.setLocalMatrix(transform)
            fill.shader = highlight
            fill.alpha = 255
            canvas.drawRoundRect(bounds, radius, radius, fill)
            fill.shader = null
        }
        stroke.color = if (pressed) 0x8CFFFFFF.toInt() else 0x4DFFFFFF
        stroke.strokeWidth = 1.25f * density
        canvas.drawRoundRect(bounds, radius, radius, stroke)
    }

    fun arrow(canvas: Canvas, cx: Float, cy: Float, size: Float, direction: Int, color: Int) {
        stroke.color = color
        stroke.strokeWidth = size * .22f
        chevron.reset()
        chevron.moveTo(-size, size * .5f)
        chevron.lineTo(0f, -size * .5f)
        chevron.lineTo(size, size * .5f)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(direction * 90f)
        canvas.drawPath(chevron, stroke)
        canvas.restore()
    }

    companion object {
        fun ink(pressed: Boolean) = if (pressed) 0xFF03111F.toInt() else 0xFFF4F7FB.toInt()
    }
}
