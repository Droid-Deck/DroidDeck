package com.droiddeck.launcher.input

import com.droiddeck.launcher.session.SessionPrefs

/** Decides where the on-screen controls go and why they're hidden, from the session's state alone. */
object ControlsPresenter {
    enum class Place { NONE, MAIN, BOTTOM, STEAM }

    data class State(
        val mode: String,
        val desktop: Boolean,
        val virtualPad: Boolean,
        val covered: Boolean,
        val controllerInUse: Boolean,
        val hiddenByTap: Boolean,
        val steamLayout: Boolean,
        val bottomScreen: Boolean,
    )

    data class Plan(val place: Place, val hidden: OnScreenControls.Hidden? = null) {
        val shown get() = place != Place.NONE && hidden == null
    }

    fun plan(state: State): Plan = when {
        state.covered || !state.virtualPad || state.mode == SessionPrefs.OSC_NEVER -> Plan(Place.NONE)
        state.mode == SessionPrefs.OSC_AUTO && state.desktop -> Plan(Place.NONE)
        else -> Plan(
            when {
                state.bottomScreen -> Place.BOTTOM
                state.steamLayout -> Place.STEAM
                else -> Place.MAIN
            },
            when {
                state.hiddenByTap -> OnScreenControls.Hidden.BY_TAP
                state.mode == SessionPrefs.OSC_AUTO && state.controllerInUse -> OnScreenControls.Hidden.BY_CONTROLLER
                else -> null
            },
        )
    }
}
