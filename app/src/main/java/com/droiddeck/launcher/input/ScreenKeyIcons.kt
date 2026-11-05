package com.droiddeck.launcher.input

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

object ScreenKeyIcons {
    fun has(code: Int): Boolean = code in SET

    private val SET = setOf(28, 14, 15, 57, 42, 54, 29, 97, 125, 103, 108, 105, 106)

    fun draw(canvas: Canvas, code: Int, cx: Float, cy: Float, box: Float, paint: Paint): Boolean {
        if (!has(code)) return false
        val h = box / 2f
        val stroke = box * 0.085f
        val path = Path()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (code) {
            28 -> {
                path.moveTo(cx - 0.34f * h, cy - 0.22f * h)
                path.lineTo(cx + 0.30f * h, cy - 0.22f * h)
                path.lineTo(cx + 0.30f * h, cy + 0.24f * h)
                path.lineTo(cx - 0.04f * h, cy + 0.24f * h)
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(cx + 0.12f * h, cy + 0.06f * h)
                path.lineTo(cx - 0.04f * h, cy + 0.24f * h)
                path.lineTo(cx + 0.12f * h, cy + 0.42f * h)
                canvas.drawPath(path, paint)
            }

            14 -> {
                path.moveTo(cx - 0.72f * h, cy)
                path.lineTo(cx - 0.10f * h, cy - 0.50f * h)
                path.lineTo(cx + 0.74f * h, cy - 0.50f * h)
                path.lineTo(cx + 0.74f * h, cy + 0.50f * h)
                path.lineTo(cx - 0.10f * h, cy + 0.50f * h)
                path.close()
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(cx + 0.10f * h, cy - 0.20f * h)
                path.lineTo(cx + 0.44f * h, cy + 0.20f * h)
                path.moveTo(cx + 0.44f * h, cy - 0.20f * h)
                path.lineTo(cx + 0.10f * h, cy + 0.20f * h)
                canvas.drawPath(path, paint)
            }

            15 -> {
                path.moveTo(cx - 0.72f * h, cy)
                path.lineTo(cx + 0.44f * h, cy)
                path.moveTo(cx + 0.14f * h, cy - 0.30f * h)
                path.lineTo(cx + 0.46f * h, cy)
                path.lineTo(cx + 0.14f * h, cy + 0.30f * h)
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(cx + 0.70f * h, cy - 0.48f * h)
                path.lineTo(cx + 0.70f * h, cy + 0.48f * h)
                canvas.drawPath(path, paint)
            }

            57 -> {
                path.moveTo(cx - 0.64f * h, cy - 0.22f * h)
                path.lineTo(cx - 0.64f * h, cy + 0.20f * h)
                path.lineTo(cx + 0.64f * h, cy + 0.20f * h)
                path.lineTo(cx + 0.64f * h, cy - 0.22f * h)
                canvas.drawPath(path, paint)
            }

            42, 54 -> {
                paint.style = Paint.Style.FILL
                path.moveTo(cx, cy - 0.55f * h)
                path.lineTo(cx + 0.62f * h, cy + 0.02f * h)
                path.lineTo(cx + 0.26f * h, cy + 0.02f * h)
                path.lineTo(cx + 0.26f * h, cy + 0.32f * h)
                path.lineTo(cx - 0.26f * h, cy + 0.32f * h)
                path.lineTo(cx - 0.26f * h, cy + 0.02f * h)
                path.lineTo(cx - 0.62f * h, cy + 0.02f * h)
                path.close()
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(cx - 0.26f * h, cy + 0.44f * h)
                path.lineTo(cx + 0.26f * h, cy + 0.44f * h)
                path.lineTo(cx + 0.26f * h, cy + 0.58f * h)
                path.lineTo(cx - 0.26f * h, cy + 0.58f * h)
                path.close()
                canvas.drawPath(path, paint)
            }

            29, 97 -> {
                path.moveTo(cx - 0.58f * h, cy + 0.26f * h)
                path.lineTo(cx, cy - 0.28f * h)
                path.lineTo(cx + 0.58f * h, cy + 0.26f * h)
                canvas.drawPath(path, paint)
            }

            125 -> {
                paint.style = Paint.Style.FILL
                val gap = 0.07f * h
                val side = 0.34f * h
                for (dxi in -1..1 step 2) for (dyi in -1..1 step 2) {
                    val left = cx + dxi * (gap + side / 2f) - side / 2f
                    val top = cy + dyi * (gap + side / 2f) - side / 2f
                    path.addRect(left, top, left + side, top + side, Path.Direction.CW)
                }
                canvas.drawPath(path, paint)
            }

            103, 108, 105, 106 -> {
                paint.style = Paint.Style.FILL
                val r = 0.52f * h
                when (code) {
                    103 -> {
                        path.moveTo(cx, cy - r)
                        path.lineTo(cx + r * 0.80f, cy + r * 0.55f)
                        path.lineTo(cx - r * 0.80f, cy + r * 0.55f)
                    }

                    108 -> {
                        path.moveTo(cx, cy + r)
                        path.lineTo(cx + r * 0.80f, cy - r * 0.55f)
                        path.lineTo(cx - r * 0.80f, cy - r * 0.55f)
                    }

                    105 -> {
                        path.moveTo(cx - r, cy)
                        path.lineTo(cx + r * 0.55f, cy - r * 0.80f)
                        path.lineTo(cx + r * 0.55f, cy + r * 0.80f)
                    }

                    else -> {
                        path.moveTo(cx + r, cy)
                        path.lineTo(cx - r * 0.55f, cy - r * 0.80f)
                        path.lineTo(cx - r * 0.55f, cy + r * 0.80f)
                    }
                }
                path.close()
                canvas.drawPath(path, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
        return true
    }
}
