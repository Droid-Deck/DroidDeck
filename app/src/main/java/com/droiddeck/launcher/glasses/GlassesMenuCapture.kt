package com.droiddeck.launcher.glasses

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Looper
import android.view.Choreographer
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import kotlin.math.min
import kotlin.math.roundToInt

class GlassesMenuCapture(private val activity: Activity, private val onSize: (Int, Int) -> Unit,
                         private val onFailure: (Throwable) -> Unit = {}) : Choreographer.FrameCallback {
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var running = false
    private var framePending = false
    private var lastFrameNs = Long.MIN_VALUE
    private var failures = 0
    private var bufferWidth = 0
    private var bufferHeight = 0
    private val basePosition = IntArray(2)
    private val windowPosition = IntArray(2)

    fun attach(texture: SurfaceTexture, surface: Surface) {
        checkMainThread()
        this.texture = texture
        this.surface = surface
        bufferWidth = 0
        bufferHeight = 0
        failures = 0
        lastFrameNs = Long.MIN_VALUE
    }

    fun start() {
        checkMainThread()
        if (running || activity.isDestroyed || activity.isFinishing) return
        running = true
        failures = 0
        lastFrameNs = Long.MIN_VALUE
        schedule()
    }

    fun stop() {
        checkMainThread()
        running = false
        if (framePending) Choreographer.getInstance().removeFrameCallback(this)
        framePending = false
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePending = false
        if (!running) return
        if (activity.isDestroyed || activity.isFinishing) {
            stop()
            return
        }
        if (lastFrameNs == Long.MIN_VALUE || frameTimeNanos - lastFrameNs >= FRAME_INTERVAL_NS) {
            lastFrameNs = frameTimeNanos
            try {
                capture()
                failures = 0
            } catch (error: Exception) {
                if (++failures >= MAX_FAILURES) {
                    stop()
                    onFailure(error)
                }
            }
        }
        if (running) schedule()
    }

    private fun schedule() {
        if (framePending) return
        framePending = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun capture() {
        val target = surface ?: return
        val input = texture ?: return
        val decor = activity.window.decorView
        if (!decor.isAttachedToWindow || !decor.isShown) return
        val size = captureSize(decor.width, decor.height) ?: return
        check(target.isValid)
        if (bufferWidth != size.first || bufferHeight != size.second) {
            input.setDefaultBufferSize(size.first, size.second)
            onSize(size.first, size.second)
            bufferWidth = size.first
            bufferHeight = size.second
        }
        val candidates = if (Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews() else emptyList()
        val roots = captureRoots(activity, candidates)
        val canvas = target.lockHardwareCanvas()
        val initialSave = canvas.save()
        try {
            canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
            val scale = min(canvas.width.toFloat() / decor.width, canvas.height.toFloat() / decor.height)
            canvas.translate((canvas.width - decor.width * scale) / 2f, (canvas.height - decor.height * scale) / 2f)
            canvas.scale(scale, scale)
            canvas.clipRect(0, 0, decor.width, decor.height)
            decor.getLocationOnScreen(basePosition)
            for (root in roots) {
                val params = root.layoutParams as? WindowManager.LayoutParams
                if (root !== decor && params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    canvas.drawColor(Color.argb((params.dimAmount.coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0))
                }
                root.getLocationOnScreen(windowPosition)
                val saved = canvas.save()
                try {
                    canvas.translate((windowPosition[0] - basePosition[0]).toFloat(), (windowPosition[1] - basePosition[1]).toFloat())
                    root.draw(canvas)
                } finally {
                    canvas.restoreToCount(saved)
                }
            }
        } finally {
            try {
                canvas.restoreToCount(initialSave)
            } finally {
                target.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }

    internal companion object {
        private const val FRAME_INTERVAL_NS = 33_333_334L
        private const val MAX_FAILURES = 3
        private const val MAX_EDGE = 1920

        internal fun captureSize(width: Int, height: Int): Pair<Int, Int>? {
            if (width <= 0 || height <= 0) return null
            val scale = min(1.0, MAX_EDGE.toDouble() / maxOf(width, height))
            return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
        }

        internal fun captureRoots(activity: Activity, candidates: List<View>): List<View> {
            val decor = activity.window.decorView
            if (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 ||
                !decor.isAttachedToWindow || !decor.isShown) return emptyList()
            if (Build.VERSION.SDK_INT < 29) return listOf(decor)
            val display = decor.display?.displayId ?: return listOf(decor)
            val token = decor.applicationWindowToken
            return listOf(decor) + candidates.filter { root ->
                if (root === decor || !root.isAttachedToWindow || !root.isShown || root.display?.displayId != display) return@filter false
                val params = root.layoutParams as? WindowManager.LayoutParams ?: return@filter false
                if (params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 ||
                    params.type !in WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW) return@filter false
                ownsContext(root.context, activity) || (token != null && root.applicationWindowToken == token)
            }.distinct()
        }

        private fun ownsContext(context: Context, activity: Activity): Boolean {
            var current = context
            repeat(16) {
                if (current === activity) return true
                val next = (current as? ContextWrapper)?.baseContext ?: return false
                if (next === current) return false
                current = next
            }
            return false
        }
    }
}
