package com.droiddeck.launcher.session

internal object SessionVisibilityPolicy {
    fun isHidden(
        activityStarted: Boolean,
        screenOn: Boolean,
        activityOnDefaultDisplay: Boolean,
        activityDisplayOn: Boolean,
    ): Boolean = !activityStarted || if (activityOnDefaultDisplay) !screenOn else !activityDisplayOn

    fun shouldResumeOnVisibleTransition(
        previouslyHidden: Boolean?,
        currentlyHidden: Boolean,
        suspendPolicy: String,
    ): Boolean = previouslyHidden == true && !currentlyHidden &&
        suspendPolicy in setOf(SessionPrefs.SUSPEND_AUTO, SessionPrefs.SUSPEND_NATIVE)
}
