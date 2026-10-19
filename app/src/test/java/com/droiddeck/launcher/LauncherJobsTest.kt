package com.droiddeck.launcher

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistryController
import com.droiddeck.launcher.frontend.GameLaunchIntent
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.GameSaves
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.ui.LaunchOrigin
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LauncherJobsTest {
    @Before fun prepare() { reset() }
    @After fun cleanup() { reset() }

    private fun reset() {
        LauncherJobs.saveBusy = null
        LauncherJobs.protonBusyId = null
        LauncherJobs.pkgStage = null
        LauncherJobs::class.java.getDeclaredField("savePicked").apply { isAccessible = true }.set(null, null)
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        LaunchOrigin.take()
        LaunchOrigin.flooding = null
    }

    private fun screen(saved: Bundle? = null): MainActivity = Robolectric.buildActivity(MainActivity::class.java).get().also {
        val savedState = ComponentActivity::class.java.declaredFields.single { field -> field.type == SavedStateRegistryController::class.java }
            .apply { isAccessible = true }.get(it) as SavedStateRegistryController
        savedState.performRestore(saved)
        invoke(it, "restoreSavePickerOwner", saved)
        (it.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.RESUMED
        state<String?>(it, "installed").value = "test runtime"
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(activity: MainActivity, name: String): MutableState<T> =
        MainActivity::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(activity) as MutableState<T>

    private fun invoke(activity: MainActivity, name: String, vararg arguments: Any?): Any? =
        MainActivity::class.java.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(activity, *arguments)

    @Test fun anotherScreenCannotReplaceAnOutstandingSavePicker() {
        val first = screen()
        val second = screen()
        val unusedGame: () -> GameSaves.Game = { error("picker result was not submitted") }
        invoke(first, "importSaves", "first game", unusedGame)
        val callback = LauncherJobs.savePicked
        assertNotNull(callback)
        assertNotNull(shadowOf(first).nextStartedActivity)

        invoke(second, "exportSaves", "second game", GameSaves.Layout.values().first(), unusedGame)
        assertSame("another screen replaced the pending import", callback, LauncherJobs.savePicked)
        assertNull(shadowOf(second).nextStartedActivity)
        invoke(second, "pickGameExport", null)
        assertSame(callback, LauncherJobs.savePicked)
        assertNull(shadowOf(second).nextStartedActivity)
    }

    @Test fun aDirectoryResultCannotConsumeAZipRequest() {
        val activity = screen()
        invoke(activity, "importSaves", "game", { error("no file was selected") })
        shadowOf(activity).nextStartedActivityForResult
        val callback = LauncherJobs.savePicked
        @Suppress("UNCHECKED_CAST")
        val picker = MainActivity::class.java.getDeclaredField("pickSaveDir").apply { isAccessible = true }
            .get(activity) as ActivityResultLauncher<Intent>
        picker.launch(Intent(activity, com.droiddeck.launcher.files.InAppFilePicker::class.java))
        val request = shadowOf(activity).nextStartedActivityForResult

        assertTrue(activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null))
        assertSame("the wrong result cleared the pending import", callback, LauncherJobs.savePicked)
    }

    @Test fun aRecreatedScreenCanCancelTheOriginalPicker() {
        val original = screen()
        invoke(original, "importSaves", "game", { error("no file was selected") })
        val request = shadowOf(original).nextStartedActivityForResult
        val saved = Bundle()
        original.activityResultRegistry.onSaveInstanceState(saved)
        val activityState = Bundle()
        invoke(original, "onSaveInstanceState", activityState)
        val callback = LauncherJobs.savePicked
        invoke(original, "onDestroy")
        assertSame("temporary destruction discarded the pending request", callback, LauncherJobs.savePicked)
        val replacement = screen(activityState)
        replacement.activityResultRegistry.onRestoreInstanceState(saved)
        assertTrue(replacement.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null))
        assertNull(LauncherJobs.savePicked)
    }

    @Test fun anotherScreensZipResultCannotConsumeTheOwnersRequest() {
        val owner = screen()
        invoke(owner, "importSaves", "game", { error("no file was selected") })
        shadowOf(owner).nextStartedActivityForResult
        val callback = LauncherJobs.savePicked
        val other = screen()
        @Suppress("UNCHECKED_CAST")
        val picker = MainActivity::class.java.getDeclaredField("pickSaveZip").apply { isAccessible = true }
            .get(other) as ActivityResultLauncher<Intent>
        picker.launch(Intent(other, com.droiddeck.launcher.files.InAppFilePicker::class.java))
        val request = shadowOf(other).nextStartedActivityForResult
        assertTrue(other.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null))
        assertSame(callback, LauncherJobs.savePicked)
    }

    @Test fun aPermanentlyFinishedOwnerReleasesItsPicker() {
        val original = screen()
        invoke(original, "importSaves", "game", { error("no file was selected") })
        assertNotNull(shadowOf(original).nextStartedActivity)
        original.finish()
        invoke(original, "onDestroy")
        assertNull("a finished screen left all future picker requests blocked", LauncherJobs.savePicked)

        val next = screen()
        invoke(next, "importSaves", "next game", { error("no file was selected") })
        assertNotNull(shadowOf(next).nextStartedActivity)
    }

    @Test fun finishingAnotherScreenDoesNotReleaseThePicker() {
        val owner = screen()
        invoke(owner, "importSaves", "game", { error("no file was selected") })
        val callback = LauncherJobs.savePicked
        val other = screen()
        other.finish()
        invoke(other, "onDestroy")
        assertSame(callback, LauncherJobs.savePicked)
    }

    @Test fun aSaveBlockedLaunchIsReportedAsNotStarted() {
        val activity = screen()
        LauncherJobs.saveBusy = "saving"
        val started = invoke(activity, "startSession", Intent(activity, SessionActivity::class.java), false)
        assertEquals(false, started)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun aSaveBlockedDeepLinkRemainsPending() {
        val activity = screen()
        activity.intent = Intent(GameLaunchIntent.ACTION).putExtra(GameLaunchIntent.EXTRA_APP_ID, "123")
        MainActivity::class.java.getDeclaredField("pendingGameLink").apply { isAccessible = true }.set(activity, "123")
        LauncherJobs.saveBusy = "saving"

        invoke(activity, "handleGameLink", listOf(Library.SteamGame(123, "game", null, "steam")))
        assertEquals("123", activity.intent.getStringExtra(GameLaunchIntent.EXTRA_APP_ID))
        assertEquals(GameLaunchIntent.ACTION, activity.intent.action)
        assertEquals("123", MainActivity::class.java.getDeclaredField("pendingGameLink").apply { isAccessible = true }.get(activity))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun anAnimatedLaunchRechecksSaveAndPackageWorkBeforeStarting() {
        for (job in listOf("save", "proton", "package")) {
            val activity = screen()
            LaunchOrigin.mark(Rect(0f, 0f, 40f, 40f), Any())
            activity.startActivity(Intent(activity, SessionActivity::class.java))
            val flood = state<Any?>(activity, "flood").value
            assertNotNull("animation was not queued", flood)
            when (job) {
                "save" -> LauncherJobs.saveBusy = "saving"
                "proton" -> LauncherJobs.protonBusyId = "installing"
                else -> LauncherJobs.pkgStage = "installing"
            }
            invoke(activity, "launchFlooded", flood)
            assertNull("animated launch bypassed $job work", shadowOf(activity).nextStartedActivity)
            assertNull(state<Any?>(activity, "flood").value)
            reset()
        }
    }
}
