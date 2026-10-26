package com.droiddeck.launcher

import android.content.Intent
import android.os.Looper
import com.droiddeck.launcher.session.LoadingState
import com.droiddeck.launcher.session.ProtonExtras
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.ui.LaunchOrigin
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CompatLaunchTest {
    private lateinit var activity: FrontEndActivity
    private lateinit var protons: ProtonMenu

    @Before fun setUp() {
        installing(false)
        LaunchOrigin.take()
        activity = Robolectric.buildActivity(FrontEndActivity::class.java).get()
        protons = ReflectionHelpers.getField(activity, "protons")
        protons.protonBusyId = "droiddeck"
        protons.protonStage = "Downloading DroidDeck Proton"
        protons.protonPercent = 37
    }

    @After fun cleanUp() {
        cancel()
        installing(false)
        LaunchOrigin.take()
    }

    @Test fun waitingShowsRealProgressThenStartsTheOriginalRequestOnce() {
        activity.startActivity(request("steam://rungameid/42"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(37, loading().percent)
        assertTrue(loading().step.contains("37%"))
        protons.protonStage = "Verifying download"
        protons.protonPercent = -1
        advance()
        assertEquals("Verifying download", loading().step)
        assertEquals(-1, loading().percent)
        protons.protonBusyId = null
        advance()
        assertEquals("steam://rungameid/42", shadowOf(activity).nextStartedActivity.getStringExtra(SessionService.EXTRA_STEAM_URL))
        assertNull(waiting())
        advance()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun cancellingTheLaunchLeavesTheInstallRunningAndPreventsALateStart() {
        activity.startActivity(request("first"))
        cancel()
        assertEquals("droiddeck", protons.protonBusyId)
        protons.protonBusyId = null
        advance()
        assertNull(waiting())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun installationFailureStaysOnTheLoadingErrorWithoutStarting() {
        activity.startActivity(request("first"))
        protons.protonProblem = "Checksum verification failed"
        protons.protonBusyId = null
        advance()
        assertTrue(loading().ended)
        assertEquals("Checksum verification failed", loading().step)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun repeatedLaunchesDoNotReplaceThePendingGame() {
        activity.startActivity(request("first"))
        activity.startActivity(request("second"))
        protons.protonBusyId = null
        advance()
        assertEquals("first", shadowOf(activity).nextStartedActivity.getStringExtra(SessionService.EXTRA_STEAM_URL))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun anInstallOutsideTheLauncherUsesIndeterminateProgress() {
        protons.protonBusyId = null
        installing(true)
        activity.startActivity(request("first"))
        assertEquals(-1, loading().percent)
        assertNull(shadowOf(activity).nextStartedActivity)
        installing(false)
        advance()
        assertNotNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun finishingInTheBackgroundWaitsUntilTheLauncherIsVisible() {
        activity.startActivity(request("first"))
        ReflectionHelpers.setField(activity, "compatLaunchVisible", false)
        protons.protonBusyId = null
        advance()
        assertNull(shadowOf(activity).nextStartedActivity)
        ReflectionHelpers.setField(activity, "compatLaunchVisible", true)
        advance()
        assertNotNull(shadowOf(activity).nextStartedActivity)
    }

    private fun request(url: String) = Intent(activity, SessionActivity::class.java).putExtra(SessionService.EXTRA_STEAM_URL, url)
    private fun installing(on: Boolean) = ReflectionHelpers.setStaticField(ProtonExtras::class.java, "installInProgress", on)
    private fun waiting(): Any? = ReflectionHelpers.callInstanceMethod(activity, "getCompatLaunch")
    private fun loading(): LoadingState = ReflectionHelpers.getField(requireNotNull(waiting()), "loading")
    private fun cancel() { ReflectionHelpers.callInstanceMethod<Void>(activity, "cancelCompatLaunch") }
    private fun advance() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
}
