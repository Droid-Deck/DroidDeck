package com.droiddeck.launcher.hud

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path

/** artwork/droiddeck-mark.svg: the split D with its circular cut-out and the blue orb. */
internal object DroidDeckMark {
    private const val VIEW_X = -55.98f
    const val VIEW_W = 239.92f
    const val VIEW_H = 223.72f

    private val body: Path by lazy {
        val d = Path().apply {
            moveTo(-31.98f, 0f)
            lineTo(63.97f, 0f)
            cubicTo(125.81f, 0f, 175.93f, 50.08f, 175.93f, 111.86f)
            cubicTo(175.93f, 173.64f, 125.81f, 223.72f, 63.97f, 223.72f)
            lineTo(-31.98f, 223.72f)
            close()
        }
        val cut = Path().apply {
            addCircle(63.98f, 111.86f, 71.81f, Path.Direction.CW)
            addRect(56.055f, 0f, 71.885f, 45f, Path.Direction.CW)
            addRect(56.055f, 178.72f, 71.885f, 223.72f, Path.Direction.CW)
        }
        d.op(cut, Path.Op.DIFFERENCE)
        d.offset(-VIEW_X, 0f)
        d
    }

    private val scaled = Path()
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun widthFor(height: Float): Float = height * VIEW_W / VIEW_H

    /** Where the drawn mark starts and ends inside [widthFor]: the artwork has empty space on both sides. */
    fun inkLeftFor(height: Float): Float = (-31.98f - VIEW_X) * height / VIEW_H
    fun inkRightFor(height: Float): Float = (175.93f - VIEW_X) * height / VIEW_H

    fun draw(canvas: Canvas, left: Float, top: Float, height: Float, bodyColor: Int, orbColor: Int) {
        val s = height / VIEW_H
        matrix.setScale(s, s)
        matrix.postTranslate(left, top)
        body.transform(matrix, scaled)
        paint.color = bodyColor
        canvas.drawPath(scaled, paint)
        paint.color = orbColor
        canvas.drawCircle(left + (63.98f - VIEW_X) * s, top + 111.86f * s, 55.98f * s, paint)
    }
}
