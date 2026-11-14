package com.droiddeck.launcher.ui

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.droiddeck.launcher.core.AppUiPrefs

internal class DualScreenPresentation(
    private val host: ComponentActivity,
    display: Display,
) : Presentation(host, display) {
    private var content: ComposeView? = null

    init {
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        setOnDismissListener { DualScreenPanel.detach(this) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        applyImmersiveMode()
        val decor = window?.decorView ?: return
        decor.setViewTreeLifecycleOwner(host)
        decor.setViewTreeViewModelStoreOwner(host)
        decor.setViewTreeSavedStateRegistryOwner(host)
        decor.setViewTreeOnBackPressedDispatcherOwner(host)
        val view = ComposeView(context)
        content = view
        view.setContent {
            val model = DualScreenPanel.model
            val request = DualScreenPanel.typing
            val typingOn = request != null && DualScreenPanel.typingShown
            LaunchedEffect(typingOn) { acceptFocus(typingOn) }
            DroidDeckTheme(model?.theme, appScale = model?.appScale ?: AppUiPrefs.DEFAULT_SCALE) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (request != null && typingOn) TypingPanel(request) {
                        if (DualScreenPanel.typing === request) DualScreenPanel.finishTyping()
                    } else if (model != null) DualPanelContent(model)
                }
            }
        }
        setContentView(view)
    }

    private fun acceptFocus(on: Boolean) {
        val window = window ?: return
        if (on) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            content?.requestFocus()
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            host.window?.decorView?.requestFocus()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (DualScreenPanel.typingShown) DualScreenPanel.finishTyping()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (DualScreenPanel.typingShown && event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) DualScreenPanel.finishTyping()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}
