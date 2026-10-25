package com.droiddeck.launcher.input

import android.content.ContextWrapper
import android.os.Bundle
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE,
    instrumentedPackages = ["com.droiddeck.launcher.input.FakeInputWriter"],
    shadows = [JoyConPadTest.RecordingWriter::class])
class SdlBridgeLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val dirs = mutableListOf<File>()
    private val pads = mutableListOf<PadBridge>()

    private fun pad() = PadBridge(Files.createTempDirectory("sdl-pad").toFile().also { dirs += it })
        .also { pads += it }

    private fun attach(pad: PadBridge): ContextWrapper {
        SessionPrefs.setSdl3Controllers(app, true)
        return ContextWrapper(app).also { DualSenseBridge.attach(it, pad) }
    }

    private fun frame(slot: Int, mask: Int = 1) {
        DualSenseBridge::class.java.getDeclaredMethod("onFrame", Bundle::class.java)
            .apply { isAccessible = true }.invoke(DualSenseBridge, Bundle().apply {
                putInt("slot", slot); putInt("mask", mask); putInt("lx", 16000)
            })
    }

    @After fun close() {
        val token = Any()
        DualSenseBridge.beginSession(token)
        DualSenseBridge.endSession(token)
        pads.forEach { it.stop() }
        dirs.forEach { it.deleteRecursively() }
        JoyConPadTest.RecordingWriter.pads.clear()
    }

    @Test fun replacingTheScreenReclaimsExtraPlayersAndResendsHeldInput() {
        val first = pad()
        attach(first)
        frame(0); frame(1); frame(2)
        assertTrue(JoyConPadTest.RecordingWriter.pads.getValue(1).isDown(PadState.A))
        attach(pad())
        JoyConPadTest.RecordingWriter.pads.clear()
        frame(0); frame(1); frame(2)
        for (slot in 0..2) {
            assertTrue("player $slot lost a held button", JoyConPadTest.RecordingWriter.pads.getValue(slot).isDown(PadState.A))
        }
    }

    @Test fun anOldServiceCannotDisconnectANewerSession() {
        val old = Any()
        val current = Any()
        DualSenseBridge.beginSession(old)
        attach(pad())
        DualSenseBridge.beginSession(current)
        attach(pad())
        DualSenseBridge.endSession(old)
        frame(1)
        assertTrue(JoyConPadTest.RecordingWriter.pads.getValue(1).isDown(PadState.A))
        DualSenseBridge.endSession(current)
        JoyConPadTest.RecordingWriter.pads.clear()
        frame(1)
        assertTrue(JoyConPadTest.RecordingWriter.pads.isEmpty())
    }

    @Test fun aCanceledLaunchCanDisconnectItsScreenWithoutAService() {
        val screen = attach(pad())
        DualSenseBridge.detach(screen)
        frame(1)
        assertFalse(DualSenseBridge.isBridgedSlot(1))
        assertFalse(DualSenseBridge::class.java.getDeclaredField("sessionActive")
            .apply { isAccessible = true }.getBoolean(DualSenseBridge))
    }

    @Test fun anOldScreenCannotDisconnectItsReplacement() {
        val old = attach(pad())
        attach(pad())
        DualSenseBridge.detach(old)
        DualSenseBridge.screenClosed(old)
        frame(1)
        assertTrue(JoyConPadTest.RecordingWriter.pads.getValue(1).isDown(PadState.A))
    }

    @Test fun attachingTheSamePadPreservesItsPlayerClaims() {
        val current = pad()
        attach(current)
        frame(1)
        attach(current)
        assertEquals(2, current.claimBridgeSlot())
    }

    @Test fun sessionIntegrationRequiresExplicitOptIn() {
        SessionPrefs.setSdl3Controllers(app, false)
        assertFalse(HookupBridge.wanted(app))
        assertFalse(DualSenseBridge.attach(app, pad()))
    }
}
