package com.droiddeck.launcher.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentHost
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Where the pet is on the screen and how it moves: dragged anywhere, then settling against the
 * nearer side edge, or dropped on the close target to turn it off. Positions are screen pixels.
 */
internal class PetMotion(private val context: Context) {
    var x by mutableFloatStateOf(0f)
        private set
    var y by mutableFloatStateOf(0f)
        private set
    var right by mutableStateOf(true)
        private set
    var dragging by mutableStateOf(false)
        private set
    var width = 0f
        private set
    var height = 0f
        private set
    private val density = context.resources.displayMetrics.density
    val size = 76 * density
    private val margin = 6 * density
    private val inset = 24 * density
    private var settle: ValueAnimator? = null

    val target get() = Pair(width / 2, height - 72 * density)
    val overTarget get() = dragging && hypot(x + size / 2 - target.first, y + size / 2 - target.second) < size

    /** Fits the pet into a [w] by [h] screen, keeping its side and relative height. */
    fun fit(w: Float, h: Float) {
        if (w <= 0 || h <= 0 || (w == width && h == height)) return
        val first = width == 0f
        width = w
        height = h
        val (side, fraction) = if (first) AgentPet.place(context) else right to fractionOf(y)
        right = side
        x = edge(side)
        y = inset + (travel()) * fraction
    }

    private fun travel() = (height - size - inset * 2).coerceAtLeast(0f)
    private fun fractionOf(at: Float) = if (travel() > 0) ((at - inset) / travel()).coerceIn(0f, 1f) else 0f
    private fun edge(side: Boolean) = if (side) width - size - margin else margin

    fun start() {
        settle?.cancel()
        dragging = true
    }

    fun move(dx: Float, dy: Float) {
        x += dx
        y += dy
    }

    /** Ends a drag; true when the pet was dropped on the close target. */
    fun end(): Boolean {
        val dismissed = overTarget
        dragging = false
        if (dismissed) return true
        right = x + size / 2 > width / 2
        val toY = y.coerceIn(inset, (height - size - inset).coerceAtLeast(inset))
        AgentPet.save(context, right, fractionOf(toY))
        val fromX = x
        val fromY = y
        val toX = edge(right)
        settle = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener {
                val f = it.animatedValue as Float
                x = fromX + (toX - fromX) * f
                y = fromY + (toY - fromY) * f
            }
            start()
        }
        return false
    }
}

/** Lets a ComposeView live in a window no activity owns. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/**
 * The pet over every app, in windows of its own: the pet, its message preview, the close target
 * while it is dragged, and the chat it opens. Needs the "display over other apps" permission.
 */
