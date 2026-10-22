package com.droiddeck.launcher

import android.content.Intent
import com.droiddeck.launcher.session.SessionService

internal object SessionLaunchGate {
    fun shouldCheckBusyState(intent: Intent, sessionRunning: Boolean): Boolean =
        !sessionRunning || (intent.action != SessionService.ACTION_RESUME && intent.extras?.isEmpty != true)
}
