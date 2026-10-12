package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProfileFollowStateTest {
    @Test fun reopeningGamesPageResumesFollowingSteamAfterManualInspection() {
        val state = GameProfileFollowState()

        state.selectManually()
        assertFalse(state.followsSteam)

        state.resumeFollowingSteam()
        assertTrue(state.followsSteam)
    }
}
