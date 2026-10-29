package com.droiddeck.launcher.xr

import android.content.Context
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.xr.XrEntryReadiness

object AndroidXrEntry {
    // Runtime capabilities must be probed inside the Full Space activity.
    fun readiness(context: Context): XrEntryReadiness = XrEntryReadiness.evaluate(
        platform = XrPlatform.current(context),
        guestReady = CompositorHost.isStarted && SessionState.running &&
            !SessionState.stopRequested && SessionState.sessionId != null &&
            SessionState.mode == SessionService.MODE_STEAM,
    )
}
