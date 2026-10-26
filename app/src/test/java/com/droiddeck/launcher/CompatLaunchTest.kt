package com.droiddeck.launcher

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import com.droiddeck.launcher.session.LoadingState
import com.droiddeck.launcher.session.ProtonExtras
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.ui.LaunchOrigin
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        ReflectionHelpers.setField(activity, "compatLaunchVisible", true)
        protons.protonBusyId = "droiddeck"
        protons.protonProblem = null
        protons.protonStage = "Downloading DroidDeck Proton"
        protons.protonPercent = 37
        setString("setPkgStage", null)
    }

    @After fun cleanUp() {
        cancel()
        protons.protonBusyId = null
        protons.protonProblem = null
        protons.protonStage = null
        protons.protonPercent = -1
        setString("setPkgStage", null)
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

    @Test fun aRestoredCompletedRequestWaitsForResume() {
        activity.startActivity(request("first"))
        val saved = save()
        cancel()
        protons.protonBusyId = null
        recreate(saved)
        advance()
        assertNull(shadowOf(activity).nextStartedActivity)
        ReflectionHelpers.setField(activity, "compatLaunchVisible", true)
        advance()
        assertEquals("first", shadowOf(activity).nextStartedActivity.getStringExtra(SessionService.EXTRA_STEAM_URL))
    }

    @Test fun overlappingPackageWorkKeepsTheOriginalLaunchUntilItFinishes() {
        activity.startActivity(request("first"))
        setString("setPkgStage", "Installing desktop")
        ReflectionHelpers.callInstanceMethod<Void>(activity, "setPkgPercent", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 64))
        protons.protonBusyId = null
        advance()
        assertNotNull(waiting())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("Installing desktop", loading().step)
        assertEquals(64, loading().percent)
        setString("setPkgStage", null)
        advance()
        assertEquals("first", shadowOf(activity).nextStartedActivity.getStringExtra(SessionService.EXTRA_STEAM_URL))
        advance()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun overlappingRuntimeWorkDoesNotDropThePendingLaunch() {
        activity.startActivity(request("first"))
        setString("setStage", "Updating runtime")
        ReflectionHelpers.callInstanceMethod<Void>(activity, "setBusy", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        protons.protonBusyId = null
        advance()
        assertNotNull(waiting())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("Updating runtime", loading().step)
        ReflectionHelpers.callInstanceMethod<Void>(activity, "setBusy", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        advance()
        assertEquals("first", shadowOf(activity).nextStartedActivity.getStringExtra(SessionService.EXTRA_STEAM_URL))
    }

    @Test fun aPackageDownloadStillBlocksTheLaunchAfterRecreationAndReportsOldWorkerProgress() {
        activity.startActivity(request("first"))
        setString("setPkgStage", "Downloading AppImage")
        ReflectionHelpers.callInstanceMethod<Void>(activity, "setPkgPercent", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 23))
        val workerActivity = activity
        val saved = save()
        cancel()
        recreate(saved)
        ReflectionHelpers.setField(activity, "compatLaunchVisible", true)
        protons.protonBusyId = null
        advance()
        assertNotNull(waiting())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("Downloading AppImage", loading().step)
        assertEquals(23, loading().percent)
        android.os.Handler(Looper.getMainLooper()).post {
            setString("setPkgStage", "Placing AppImage", workerActivity)
            ReflectionHelpers.callInstanceMethod<Void>(workerActivity, "setPkgPercent", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 90))
        }
        advance()
        assertEquals("Placing AppImage", loading().step)
        assertEquals(90, loading().percent)
        assertNull(shadowOf(activity).nextStartedActivity)
        android.os.Handler(Looper.getMainLooper()).post { setString("setPkgStage", null, workerActivity) }
        advance()
        assertEquals("first", shadowOf(activity).nextStartedActivity.getStringExtra(SessionService.EXTRA_STEAM_URL))
        advance()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun aFailedInstallAfterRecreationKeepsItsProgressAndError() = recreationFailure(false)

    @Test fun aFailedInstallBeforeRestorationStillShowsItsError() = recreationFailure(true)

    private fun recreationFailure(finishBeforeRestore: Boolean) {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        protons.protonBusyId = null
        protons.installer = { _, progress ->
            progress("Verifying download", 64)
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            "Checksum verification failed"
        }
        try {
            protons.installProton("droiddeck")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            activity.startActivity(request("first"))
            val saved = save()
            cancel()
            if (finishBeforeRestore) {
                finish.countDown()
                waitForInstall()
            }
            recreate(saved)
            if (!finishBeforeRestore) {
                advance()
                assertEquals(64, loading().percent)
                finish.countDown()
                waitForInstall()
            }
            ReflectionHelpers.setField(activity, "compatLaunchVisible", true)
            advance()
            assertEquals("Checksum verification failed", loading().step)
            assertTrue(loading().ended)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            finish.countDown()
            waitForInstall()
        }
    }

    private fun waitForInstall() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (protons.protonBusyId != null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertNull(protons.protonBusyId)
    }

    private fun save() = Bundle().also {
        ReflectionHelpers.callInstanceMethod<Void>(activity, "saveCompatLaunch", ReflectionHelpers.ClassParameter.from(Bundle::class.java, it))
    }

    private fun recreate(saved: Bundle) {
        activity = Robolectric.buildActivity(FrontEndActivity::class.java).get()
        protons = ReflectionHelpers.getField(activity, "protons")
        ReflectionHelpers.callInstanceMethod<Void>(activity, "restoreCompatLaunch", ReflectionHelpers.ClassParameter.from(Bundle::class.java, saved))
    }

    private fun setString(method: String, value: String?, target: FrontEndActivity = activity) {
        ReflectionHelpers.callInstanceMethod<Void>(target, method, ReflectionHelpers.ClassParameter.from(String::class.java, value))
    }

    private fun request(url: String) = Intent(activity, SessionActivity::class.java).putExtra(SessionService.EXTRA_STEAM_URL, url)
    private fun installing(on: Boolean) = ReflectionHelpers.setStaticField(ProtonExtras::class.java, "installInProgress", on)
    private fun waiting(): Any? = ReflectionHelpers.callInstanceMethod(activity, "getCompatLaunch")
    private fun loading(): LoadingState = ReflectionHelpers.getField(requireNotNull(waiting()), "loading")
    private fun cancel() { ReflectionHelpers.callInstanceMethod<Void>(activity, "cancelCompatLaunch") }
    private fun advance() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
}
