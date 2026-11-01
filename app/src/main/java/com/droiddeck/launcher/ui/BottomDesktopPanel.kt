package com.droiddeck.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.SystemClock
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.graphics.RectF
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.GuestKeys
import com.droiddeck.launcher.input.InputSpace
import com.droiddeck.launcher.input.KeyboardHost
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.PointerGestures
import com.droiddeck.launcher.input.TouchpadGestures
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.BottomDesktop

@SuppressLint("ViewConstructor")
class BottomDesktopPanel(
    context: Context,
    onClose: () -> Unit,
    onMenu: (() -> Unit)?,
    steamButtons: Pair<() -> Unit, () -> Unit>?,
) : LinearLayout(context), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private val area = FrameLayout(context).apply { clipChildren = true }
    private val state = mutableStateOf(BottomDesktop.state)
    private val status = ComposeView(context).apply {
        setContent { DroidDeckTheme { BottomDesktopStatus(state.value, ::startAgain) } }
    }
    private val keyboardHost = KeyboardHost(context) { event -> sendKey(event) }
    private var directTouch = SessionPrefs.bottomDesktopDirectTouch(context)
    private lateinit var touchButton: ImageView
    private lateinit var title: TextView
    private val trackpad = TouchpadGestures(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), TrackpadInput()).apply {
        desktop = true
    }
    private var attached = false
    /** The surface was given to a compositor process; a new process cannot connect to it again. */
    private var surfaceUsed = false
    private var fullWidth = 0
    private var fullHeight = 0
    private var lastTouchY = 0f
    private var lastPressMs = 0L
    private var scrollRestX = 0f
    private var scrollRestY = 0f
    private val stateListener: () -> Unit = { showState() }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        addView(bar(onClose, onMenu, steamButtons), LayoutParams(-1, -2))
        area.addView(keyboardHost)
        addSurface()
        area.addView(status, FrameLayout.LayoutParams(-1, -1))
        addView(area, LayoutParams(-1, 0, 1f))
        keyboardHost.onImeChanged = { pan() }
        showTouchMode()
    }

    private fun addSurface() {
        surfaceView = SurfaceView(context)
        surfaceView.holder.addCallback(this)
        @Suppress("ClickableViewAccessibility")
        surfaceView.setOnTouchListener { _, event -> onSurfaceTouch(event) }
        area.addView(surfaceView, 1, FrameLayout.LayoutParams(fullWidth.takeIf { it > 0 } ?: -1, fullHeight.takeIf { it > 0 } ?: -1))
    }

    private fun bar(onClose: () -> Unit, onMenu: (() -> Unit)?, steamButtons: Pair<() -> Unit, () -> Unit>?): View {
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.BLACK)
        }
        fun gap() = View(context).apply { layoutParams = LayoutParams(dp(6), 1) }
        bar.addView(DeckControlsPanel.roundButton(context, "×", context.getString(R.string.deck_close), onClose))
        title = TextView(context).apply {
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.rgb(0x7A, 0x80, 0x8A))
            setPadding(dp(12), 0, dp(6), 0)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bar.addView(title, LayoutParams(0, -2, 1f))
        touchButton = DeckControlsPanel.iconButton(context, R.drawable.ic_bottom_mouse, "") { setDirectTouch(!directTouch) }
        bar.addView(touchButton)
        bar.addView(gap())
        bar.addView(DeckControlsPanel.iconButton(context, R.drawable.ic_bottom_keyboard,
            context.getString(R.string.second_show_keyboard)) { keyboardHost.toggle() })
        onMenu?.let {
            bar.addView(gap())
            bar.addView(DeckControlsPanel.menuButton(context, it))
        }
        steamButtons?.let { (steam, qam) ->
            bar.addView(gap())
            bar.addView(DeckControlsPanel.roundButton(context, "STEAM", context.getString(R.string.deck_open_steam_menu), steam, wide = true))
            bar.addView(gap())
            bar.addView(DeckControlsPanel.roundButton(context, "•••", context.getString(R.string.drawer_open_qam), qam, wide = true))
        }
        return bar
    }

    private fun setDirectTouch(on: Boolean) {
        trackpad.cancel()
        BottomDesktop.sendTouch(3, -1, 0, 0)
        directTouch = on
        SessionPrefs.setBottomDesktopDirectTouch(context, on)
        showTouchMode()
    }

    private fun showTouchMode() {
        touchButton.setImageResource(if (directTouch) R.drawable.ic_bottom_touch else R.drawable.ic_bottom_mouse)
        touchButton.contentDescription = context.getString(
            if (directTouch) R.string.bottom_desktop_direct_touch else R.string.bottom_desktop_trackpad)
        title.text = context.getString(R.string.bottom_desktop_title,
            context.getString(if (directTouch) R.string.bottom_desktop_mode_touch else R.string.bottom_desktop_mode_trackpad))
        BottomDesktop.showCursor(!directTouch)
        if (!directTouch && surfaceView.width > 0) {
            trackpad.bounds = RectF(0f, 0f, surfaceView.width.toFloat(), surfaceView.height.toFloat())
            trackpad.place(surfaceView.width / 2f, surfaceView.height / 2f)
            movePointer(trackpad.x, trackpad.y)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        BottomDesktop.addListener(stateListener)
        BottomDesktop.setTextInputListener { focused ->
            val tapped = SystemClock.uptimeMillis() - lastPressMs < TEXT_FIELD_TAP_MS
            val hardKeyboard = resources.configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
            if (focused && tapped && !hardKeyboard && !keyboardHost.shown) keyboardHost.show()
            else if (!focused && keyboardHost.shown) keyboardHost.hide()
        }
        showState()
        post { if (!keyboardHost.shown) requestFocus() }
    }

    override fun onDetachedFromWindow() {
        BottomDesktop.removeListener(stateListener)
        BottomDesktop.setTextInputListener(null)
        keyboardHost.takeIf { it.shown }?.hide()
        trackpad.cancel()
        status.disposeComposition()
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (BottomDesktop.state == BottomDesktop.State.RUNNING && !PadBridge.isControllerKeyEvent(event) &&
            event.keyCode != KeyEvent.KEYCODE_BACK && sendKey(event)) true
        else super.dispatchKeyEvent(event)

    private fun sendKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
        if (GuestKeys.send(event, BottomDesktop::sendKey)) return true
        val ch = event.unicodeChar
        if (event.action == KeyEvent.ACTION_DOWN && ch > 0) {
            BottomDesktop.sendText(String(Character.toChars(ch)))
            return true
        }
        return false
    }

    private fun startAgain() {
        if (surfaceUsed && !BottomDesktop.connected) {
            attached = false
            surfaceUsed = false
            area.removeView(surfaceView)
            addSurface()
            return
        }
        if (attached) attach(surfaceView.holder)
    }

    private fun showState() {
        state.value = BottomDesktop.state
        status.visibility = if (state.value == BottomDesktop.State.RUNNING) View.GONE else View.VISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { holdSurfaceSize() }
    }

    // The keyboard shrinks the window; the desktop keeps its size and slides up under it instead of being squeezed.
    private fun holdSurfaceSize() {
        val w = area.width
        val h = area.height
        if (w <= 0 || h <= 0) return
        if (w != fullWidth || h > fullHeight || !keyboardHost.shown) {
            fullWidth = w
            fullHeight = h
            surfaceView.layoutParams = FrameLayout.LayoutParams(w, h)
        }
        pan()
    }

    private fun pan() {
        val visible = area.height
        val hidden = (fullHeight - visible).coerceAtLeast(0)
        val anchor = if (directTouch) lastTouchY else trackpad.y
        surfaceView.translationY = if (hidden == 0 || !keyboardHost.shown) 0f
        else -(anchor - visible * 0.4f).coerceIn(0f, hidden.toFloat())
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!attached) attach(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (holder !== surfaceView.holder) return
        attached = false
        surfaceUsed = false
        BottomDesktop.detach()
    }

    private fun attach(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        val (width, height) = BottomDesktop.outputSizeFor(fullWidth.takeIf { it > 0 } ?: frame.width(),
            fullHeight.takeIf { it > 0 } ?: frame.height())
        if (width <= 0 || height <= 0) return
        if (frame.width() != width || frame.height() != height) {
            holder.setFixedSize(width, height)
            return
        }
        attached = true
        surfaceUsed = true
        BottomDesktop.attach(context, holder.surface, width, height, display?.refreshRate ?: 60f)
        showTouchMode()
    }

    private fun onSurfaceTouch(event: MotionEvent): Boolean {
        val w = surfaceView.width
        val h = surfaceView.height
        if (w <= 0 || h <= 0 || BottomDesktop.state != BottomDesktop.State.RUNNING) return true
        if (event.actionMasked == MotionEvent.ACTION_UP) lastPressMs = SystemClock.uptimeMillis()
        if (directTouch) return forwardTouch(event, w, h)
        val bounds = RectF(0f, 0f, w.toFloat(), h.toFloat())
        if (trackpad.bounds != bounds) {
            trackpad.bounds = bounds
            trackpad.place(trackpad.x, trackpad.y)
        }
        return trackpad.onTouch(event)
    }

    private fun forwardTouch(event: MotionEvent, w: Int, h: Int): Boolean {
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> 2
            MotionEvent.ACTION_CANCEL -> { BottomDesktop.sendTouch(3, -1, 0, 0); return true }
            else -> return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) lastTouchY = event.y
        if (action == 1) {
            for (i in 0 until event.pointerCount) send(1, event.getPointerId(i), event.getX(i), event.getY(i), w, h)
        } else {
            val i = event.actionIndex
            send(action, event.getPointerId(i), event.getX(i), event.getY(i), w, h)
        }
        return true
    }

    private fun send(action: Int, id: Int, x: Float, y: Float, w: Int, h: Int) =
        BottomDesktop.sendTouch(action, id, InputSpace.x(x, w), InputSpace.y(y, h))

    private fun movePointer(x: Float, y: Float) =
        BottomDesktop.sendPointer(InputSpace.x(x, surfaceView.width), InputSpace.y(y, surfaceView.height))

    private inner class TrackpadInput : PointerGestures.Listener {
        override fun onMove(x: Float, y: Float) = movePointer(x, y)

        override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) {
            movePointer(x, y)
            BottomDesktop.sendScene(SCENE_BUTTON, button, if (pressed) 1 else 0)
        }

        override fun onWheel(steps: Int) = BottomDesktop.sendScene(SCENE_WHEEL, steps)

        override fun onScroll(dx: Float, dy: Float) {
            val (outW, outH) = BottomDesktop.outputSizeFor(surfaceView.width, surfaceView.height)
            scrollRestX += dx * outW / surfaceView.width.coerceAtLeast(1) * AXIS_UNITS_PER_PIXEL
            scrollRestY += dy * outH / surfaceView.height.coerceAtLeast(1) * AXIS_UNITS_PER_PIXEL
            val h = scrollRestX.toInt()
            val v = scrollRestY.toInt()
            if (h == 0 && v == 0) return
            scrollRestX -= h
            scrollRestY -= v
            BottomDesktop.sendScene(SCENE_AXIS, v, h)
        }

        override fun onScrollEnd() {
            scrollRestX = 0f
            scrollRestY = 0f
            BottomDesktop.sendScene(SCENE_AXIS, 0, 0)
        }

        override fun onZoom(steps: Int) {
            BottomDesktop.sendKey(KEY_LEFTCTRL, true)
            BottomDesktop.sendScene(SCENE_WHEEL, steps)
            BottomDesktop.sendKey(KEY_LEFTCTRL, false)
        }

        override fun onThreeFingerTap(): Boolean {
            post { keyboardHost.toggle() }
            return true
        }

        override fun onLongPress() {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val SCENE_BUTTON = 3
        const val SCENE_WHEEL = 4
        const val SCENE_AXIS = 12
        const val KEY_LEFTCTRL = 29
        const val AXIS_UNITS_PER_PIXEL = 3f
        const val TEXT_FIELD_TAP_MS = 1_000L
    }
}
