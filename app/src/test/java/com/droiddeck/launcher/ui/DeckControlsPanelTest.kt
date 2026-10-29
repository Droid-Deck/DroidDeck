package com.droiddeck.launcher.ui

import android.view.MotionEvent
import android.view.View
import com.droiddeck.launcher.input.JoyConPadTest
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.droiddeck.launcher.input.FakeInputWriter"],
    shadows = [JoyConPadTest.RecordingWriter::class])
class DeckControlsPanelTest {
    @Test fun takesTouchAtMatchesWhatATouchDoes() {
        val directory = Files.createTempDirectory("deck-panel").toFile()
        try {
            SessionState.deckPad = false
            // No pad: the Deck layouts (trackpads, grips). A pad without Deck controls: the gamepad layout
            // (sticks, d-pad, face buttons).
            for (pad in listOf(null, PadBridge(directory))) for (portraitDock in listOf(false, true)) {
                val name = "pad=${pad != null} portraitDock=$portraitDock"
                val panel = DeckControlsPanel(RuntimeEnvironment.getApplication(), onClose = {}, onSteamMenu = {}, onQam = {},
                    pad = pad, portraitDock = portraitDock)
                assertEquals(name, pad == null, panel.deckControls)
                panel.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
                panel.layout(0, 0, 1200, 480)
                var taken = 0
                var missed = 0
                for (x in 0 until 1200 step 30) for (y in 0 until 480 step 20) {
                    val expected = panel.takesTouchAt(x.toFloat(), y.toFloat())
                    val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
                    assertEquals("$name ($x, $y)", expected, panel.dispatchTouchEvent(down))
                    down.recycle()
                    // Cancel rather than lift, so no button fires its action.
                    MotionEvent.obtain(0L, 1L, MotionEvent.ACTION_CANCEL, x.toFloat(), y.toFloat(), 0).also { panel.dispatchTouchEvent(it); it.recycle() }
                    if (expected) taken++ else missed++
                }
                assertTrue("$name: some points land on controls", taken > 0)
                assertTrue("$name: some points land between them", missed > 0)
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
