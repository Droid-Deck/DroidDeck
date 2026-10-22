package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset


internal object Steps {
    var step by mutableStateOf<StepAsk?>(null)
        private set
    var open by mutableStateOf(false)
        private set

    fun ask(a: StepAsk) {
        step?.onDismiss()
        step = a
        open = true
    }

    fun fold() { if (step?.canDismiss() != false) open = false }

    fun drop() {
        step?.let { step = null; it.onDismiss() }
        open = false
    }

    internal fun closed(a: StepAsk) {
        if (step === a) step = null
        a.onDismiss()
    }
}

@Composable
internal fun StepsLayer(origin: Offset) {
    val ask = Steps.step ?: return
    androidx.activity.compose.BackHandler(enabled = Steps.open) { Steps.fold() }
    key(ask) {
        StepOut(
            open = Steps.open, pill = ask.anchor.translate(-origin), side = ask.side, accent = ask.accent,
            onDismiss = { Steps.fold() }, onClosed = { Steps.closed(ask) },
            pillCorner = ask.pillCorner, maxWidth = ask.maxWidth, items = ask.items, handle = ask.handle, content = ask.content,
        )
    }
}
