package com.droiddeck.launcher.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.io.IOException

class SessionClipboard(context: Context, private val hasFocus: () -> Boolean) {
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val handler = Handler(Looper.getMainLooper())
    private val hostFile = File(context.filesDir, "session/android-clipboard")
    private val guestFile = guestFile(context)
    private var guestObserver: FileObserver? = null
    private var active = false
    private var published = false
    private var shared: String? = null
    private var echo: String? = null
    private var capture: ((String?) -> Unit)? = null
    private var captureBefore: String? = null
    private var captureLate: (() -> Unit)? = null
    private val androidListener = ClipboardManager.OnPrimaryClipChangedListener { refresh(changed = true) }
    private val guestListener = object : WaylandCompositor.ClipboardListener {
        override fun onGuestClipboardText(text: String) {
            handler.post { receiveGuestText(text, WaylandCompositor.isClipboardListener(this)) }
        }
    }

    private fun receiveGuestText(text: String, current: Boolean) {
        val taken = capture?.takeIf { text != captureBefore }
        if (taken != null && current) {
            capture = null
            handler.removeCallbacks(captureTimeout)
            WaylandCompositor.setClipboardText(shared)
            taken(text)
        } else if (active && hasFocus() && current) {
            val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
            shared = text
            published = true
            if (clip?.toString() != text) {
                echo = text
                clipboard.setPrimaryClip(ClipData.newPlainText("DroidDeck", text))
            }
        }
    }

    private fun watchGuestFile() {
        guestFile.parentFile?.mkdirs()
        guestFile.delete()
        @Suppress("DEPRECATION")
        guestObserver = object : FileObserver(guestFile.parentFile!!.path, MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != guestFile.name) return
                val text = try {
                    guestFile.readText(Charsets.UTF_8)
                } catch (e: IOException) {
                    Log.w("SessionClipboard", "Could not read text copied in the desktop", e)
                    return
                } finally {
                    guestFile.delete()
                }
                if (text.isNotEmpty()) handler.post { receiveGuestText(text, true) }
            }
        }.also { it.startWatching() }
    }

    private val captureTimeout = Runnable {
        val taken = capture ?: return@Runnable
        capture = null
        captureLate?.invoke()
        taken(null)
    }

    fun captureGuest(timeoutMs: Long, onTimeout: () -> Unit = {}, onText: (String?) -> Unit) {
        capture?.let { handler.removeCallbacks(captureTimeout); it(null) }
        capture = onText
        captureLate = onTimeout
        captureBefore = shared
        handler.postDelayed(captureTimeout, timeoutMs)
    }

    fun start() {
        if (active) return
        active = true
        clipboard.addPrimaryClipChangedListener(androidListener)
        WaylandCompositor.setClipboardListener(guestListener)
        watchGuestFile()
        refresh()
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacks(captureTimeout)
        capture?.let { capture = null; it(null) }
        clipboard.removePrimaryClipChangedListener(androidListener)
        WaylandCompositor.clearClipboardListener(guestListener)
        guestObserver?.stopWatching()
        guestObserver = null
        handler.removeCallbacksAndMessages(null)
    }

    fun refresh(changed: Boolean = false) {
        if (!active || !hasFocus()) return
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount == 0) return
        val text = clip?.getItemAt(0)?.text?.toString()
        if (echo != null && text == echo) {
            echo = null
            return
        }
        echo = null
        if (!changed && published && text == shared) return
        publish(text)
    }

    private fun publish(text: String?) {
        published = true
        shared = text
        WaylandCompositor.setClipboardText(text)
        val pending = File(hostFile.parentFile, "android-clipboard.pending")
        try {
            hostFile.parentFile?.mkdirs()
            pending.writeText(text.orEmpty(), Charsets.UTF_8)
            Os.rename(pending.path, hostFile.path)
        } catch (e: IOException) {
            Log.w("SessionClipboard", "Could not share text with Xwayland", e)
        } catch (e: android.system.ErrnoException) {
            Log.w("SessionClipboard", "Could not publish text for Xwayland", e)
        } finally {
            pending.delete()
        }
    }

    companion object {
        fun guestFile(context: Context) = File(context.filesDir, "session/guest-clipboard/text")
    }
}
