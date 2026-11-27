package com.droiddeck.launcher.ui

import com.droiddeck.launcher.update.AppUpdates
import com.droiddeck.launcher.update.AppUpdates.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatesRailIconTest {
    private fun me(pr: Int = 0, ci: Boolean = true) = AppUpdates.Installed("abc", pr, "1.0", 1, ci, ciBuild = ci)

    @Test
    fun releaseBuildsFollowTheirChannel() {
        assertEquals(BuildKind.STABLE, RunningBuild.kind(me(), Channel.STABLE))
        assertEquals(BuildKind.PREVIEW, RunningBuild.kind(me(), Channel.NIGHTLY))
    }

    @Test
    fun prBuildsAreTestAndNonCiBuildsAreLocal() {
        assertEquals(BuildKind.TEST, RunningBuild.kind(me(pr = 12), Channel.TEST))
        assertEquals(BuildKind.LOCAL, RunningBuild.kind(me(ci = false), Channel.STABLE))
    }
}
