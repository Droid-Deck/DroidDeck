package com.droiddeck.launcher.glasses

import android.app.Activity
import android.app.Application
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import com.droiddeck.launcher.QuestXrActivity
import com.droiddeck.launcher.SessionActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class GlassesMenuControllerTest {
    private lateinit var owner: Activity
    private lateinit var ownerLifecycle: ActivityController<Activity>
    private lateinit var external: Display
    private lateinit var menus: GlassesMenuController
    private val displays = mutableListOf<Int>()
    private val outputs = mutableListOf<FakeOutput>()
    private val subscriptions = mutableListOf<Boolean>()
    private val scheduled = ArrayDeque<() -> Unit>()
    private var target: Display? = null
    private var creationError: RuntimeException? = null
    private var onCreateOutput: (FakeOutput) -> Unit = {}
    private val saved = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN,
        headTracking = true, screenWidthDm = 35, screenDistanceDm = 45)
    private lateinit var glasses: GlassesState

    @Before fun setUp() {
        ownerLifecycle = Robolectric.buildActivity(Activity::class.java).setup()
        owner = ownerLifecycle.get()
        external = addDisplay()
        target = external
        glasses = GlassesState(present = true, connected = true, controllable = true,
            supportsScreen3dof = true, displayId = external.displayId, width = 1920, height = 1080)
        menus = GlassesMenuController(GlassesMenuPose(spatialEnabled = { false }),
            display = { target }, observe = { subscriptions += it },
            create = { activity, display, failure ->
                creationError?.let { throw it }
                FakeOutput(activity, display, failure).also {
                    outputs += it
                    onCreateOutput(it)
                }
            }, schedule = { scheduled.addLast(it) })
        menus.update(glasses, saved)
    }

    @After fun tearDown() {
        menus.pause(owner)
        ownerLifecycle.pause().stop().destroy()
        displays.forEach(ShadowDisplayManager::removeDisplay)
    }

    @Test fun menusStartOnlyForSupportedControlledExternalOutputWithTrackingSelected() {
        val invalid = listOf(
            glasses.copy(connected = false) to saved,
            glasses.copy(controllable = false) to saved,
            glasses.copy(supportsScreen3dof = false) to saved,
            glasses.copy(noSignal = true) to saved,
            glasses.copy(displayId = Display.INVALID_DISPLAY) to saved,
            glasses to saved.copy(gameOnGlasses = false),
            glasses to saved.copy(headTracking = false),
            glasses to saved.copy(picture3d = GlassesSettings.PICTURE_AUTO),
        )
        for ((state, preferences) in invalid) {
            menus.update(state, preferences)
            menus.resume(owner)
            assertTrue(outputs.isEmpty())
            assertTrue(subscriptions.isEmpty())
        }
        menus.update(glasses, saved)
        assertEquals(1, outputs.size)
        assertEquals(listOf(true), subscriptions)
        assertEquals(1, outputs.single().shows)
        assertEquals(saved, outputs.single().settings)
    }

    @Test fun absentDisplayAndThePhoneDisplayNeverCreateAnOutput() {
        target = null
        menus.resume(owner)
        target = owner.windowManager.defaultDisplay
        menus.update(glasses, saved)
        assertTrue(outputs.isEmpty())
        assertTrue(subscriptions.isEmpty())
        target = external
        menus.update(glasses, saved)
        assertEquals(1, outputs.size)
    }

    @Test fun sessionContentIsExcludedUntilItsMenuIsVisibleAndStalePauseCannotCloseNewOwner() {
        menus.resume(owner)
        val first = outputs.single()
        val session = Robolectric.buildActivity(SessionActivity::class.java).get()
        menus.resume(session)
        assertEquals(1, first.closes)
        assertEquals(1, outputs.size)

        menus.setSessionMenuVisible(session, true)
        val overlay = outputs.last()
        assertSame(session, overlay.owner)
        assertEquals(2, outputs.size)
        menus.setSessionMenuVisible(session, false)
        assertEquals(1, overlay.closes)

        menus.resume(owner)
        val latest = outputs.last()
        menus.pause(session)
        menus.setSessionMenuVisible(session, true)
        assertEquals(0, latest.closes)
        assertEquals(3, outputs.size)
    }

    @Test fun pauseReleasesSubscriptionAndLateFailureCannotCloseAReplacement() {
        menus.resume(owner)
        val old = outputs.single()
        menus.pause(owner)
        menus.pause(owner)
        assertEquals(1, old.closes)
        assertEquals(listOf(true, false), subscriptions)

        menus.resume(owner)
        val current = outputs.last()
        old.fail(IllegalStateException("late GL callback"))
        assertEquals(0, current.closes)
        assertEquals(2, outputs.size)
        menus.pause(owner)
        assertEquals(1, current.closes)
        assertFalse(subscriptions.last())
    }

    @Test fun destroyedActivityCannotRecreateItsOutputAfterCleanup() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup()
        val destroyed = lifecycle.get()
        menus.resume(destroyed)
        val output = outputs.single()
        lifecycle.pause().stop().destroy()
        menus.pause(destroyed)
        menus.resume(destroyed)
        assertEquals(1, output.closes)
        assertEquals(1, outputs.size)
        assertFalse(subscriptions.last())
        menus.pause(destroyed)
    }

    @Test fun secureFinishingAndImmersiveQuestActivitiesCannotBeCaptured() {
        owner.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        menus.resume(owner)
        assertTrue(outputs.isEmpty())
        owner.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val quest = Robolectric.buildActivity(QuestXrActivity::class.java).get()
        menus.resume(quest)
        assertTrue(outputs.isEmpty())
        owner.finish()
        menus.resume(owner)
        assertTrue(outputs.isEmpty())
    }

    @Test fun settingsReconfigureTheSameOutputAndDisablingTrackingClosesIt() {
        menus.resume(owner)
        val output = outputs.single()
        val changed = saved.copy(screenWidthDm = 50, screenDistanceDm = 60, positionalTracking = true)
        menus.update(glasses.copy(sbs = true), changed)
        assertEquals(1, outputs.size)
        assertEquals(2, output.configurations)
        assertEquals(changed, output.settings)
        assertTrue(output.state!!.sbs)
        assertEquals(listOf(true), subscriptions)

        menus.update(glasses, changed.copy(headTracking = false))
        assertEquals(1, output.closes)
        assertFalse(subscriptions.last())
        menus.update(glasses, changed)
        assertEquals(2, outputs.size)
        assertTrue(subscriptions.last())
    }

    @Test fun displayRemovalAndReplacementCloseOldOutputAndRebindOnlyTheCurrentDisplay() {
        menus.resume(owner)
        val old = outputs.single()
        target = null
        menus.update(glasses.copy(displayId = Display.INVALID_DISPLAY), saved)
        assertEquals(1, old.closes)
        assertFalse(subscriptions.last())

        target = addDisplay()
        menus.update(glasses.copy(displayId = target!!.displayId), saved)
        assertEquals(2, outputs.size)
        assertSame(target, outputs.last().display)
        old.fail(IllegalStateException("removed display"))
        assertEquals(0, outputs.last().closes)
    }

    @Test fun creationFailureIsLatchedUntilPreferencesChangeWithoutSubscribing() {
        creationError = IllegalStateException("no EGL display")
        menus.resume(owner)
        assertTrue(outputs.isEmpty())
        assertTrue(subscriptions.isEmpty())
        creationError = null
        menus.update(glasses, saved)
        assertTrue(outputs.isEmpty())
        menus.update(glasses, saved.copy(screenWidthDm = 40))
        assertEquals(1, outputs.size)
        assertTrue(subscriptions.last())
    }

    @Test fun configureAndShowExceptionsReleaseOutputAndDoNotEscapeUpdates() {
        onCreateOutput = { it.showError = IllegalStateException("window removed") }
        menus.resume(owner)
        assertEquals(1, outputs.single().closes)
        assertEquals(listOf(true, false), subscriptions)

        onCreateOutput = {}
        menus.update(glasses, saved.copy(screenWidthDm = 40))
        val next = outputs.last()
        next.onConfigure = { throw IllegalStateException("surface lost") }
        menus.update(glasses, saved)
        assertEquals(1, next.closes)
        assertFalse(subscriptions.last())
        menus.update(glasses, saved)
        assertEquals(2, outputs.size)
    }

    @Test fun synchronousFactoryOrConfigureFailureCannotShowOrSubscribeAClosedOutput() {
        onCreateOutput = { it.fail(IllegalStateException("creation callback")) }
        menus.resume(owner)
        assertEquals(0, outputs.single().shows)
        assertEquals(1, outputs.single().closes)
        assertFalse(subscriptions.contains(true))

        onCreateOutput = { output -> output.onConfigure = { output.fail(IllegalStateException("configure callback")) } }
        menus.update(glasses, saved.copy(screenWidthDm = 40))
        assertEquals(2, outputs.size)
        assertEquals(0, outputs.last().shows)
        assertEquals(1, outputs.last().closes)
        assertFalse(subscriptions.contains(true))
    }

    @Test fun closeFailureStillReleasesObserverAndDoesNotBlockTheNextResume() {
        menus.resume(owner)
        outputs.single().closeError = IllegalStateException("already destroyed surface")
        menus.pause(owner)
        assertFalse(subscriptions.last())
        menus.resume(owner)
        assertEquals(2, outputs.size)
        assertEquals(1, outputs.last().shows)
    }

    @Test fun unexpectedDismissalGetsOneScheduledRetryAndStopsAfterASecondFailure() {
        menus.resume(owner)
        val old = outputs.single()
        old.fail(GlassesMenuDisplayStopped())
        assertEquals(1, old.closes)
        assertFalse(subscriptions.last())
        assertEquals(1, scheduled.size)
        assertEquals(1, outputs.size)

        scheduled.removeFirst().invoke()
        assertEquals(2, outputs.size)
        assertTrue(subscriptions.last())
        outputs.last().fail(GlassesMenuDisplayStopped())
        assertTrue(scheduled.isEmpty())
        assertFalse(subscriptions.last())
        menus.update(glasses, saved)
        assertEquals(2, outputs.size)

        menus.update(glasses, saved.copy(screenWidthDm = 40))
        assertEquals(3, outputs.size)
        outputs.last().fail(GlassesMenuDisplayStopped())
        assertEquals(1, scheduled.size)
    }

    @Test fun scheduledDismissalRecoveryCannotReopenAfterPauseOrReplaceANewerOutput() {
        menus.resume(owner)
        outputs.single().fail(GlassesMenuDisplayStopped())
        menus.pause(owner)
        scheduled.removeFirst().invoke()
        assertEquals(1, outputs.size)
        assertFalse(subscriptions.last())

        menus.resume(owner)
        outputs.last().fail(GlassesMenuDisplayStopped())
        menus.pause(owner)
        menus.resume(owner)
        val replacement = outputs.last()
        scheduled.removeFirst().invoke()
        assertEquals(3, outputs.size)
        assertEquals(0, replacement.closes)
        assertTrue(subscriptions.last())
    }

    @Test fun displayResizeAllowsRetryAfterARealRendererFailureWithoutRetryingEveryStateUpdate() {
        menus.resume(owner)
        outputs.single().fail(IllegalStateException("old surface size"))
        assertTrue(scheduled.isEmpty())
        menus.update(glasses.copy(tracking = true), saved)
        assertEquals(1, outputs.size)
        menus.update(glasses.copy(width = 3840, sbs = true), saved)
        assertEquals(2, outputs.size)
        assertEquals(3840, outputs.last().state!!.width)
        assertTrue(subscriptions.last())
    }

    private fun addDisplay(): Display {
        val id = ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        displays += id
        return RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java).getDisplay(id)
    }

    private class FakeOutput(val owner: Activity, val display: Display, val fail: (Throwable) -> Unit) : GlassesMenuController.Output {
        var configurations = 0
        var shows = 0
        var closes = 0
        var state: GlassesState? = null
        var settings: GlassesSettings? = null
        var onConfigure: () -> Unit = {}
        var showError: RuntimeException? = null
        var closeError: RuntimeException? = null
        override fun configure(state: GlassesState, settings: GlassesSettings) {
            configurations++
            this.state = state
            this.settings = settings
            onConfigure()
        }
        override fun show() { shows++; showError?.let { throw it } }
        override fun close() { closes++; closeError?.let { throw it } }
    }
}
