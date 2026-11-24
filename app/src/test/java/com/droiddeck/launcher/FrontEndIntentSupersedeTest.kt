package com.droiddeck.launcher

import android.content.Intent
import com.droiddeck.launcher.core.SteamIntegration
import com.droiddeck.launcher.frontend.GameRequest
import com.droiddeck.launcher.session.LaunchCoordinator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * A replacement activity intent invalidates everything this activity has not dispatched yet: a
 * resolved but unhandled game link, and an accepted launch that is still waiting for its session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FrontEndIntentSupersedeTest {
    private lateinit var activity: FrontEndActivity
    private lateinit var coordinator: LaunchCoordinator<Intent, GameRequest>

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(FrontEndActivity::class.java).get()
        coordinator = ReflectionHelpers.getField(activity, "launchCoordinator")
    }

    @Test
    fun aReplacementIntentDropsAResolvedPendingLink() {
        val owner = Intent(activity, FrontEndActivity::class.java)
        val ticket = coordinator.beginLink(owner)
        assertTrue(coordinator.resolve(ticket, GameRequest(id = "app")))

        supersede()

        assertFalse(coordinator.hasPending())
        assertNull(coordinator.pendingLink())
    }

    @Test
    fun aReplacementIntentInvalidatesAnAcceptedLaunch() {
        val ticket = coordinator.beginLaunch()
        assertTrue(coordinator.isCurrent(ticket))

        supersede()

        assertFalse(coordinator.isCurrent(ticket))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun anActionSetupIntentSupersedesTheGameLinkItReplaced() {
        val owner = Intent(activity, FrontEndActivity::class.java)
        val ticket = coordinator.beginLink(owner)
        assertTrue(coordinator.resolve(ticket, GameRequest(id = "app")))

        deliver(Intent(activity, FrontEndActivity::class.java).setAction(SteamIntegration.ACTION_SETUP))

        assertFalse(coordinator.hasPending())
        assertFalse(coordinator.isCurrent(ticket))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun supersede() {
        ReflectionHelpers.callInstanceMethod<Void>(activity, "supersedeUncommittedLaunch")
    }

    /** onNewIntent() is protected, which is exactly how the platform delivers a replacement intent. */
    private fun deliver(intent: Intent) {
        val method = FrontEndActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
        method.isAccessible = true
        try {
            method.invoke(activity, intent)
        } finally {
            method.isAccessible = false
        }
    }
}
