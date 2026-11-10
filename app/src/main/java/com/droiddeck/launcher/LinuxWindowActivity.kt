package com.droiddeck.launcher

import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import com.droiddeck.launcher.input.EvdevKeys
import com.droiddeck.launcher.input.KeyboardHost
import com.droiddeck.launcher.input.LinuxWindowTouch
import com.droiddeck.launcher.input.AndroidPointerSource
import com.droiddeck.launcher.input.PointerGestures
import com.droiddeck.launcher.session.LinuxWindow
import com.droiddeck.launcher.session.LinuxWindowIcons
import com.droiddeck.launcher.session.LinuxWindowEditor
import com.droiddeck.launcher.session.LinuxWindowSize
import com.droiddeck.launcher.session.LinuxWindowPresentation
import com.droiddeck.launcher.session.LinuxWindows
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.launcher.wayland.WaylandCompositor
import com.droiddeck.launcher.ui.CursorOverlay

class LinuxWindowActivity : ComponentActivity(), SurfaceHolder.Callback {
    private var key = ""
    private var metadata: LinuxWindow? = null
    private lateinit var surface: SurfaceView
    private lateinit var keyboard: KeyboardHost
    private lateinit var touch: LinuxWindowTouch
    private var removing = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceGeneration = 0L
    private val pressed = mutableSetOf<Int>()
    private var lastTapAt = 0L
    private var editor: LinuxWindowEditor? = null
    private var presentationPoll = 0
    private var cursorPosition by mutableStateOf(Offset.Zero)
    private var cursorVisible by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = intent.getStringExtra("windowKey").orEmpty()
        val window = LinuxWindows.attach(this, key)
        if (window == null || !SessionState.running || !LinuxWindows.enabled) {
            removing = true
            finishAndRemoveTask()
            return
        }
        val noCursor = android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL)
        val frame = FrameLayout(this).apply { pointerIcon = noCursor }
        surface = SurfaceView(this).apply {
            holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            holder.addCallback(this@LinuxWindowActivity)
            pointerIcon = noCursor
            setOnTouchListener { _, event ->
                if (AndroidPointerSource.requestsKeyboardFocus(event)) focusWindow(true)
                touch.onPointer(event)
            }
            setOnGenericMotionListener { _, event ->
                if (AndroidPointerSource.requestsKeyboardFocus(event)) focusWindow(true)
                touch.onPointer(event)
            }
        }
        frame.addView(surface, FrameLayout.LayoutParams(-1, -1))
        frame.addView(object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent) = false
            override fun dispatchGenericMotionEvent(event: MotionEvent) = false
        }.apply {
            pointerIcon = noCursor
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(ComposeView(context).apply {
                pointerIcon = noCursor
                setContent { CursorOverlay({ cursorPosition }, cursorVisible, resources.displayMetrics.density) }
            }, FrameLayout.LayoutParams(-1, -1))
        }, FrameLayout.LayoutParams(-1, -1))
        keyboard = KeyboardHost(this)
        keyboard.onCommitText = { LinuxWindows.text(key, "commit", it) }
        keyboard.onComposingText = { text, cursor -> LinuxWindows.text(key, "preedit", text, cursor) }
        keyboard.onImeChanged = { visible -> if (!visible) lastTapAt = 0L }
        touch = LinuxWindowTouch(this, ::input, onTap = {
            lastTapAt = SystemClock.uptimeMillis()
            surface.postDelayed({ textFocusChanged(LinuxWindows.editor(key)) }, 180)
        }, onKeyboard = { keyboard.toggle() }, onCursor = { x, y, visible ->
            cursorPosition = Offset(x, y)
            cursorVisible = visible
        }, sendGesture = { type, a, b, c, time ->
            if (surfaceGeneration > 0)
                WaylandCompositor.nativePointerGesture(key, surfaceGeneration, type, a, b, c, 0f, time)
        }, sendMotion = { x, y, time ->
            if (surfaceGeneration > 0)
                WaylandCompositor.nativePointerGesture(key, surfaceGeneration, 10, x, y, 0f, 0f, time)
        })
        frame.addView(keyboard)
        setContentView(frame)
        metadataChanged(window)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (keyboard.shown && (android.os.Build.VERSION.SDK_INT < 30 || keyboard.imeShowing))
                    keyboard.hide()
                else moveTaskToBack(true)
            }
        })
    }

    fun metadataChanged(window: LinuxWindow) {
        val previous = metadata
        metadata = window
        if (window.minimized) moveTaskToBack(true)
        if (previous?.title == window.title && previous.icon === window.icon) return
        title = window.title
        setTaskDescription(LinuxWindowIcons.description(this, window.title, window.icon))
    }

    fun textFocusChanged(value: LinuxWindowEditor?) {
        val current = value?.takeIf { it.key == key && LinuxWindows.owner == key }
        if (!::keyboard.isInitialized) return
        if (current == null) {
            editor = null
            if (keyboard.shown) keyboard.hide()
            return
        }
        if (editor?.context != current.context) {
            if (keyboard.shown) keyboard.hide()
            keyboard.editorInputType = when (current.purpose) {
                2, 3, 4 -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                5 -> InputType.TYPE_CLASS_PHONE
                6 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                7 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                8, 9 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
        }
        editor = current
        if (!isFinishing && window.decorView.hasWindowFocus() &&
            SystemClock.uptimeMillis() - lastTapAt < 1500) keyboard.show()
    }

    private fun input(type: Int, a: Int, b: Int) {
        if (surfaceGeneration > 0 && (type != 4 || LinuxWindows.owner == key))
            WaylandCompositor.nativeWindowInput(key, surfaceGeneration, type, a, b)
    }

    private fun focusWindow(clicked: Boolean = false) {
        if (!::surface.isInitialized || !surface.holder.surface.isValid ||
            (!clicked && !window.decorView.hasWindowFocus())) return
        if (!SessionState.running || !LinuxWindows.enabled) return
        if (LinuxWindows.owner != key) LinuxWindows.claim(key)
        input(6, 1, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusWindow() else {
            releaseInput()
            if (LinuxWindows.owner == key) {
                input(6, 0, 0)
                LinuxWindows.desktop()
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceGeneration = LinuxWindows.nextSurfaceGeneration()
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!LinuxWindows.prepare(key, width, height, surfaceGeneration)) return
        surfaceWidth = width
        surfaceHeight = height
        touch.resize(width, height)
        CompositorHost.attachWindow(key, surfaceGeneration, holder.surface, width, height)
        val size = LinuxWindowSize.accept(width, height, surfaceGeneration) ?: return
        val token = ++presentationPoll
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var prepared = false
        var firstFrameDeadline = Long.MAX_VALUE
        val monitor = object : Runnable {
            override fun run() {
                if (token != presentationPoll || removing || !holder.surface.isValid) return
                val stats = WaylandCompositor.nativeWindowStats(key)
                when (LinuxWindowPresentation.state(stats, size)) {
                    LinuxWindowPresentation.State.FAILED -> {
                        LinuxWindows.presentationFailed(key, size.generation, "Native presentation error ${stats[11]}")
                        return
                    }
                    LinuxWindowPresentation.State.PREPARED -> {
                        if (!prepared) {
                            if (!LinuxWindows.resize(key, width, height, size.generation)) return
                            prepared = true
                            firstFrameDeadline = SystemClock.elapsedRealtime() + 15_000
                            focusWindow()
                        }
                        if (stats[5] == 0L && SystemClock.elapsedRealtime() >= firstFrameDeadline) {
                            LinuxWindows.presentationFailed(key, size.generation, "Guest output did not produce a presentable frame")
                            return
                        }
                    }
                    LinuxWindowPresentation.State.WAITING -> if (!prepared && SystemClock.elapsedRealtime() >= deadline) {
                        LinuxWindows.presentationFailed(key, size.generation, "Native surface preparation timed out")
                        return
                    }
                }
                surface.postDelayed(this, 100)
            }
        }
        surface.post(monitor)
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseInput()
        presentationPoll++
        surfaceWidth = 0
        surfaceHeight = 0
        CompositorHost.detachWindow(key, surfaceGeneration)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (LinuxWindows.owner == key && event.keyCode != KeyEvent.KEYCODE_BACK) {
            var code = EvdevKeys.fromKeyCode(event.keyCode)
            val ch = event.unicodeChar
            val plain = if (ch > 0) EvdevKeys.unshiftedChar(ch) else 0
            if (code <= 0 && ch > 0) code = EvdevKeys.fromKeyCode(
                EvdevKeys.keycodeForChar(if (plain != 0) plain else ch))
            if (code <= 0 && event.scanCode > 0) code = event.scanCode
            if (code > 0) {
                val down = event.action == KeyEvent.ACTION_DOWN
                if (down && event.repeatCount > 0) return true
                val shifted = plain != 0 && event.deviceId <= 0
                if (down && shifted) {
                    pressed.add(42)
                    touch.keyboardKey(42, true)
                }
                if (down) pressed.add(code) else pressed.remove(code)
                touch.keyboardKey(code, down)
                if (!down && shifted) {
                    pressed.remove(42)
                    touch.keyboardKey(42, false)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun releaseInput() {
        if (::touch.isInitialized) { touch.cancel(); touch.cancelMouse(); touch.hideCursor() }
        if (LinuxWindows.owner == key) {
            pressed.forEach { touch.keyboardKey(it, false) }
            listOf(PointerGestures.BTN_LEFT, PointerGestures.BTN_RIGHT, PointerGestures.BTN_MIDDLE)
                .forEach { input(3, it, 0) }
        }
        if (LinuxWindows.owner != key && ::touch.isInitialized) pressed.forEach { touch.keyboardKey(it, false) }
        pressed.clear()
    }

    override fun onStart() {
        super.onStart()
        if (::touch.isInitialized) touch.start()
        LinuxWindows.visibility(this, true)
    }

    override fun onStop() {
        if (::keyboard.isInitialized) keyboard.hide()
        lastTapAt = 0L
        if (::touch.isInitialized) touch.stop()
        LinuxWindows.visibility(this, false)
        releaseInput()
        if (LinuxWindows.owner == key) LinuxWindows.desktop()
        super.onStop()
    }

    fun removeFromRegistry() {
        removing = true
        finishAndRemoveTask()
    }

    val registryRemoving get() = removing

    override fun onDestroy() {
        presentationPoll++
        if (::touch.isInitialized) touch.stop()
        releaseInput()
        if (!removing && isFinishing && LinuxWindows.enabled && SessionState.running)
            LinuxWindows.closeFromAndroid(key)
        LinuxWindows.detach(this, key)
        LinuxWindows.visibility(this, false)
        CompositorHost.detachWindow(key, surfaceGeneration)
        super.onDestroy()
    }
}
