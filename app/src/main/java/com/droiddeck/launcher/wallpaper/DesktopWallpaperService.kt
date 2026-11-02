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
            val scale = maxOf(w / DESIGN_W, h / DESIGN_H)
            canvas.save()
            canvas.translate((w - DESIGN_W * scale) / 2f, (h - DESIGN_H * scale) / 2f)
            canvas.scale(scale, scale)

            paint.shader = RadialGradient(
                960f, 540f, 1150f,
                intArrayOf(0xFF14213A.toInt(), 0xFF0A1120.toInt(), 0xFF04060B.toInt()),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(-DESIGN_W, -DESIGN_H, DESIGN_W * 2, DESIGN_H * 2, paint)

            paint.shader = RadialGradient(
                947.21f, 540f, 420f,
                intArrayOf(0x381A9FFF, 0x001A9FFF), null, Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(947.21f, 540f, 420f, paint)
            paint.shader = null

            canvas.save()
            canvas.translate(844.84f, 361.02f)
            canvas.scale(1.6f, 1.6f)
            paint.color = Color.WHITE
            canvas.drawPath(logo, paint)
            paint.color = 0xFF1A9FFF.toInt()
            canvas.drawCircle(63.98f, 111.86f, 55.98f, paint)
            canvas.restore()
            canvas.restore()
        }
    }

    private companion object {
        const val DESIGN_W = 1920f
        const val DESIGN_H = 1080f
    }
}
