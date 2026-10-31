package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.view.Choreographer

class InputMotion(private val router: () -> InputRouter?) : Choreographer.FrameCallback {
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var lastFrame = 0L
    private val start = Runnable {
        if (!scheduled) {
            scheduled = true
            lastFrame = System.nanoTime()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun request() {
        if (Looper.myLooper() == Looper.getMainLooper()) start.run() else handler.post(start)
    }

    override fun doFrame(frameTimeNanos: Long) {
        val dt = (frameTimeNanos - lastFrame).coerceAtLeast(0) / 1_000_000_000f
        lastFrame = frameTimeNanos
        if (router()?.tick(dt) == true) Choreographer.getInstance().postFrameCallback(this)
        else scheduled = false
    }

    fun stop() {
        handler.removeCallbacks(start)
        Choreographer.getInstance().removeFrameCallback(this)
        scheduled = false
    }
}
