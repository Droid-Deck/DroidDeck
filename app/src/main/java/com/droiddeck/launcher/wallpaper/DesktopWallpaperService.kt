package com.droiddeck.launcher.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class DesktopWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = DesktopEngine()

    private inner class DesktopEngine : Engine() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val logo = Path().apply {
            moveTo(-31.98f, 0f)
            lineTo(63.97f, 0f)
            cubicTo(125.81f, 0f, 175.93f, 50.08f, 175.93f, 111.86f)
            cubicTo(175.93f, 173.64f, 125.81f, 223.72f, 63.97f, 223.72f)
            lineTo(-31.98f, 223.72f)
            close()
            addCircle(63.98f, 111.86f, 71.81f, Path.Direction.CCW)
            addRect(56.055f, 0f, 71.885f, 45f, Path.Direction.CCW)
            addRect(56.055f, 178.72f, 71.885f, 223.72f, Path.Direction.CCW)
            fillType = Path.FillType.EVEN_ODD
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) = draw(holder)

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = draw(holder)

        private fun draw(holder: SurfaceHolder) {
            val canvas = holder.lockCanvas() ?: return
            try {
                render(canvas, canvas.width.toFloat(), canvas.height.toFloat())
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun render(canvas: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val cy = h / 2f
            paint.shader = RadialGradient(
                cx, cy, maxOf(w, h) * 0.75f,
                intArrayOf(0xFF14213A.toInt(), 0xFF0A1120.toInt(), 0xFF04060B.toInt()),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)

            val scale = minOf(w, h) * LOGO_SPAN / LOGO_H
            val ring = LOGO_RING * scale
            val orbX = cx + (LOGO_ORB_X - LOGO_CX) * scale
            paint.shader = RadialGradient(
                cx, cy, minOf(w, h) * 0.5f,
                intArrayOf(0x331A9FFF, 0x001A9FFF), null, Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, minOf(w, h) * 0.5f, paint)
            paint.shader = null

            canvas.save()
            canvas.translate(cx - LOGO_CX * scale, cy - LOGO_CY * scale)
            canvas.scale(scale, scale)
            paint.color = Color.WHITE
            canvas.drawPath(logo, paint)
            canvas.restore()
            paint.color = 0xFF0A1120.toInt()
            canvas.drawCircle(orbX, cy, ring, paint)
            paint.color = 0xFF1A9FFF.toInt()
            canvas.drawCircle(orbX, cy, LOGO_ORB * scale, paint)
        }
    }

    private companion object {
        const val LOGO_H = 223.72f
        const val LOGO_CX = 71.975f
        const val LOGO_ORB_X = 63.98f
        const val LOGO_CY = 111.86f
        const val LOGO_RING = 71.81f
        const val LOGO_ORB = 55.98f
        const val LOGO_SPAN = 0.36f
    }
}
