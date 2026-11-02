package com.droiddeck.launcher.wallpaper

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.compose.ui.graphics.toArgb
import com.droiddeck.launcher.ui.Themes
import kotlin.math.cos
import kotlin.math.sin

class SteamWallService : WallpaperService() {
    override fun onCreateEngine(): Engine = WallEngine()

    private inner class WallEngine : Engine() {
        private val thread = HandlerThread("steam-wall").also { it.start() }
        private val handler = Handler(thread.looper)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        private val lock = Any()
        private var visible = false
        private var surfaceLive = false
        private var origin = 0L
        private val tick = object : Runnable {
            override fun run() {
                draw()
                if (visible) handler.postDelayed(this, FRAME_MS)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            handler.post {
                this.visible = visible
                handler.removeCallbacks(tick)
                if (visible) {
                    if (origin == 0L) origin = SystemClock.uptimeMillis()
                    handler.post(tick)
                }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            synchronized(lock) { surfaceLive = true }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            handler.post { draw() }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            handler.post { draw() }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacksAndMessages(null)
            synchronized(lock) { surfaceLive = false }
        }

        override fun onDestroy() {
            handler.removeCallbacksAndMessages(null)
            synchronized(lock) { surfaceLive = false }
            thread.quitSafely()
        }

        private fun draw() {
            synchronized(lock) {
                if (!surfaceLive) return
                val canvas = surfaceHolder.lockCanvas() ?: return
                try {
                    render(canvas, canvas.width.toFloat(), canvas.height.toFloat())
                } finally {
                    surfaceHolder.unlockCanvasAndPost(canvas)
                }
            }
        }

        private fun render(canvas: Canvas, w: Float, h: Float) {
            val pal = Themes.current(applicationContext)
            val bg = pal.background.toArgb()
            val d = resources.displayMetrics.density
            val capW = 112f * d
            val capH = 168f * d
            val gap = 14f * d
            val radius = 10f * d
            val tilt = Math.toRadians(TILT.toDouble())
            val cs = cos(tilt).toFloat()
            val sn = sin(tilt).toFloat()
            val wallW = (w * cs + h * sn) * 1.1f
            val wallH = maxOf(h * 2.4f, (w * sn + h * cs) * 1.1f)
            val columns = ((wallW + gap) / (capW + gap)).toInt() + 1
            val perRun = ((wallH + gap) / (capH + gap)).toInt() + 1
            val run = (capH + gap) * perRun
            val elapsed = SystemClock.uptimeMillis() - origin
            val shift = run * ((elapsed % DRIFT_MS) / DRIFT_MS.toFloat())
            val px = w / 2f
            val py = h / 2f
            val left = px - wallW / 2f
            val top = py - wallH / 2f

            canvas.drawColor(bg)
            fill.color = pal.surface.toArgb()
            stroke.color = pal.line.toArgb()
            stroke.strokeWidth = d
            canvas.save()
            canvas.rotate(-TILT, px, py)
            for (c in 0 until columns) {
                val x = left + c * (capW + gap)
                val dy = if (c % 2 == 0) -shift else shift - run
                for (i in 0 until perRun * 2) {
                    val y = top + i * (capH + gap) + dy
                    val lx = x + capW / 2 - px
                    val ly = y + capH / 2 - py
                    val sx = px + lx * cs + ly * sn
                    val sy = py - lx * sn + ly * cs
                    if (sx < -capH || sx > w + capH || sy < -capH || sy > h + capH) continue
                    rect.set(x, y, x + capW, y + capH)
                    canvas.drawRoundRect(rect, radius, radius, fill)
                    canvas.drawRoundRect(rect, radius, radius, stroke)
                }
            }
            canvas.restore()

            val reach = maxOf(w, h) * 0.7f
            fill.shader = RadialGradient(
                px, py, reach,
                intArrayOf(bg, bg, (bg and 0x00FFFFFF) or 0xB3000000.toInt(), bg and 0x00FFFFFF),
                floatArrayOf(0f, 0.2f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
        }
    }

    private companion object {
        const val TILT = 13f
        const val DRIFT_MS = 50_000L
        const val FRAME_MS = 33L
    }
}
