package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.droiddeck.launcher.input.*

internal class InputLayoutPreviewView(context: Context) : View(context) {
    private val renderer = InputSurfaceView(context)
    private var sourceHeight = 563
    fun update(layout: SavedInputLayout, role: InputRole) {
        sourceHeight = if (role == InputRole.PRIMARY) 563 else 870
        renderer.measure(MeasureSpec.makeMeasureSpec(1000, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sourceHeight, MeasureSpec.EXACTLY))
        renderer.layout(0, 0, 1000, sourceHeight)
        renderer.showLayout(layout.copy(style = layout.style.copy(opacity = 100)))
        renderer.measure(MeasureSpec.makeMeasureSpec(1000, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sourceHeight, MeasureSpec.EXACTLY))
        renderer.layout(0, 0, 1000, sourceHeight)
        contentDescription = layout.name
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(android.graphics.Color.rgb(10, 11, 13))
        val scale = minOf(width / 1000f, height / sourceHeight.toFloat())
        canvas.translate((width - 1000 * scale) / 2, (height - sourceHeight * scale) / 2)
        canvas.scale(scale, scale)
        renderer.draw(canvas)
        canvas.restore()
    }
}

private const val GRAY_SWATCH = 0xFF26292E.toInt()
