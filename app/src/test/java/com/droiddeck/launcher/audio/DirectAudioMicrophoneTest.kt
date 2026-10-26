package com.droiddeck.launcher.audio

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.function.Consumer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DirectAudioMicrophoneTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun toggleOnlyChangesTheSessionCaptureControl() {
        val fifo = File(folder.root, "mic.fifo")
        val relay = DirectAudioRelayComponent(File(folder.root, "relay.sock"), fifo)
        val changes = mutableListOf<Boolean>()
        relay.onMicEnabled = Consumer { changes.add(it) }
        val control = File(folder.root, "mic-enabled")
        assertTrue(relay.setMicEnabled(false))
        assertEquals("0", control.readText())
        assertTrue(changes.isEmpty())
        log(relay, "DA-Relay: mic enabled=0")
        assertEquals(listOf(false), changes)
        assertTrue(relay.setMicEnabled(true))
        assertEquals("1", control.readText())
        log(relay, "DA-Relay: mic enabled=1")
        assertEquals(listOf(false, true), changes)
        assertFalse(fifo.exists())
    }

    @Test fun sessionWithoutCaptureCannotEnableIt() {
        val relay = DirectAudioRelayComponent(File(folder.root, "relay.sock"), null)
        assertFalse(relay.setMicEnabled(true))
        assertFalse(File(folder.root, "mic-enabled").exists())
    }

    @Test fun captureDemandDoesNotOverrideTheAcknowledgedToggle() {
        val relay = DirectAudioRelayComponent(File(folder.root, "relay.sock"), File(folder.root, "mic.fifo"))
        val changes = mutableListOf<Boolean>()
        relay.onMicEnabled = Consumer { changes.add(it) }
        log(relay, "DA-Relay: mic enabled=0")
        log(relay, "DA-Relay: mic start (game wants it, 1 consumer(s))")
        log(relay, "DA-Relay: mic stop (game)")
        assertEquals(listOf(false), changes)
        assertTrue(relay.awaitMicReleased(0))
    }

    @Test fun controlWriteFailureStillStartsPlaybackWithCaptureDisabled() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.applicationInfo
        val previous = info.nativeLibraryDir
        val nativeDir = folder.newFolder("native")
        val args = File(folder.root, "arguments")
        File(nativeDir, DirectAudioRelayComponent.BINARY).apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" > '${args.path}.new'\nmv '${args.path}.new' '${args.path}'\n")
            setExecutable(true)
        }
        val fifo = folder.newFile("mic.fifo")
        File(folder.root, "mic-enabled.new").mkdir()
        val relay = DirectAudioRelayComponent(File(folder.root, "relay.sock"), fifo)
        relay.attach(context)
        try {
            info.nativeLibraryDir = nativeDir.path
            relay.start()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!args.isFile && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("Playback helper did not start", args.isFile)
            val command = args.readLines()
            assertTrue(command.contains("--socket"))
            assertFalse(command.contains("--mic-fifo"))
            assertEquals(listOf("--mic-control", "/dev/null"), command.takeLast(2))
        } finally {
            info.nativeLibraryDir = previous
        }
    }

    private fun log(relay: DirectAudioRelayComponent, line: String) =
        ReflectionHelpers.callInstanceMethod<Unit>(relay, "trackMic", ReflectionHelpers.ClassParameter.from(String::class.java, line))
}
