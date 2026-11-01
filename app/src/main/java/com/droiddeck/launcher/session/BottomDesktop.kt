package com.droiddeck.launcher.session

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import android.view.Surface
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.wayland.BottomDesktopCompositorService as Comp
import java.io.File

/**
 * KDE Plasma on the bottom screen while the session keeps the top one: its own compositor process
 * ([Comp]) and a second guest started with the session's environment, like the bottom-screen terminal.
 */
object BottomDesktop {
    private const val TAG = "BottomDesktop"
    const val SCRIPT = "/usr/local/bin/droiddeck-bottom-desktop"
    private const val TARGET_SHORT_SIDE = 720f

    enum class State { OFF, STARTING, RUNNING, ENDED, FAILED }

    private class Launch(
        val guest: List<String>,
        val binds: List<String>,
        val sessionRoot: File,
        val hostEnvironment: Array<String>,
        val root: File,
        val sessionDir: File,
        val generation: Int,
        val installed: Boolean,
    )

    @Volatile private var launch: Launch? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    @Volatile var state = State.OFF
        private set
    private var onTextInput: ((Boolean) -> Unit)? = null

    private var appContext: Context? = null
    private var service: Messenger? = null
    private var binding: ServiceConnection? = null
    private var pendingAttach: Message? = null
    /** The desktop guest's proot: -1 when there is none, 0 while it is being started. */
    private var guestPid = -1
    private var guestGeneration = 0
    private var outputSize = 0 to 0

    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                Comp.MSG_ATTACHED -> if (guestPid < 0 && state == State.STARTING) startGuest()
                Comp.MSG_FIRST_FRAME -> if (state == State.STARTING) setState(State.RUNNING)
                Comp.MSG_TEXT_INPUT -> onTextInput?.invoke(msg.arg1 != 0)
            }
        }
    })

    fun prepare(
        guest: List<String>,
        binds: List<String>,
        sessionRoot: File,
        hostEnvironment: Array<String>,
        root: File,
        sessionDir: File,
        generation: Int,
    ) {
        launch = Launch(ArrayList(guest), ArrayList(binds), sessionRoot, hostEnvironment.copyOf(), root, sessionDir, generation,
            DesktopCatalog.desktopInstalled(root))
    }

    fun available(): Boolean =
        launch?.installed == true && SessionState.running && SessionState.mode != SessionService.MODE_DESKTOP

    /**
     * The desktop renders below the panel's resolution: about 720 pixels on the short side keeps
     * Plasma's text big enough to touch and its software rendering cheap. A running desktop keeps its size.
     */
    fun outputSizeFor(width: Int, height: Int): Pair<Int, Int> {
        if ((state == State.STARTING || state == State.RUNNING) && outputSize.first > 0) return outputSize
        if (width <= 0 || height <= 0) return 0 to 0
        val scale = maxOf(1f, Math.round(minOf(width, height) / TARGET_SHORT_SIDE * 2f) / 2f)
        return ((width / scale).toInt() and 1.inv()) to ((height / scale).toInt() and 1.inv())
    }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }

    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    fun setTextInputListener(listener: ((Boolean) -> Unit)?) { onTextInput = listener }

    private fun setState(next: State) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { setState(next) }; return }
        if (state == next) return
        Log.i(TAG, "state $state -> $next")
        state = next
        listeners.toList().forEach { it() }
    }

    fun attach(context: Context, surface: Surface, width: Int, height: Int, refreshHz: Float) {
        val current = launch
        if (current == null || !SessionState.running) {
            setState(State.FAILED)
            return
        }
        appContext = context.applicationContext
        outputSize = width to height
        val dir = File(current.sessionDir, "bottom-desktop").apply { mkdirs() }
        val msg = Message.obtain(null, Comp.MSG_ATTACH).apply {
            obj = surface
            replyTo = replies
            data = Bundle().apply {
                putString(Comp.KEY_RUNTIME_DIR, runtimeDir(context).path)
                putInt(Comp.KEY_WIDTH, width)
                putInt(Comp.KEY_HEIGHT, height)
                putFloat(Comp.KEY_REFRESH, refreshHz)
                putString(Comp.KEY_LOG, File(dir, "wayland.log").path)
            }
        }
        if (state == State.OFF || state == State.ENDED || state == State.FAILED) setState(State.STARTING)
        val target = service
        if (target != null) send(target, msg) else {
            pendingAttach = msg
            bind(context.applicationContext)
        }
    }

    fun detach() {
        pendingAttach = null
        service?.let { send(it, Message.obtain(null, Comp.MSG_DETACH)) }
    }

    fun sendTouch(action: Int, pointerId: Int, x: Int, y: Int) {
        val target = service ?: return
        send(target, Message.obtain(null, Comp.MSG_TOUCH, (pointerId shl 8) or (action and 0xff),
            (x.coerceIn(0, 0xffff) shl 16) or y.coerceIn(0, 0xffff)))
    }

    fun sendKey(evdev: Int, down: Boolean) {
        val target = service ?: return
        send(target, Message.obtain(null, Comp.MSG_KEY, evdev, if (down) 1 else 0))
    }

    fun sendText(text: String) {
        val target = service ?: return
        send(target, Message.obtain(null, Comp.MSG_TEXT).apply { data = Bundle().apply { putString(Comp.KEY_TEXT, text) } })
    }

    fun stop(context: Context, generation: Int? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { stop(context, generation) }; return }
        val current = launch
        if (generation != null && current != null && current.generation != generation) return
        if (generation != null) launch = null
        val pid = guestPid
        guestPid = -1
        guestGeneration++
        if (pid > 1) SessionService.stopTerminalProcess(context, pid)
        service?.let { send(it, Message.obtain(null, Comp.MSG_STOP)) }
        binding?.let { runCatching { context.applicationContext.unbindService(it) } }
        binding = null
        service = null
        pendingAttach = null
        setState(State.OFF)
    }

    private fun bind(context: Context) {
        if (binding != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binding !== this || binder == null) return
                val messenger = Messenger(binder)
                service = messenger
                pendingAttach?.let { send(messenger, it) }
                pendingAttach = null
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (binding !== this) return
                Log.w(TAG, "the desktop's compositor process died")
                service = null
                binding = null
                runCatching { context.unbindService(this) }
                val pid = guestPid
                guestPid = -1
                guestGeneration++
                if (pid > 1) SessionService.stopTerminalProcess(context, pid)
                setState(State.FAILED)
            }
        }
        binding = connection
        val bound = runCatching {
            context.bindService(Intent(context, Comp::class.java), connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        }.getOrDefault(false)
        if (!bound) {
            Log.e(TAG, "could not bind the desktop's compositor")
            binding = null
            setState(State.FAILED)
        }
    }

    private fun send(target: Messenger, msg: Message) {
        runCatching { target.send(msg) }.onFailure { Log.w(TAG, "compositor message ${msg.what} not delivered", it) }
    }

    private fun runtimeDir(context: Context) = File(context.filesDir, ".wayland-rt-bottom")

    private fun startGuest() {
        val context = appContext ?: return
        val current = launch
        if (current == null || !SessionState.running) {
            setState(State.FAILED)
            return
        }
        val generation = ++guestGeneration
        guestPid = 0
        val (width, height) = outputSize
        service?.let { send(it, Message.obtain(null, Comp.MSG_NEW_GUEST)) }
        Thread({
            if (!DesktopCatalog.desktopInstalled(current.root)) {
                Log.e(TAG, "the desktop package is not installed")
                main.post { if (generation == guestGeneration) { guestPid = -1; setState(State.FAILED) } }
                return@Thread
            }
            val dir = File(current.sessionDir, "bottom-desktop").apply { mkdirs() }
            val runtime = runtimeDir(context).apply { mkdirs() }
            val binds = LinuxRuntime.binds(context, current.sessionRoot, runtime, Environment.getExternalStorageDirectory(), current.binds)
            val guest = ArrayList(current.guest).apply {
                add("BL_WIDTH=$width")
                add("BL_HEIGHT=$height")
                add("BL_DEBUG_DIR=" + dir.path)
                add("BL_TOP_SESSION=" + SessionState.mode)
                add(SCRIPT)
            }
            val command = LinuxRuntime.commandWithBinds(context, current.root, binds, guest)
            val line = command.joinToString(" ") { it.replace("\\", "\\\\").replace(" ", "\\ ") }
            val pid = HostProcess.start(line, current.hostEnvironment, current.root, { status ->
                main.post {
                    if (generation != guestGeneration) return@post
                    Log.i(TAG, "the desktop ended ($status)")
                    SessionService.terminalProcessExited(context, guestPid)
                    guestPid = -1
                    guestGeneration++
                    setState(if (state == State.RUNNING || status == 0) State.ENDED else State.FAILED)
                }
            }, null, File(dir, "proot.log"))
            main.post {
                if (generation != guestGeneration) {
                    if (pid > 1) SessionService.stopTerminalProcess(context, pid)
                    return@post
                }
                if (pid <= 1) {
                    guestPid = -1
                    Log.e(TAG, "the desktop's guest did not start")
                    setState(State.FAILED)
                    return@post
                }
                guestPid = pid
                SessionService.registerTerminalProcess(context, pid)
                Log.i(TAG, "desktop guest pid $pid at ${width}x$height")
            }
        }, "bottom-desktop-start").start()
    }
}
