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
    /** Echoed in every reply, so the session process can tell replies to an earlier attach apart. */
    @Volatile private var attachTag = 0

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_ATTACH -> attach(msg)
                MSG_DETACH -> CompositorHost.detach(null)
                MSG_TOUCH -> WaylandCompositor.nativeSendTouch(msg.arg1 and 0xff, msg.arg1 shr 8,
                    msg.arg2 ushr 16, msg.arg2 and 0xffff)
                MSG_KEY -> WaylandCompositor.nativeSendKey(msg.arg1, msg.arg2)
                MSG_POINTER -> WaylandCompositor.nativeSendPointer(1, msg.arg1, msg.arg2)
                MSG_SCENE -> WaylandCompositor.nativeSendSceneInput(msg.arg1, msg.arg2, msg.peekData()?.getInt(KEY_B) ?: 0)
                MSG_CURSOR -> if (CompositorHost.isStarted) WaylandCompositor.nativeSetCursorComposite(msg.arg1 != 0)
                MSG_NEW_GUEST -> WaylandCompositor.nativeResetFirstFrame()
                MSG_TEXT -> msg.data?.getString(KEY_TEXT)?.let { WaylandCompositor.textInputCommit(it) }
                MSG_STOP -> {
                    Log.i(TAG, "bottom-screen desktop closed; ending its compositor process")
                    // A producer that dies still connected keeps the panel's surface from taking another one.
                    CompositorHost.detach(null)
                    postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
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
        attachTag = msg.arg1
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
            WaylandCompositor.nativeSetScaleMode(SCALE_FIT, ALIGN_CENTER)
        }
        val turnip = com.droiddeck.launcher.gpu.TurnipDriver(this)
        val driverId = if (CompositorHost.isStarted) null else turnip.install()
        val started = CompositorHost.startOrAttach(
            this, surface, runtimeDir.path, driverId?.let { turnip.driverPath(it) }, driverId?.let { turnip.libraryName(it) },
            applicationInfo.nativeLibraryDir,
            width, height, data.getFloat(KEY_REFRESH, 60f), 0,
        )
        Log.i(TAG, (if (started) "compositor started" else "compositor attached") + " at ${width}x$height in ${runtimeDir.path}")
        reply(MSG_ATTACHED)
    }

    private fun reply(what: Int) {
        val target = client ?: return
        runCatching { target.send(Message.obtain(null, what, attachTag, 0)) }
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
        const val MSG_POINTER = 8
        const val MSG_SCENE = 9
        const val MSG_CURSOR = 10
        const val MSG_ATTACHED = 101
        const val MSG_FIRST_FRAME = 102
        const val KEY_RUNTIME_DIR = "runtimeDir"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_REFRESH = "refresh"
        const val KEY_LOG = "log"
        const val KEY_TEXT = "text"
        const val KEY_B = "b"
        private const val SCALE_FIT = 1
        private const val ALIGN_CENTER = 0
    }
}
