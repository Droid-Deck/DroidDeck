package com.droiddeck.launcher.ui

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

class ExternalOutputScreen(context: Context, display: Display, private val callback: SurfaceHolder.Callback) : Presentation(context, display) {
    val surfaceView = SurfaceView(getContext())
    val holder: SurfaceHolder get() = surfaceView.holder

    val hz: Float get() = display.mode.refreshRate
    val requestedHz: Float get() = display.mode.refreshRate

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

    /**
     * XDesk-style display adoption: use the mode the monitor connected with and let the
     * session output follow it. Asking the system for a different mode is ignored by
     * OxygenOS/ColorOS and leaves the session and the panel disagreeing.
     */
    fun applyMode() {
        surfaceView.holder.setSizeFromLayout()
    }
}
