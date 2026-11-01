package com.droiddeck.launcher.wayland

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import android.view.Surface
import com.droiddeck.launcher.core.FileUtils
import java.io.File

/**
 * The bottom-screen desktop's compositor. The compositor's native state is one per process, so the
 * desktop gets a process of its own while the session's compositor keeps the top screen.
 */
class BottomDesktopCompositorService : Service() {
    private var client: Messenger? = null

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_ATTACH -> attach(msg)
                MSG_DETACH -> CompositorHost.detach(null)
                MSG_TOUCH -> WaylandCompositor.nativeSendTouch(msg.arg1 and 0xff, msg.arg1 shr 8,
                    msg.arg2 ushr 16, msg.arg2 and 0xffff)
                MSG_KEY -> WaylandCompositor.nativeSendKey(msg.arg1, msg.arg2)
                MSG_NEW_GUEST -> WaylandCompositor.nativeResetFirstFrame()
                MSG_TEXT -> msg.data?.getString(KEY_TEXT)?.let { WaylandCompositor.textInputCommit(it) }
                MSG_STOP -> {
                    Log.i(TAG, "bottom-screen desktop closed; ending its compositor process")
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        // The session process went away without saying so; nothing is left to draw for.
        android.os.Process.killProcess(android.os.Process.myPid())
        return false
    }

    private fun attach(msg: Message) {
        val surface = msg.obj as? Surface ?: return
        val data = msg.data ?: return
        client = msg.replyTo
        val runtimeDir = File(data.getString(KEY_RUNTIME_DIR) ?: return).apply { mkdirs() }
        val width = data.getInt(KEY_WIDTH)
        val height = data.getInt(KEY_HEIGHT)
        if (!CompositorHost.isStarted) {
            FileUtils.copyAsset(this, "wayland/keymap.xkb", File(runtimeDir, "keymap.xkb"))
            data.getString(KEY_LOG)?.let { path ->
                WaylandCompositor.setSessionLogFile(File(path))
                runCatching { android.system.Os.setenv("BL_WAYLAND_LOG", path, true) }
                    .onFailure { Log.w(TAG, "could not point the compositor's log at $path", it) }
            }
            WaylandCompositor.setFirstFrameListener { reply(MSG_FIRST_FRAME) }
            WaylandCompositor.setTextInputListener { enabled, _, _, _, _, _ ->
                reply(MSG_TEXT_INPUT, if (enabled) 1 else 0)
            }
            WaylandCompositor.nativeSetScaleMode(SCALE_FIT, ALIGN_CENTER)
        }
        val started = CompositorHost.startOrAttach(
            this, surface, runtimeDir.path, null, null, applicationInfo.nativeLibraryDir,
            width, height, data.getFloat(KEY_REFRESH, 60f), 0,
        )
        Log.i(TAG, (if (started) "compositor started" else "compositor attached") + " at ${width}x$height in ${runtimeDir.path}")
        reply(MSG_ATTACHED)
    }

    private fun reply(what: Int, arg1: Int = 0) {
        val target = client ?: return
        runCatching { target.send(Message.obtain(null, what, arg1, 0)) }
            .onFailure { Log.w(TAG, "the session process did not take message $what", it) }
    }

    companion object {
        private const val TAG = "BottomDesktopComp"
        const val MSG_ATTACH = 1
        const val MSG_DETACH = 2
        const val MSG_TOUCH = 3
        const val MSG_KEY = 4
        const val MSG_TEXT = 5
        const val MSG_STOP = 6
        const val MSG_NEW_GUEST = 7
        const val MSG_ATTACHED = 101
        const val MSG_FIRST_FRAME = 102
        const val MSG_TEXT_INPUT = 103
        const val KEY_RUNTIME_DIR = "runtimeDir"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_REFRESH = "refresh"
        const val KEY_LOG = "log"
        const val KEY_TEXT = "text"
        private const val SCALE_FIT = 1
        private const val ALIGN_CENTER = 0
    }
}
