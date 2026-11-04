package com.droiddeck.launcher.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Display
import android.view.SurfaceHolder
import androidx.compose.ui.graphics.toArgb
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.ui.Themes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        private val clip = Path()
        private val art = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        @Volatile private var covers: List<Bitmap> = emptyList()
        private val lock = Any()
        private val redraw = Runnable { draw() }
        private var visible = false
        private var surfaceLive = false
        private var lastFrame = 0L
        private var phase = 0f
        private val tick = object : Runnable {
            override fun run() {
                draw()
                if (visible && synchronized(lock) { surfaceLive }) handler.postDelayed(this, 1000L / framesPerSecond())
            }
        }

        private fun framesPerSecond(): Int {
            val fps = WallPrefs.fps(applicationContext)
            if (fps != WallPrefs.MATCH_SCREEN) return fps
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            return (display?.refreshRate ?: 60f).toInt().coerceIn(30, 144)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            handler.post { covers = loadCovers() }
        }

        private fun loadCovers(): List<Bitmap> {
            val d = resources.displayMetrics.density
            val targetW = (112f * d).toInt()
            val files = runCatching { Library.steamLibraryArt(applicationContext).shuffled().take(MAX_COVERS) }
                .getOrDefault(emptyList())
            return files.mapNotNull { (_, file) ->
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
                    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    })
                }.getOrNull()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            handler.post {
                this.visible = visible
                handler.removeCallbacks(tick)
                if (visible) {
                    lastFrame = SystemClock.uptimeMillis()
                    handler.post(tick)
                }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            synchronized(lock) { surfaceLive = true }
            handler.post {
                handler.removeCallbacks(tick)
                if (visible) handler.post(tick)
            }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            redrawNow()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            handler.removeCallbacks(redraw)
            handler.post(redraw)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(tick)
            handler.removeCallbacks(redraw)
            synchronized(lock) { surfaceLive = false }
        }

        override fun onDestroy() {
            synchronized(lock) { surfaceLive = false }
            handler.post {
                visible = false
                handler.removeCallbacks(tick)
                handler.removeCallbacks(redraw)
            }
            thread.quitSafely()
            covers = emptyList()
        }

        private fun redrawNow() {
            val done = CountDownLatch(1)
            handler.removeCallbacks(redraw)
            handler.post(redraw)
            handler.post { done.countDown() }
            done.await(REDRAW_WAIT_MS, TimeUnit.MILLISECONDS)
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
            val now = SystemClock.uptimeMillis()
            if (lastFrame != 0L) phase = (phase + (now - lastFrame) * WallPrefs.speed(applicationContext) / DRIFT_MS) % 1f
            lastFrame = now
            val shift = run * phase
            val px = w / 2f
            val py = h / 2f
            val left = px - wallW / 2f
            val top = py - wallH / 2f

            val deck = covers
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
                    if (deck.isNotEmpty()) {
                        val cover = deck[(c * 5 + i % perRun) % deck.size]
                        clip.rewind()
                        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
                        canvas.save()
                        canvas.clipPath(clip)
                        canvas.drawBitmap(cover, null, rect, art)
                        canvas.restore()
                    }
                    canvas.drawRoundRect(rect, radius, radius, stroke)
                }
            }
            canvas.restore()
        }
    }

    private companion object {
        const val MAX_COVERS = 48
        const val TILT = 13f
        const val DRIFT_MS = 50_000L
        const val REDRAW_WAIT_MS = 200L
    }
}
