package com.droiddeck.launcher.ui

import android.app.Presentation
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

internal val LocalInputActivity = staticCompositionLocalOf<ComponentActivity?> { null }

fun ComponentActivity.screenIsOff(): Boolean = getSystemService(android.os.PowerManager::class.java)?.isInteractive == false

@Composable
internal fun InputDisplayContent(host: ComponentActivity, display: Display, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val parent = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(host, display.displayId) {
        val presentation = object : Presentation(host, display) {
            var compose: ComposeView? = null
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    prepareInputEditor()
                    decorView.setViewTreeLifecycleOwner(host)
                    decorView.setViewTreeViewModelStoreOwner(host)
                    decorView.setViewTreeSavedStateRegistryOwner(host)
                    decorView.setViewTreeOnBackPressedDispatcherOwner(host)
                }
                // Presentation contexts cannot host TYPE_APPLICATION dialogs on Android 12+.
                compose = ComposeView(host).also {
                    it.setParentCompositionContext(parent)
                    it.setContent { CompositionLocalProvider(LocalInputActivity provides host) { currentContent() } }
                    setContentView(it)
                }
            }
            override fun dismiss() { compose?.disposeComposition(); compose = null; super.dismiss() }
        }
        presentation.setOnCancelListener { dismiss() }
        fun showPresentation() {
            if (!display.isValid) { dismiss(); return }
            try {
                presentation.show()
            } catch (_: WindowManager.InvalidDisplayException) {
                presentation.dismiss()
                dismiss()
            }
        }
        showPresentation()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !host.screenIsOff()) presentation.hide()
            if (event == Lifecycle.Event.ON_START) showPresentation()
        }
        host.lifecycle.addObserver(observer)
        onDispose { host.lifecycle.removeObserver(observer); presentation.dismiss() }
    }
}