@SuppressLint("ClickableViewAccessibility")
internal class PetWindows(private val context: Context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayOwner()
    private val motion = PetMotion(context)
    private val scope = MainScope()
    /** The chat is open, or opening; its window stays up until it has shrunk back into the pet. */
    private var chatOpen by mutableStateOf(false)
    private var bubble by mutableStateOf<String?>(null)
    private var bubbleWidth by mutableFloatStateOf(0f)
    private var bubbleHeight by mutableFloatStateOf(0f)
    /** The windows are in the window manager, so moving one goes through it. */
    private var added = false

    // A window emptied to no size keeps showing its last frame, so each is hidden as a whole instead.
    private val head = window(touchable = true, focusable = false) {
        PetFigure(petStateNow(), Modifier.size(76.dp))
    }
    private val preview = window(touchable = true, focusable = false, onSize = { w, h -> bubbleWidth = w.toFloat(); bubbleHeight = h.toFloat() }) {
        bubble?.let { PetPreview(it, Modifier.padding(2.dp)) { openChat() } }
    }
    private val target = window(touchable = false, focusable = false) {
        PetCloseTarget(motion.overTarget)
    }
    private val chat: Window = window(touchable = true, focusable = true, full = true) {
        PetChat(chatOpen, motion, ::closeChat) { setVisible(chat, false) }
    }

    init {
        head.view.setOnTouchListener(DragTouch())
        scope.launch { snapshotFlow { Triple(motion.x, motion.y, motion.right) }.collectLatest { place() } }
        scope.launch { snapshotFlow { bubbleWidth to bubbleHeight }.collectLatest { place() } }
        scope.launch { snapshotFlow { AgentPet.agentOnScreen == 0 }.collectLatest { setVisible(head, it) } }
        scope.launch { snapshotFlow { motion.dragging }.collectLatest { setVisible(target, it) } }
        scope.launch {
            snapshotFlow { bubble != null && !chatOpen && !motion.dragging && AgentPet.agentOnScreen == 0 }.collectLatest { setVisible(preview, it) }
        }
        scope.launch {
            snapshotFlow { petBubbleText() }.collectLatest { text ->
                bubble = text
                if (text != null) {
                    kotlinx.coroutines.delay(PREVIEW_MS)
                    bubble = null
                }
            }
        }
        val metrics = context.resources.displayMetrics
        motion.fit(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
        place()
        listOf(chat, target, preview).forEach { setVisible(it, false) }
        listOf(chat, target, preview, head).forEach { windows.addView(it.view, it.params) }
        added = true
    }

    private class Window(val view: FrameLayout, val params: WindowManager.LayoutParams)

    private fun window(
        touchable: Boolean, focusable: Boolean, full: Boolean = false, onSize: ((Int, Int) -> Unit)? = null, content: @Composable () -> Unit,
    ): Window {
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { DroidDeckTheme(appScale = com.droiddeck.launcher.core.AppUiPrefs.scale(context)) { content() } }
        }
        val frame = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (full && event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && chatOpen) {
                    closeChat()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            addView(compose)
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }
        val size = if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (full) softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (onSize != null) compose.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> onSize(v.width, v.height) }
        return Window(frame, params)
    }

    private fun setVisible(window: Window, shown: Boolean) {
        window.view.visibility = if (shown) View.VISIBLE else View.GONE
    }

    private fun place() {
        val gap = 10 * context.resources.displayMetrics.density
        update(head, motion.x, motion.y)
        val bx = if (motion.right) motion.x - gap - bubbleWidth else motion.x + motion.size + gap
        update(preview, bx, motion.y + motion.size / 2 - bubbleHeight / 2)
        val (tx, ty) = motion.target
        update(target, tx - motion.size / 2, ty - motion.size / 2)
    }

    private fun update(window: Window, x: Float, y: Float) {
        if (window.params.x == x.roundToInt() && window.params.y == y.roundToInt()) return
        window.params.x = x.roundToInt()
        window.params.y = y.roundToInt()
        if (added) runCatching { windows.updateViewLayout(window.view, window.params) }
    }

    /** The window is shown first and the chat grows once it is on screen, or the start of the animation plays where nobody sees it. */
    private fun openChat() {
        bubble = null
        if (chatOpen) return
        setVisible(chat, true)
        androidx.core.view.OneShotPreDrawListener.add(chat.view) { chatOpen = true }
        chat.view.invalidate()
    }

    private fun closeChat() {
        chatOpen = false
    }

    fun close() {
        scope.cancel()
        listOf(head, preview, target, chat).forEach { runCatching { windows.removeView(it.view) } }
        owner.destroy()
    }

    /** Drags and taps on the pet, in screen coordinates, since its window moves under the finger. */
    private inner class DragTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; lastX = e.rawX; lastY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                        moved = true
                        bubble = null
                        closeChat()
                        motion.start()
                    }
                    if (moved) motion.move(e.rawX - lastX, e.rawY - lastY)
                    lastX = e.rawX; lastY = e.rawY
                }
                MotionEvent.ACTION_UP -> if (moved) {
                    if (motion.end()) AgentPet.set(context, false)
                } else if (chatOpen) closeChat() else openChat()
                MotionEvent.ACTION_CANCEL -> if (moved) motion.end()
            }
            return true
        }
    }

    companion object {
        fun allowed(context: Context) = Settings.canDrawOverlays(context)
    }
}

/**
 * The floating chat: the Agent conversation in a panel beside the pet. It grows out of the pet the
 * way the session drawer's stop confirmation grows out of its button, its height first and then
 * its width with a little overshoot, and its contents fade in after; closing runs it backwards and
 * then calls [onClosed].
 */
