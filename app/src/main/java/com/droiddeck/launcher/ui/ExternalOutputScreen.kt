package com.droiddeck.launcher.ui

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
import com.droiddeck.launcher.session.ExternalOutput

class ExternalOutputScreen(context: Context, display: Display, private val callback: SurfaceHolder.Callback) : Presentation(context, display) {
    val surfaceView = SurfaceView(getContext())
    val holder: SurfaceHolder get() = surfaceView.holder

    val hz: Float get() = ExternalOutput.preferredMode(display).refreshRate

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
        applyMode()
        surfaceView.holder.addCallback(callback)
    }

    fun applyMode() {
        val w = window ?: return
        val best = ExternalOutput.preferredMode(display)
        if (w.attributes.preferredDisplayModeId != best.modeId) {
            Log.i("ExternalOutputScreen", "display ${display.displayId}: prefer mode ${best.modeId} ${ExternalOutput.mode(best)}")
            w.attributes = w.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }
}
