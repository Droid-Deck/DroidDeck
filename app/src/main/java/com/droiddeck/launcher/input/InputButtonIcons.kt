package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import com.droiddeck.launcher.R

object InputButtonIcons {
    // Persisted symbol IDs must not depend on Android resource IDs or overlap Linux key codes.
    const val MENU = 1001
    const val KEYBOARD = 1002
    const val STEAM = 1003
    const val QUICK_ACCESS = 1004
    const val SWAP_SCREENS = 1005

    fun has(code: Int): Boolean = code in codes

    private val keyCodes = listOf(28, 14, 15, 57, 42, 54, 29, 97, 125, 103, 108, 105, 106)
    val codes = listOf(MENU, KEYBOARD, STEAM, QUICK_ACCESS, SWAP_SCREENS) + keyCodes

    fun label(context: Context, code: Int): String = when (code) {
        MENU -> context.getString(R.string.touch_droiddeck_menu)
        KEYBOARD -> context.getString(R.string.drawer_keyboard)
        STEAM -> context.getString(R.string.drawer_steam_menu)
        QUICK_ACCESS -> context.getString(R.string.drawer_open_qam)
        SWAP_SCREENS -> context.getString(R.string.swap_screens)
        else -> pcKeyLabel(context, code)
    }

    fun drawable(context: Context, code: Int): Drawable? = when (code) {
        MENU -> R.drawable.ic_stat_session
        KEYBOARD -> R.drawable.ic_bottom_keyboard
        STEAM -> R.drawable.ic_input_steam
        QUICK_ACCESS -> R.drawable.ic_input_quick_access
        SWAP_SCREENS -> R.drawable.ic_bottom_swap
        else -> null
    }?.let { context.getDrawable(it)?.mutate() }

    fun draw(canvas: Canvas, code: Int, cx: Float, cy: Float, box: Float, paint: Paint, drawable: Drawable? = null): Boolean {
        if (drawable != null) {
            drawable.setTint(paint.color)
            val half = box / 2f
            drawable.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            drawable.draw(canvas)
            return true
        }
        if (code !in keyCodes) return false
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
