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

class GlassesScreen(context: Context, display: Display, private val callback: SurfaceHolder.Callback) : Presentation(context, display) {
    val surfaceView = SurfaceView(getContext())
    val holder: SurfaceHolder get() = surfaceView.holder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            attributes = attributes.apply {
                display.supportedModes.maxByOrNull { it.refreshRate * 10_000 + it.physicalWidth }?.let { best ->
                    if (display.mode.physicalWidth == best.physicalWidth) preferredDisplayModeId = best.modeId
                }
            }
        }
        surfaceView.holder.addCallback(callback)
    }
}
