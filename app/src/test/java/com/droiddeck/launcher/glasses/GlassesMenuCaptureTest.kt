package com.droiddeck.launcher.glasses

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.Presentation
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.view.Choreographer
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GlassesMenuCaptureTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private val dialogs = mutableListOf<Dialog>()

    @Before fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        activity = controller.get()
        activity.window.decorView.layout(0, 0, 1920, 1080)
    }

    @After fun tearDown() {
        dialogs.forEach { it.dismiss() }
        controller.pause().stop().destroy()
    }

    @Test fun onlyTheActivityAndItsOwnDialogAreIncludedOnce() {
        val own = dialog(activity)
        val otherController = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val other = otherController.get()
        val otherDialog = dialog(other)
        try {
            val decor = activity.window.decorView
            val roots = GlassesMenuCapture.captureRoots(activity,
                listOf(other.window.decorView, own, otherDialog, decor, own))
            assertEquals(listOf(decor, own), roots)
        } finally {
            dialogs.removeAll { if (it.ownerActivity === other) { it.dismiss(); true } else false }
            otherController.pause().stop().destroy()
        }
    }

    @Test fun secureActivityProducesNoRootsAndSecureDialogsAreExcluded() {
        val secure = dialog(activity)
        val params = secure.layoutParams as WindowManager.LayoutParams
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_SECURE
        assertEquals(listOf(activity.window.decorView), GlassesMenuCapture.captureRoots(activity, listOf(secure)))

        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        assertTrue(GlassesMenuCapture.captureRoots(activity, listOf(secure)).isEmpty())
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        assertEquals(listOf(activity.window.decorView), GlassesMenuCapture.captureRoots(activity, listOf(secure)))
    }

    @Test fun systemWindowsAndPresentationsOnOtherDisplaysAreExcluded() {
        val own = dialog(activity)
        (own.layoutParams as WindowManager.LayoutParams).type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val displayId = ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        val presentation = Presentation(activity, display).apply {
            setContentView(FrameLayout(context))
            show()
        }.also(dialogs::add)
        try {
            assertEquals(listOf(activity.window.decorView), GlassesMenuCapture.captureRoots(activity,
                listOf(own, presentation.window!!.decorView)))
        } finally {
            presentation.dismiss()
            dialogs.remove(presentation)
            ShadowDisplayManager.removeDisplay(displayId)
        }
    }

    @Test
    @Config(sdk = [26])
    fun olderAndroidCapturesOnlyTheActivityDecor() {
        val own = dialog(activity)
        assertEquals(listOf(activity.window.decorView), GlassesMenuCapture.captureRoots(activity, listOf(own)))
    }

    @Test fun captureSizesPreserveOrientationAndNeverUpscale() {
        assertEquals(1920 to 1080, GlassesMenuCapture.captureSize(3840, 2160))
        assertEquals(1080 to 1920, GlassesMenuCapture.captureSize(2160, 3840))
        assertEquals(1200 to 1800, GlassesMenuCapture.captureSize(1200, 1800))
        val fold = GlassesMenuCapture.captureSize(2152, 1812)!!
        assertEquals(1920, fold.first)
        assertEquals(2152.0 / 1812.0, fold.first.toDouble() / fold.second, 0.001)
        assertNull(GlassesMenuCapture.captureSize(0, 1000))
        assertNull(GlassesMenuCapture.captureSize(1000, -1))
    }

    @Test fun repeatedSurfaceFailureStopsOnceAndDoesNotRetryAfterStop() {
        var sizes = 0
        var failures = 0
        val capture = GlassesMenuCapture(activity, { _, _ -> sizes++ }, { failures++ })
        val input = SurfaceTexture(0)
        val target = Surface(input)
        target.release()
        try {
            capture.attach(input, target)
            capture.start()
            for (time in listOf(1L, 33_333_335L, 66_666_669L, 100_000_003L)) {
                Choreographer.getInstance().removeFrameCallback(capture)
                capture.doFrame(time)
            }
            assertEquals(1, failures)
            assertEquals(0, sizes)
            assertFalse(pending(capture))
        } finally {
            capture.stop()
            input.release()
        }
    }

    @Test fun stoppingRejectsAnAlreadyDispatchedFrameWithoutRescheduling() {
        val capture = GlassesMenuCapture(activity, { _, _ -> fail("Stopped capture changed its size") })
        capture.start()
        capture.stop()
        capture.doFrame(1L)
        assertFalse(pending(capture))
    }

    private fun dialog(owner: Activity): View = Dialog(owner).apply {
        setOwnerActivity(owner)
        setContentView(FrameLayout(context))
        show()
    }.also(dialogs::add).window!!.decorView

    private fun pending(capture: GlassesMenuCapture): Boolean =
        GlassesMenuCapture::class.java.getDeclaredField("framePending").apply { isAccessible = true }.getBoolean(capture)
}
