package com.droiddeck.launcher.input

import android.os.Handler
import com.droiddeck.launcher.session.SessionState
import java.nio.file.Files
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.droiddeck.launcher.input.FakeInputWriter"],
    shadows = [JoyConPadTest.RecordingWriter::class])
class PadBridgeQamTest {
    private val directory = Files.createTempDirectory("qam-pad").toFile()
    private lateinit var bridge: PadBridge

    @Before fun start() {
        JoyConPadTest.RecordingWriter.pads.clear()
        ReflectionHelpers.setStaticField(FakeInputWriter::class.java, "padType", FakeInputWriter.PAD_TYPE_DECK)
        SessionState.deckPad = true
        bridge = PadBridge(directory)
        assertTrue(bridge.start())
    }

    @After fun stop() {
        bridge.stop()
        SessionState.deckPad = false
        directory.deleteRecursively()
    }

    @Test fun onlyNativeQamControllersAcceptAHeldBack() {
        for (type in listOf(-1, FakeInputWriter.PAD_TYPE_DECK, FakeInputWriter.PAD_TYPE_STEAM,
            FakeInputWriter.PAD_TYPE_XBOX, FakeInputWriter.PAD_TYPE_DS4, FakeInputWriter.PAD_TYPE_DUALSENSE)) {
            ReflectionHelpers.setStaticField(FakeInputWriter::class.java, "padType", type)
            val supported = type <= FakeInputWriter.PAD_TYPE_STEAM
            assertEquals(supported, bridge.canHoldQam())
            bridge.setBackQamPressed(true)
            bridge.applyExternal({}, false)
            assertEquals(supported, pad().isDown(PadState.QAM))
            bridge.setBackQamPressed(false)
        }
        SessionState.deckPad = false
        assertFalse(bridge.canHoldQam())
    }

    @Test fun qamAndOtherButtonsShareAReportUntilBackIsReleased() {
        bridge.setBackQamPressed(true)
        advanceTime()
        bridge.applyExternal({ it.press(PadState.X, true); it.press(PadState.SELECT, true) }, true)
        assertTrue(pad().isDown(PadState.QAM))
        assertTrue(pad().isDown(PadState.X))
        assertTrue(pad().isDown(PadState.SELECT))
        bridge.setBackQamPressed(false)
        assertFalse(pad().isDown(PadState.QAM))
        assertTrue(pad().isDown(PadState.X))
        assertTrue(pad().isDown(PadState.SELECT))
    }

    @Test fun touchQamAndBackHaveIndependentReleases() {
        bridge.setBackQamPressed(true)
        bridge.setSystemButtons(false, true)
        bridge.setBackQamPressed(false)
        assertTrue(pad().isDown(PadState.QAM))
        bridge.setBackQamPressed(true)
        bridge.setSystemButtons(false, false)
        assertTrue(pad().isDown(PadState.QAM))
        bridge.setBackQamPressed(false)
        assertFalse(pad().isDown(PadState.QAM))
    }

    @Test fun aTimedQamTapCannotReleaseAHeldBack() {
        bridge.triggerQam()
        bridge.setBackQamPressed(true)
        advanceTime()
        assertTrue(pad().isDown(PadState.QAM))
        bridge.setBackQamPressed(false)
        assertFalse(pad().isDown(PadState.QAM))
    }

    @Test fun releasingAllOrStoppingClearsTheHold() {
        bridge.setBackQamPressed(true)
        bridge.releaseAll()
        assertFalse(pad().isDown(PadState.QAM))
        bridge.setBackQamPressed(true)
        bridge.stop()
        bridge.start()
        bridge.applyExternal({}, false)
        assertFalse(pad().isDown(PadState.QAM))
    }

    @Test fun disablingTheBuiltinPadClearsAndRejectsTheHold() {
        bridge.setBackQamPressed(true)
        bridge.setBuiltinPad(false)
        assertFalse(bridge.canHoldQam())
        assertFalse(pad().isDown(PadState.QAM))
        bridge.setBackQamPressed(true)
        bridge.setBuiltinPad(true)
        bridge.applyExternal({}, false)
        assertFalse(pad().isDown(PadState.QAM))
    }

    private fun pad() = requireNotNull(JoyConPadTest.RecordingWriter.pads[0])

    private fun advanceTime() {
        val handler = ReflectionHelpers.getStaticField<Handler>(PadBridge::class.java, "chordHandler")
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(2))
    }
}
