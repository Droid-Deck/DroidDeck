package com.droiddeck.launcher.glasses

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs

class GlassesScreen(context: Context, display: Display, refreshHz: Int, private val callback: SurfaceHolder.Callback) : Presentation(context, display) {
    val surfaceView = SurfaceView(getContext())
    val holder: SurfaceHolder get() = surfaceView.holder
    private var wantHz = refreshHz

    val hz: Float get() = display.mode.refreshRate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyMode()
        surfaceView.holder.addCallback(callback)
    }

    fun prefer(hz: Int) {
        if (hz == wantHz) return
        wantHz = hz
        applyMode()
    }

    private fun applyMode() {
        val w = window ?: return
        val width = display.mode.physicalWidth
        val best = display.supportedModes.filter { it.physicalWidth == width }.minByOrNull { abs(it.refreshRate - wantHz) } ?: return
        if (w.attributes.preferredDisplayModeId != best.modeId) w.attributes = w.attributes.apply { preferredDisplayModeId = best.modeId }
    }
}
