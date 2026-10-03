package com.droiddeck.launcher.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.io.IOException

/** Text selections shared by Android and the guest while the session is visible. */
class SessionClipboard(context: Context, private val hasFocus: () -> Boolean) {
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val handler = Handler(Looper.getMainLooper())
    private val hostFile = File(context.filesDir, "session/android-clipboard")
    private var active = false
    private val androidListener = ClipboardManager.OnPrimaryClipChangedListener { refresh() }
    private val guestListener = object : WaylandCompositor.ClipboardListener {
        override fun onGuestClipboardText(text: String) {
            // Native callbacks arrive on the compositor thread. Do not publish a delayed callback
            // after this activity has paused or been replaced.
            handler.post {
                if (active && hasFocus() && WaylandCompositor.isClipboardListener(this)) {
                    val current = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                    if (current?.toString() != text) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("DroidDeck", text))
                    }
                }
            }
        }
    }

    fun start() {
        if (active) return
        active = true
        clipboard.addPrimaryClipChangedListener(androidListener)
        WaylandCompositor.setClipboardListener(guestListener)
        refresh()
    }

    fun stop() {
        if (!active) return
        active = false
        clipboard.removePrimaryClipChangedListener(androidListener)
        WaylandCompositor.clearClipboardListener(guestListener)
        handler.removeCallbacksAndMessages(null)
    }

    /** Android 10+ permits clipboard reads only with window focus. Resume can precede focus. */
    fun refresh() {
        if (!active || !hasFocus()) return
        val clip = clipboard.primaryClip
        // Only share literal text, never resolve content URIs or load copied files.
        if (clip == null) {
            publish(null)
        } else if (clip.itemCount > 0) {
            publish(clip.getItemAt(0).text?.toString())
        }
    }

    private fun publish(text: String?) {
        WaylandCompositor.setClipboardText(text)
        // Gamescope needs the same selection in its own Xwayland. Rename complete UTF-8 text
        // into place so its helper never sees half a launch command. Kept in private app data.
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
}
