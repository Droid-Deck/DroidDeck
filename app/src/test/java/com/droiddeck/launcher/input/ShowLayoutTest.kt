package com.droiddeck.launcher.input

import android.content.Context
import android.view.View
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.InputSurfaceView
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w833dp-h468dp-land-369dpi")
class ShowLayoutTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @After fun close() { context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit() }

    private fun sized(view: View, w: Int, h: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    @Test fun aGeneratedLayoutIsDrawnFromTheKitAtTheViewSize() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_XBOX360)
        val view = InputSurfaceView(context)
        sized(view, 1240, 1080)
        view.showLayout(SavedInputLayout(InputLibraries.OVERLAY, "Gamepad"))
        assertEquals(KitLayout.elements(context, PadKit.XBOX, 1240, 1080), view.shownElements)
        view.releaseInputs()
    }

    @Test fun aSavedLayoutIsDrawnAsSaved() {
        val saved = listOf(InputSurfaceElement(InputControl.A, .4f, .4f, .1f, .1f))
        val view = InputSurfaceView(context)
        sized(view, 1240, 1080)
        view.showLayout(SavedInputLayout("mine", "Mine", saved))
        assertEquals(saved, view.shownElements)
        view.showLayout(null)
        assertTrue(view.shownElements.isEmpty())
        view.releaseInputs()
    }
}