@Composable
private fun PetChat(open: Boolean, motion: PetMotion, onClose: () -> Unit, onClosed: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            val k = e.nativeKeyEvent
            if (k.keyCode != KeyEvent.KEYCODE_BUTTON_B || k.action != KeyEvent.ACTION_UP) return@onPreviewKeyEvent false
            onClose()
            true
        },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val gap = with(density) { 10.dp.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val corner = with(density) { 16.dp.toPx() }
        val room = if (motion.right) motion.x - gap - margin else w - motion.x - motion.size - gap - margin
        val panelW = minOf(with(density) { 820.dp.toPx() }, room).coerceAtLeast(with(density) { 280.dp.toPx() })
        val panelH = (h - margin * 2).coerceAtLeast(motion.size)
        val panelX = (if (motion.right) motion.x - gap - panelW else motion.x + motion.size + gap).coerceIn(0f, (w - panelW).coerceAtLeast(0f))
        val panel = Rect(panelX, margin, panelX + panelW, margin + panelH)
        val inset = with(density) { 4.dp.toPx() }
        val pet = Rect(motion.x + inset, motion.y + inset, motion.x + motion.size - inset, motion.y + motion.size - inset)

        val left = remember { Animatable(pet.left) }
        val right = remember { Animatable(pet.right) }
        val top = remember { Animatable(pet.top) }
        val bottom = remember { Animatable(pet.bottom) }
        val round = remember { Animatable(pet.width / 2) }
        val dim = remember { Animatable(0f) }
        val header = remember { Animatable(0f) }
        val shown = remember { Animatable(0f) }
        var grown by remember { mutableStateOf(false) }
        // As with the stop confirmation: the panel first grows tall behind the pet, then both sides spring out,
        // the far one with an overshoot; the far side moves furthest, so it stays ahead of the near one.
        val far = if (motion.right) left else right
        val near = if (motion.right) right else left
        val farTarget = if (motion.right) panel.left else panel.right
        val nearTarget = if (motion.right) panel.right else panel.left
        LaunchedEffect(open) {
            if (open) {
                left.snapTo(pet.left); right.snapTo(pet.right); top.snapTo(pet.top); bottom.snapTo(pet.bottom); round.snapTo(pet.width / 2)
                shown.snapTo(0f)
                header.snapTo(0f)
                grown = true
                coroutineScope {
                    launch { dim.animateTo(1f, Motion.tw(260)) }
                    launch { round.animateTo(corner, Motion.tw(200)) }
                    launch { top.animateTo(panel.top, Motion.sp(0.7f, 380f)) }
                    launch { bottom.animateTo(panel.bottom, Motion.sp(0.7f, 380f)) }
                    launch { delay(Motion.ms(110).toLong()); far.animateTo(farTarget, Motion.sp(0.55f, 300f)) }
                    launch { delay(Motion.ms(110).toLong()); near.animateTo(nearTarget, Motion.sp(0.7f, 380f)) }
                    launch { delay(Motion.ms(260).toLong()); header.animateTo(1f, Motion.tw(220)) }
                    launch { delay(Motion.ms(315).toLong()); shown.animateTo(1f, Motion.tw(220)) }
                }
            } else if (grown) {
                coroutineScope {
                    launch { shown.animateTo(0f, Motion.tw(90)) }
                    launch { header.animateTo(0f, Motion.tw(90)) }
                    launch { delay(Motion.ms(60).toLong()); dim.animateTo(0f, Motion.tw(300)) }
                    launch { delay(Motion.ms(60).toLong()); far.animateTo(if (motion.right) pet.left else pet.right, Motion.sp(1f, 420f)) }
                    launch { delay(Motion.ms(60).toLong()); near.animateTo(if (motion.right) pet.right else pet.left, Motion.sp(1f, 420f)) }
                    launch { delay(Motion.ms(160).toLong()); top.animateTo(pet.top, Motion.sp(1f, 380f)) }
                    launch { delay(Motion.ms(160).toLong()); bottom.animateTo(pet.bottom, Motion.sp(1f, 380f)) }
                    launch { delay(Motion.ms(200).toLong()); round.animateTo(pet.width / 2, Motion.tw(200)) }
                }
                grown = false
                onClosed()
            }
        }
        // Once open, the panel follows the screen, as when the keyboard takes part of it.
        LaunchedEffect(panel) {
            if (open && shown.value > 0f) {
                left.snapTo(panel.left); right.snapTo(panel.right); top.snapTo(panel.top); bottom.snapTo(panel.bottom)
            }
        }

        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = dim.value }.background(Color(0x59000000))
                .clickable(remember { MutableInteractionSource() }, null, enabled = open) { onClose() },
        )
        val fill = colors.background
        val rim = pal.line
        // The conversation stays built while the chat is closed, so opening only has to animate.
        Canvas(Modifier.fillMaxSize()) {
            if (!grown) return@Canvas
            val r = CornerRadius(round.value)
            val at = Offset(left.value, top.value)
            val box = Size(right.value - left.value, bottom.value - top.value)
            drawRoundRect(fill, at, box, r)
            drawRoundRect(rim, at, box, r, style = Stroke(1.dp.toPx()))
        }
        Column(
            Modifier.offset { IntOffset(panel.left.roundToInt(), panel.top.roundToInt()) }
                .size(with(density) { panel.width.toDp() }, with(density) { panel.height.toDp() })
                .clickable(remember { MutableInteractionSource() }, null, enabled = open) {}
                .padding(start = 12.dp, end = 12.dp, top = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp).graphicsLayer { alpha = header.value; translationY = (1f - header.value) * 6.dp.toPx() },
            ) {
                Text(stringResource(R.string.agent_pet_name), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                    modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.weight(1f))
                HeaderAction(Icons.Outlined.Close, stringResource(R.string.agent_close), size = 34.dp, tag = "agent-pet-close", onClick = onClose)
            }
            AgentPage(Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 6.dp.toPx() }, visible = open, startTyping = AgentHost.entries.isEmpty(), hidesPet = false)
        }
    }
}

@Composable
internal fun PetCloseTarget(over: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(4.dp).size(if (over) 84.dp else 68.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color(0xCC0E141B))
            .border(2.dp, if (over) Color(0xFFFF5A5F) else Color(0x66FFFFFF), androidx.compose.foundation.shape.CircleShape),
    ) { Icon(Icons.Outlined.Close, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
}
