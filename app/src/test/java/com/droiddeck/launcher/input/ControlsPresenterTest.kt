package com.droiddeck.launcher.input

import com.droiddeck.launcher.input.ControlsPresenter.Place
import com.droiddeck.launcher.input.ControlsPresenter.State
import com.droiddeck.launcher.input.OnScreenControls.Hidden
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.*
import org.junit.Test

class ControlsPresenterTest {
    private val modes = listOf(SessionPrefs.OSC_AUTO, SessionPrefs.OSC_ALWAYS, SessionPrefs.OSC_NEVER)

    private fun states() = sequence {
        for (mode in modes) for (bits in 0 until 128) {
            fun bit(i: Int) = bits and (1 shl i) != 0
            yield(State(mode, desktop = bit(0), virtualPad = bit(1), covered = bit(2), controllerInUse = bit(3), hiddenByTap = bit(4),
                steamLayout = bit(5), bottomScreen = bit(6)))
        }
    }

    @Test fun everyStateGetsThePlanTheRulesDescribe() {
        for (state in states()) {
            val plan = ControlsPresenter.plan(state)
            val off = state.covered || !state.virtualPad || state.mode == SessionPrefs.OSC_NEVER || state.mode == SessionPrefs.OSC_AUTO && state.desktop
            if (off) {
                assertEquals(state.toString(), ControlsPresenter.Plan(Place.NONE), plan)
                assertFalse(plan.shown)
                continue
            }
            val place = if (state.steamLayout) Place.STEAM else if (state.bottomScreen) Place.BOTTOM else Place.MAIN
            val hidden = if (state.hiddenByTap) Hidden.BY_TAP else if (state.mode == SessionPrefs.OSC_AUTO && state.controllerInUse) Hidden.BY_CONTROLLER else null
            assertEquals(state.toString(), ControlsPresenter.Plan(place, hidden), plan)
            assertEquals(state.toString(), hidden == null, plan.shown)
        }
    }

    private val playing = State(SessionPrefs.OSC_AUTO, desktop = false, virtualPad = true, covered = false, controllerInUse = false,
        hiddenByTap = false, steamLayout = false, bottomScreen = false)

    @Test fun autoHidesForAControllerButAlwaysKeepsTheControls() {
        assertEquals(ControlsPresenter.Plan(Place.MAIN), ControlsPresenter.plan(playing))
        assertEquals(Hidden.BY_CONTROLLER, ControlsPresenter.plan(playing.copy(controllerInUse = true)).hidden)
        assertNull(ControlsPresenter.plan(playing.copy(mode = SessionPrefs.OSC_ALWAYS, controllerInUse = true)).hidden)
    }

    @Test fun aTapHideWinsOverAControllerHide() {
        assertEquals(Hidden.BY_TAP, ControlsPresenter.plan(playing.copy(controllerInUse = true, hiddenByTap = true)).hidden)
        assertEquals(Hidden.BY_TAP, ControlsPresenter.plan(playing.copy(mode = SessionPrefs.OSC_ALWAYS, hiddenByTap = true)).hidden)
    }

    @Test fun theSteamLayoutWinsOverTheBottomScreen() {
        assertEquals(Place.STEAM, ControlsPresenter.plan(playing.copy(steamLayout = true, bottomScreen = true)).place)
        assertEquals(Place.BOTTOM, ControlsPresenter.plan(playing.copy(bottomScreen = true)).place)
    }

    @Test fun theDesktopShowsControlsOnlyWhenAlwaysIsChosen() {
        assertEquals(Place.NONE, ControlsPresenter.plan(playing.copy(desktop = true)).place)
        assertEquals(Place.MAIN, ControlsPresenter.plan(playing.copy(desktop = true, mode = SessionPrefs.OSC_ALWAYS)).place)
    }

    @Test fun aCoveringPanelOrNoVirtualPadRemovesTheControls() {
        assertEquals(Place.NONE, ControlsPresenter.plan(playing.copy(covered = true)).place)
        assertEquals(Place.NONE, ControlsPresenter.plan(playing.copy(virtualPad = false, mode = SessionPrefs.OSC_ALWAYS)).place)
        assertEquals(Place.NONE, ControlsPresenter.plan(playing.copy(mode = SessionPrefs.OSC_NEVER, steamLayout = true)).place)
    }
}
