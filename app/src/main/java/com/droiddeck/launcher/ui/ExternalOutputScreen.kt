package com.droiddeck.launcher.ui

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.droiddeck.launcher.session.ExternalOutput
import com.droiddeck.launcher.session.SessionState

class ExternalOutputScreen(context: Context, display: Display, private val callback: SurfaceHolder.Callback) : Presentation(context, display) {
    val surfaceView = SurfaceView(getContext())
    val holder: SurfaceHolder get() = surfaceView.holder

    val hz: Float get() = display.mode.refreshRate
    val requestedHz: Float get() = ExternalOutput.preferredMode(display).refreshRate

    init {
        setCancelable(false)
        setCanceledOnTouchOutside(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Asks a TV for its game mode (HDMI ALLM) where the display supports it.
        if (Build.VERSION.SDK_INT >= 30) window?.setPreferMinimalPostProcessing(true)
        applyMode()
        surfaceView.holder.addCallback(callback)
    }

    fun applyMode() {
        val w = window ?: return
        applySwapchainSize()
        val best = ExternalOutput.preferredMode(display)
        if (w.attributes.preferredDisplayModeId != best.modeId) {
            Log.i("ExternalOutputScreen", "display ${display.displayId}: prefer mode ${best.modeId} ${ExternalOutput.mode(best)}")
            w.attributes = w.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }

    private var fixedSize: Pair<Int, Int>? = null

    // SurfaceFlinger scales the buffer to the display; compositing every frame at 4K cost the GPU about 27 ms on an Adreno 750.
    private fun applySwapchainSize() {
        val size = ExternalOutput.swapchainSize(display.mode, SessionState.outputSize.second)
        if (size == fixedSize) return
        fixedSize = size
        if (size == null) surfaceView.holder.setSizeFromLayout() else surfaceView.holder.setFixedSize(size.first, size.second)
        Log.i("ExternalOutputScreen", "display ${display.displayId}: " +
            (size?.let { "drawing at ${it.first}x${it.second}, scaled by the display" } ?: "drawing at the display's size"))
    }
}
