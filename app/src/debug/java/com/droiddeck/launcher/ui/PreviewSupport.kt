package com.droiddeck.launcher.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

@Preview(name = "Handheld", widthDp = 960, heightDp = 540, apiLevel = 34)
@Preview(name = "Compact", widthDp = 480, heightDp = 360, apiLevel = 34)
@Preview(name = "Portrait", widthDp = 400, heightDp = 700, apiLevel = 34)
@Preview(name = "Large text", widthDp = 960, heightDp = 540, fontScale = 1.3f, apiLevel = 34)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class DroidDeckPreviews

/** Every preview uses the production theme and inert Android navigation/picker owners. */
@Composable
internal fun PreviewHost(theme: String = Themes.GRAPHITE, content: @Composable () -> Unit) {
    val owner = remember { PreviewOwner() }
    CompositionLocalProvider(
        LocalInspectionMode provides true,
        LocalOnBackPressedDispatcherOwner provides owner,
        LocalActivityResultRegistryOwner provides owner,
    ) {
        DroidDeckTheme(theme = theme) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
        }
    }
}

private class PreviewOwner : OnBackPressedDispatcherOwner, ActivityResultRegistryOwner, LifecycleOwner {
    override val lifecycle: Lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val onBackPressedDispatcher = OnBackPressedDispatcher()
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) = Unit
    }
}
