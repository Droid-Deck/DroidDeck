package com.droiddeck.launcher.glasses

import android.hardware.display.DisplayManager
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import org.junit.Assert.*
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
class GlassesOutputHandoffTest {
    private lateinit var activity: SessionActivity
    private lateinit var phone: SurfaceView
    private lateinit var screen: GlassesScreen

    @Before fun setup() {
        // Exercise the output lifecycle without starting the native Linux runtime.
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        phone = SurfaceView(activity)
        ReflectionHelpers.setField(activity, "surfaceView", phone)
        ReflectionHelpers.setField(activity, "ended", true)
        val app = RuntimeEnvironment.getApplication()
        val id = ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        screen = GlassesScreen(activity, display, 60, activity)
        ReflectionHelpers.setField(activity, "glassesScreen", screen)
    }

    @Test fun selectingGlassesDoesNotHidePhoneOrClaimOutputBeforeAttachment() {
        assertFalse(active())
        assertEquals(View.VISIBLE, phone.visibility)
        attach(phone.holder) // A late phone callback must not complete the handoff.
        assertFalse(active())
        assertEquals(View.VISIBLE, phone.visibility)
    }

    @Test fun attachingGlassesRemovesPhoneSurfaceButPreservesItsInputLayout() {
        phone.layout(0, 0, 1200, 800)
        attach(screen.holder)

        assertTrue(active())
        assertEquals(View.INVISIBLE, phone.visibility)
        assertEquals(1200, phone.width)
        assertEquals(800, phone.height)
    }

    @Test fun latePhoneDestructionDoesNotResetTheGlassesOutput() {
        attach(screen.holder)
        ReflectionHelpers.setField(activity, "surfaceW", 3840)
        ReflectionHelpers.setField(activity, "surfaceH", 1080)

        activity.surfaceDestroyed(phone.holder)

        assertTrue(active())
        assertEquals(3840, ReflectionHelpers.getField<Int>(activity, "surfaceW"))
        assertEquals(1080, ReflectionHelpers.getField<Int>(activity, "surfaceH"))
    }

    @Test fun disconnectRestoresPhoneAndRejectsLateGlassesAttachment() {
        attach(screen.holder)
        ReflectionHelpers.callInstanceMethod<Void>(activity, "restorePhoneOutput", from(GlassesScreen::class.java, screen))

        assertFalse(active())
        assertEquals(View.VISIBLE, phone.visibility)
        attach(screen.holder)
        assertFalse(active())
        assertEquals(View.VISIBLE, phone.visibility)
    }

    @Test fun losingGlassesSurfaceClearsThePlayingHint() {
        attach(screen.holder)
        activity.surfaceDestroyed(screen.holder)
        assertFalse(active())
    }

    private fun attach(holder: SurfaceHolder) {
        ReflectionHelpers.callInstanceMethod<Void>(activity, "onOutputAttached", from(SurfaceHolder::class.java, holder))
    }

    private fun active(): Boolean =
        ReflectionHelpers.getField<MutableState<Boolean>>(activity, "glassesOutputActive\$delegate").value
}
