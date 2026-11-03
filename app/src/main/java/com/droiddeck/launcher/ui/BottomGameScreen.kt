package com.droiddeck.launcher.ui

import android.app.Activity
import android.app.Presentation
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

class BottomGameScreen(
    private val activity: Activity,
    display: Display,
    private val callback: SurfaceHolder.Callback,
    private val onTouch: (MotionEvent) -> Boolean,
) : Presentation(activity, display) {
    val surfaceView = SurfaceView(getContext())
    val holder: SurfaceHolder get() = surfaceView.holder

    val hz: Float get() = display.mode.refreshRate

    init {
        setCancelable(false)
        setCanceledOnTouchOutside(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        @Suppress("DEPRECATION")
        window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        surfaceView.setOnTouchListener { _, event -> onTouch(event) }
        surfaceView.holder.addCallback(callback)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        activity.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        activity.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)
}

object GameScreenSwap {
    fun wanted(
        requested: Boolean,
        activityStarted: Boolean,
        sessionEnded: Boolean,
        inPip: Boolean,
        glassesOutput: Boolean,
        bottomScreenFree: Boolean,
    ): Boolean = requested && activityStarted && !sessionEnded && !inPip && !glassesOutput && bottomScreenFree
}
