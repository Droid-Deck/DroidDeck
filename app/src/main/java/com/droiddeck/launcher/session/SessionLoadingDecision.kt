package com.droiddeck.launcher.session

internal object SessionLoadingDecision {
    const val TIMEOUT_MS = 10_000L

    fun shouldReturnToLauncher(startedAt: Long, now: Long, running: Boolean, phase: SessionPhase): Boolean =
        !running && phase in setOf(SessionPhase.IDLE, SessionPhase.FAILED) && now - startedAt >= TIMEOUT_MS
}
