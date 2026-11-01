package com.droiddeck.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.GuestKeys
import com.droiddeck.launcher.input.InputSpace
import com.droiddeck.launcher.input.KeyboardHost
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.session.BottomDesktop

@SuppressLint("ViewConstructor")
class BottomDesktopPanel(
    context: Context,
    onClose: () -> Unit,
    onMenu: (() -> Unit)?,
    steamButtons: Pair<() -> Unit, () -> Unit>?,
) : LinearLayout(context), SurfaceHolder.Callback {
    private val surfaceView = SurfaceView(context)
    private val area = FrameLayout(context).apply { clipChildren = true }
    private val status = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)
    }
    private val spinner = ProgressBar(context)
    private val statusText = TextView(context).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(0xF2, 0xF4, 0xF7))
        setPadding(dp(24), dp(12), dp(24), dp(12))
    }
    private val statusAction = DeckControlsPanel.roundButton(context, context.getString(R.string.bottom_desktop_start),
        context.getString(R.string.bottom_desktop_start), ::startAgain, wide = true)
    private val keyboardHost = KeyboardHost(context) { event -> sendKey(event) }
    private var attached = false
    private var fullWidth = 0
    private var fullHeight = 0
    private var lastTouchY = 0f
    private val stateListener: () -> Unit = { showState() }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        addView(bar(onClose, onMenu, steamButtons), LayoutParams(-1, -2))
        surfaceView.holder.addCallback(this)
        @Suppress("ClickableViewAccessibility")
        surfaceView.setOnTouchListener { _, event -> forwardTouch(event) }
        area.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        status.addView(spinner, LayoutParams(dp(40), dp(40)))
        status.addView(statusText, LayoutParams(-2, -2))
        status.addView(statusAction)
        area.addView(status, FrameLayout.LayoutParams(-1, -1))
        area.addView(keyboardHost)
        addView(area, LayoutParams(-1, 0, 1f))
        keyboardHost.onImeChanged = { pan() }
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
        bar.addView(TextView(context).apply {
            text = context.getString(R.string.second_mode_desktop)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.rgb(0x7A, 0x80, 0x8A))
            setPadding(dp(12), 0, dp(6), 0)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LayoutParams(0, -2, 1f))
        bar.addView(DeckControlsPanel.roundButton(context, "⌨", context.getString(R.string.second_show_keyboard), { keyboardHost.toggle() }))
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
        bar.addView(gap())
        bar.addView(DeckControlsPanel.roundButton(context, "⏻", context.getString(R.string.bottom_desktop_stop), {
            keyboardHost.takeIf { it.shown }?.hide()
            BottomDesktop.stop(context)
        }))
        return bar
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        BottomDesktop.addListener(stateListener)
        BottomDesktop.setTextInputListener { wanted ->
            if (wanted && !keyboardHost.shown) keyboardHost.show()
            else if (!wanted && keyboardHost.shown) keyboardHost.hide()
        }
        showState()
        post { if (!keyboardHost.shown) requestFocus() }
    }

    override fun onDetachedFromWindow() {
        BottomDesktop.removeListener(stateListener)
        BottomDesktop.setTextInputListener(null)
        keyboardHost.takeIf { it.shown }?.hide()
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
        if (attached) attach(surfaceView.holder)
    }

    private fun showState() {
        val state = BottomDesktop.state
        status.visibility = if (state == BottomDesktop.State.RUNNING) View.GONE else View.VISIBLE
        spinner.visibility = if (state == BottomDesktop.State.STARTING) View.VISIBLE else View.GONE
        statusAction.visibility = if (state == BottomDesktop.State.STARTING) View.GONE else View.VISIBLE
        statusText.text = context.getString(when (state) {
            BottomDesktop.State.STARTING -> R.string.bottom_desktop_starting
            BottomDesktop.State.ENDED -> R.string.bottom_desktop_ended
            BottomDesktop.State.FAILED -> R.string.bottom_desktop_failed
            BottomDesktop.State.OFF -> R.string.bottom_desktop_off
            BottomDesktop.State.RUNNING -> R.string.second_mode_desktop
        })
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
        surfaceView.translationY = if (hidden == 0 || !keyboardHost.shown) 0f
        else -(lastTouchY - visible * 0.4f).coerceIn(0f, hidden.toFloat())
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!attached) attach(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        attached = false
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
        BottomDesktop.attach(context, holder.surface, width, height, display?.refreshRate ?: 60f)
    }

    private fun forwardTouch(event: MotionEvent): Boolean {
        val w = surfaceView.width
        val h = surfaceView.height
        if (w <= 0 || h <= 0 || BottomDesktop.state != BottomDesktop.State.RUNNING) return true
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
