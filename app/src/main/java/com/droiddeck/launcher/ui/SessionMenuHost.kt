package com.droiddeck.launcher.ui

import android.view.KeyEvent
import android.view.Window
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class SessionMenuHost(
    private val activity: ComponentActivity,
    private val window: Window,
    private val frame: FrameLayout,
    private val content: @Composable () -> Unit,
) {
    private var view: ComposeView? = null
    val shown: Boolean get() = view != null

    fun show(on: Boolean) {
        if (on == shown) return
        if (on) {
            window.decorView.apply {
                setViewTreeLifecycleOwner(activity)
                setViewTreeViewModelStoreOwner(activity)
                setViewTreeSavedStateRegistryOwner(activity)
                setViewTreeOnBackPressedDispatcherOwner(activity)
            }
            view = ComposeView(frame.context).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                defaultFocusHighlightEnabled = false
                setContent { content() }
                frame.addView(this, FrameLayout.LayoutParams(-1, -1))
                requestFocus()
            }
        } else {
            view?.let { frame.removeView(it); it.disposeComposition() }
            view = null
        }
    }

    fun focus(): Boolean = view?.requestFocus() == true

    fun dispatchKey(event: KeyEvent): Boolean {
        frame.display?.let { display ->
            KeyWindows.topOn(display.displayId)?.let { return it.rootView.dispatchKeyEvent(event) }
        }
        return view?.dispatchKeyEvent(event) == true
    }
}
