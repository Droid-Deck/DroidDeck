package com.droiddeck.launcher.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

internal class CompositorPreparation(val sessionId: String? = SessionState.sessionId) {
    val result = CompletableDeferred<Result<Unit>>()

    fun checkCurrent() {
        if (result.isCancelled || sessionId != SessionState.sessionId || SessionState.stopRequested ||
            SessionState.phase == SessionPhase.STOPPING) {
            throw CancellationException("Compositor preparation is no longer current")
        }
    }
}
