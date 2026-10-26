package com.droiddeck.launcher.ui

import android.hardware.display.DisplayManager
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.glasses.GlassesScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ExternalOutputHandoffTest {
    private lateinit var activity: SessionActivity
    private lateinit var phone: SurfaceView
    private lateinit var screen: ExternalOutputScreen

    @Before fun setup() {
        // Exercise the output lifecycle without starting the native Linux runtime.
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        phone = SurfaceView(activity)
        ReflectionHelpers.setField(activity, "surfaceView", phone)
        ReflectionHelpers.setField(activity, "ended", true)
        screen = ExternalOutputScreen(activity, presentationDisplay(), activity)
        ReflectionHelpers.setField(activity, "externalScreen", screen)
    }

    @Test fun selectingAMonitorDoesNotHidePhoneBeforeAttachment() {
        attach(phone.holder)
        assertFalse(active())
        assertEquals(View.VISIBLE, phone.visibility)
    }

    @Test fun attachingTheMonitorRemovesPhoneSurfaceButPreservesItsInputLayout() {
        phone.layout(0, 0, 1200, 800)
        attach(screen.holder)

        assertTrue(active())
        assertEquals(View.INVISIBLE, phone.visibility)
        assertEquals(1200, phone.width)
        assertEquals(800, phone.height)
    }

    @Test fun glassesTakePrecedenceOverTheMonitor() {
        val glasses = GlassesScreen(activity, presentationDisplay(), 60, activity)
        ReflectionHelpers.setField(activity, "glassesScreen", glasses)

        assertSame(glasses.holder, outputHolder())
        attach(screen.holder)
        assertFalse(active())
    }

    @Test fun latePhoneDestructionDoesNotResetTheMonitorOutput() {
        attach(screen.holder)
        ReflectionHelpers.setField(activity, "surfaceW", 1920)
        ReflectionHelpers.setField(activity, "surfaceH", 1080)

        activity.surfaceDestroyed(phone.holder)

        assertTrue(active())
        assertEquals(1920, ReflectionHelpers.getField<Int>(activity, "surfaceW"))
        assertEquals(1080, ReflectionHelpers.getField<Int>(activity, "surfaceH"))
    }

    @Test fun disconnectRestoresPhoneAndRejectsLateMonitorAttachment() {
        attach(screen.holder)
        restore()

        assertFalse(active())
        assertEquals(View.VISIBLE, phone.visibility)
        assertSame(phone.holder, outputHolder())
        attach(screen.holder)
        assertFalse(active())
        assertEquals(View.VISIBLE, phone.visibility)
    }

    @Test fun droppingTheMonitorWhileGlassesTakeOverLeavesTheGlassesOutputAlone() {
        val glasses = GlassesScreen(activity, presentationDisplay(), 60, activity)
        ReflectionHelpers.setField(activity, "glassesScreen", glasses)
        attach(glasses.holder)
        ReflectionHelpers.setField(activity, "surfaceW", 3840)
        ReflectionHelpers.setField(activity, "surfaceH", 1080)

        restore()

        assertEquals(View.INVISIBLE, phone.visibility)
        assertEquals(3840, ReflectionHelpers.getField<Int>(activity, "surfaceW"))
        assertSame(glasses.holder, outputHolder())
    }

    @Test fun glassesThatFailToShowLeaveTheMonitorOutputAndThePhoneHidden() {
        attach(screen.holder)
        val glasses = GlassesScreen(activity, presentationDisplay(), 60, activity)
        ReflectionHelpers.setField(activity, "glassesScreen", glasses)

        ReflectionHelpers.callInstanceMethod<Void>(activity, "restorePhoneOutput", from(GlassesScreen::class.java, glasses))

        assertEquals(View.INVISIBLE, phone.visibility)
        assertSame(screen.holder, outputHolder())
    }

    @Test fun losingTheMonitorSurfaceClearsTheActiveOutput() {
        attach(screen.holder)
        activity.surfaceDestroyed(screen.holder)
        assertFalse(active())
    }

    private fun presentationDisplay(): Display {
        val id = ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        val display = RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        return display
    }

    private fun attach(holder: SurfaceHolder) {
        ReflectionHelpers.callInstanceMethod<Void>(activity, "onOutputAttached", from(SurfaceHolder::class.java, holder))
    }

    private fun restore() {
        ReflectionHelpers.callInstanceMethod<Void>(activity, "restorePhoneFromExternal", from(ExternalOutputScreen::class.java, screen))
    }

    private fun outputHolder(): SurfaceHolder = ReflectionHelpers.callInstanceMethod(activity, "outputHolder")

    private fun active(): Boolean =
        ReflectionHelpers.getField<MutableState<Boolean>>(activity, "externalOutputActive\$delegate").value
}
