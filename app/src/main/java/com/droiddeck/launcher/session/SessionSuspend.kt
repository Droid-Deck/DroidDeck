package com.droiddeck.launcher.session

/**
 * When a session should pause, kept apart from the service so the rule is one thing to read and
 * one thing to test.
 *
 * The bug this exists for: on Samsung DeX the session's picture is on the monitor while the
 * phone's own panel is off and, when the user switches away, Android reports the activity hidden.
 * The pause rule read the panel - "the screen is off" - as nobody watching, so a Manual session
 * (the default) paused the moment DeX turned the phone's screen off and could not be brought back:
 * the Resume was undone by the next evaluation, because the latch was taken from the state rather
 * than from the change into it.
 *
 * So two things: a display of the session's own is not "away" (the panel of the phone says nothing
 * about a session being watched on a monitor), and Manual latches on arriving away only, which is
 * what makes a Resume final.
 */
internal object SessionSuspend {
    /**
     * The session has left the screen: this app is not the one on show, or the panel it draws on is
     * off.
     *
     * [externalDisplay] is whether the session's picture is on a display of its own - a monitor, a
     * TV, or Samsung DeX on one, which is where the activity itself runs in DeX. The phone's own
     * panel turning off then says nothing about the session, so it does not count as away unless
     * the user asks for that too ([pauseOnSecondaryDisplay]).
     */
    fun away(
        activityVisible: Boolean,
        screenOn: Boolean,
        externalDisplay: Boolean,
        pauseOnSecondaryDisplay: Boolean,
    ): Boolean {
        if (activityVisible && screenOn) return false
        if (!activityVisible) return true
        return !externalDisplay || pauseOnSecondaryDisplay
    }

    /** Whether Manual latches its pause now: on arriving away, never on staying there. */
    fun manualPause(policy: String, away: Boolean, wasAway: Boolean): Boolean =
        policy == SessionPrefs.SUSPEND_MANUAL && away && !wasAway

    fun shouldSuspend(policy: String, away: Boolean, manualPauseRequested: Boolean): Boolean = when (policy) {
        SessionPrefs.SUSPEND_AUTO -> away
        SessionPrefs.SUSPEND_MANUAL -> manualPauseRequested
        else -> false
    }
}
