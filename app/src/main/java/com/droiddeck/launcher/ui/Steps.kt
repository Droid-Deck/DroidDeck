package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

// The front end's confirms and short lists, each stepping out of the control that asked for it
// (StepOut): a sheet's Uninstall, a Components swap, a remove. The Stores page keeps its own, in
// StoresMotion; everything else asks here and is drawn over the whole screen.

internal object Steps {
    /** The confirm or list stepping out of a control now, and whether it is open. */
    var step by mutableStateOf<StepAsk?>(null)
        private set
    var open by mutableStateOf(false)
        private set

    fun ask(a: StepAsk) {
        step?.onDismiss()
        step = a
        open = true
    }

    /** Folds the current one back into its control. */
    fun fold() { open = false }

    /** Drops it at once: the control it came from has gone. */
    fun drop() {
        step?.let { step = null; it.onDismiss() }
        open = false
    }

    internal fun closed(a: StepAsk) {
        if (step === a) step = null
        a.onDismiss()
    }
}

/** [Steps] drawn over an element at [origin] (root px) that fills the screen. */
@Composable
internal fun StepsLayer(origin: Offset) {
    val ask = Steps.step ?: return
    // Back (B, or the system's) folds what is stepped out, before it reaches the page under it.
    androidx.activity.compose.BackHandler(enabled = Steps.open) { Steps.fold() }
    key(ask) {
        StepOut(
            open = Steps.open, pill = ask.anchor.translate(-origin), side = ask.side, accent = ask.accent,
            onDismiss = { Steps.fold() }, onClosed = { Steps.closed(ask) },
            pillCorner = ask.pillCorner, maxWidth = ask.maxWidth, items = ask.items, handle = ask.handle, content = ask.content,
        )
    }
}
