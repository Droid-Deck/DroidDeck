package com.droiddeck.launcher.glasses

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs

class GlassesScreen(context: Context, display: Display, refreshHz: Int, private val callback: SurfaceHolder.Callback, resolution: GlassesResolution? = null) : Presentation(context, display) {
    val surfaceView = SurfaceView(getContext())
    internal val trackingRecovery = com.droiddeck.launcher.ui.TrackingRecoveryView(getContext())
    val holder: SurfaceHolder get() = surfaceView.holder
    private var wantHz = refreshHz
    private var wantResolution = resolution

    val hz: Float get() = display.mode.refreshRate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(trackingRecovery, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyMode()
        surfaceView.holder.addCallback(callback)
    }

    fun prefer(hz: Int, resolution: GlassesResolution? = null) {
        wantHz = hz
        wantResolution = resolution
        // A 2D/SBS transition can replace the active mode without changing Hz.
        applyMode()
    }

    private fun applyMode() {
        val w = window ?: return
        val current = display.mode
        val target = wantResolution
        val width = target?.width?.times(if (current.physicalWidth >= 3000) 2 else 1) ?: current.physicalWidth
        val height = target?.height ?: current.physicalHeight
        val requested = display.supportedModes.filter { it.physicalWidth == width && it.physicalHeight == height }
        val candidates = requested.ifEmpty { display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        } }
        val best = candidates.minByOrNull { abs(it.refreshRate - wantHz) } ?: return
        if (w.attributes.preferredDisplayModeId != best.modeId) {
            Log.i("GlassesScreen", "display ${display.displayId}: prefer mode ${best.modeId} " +
                "${best.physicalWidth}x${best.physicalHeight}@${best.refreshRate} (requested $wantHz Hz)")
            w.attributes = w.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }
}
