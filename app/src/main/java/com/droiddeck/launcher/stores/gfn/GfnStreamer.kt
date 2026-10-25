package com.droiddeck.launcher.stores.gfn

import android.view.Surface
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * OpenNOW's native streamer engine (external/opennow, MIT, by Zortos) inside libdroiddeckgfn.so,
 * with DroidDeck's Android media backend: MediaCodec onto [setSurface]'s Surface, AAudio sound.
 * Commands are the engine's JSON protocol (`hello`, `start`, `stop`, ...); answers and events
 * arrive on [onMessage] from a polling thread.
 */
class GfnStreamer(private val onMessage: (JSONObject) -> Unit) {
    private var handle = nativeOpen()
    private val ids = AtomicLong(1)
    @Volatile private var open = true
    private val poller = Thread({
        while (open) {
            val line = nativePoll(handle, 250) ?: continue
            runCatching { JSONObject(line) }.getOrNull()?.let { message -> runCatching { onMessage(message) } }
        }
    }, "gfn-streamer-poll").apply { isDaemon = true; start() }

    /** Sends one command; returns its id (responses carry it), or "" if the engine is closed. */
    fun send(type: String, fields: JSONObject = JSONObject()): String {
        if (!open) return ""
        val id = "dd-" + ids.getAndIncrement()
        fields.put("id", id).put("type", type)
        return if (nativeCommand(handle, fields.toString())) id else ""
    }

    fun gamepad(slot: Int, connected: Int, buttons: Int, leftTrigger: Int, rightTrigger: Int,
                leftX: Int, leftY: Int, rightX: Int, rightY: Int) {
        if (open) nativeGamepad(handle, slot, connected, buttons, leftTrigger, rightTrigger, leftX, leftY, rightX, rightY)
    }

    fun key(virtualKey: Int, modifiers: Int, pressed: Boolean) {
        if (open) nativeKey(handle, virtualKey, modifiers, pressed)
    }

    fun close() {
        if (!open) return
        open = false
        poller.join(1_000)
        nativeClose(handle)
        handle = 0
    }

    companion object {
        init { System.loadLibrary("droiddeckgfn") }

        @JvmStatic fun setSurface(surface: Surface?) = nativeSetSurface(surface)

        @JvmStatic private external fun nativeOpen(): Long
        @JvmStatic private external fun nativeCommand(handle: Long, json: String): Boolean
        @JvmStatic private external fun nativePoll(handle: Long, timeoutMs: Int): String?
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeSetSurface(surface: Surface?)
        @JvmStatic private external fun nativeGamepad(handle: Long, slot: Int, connected: Int, buttons: Int,
                                                      leftTrigger: Int, rightTrigger: Int,
                                                      leftX: Int, leftY: Int, rightX: Int, rightY: Int)
        @JvmStatic private external fun nativeKey(handle: Long, virtualKey: Int, modifiers: Int, pressed: Boolean)
    }
}
