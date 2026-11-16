package com.droiddeck.launcher.session

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UinputDesktopComponent : SessionPart() {
    @Volatile private var server: LocalServerSocket? = null
    private val clients = HashSet<LocalSocket>()

    override fun start() {
        val s = try { LocalServerSocket(NAME) } catch (e: Exception) { Log.w(TAG, "uinput: no listener ($e)"); return }
        server = s
        Thread({
            while (server === s) {
                val client = try { s.accept() } catch (e: Exception) { break }
                if (runCatching { client.peerCredentials.uid }.getOrNull() !in setOf(android.os.Process.myUid(), 0)) {
                    runCatching { client.close() }
                    continue
                }
                synchronized(clients) { clients.add(client) }
                Thread({ serve(client) }, "uinput-device").apply { isDaemon = true; start() }
            }
        }, "uinput-desktop").apply { isDaemon = true; start() }
        Log.i(TAG, "uinput: listening on @$NAME")
    }

    override fun stop() {
        val s = server
        server = null
        runCatching { s?.close() }
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
    }

    private fun serve(client: LocalSocket) {
        try {
            read(client.inputStream, CompositorSink)
        } catch (e: Exception) {
            Log.w(TAG, "uinput: device stream ended ($e)")
        } finally {
            synchronized(clients) { clients.remove(client) }
            runCatching { client.close() }
        }
    }

    internal object CompositorSink : DesktopUinputDevice.Sink {
        override fun key(code: Int, down: Boolean) = WaylandCompositor.nativeSendKey(code, if (down) 1 else 0)
        override fun button(code: Int, down: Boolean) =
            WaylandCompositor.nativeSendSceneInput(WaylandCompositor.SCENE_BUTTON, code, if (down) 1 else 0)
        override fun motion(dx: Int, dy: Int) = WaylandCompositor.sendPointerDelta(dx, dy)
        override fun position(x: Int, y: Int) =
            WaylandCompositor.nativeSendSceneInput(WaylandCompositor.SCENE_POINTER_SCALED, x, y)
        override fun wheel(vertical120: Int, horizontal120: Int) =
            WaylandCompositor.nativeSendSceneInput(WaylandCompositor.SCENE_AXIS, vertical120, horizontal120)
    }

    companion object {
        private const val TAG = "SessionService"
        const val NAME = "droiddeck-uinput"
        const val MAGIC = 0x4655444b
        const val HEADER_BYTES = 20
        const val EVENT_BYTES = 8

        internal fun read(stream: InputStream, sink: DesktopUinputDevice.Sink) {
            val input = DataInputStream(BufferedInputStream(stream))
            val header = ByteArray(HEADER_BYTES)
            input.readFully(header)
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            if (h.int != MAGIC) return
            val xMin = h.int
            val xMax = h.int
            val yMin = h.int
            val yMax = h.int
            val device = DesktopUinputDevice(
                if (xMax > xMin) xMin..xMax else null,
                if (yMax > yMin) yMin..yMax else null,
                sink,
            )
            val event = ByteArray(EVENT_BYTES)
            val e = ByteBuffer.wrap(event).order(ByteOrder.LITTLE_ENDIAN)
            try {
                while (true) {
                    try { input.readFully(event) } catch (end: EOFException) { return }
                    device.event(e.getShort(0).toInt() and 0xFFFF, e.getShort(2).toInt() and 0xFFFF, e.getInt(4))
                }
            } finally {
                device.release()
            }
        }
    }
}
