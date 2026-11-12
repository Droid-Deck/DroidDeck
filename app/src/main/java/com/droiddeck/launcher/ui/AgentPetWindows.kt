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
import androidx.compose.ui.graphics.Path
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
 * nearer side edge, or dropped on the close target to turn it off. While its chat is open it is
 * docked at the top corner, the way a chat head hops up when its conversation opens, and goes home
 * when the chat closes. Positions are screen pixels.
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
    var width by mutableFloatStateOf(0f)
        private set
    var height by mutableFloatStateOf(0f)
        private set
    /** Docked for the chat; where to go back to is kept as a side and a relative height. */
    var docked by mutableStateOf(false)
        private set
    private val density = context.resources.displayMetrics.density
    val size = 76 * density
    private val margin = 6 * density
    private val inset = 24 * density
    private var settle: ValueAnimator? = null
    private val spring = FlingSpring { sx, sy -> x = sx; y = sy }

    val dockX get() = width - size - margin

    private companion object {
        /** The share of the pet that hangs past the edge it rests against. */
        const val TUCK = 0.2f
    }
    val dockY get() = inset
    val target get() = Pair(width / 2, height - 72 * density)
    val overTarget get() = dragging && hypot(x + size / 2 - target.first, y + size / 2 - target.second) < size

    /** Fits the pet into a [w] by [h] screen, keeping its side and relative height, as after a rotation. */
    fun fit(w: Float, h: Float) {
        if (w <= 0 || h <= 0 || (w == width && h == height)) return
        val first = width == 0f
        val fraction = if (first) AgentPet.place(context).also { right = it.first }.second else if (docked) home else fractionOf(y)
        settle?.cancel()
        spring.cancel()
        width = w
        height = h
        home = fraction
        if (docked) {
            x = dockX
            y = dockY
        } else {
            x = edge(right)
            y = inset + travel() * fraction
        }
    }

    private var home = 0f
    private fun travel() = (height - size - inset * 2).coerceAtLeast(0f)
    private fun fractionOf(at: Float) = if (travel() > 0) ((at - inset) / travel()).coerceIn(0f, 1f) else 0f
    /** At rest the pet tucks part of itself past the screen edge, as a chat head does. */
    private fun edge(side: Boolean) = if (side) width - size * (1f - TUCK) else -size * TUCK

    /** Hops up to the top corner for the chat. */
    fun dock() {
        if (docked) return
        home = fractionOf(y)
        docked = true
        glide(dockX, dockY, 260, OvershootInterpolator(0.9f))
    }

    /** Goes back to where it was before the chat opened. */
    fun undock() {
        if (!docked) return
        docked = false
        glide(edge(right), inset + travel() * home, 320, android.view.animation.DecelerateInterpolator(1.6f))
    }

    fun start() {
        settle?.cancel()
        spring.cancel()
        docked = false
        dragging = true
    }

    fun move(dx: Float, dy: Float) {
        x += dx
        y += dy
    }

    /** A drag the system took away: the pet goes back to an edge, never to the close target. */
    fun cancel() {
        if (!dragging) return
        dragging = false
        right = x + size / 2 > width / 2
        val toY = y.coerceIn(inset, (height - size - inset).coerceAtLeast(inset))
        home = fractionOf(toY)
        spring.start(x, y, 0f, 0f, edge(right), toY)
    }

    /** Ends a drag let go at [vx], [vy] pixels per second; true when the pet was dropped on the close target. */
    fun end(vx: Float = 0f, vy: Float = 0f): Boolean {
        val dismissed = overTarget
        dragging = false
        if (dismissed) return true
        right = EdgeFling.toRight(x + size / 2, width, vx, density)
        val toY = EdgeFling.restY(y, vy, inset, height - size - inset)
        home = fractionOf(toY)
        AgentPet.save(context, right, home)
        spring.start(x, y, vx, vy, edge(right), toY)
        return false
    }

    private fun glide(toX: Float, toY: Float, ms: Long, curve: android.animation.TimeInterpolator) {
        settle?.cancel()
        spring.cancel()
        val fromX = x
        val fromY = y
        settle = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (ms * Motion.scale).toLong().coerceAtLeast(0)
            interpolator = curve
            addUpdateListener {
                val f = it.animatedValue as Float
                x = fromX + (toX - fromX) * f
                y = fromY + (toY - fromY) * f
            }
            start()
        }
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
    private val turns = object : android.content.ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = fitScreen()
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

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
        PetChat(chatOpen, motion, ::closeChat) { showChat(false) }
    }

    init {
        head.view.setOnTouchListener(DragTouch())
        head.view.contentDescription = context.getString(R.string.agent_pet_open)
        head.view.setOnClickListener { if (chatOpen) closeChat() else openChat() }
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
        fitScreen()
        context.registerComponentCallbacks(turns)
        place()
        listOf(target, preview).forEach { setVisible(it, false) }
        showChat(false)
        listOf(chat, target, preview, head).forEach { windows.addView(it.view, it.params) }
        added = true
    }

    private class Window(val view: FrameLayout, val params: WindowManager.LayoutParams, val touchable: Boolean)

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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (full) 0 else WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (full) softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (onSize != null) compose.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> onSize(v.width, v.height) }
        return Window(frame, params, touchable)
    }

    /** A hidden overlay window still owns its frame, which the system can stretch over the screen, so it also stops taking touches. */
    private fun setVisible(window: Window, shown: Boolean) {
        window.view.visibility = if (shown) View.VISIBLE else View.GONE
        setTouchable(window, shown && window.touchable)
    }

    private fun setTouchable(window: Window, on: Boolean) {
        val flags = if (on) window.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else window.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (flags == window.params.flags) return
        window.params.flags = flags
        if (added) runCatching { windows.updateViewLayout(window.view, window.params) }
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

    private fun openChat() {
        bubble = null
        if (chatOpen) return
        motion.dock()
        showChat(true)
        chatOpen = true
    }

    private fun closeChat() {
        if (!chatOpen) return
        chatOpen = false
        motion.undock()
    }

    /**
     * The chat window is always drawn; closed, it waits off the screen and takes neither touches nor
     * keys, so opening it is a move and not a first layout.
     */
    private fun showChat(on: Boolean) {
        chat.params.x = if (on) 0 else OFFSCREEN
        val quiet = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        chat.params.flags = if (on) chat.params.flags and quiet.inv() else chat.params.flags or quiet
        if (added) runCatching { windows.updateViewLayout(chat.view, chat.params) }
    }

    /** The whole screen, the status and navigation bars included, since overlay windows can cover them. */
    private fun fitScreen() {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windows.maximumWindowMetrics.bounds
        else android.graphics.Point().also { @Suppress("DEPRECATION") windows.defaultDisplay.getRealSize(it) }.let { android.graphics.Rect(0, 0, it.x, it.y) }
        motion.fit(bounds.width().toFloat(), bounds.height().toFloat())
    }

    fun close() {
        runCatching { context.unregisterComponentCallbacks(turns) }
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
        private var tracker: android.view.VelocityTracker? = null

        /** The window moves under the finger, so velocity is measured on the screen. */
        private fun track(e: MotionEvent) {
            val onScreen = MotionEvent.obtain(e).apply { setLocation(e.rawX, e.rawY) }
            (tracker ?: android.view.VelocityTracker.obtain().also { tracker = it }).addMovement(onScreen)
            onScreen.recycle()
        }

        private fun release(): Pair<Float, Float> {
            val t = tracker ?: return 0f to 0f
            t.computeCurrentVelocity(1000)
            val v = t.xVelocity to t.yVelocity
            t.recycle()
            tracker = null
            return v
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            track(e)
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
                MotionEvent.ACTION_UP -> {
                    val (vx, vy) = release()
                    if (moved) {
                        if (motion.end(vx, vy)) AgentPet.set(context, false)
                    } else v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    release()
                    if (moved) motion.cancel()
                }
            }
            return true
        }
    }

    companion object {
        private const val OFFSCREEN = 10_000
        fun allowed(context: Context) = Settings.canDrawOverlays(context)
    }
}

/**
 * The floating chat, opened the way a chat head opens its conversation: the pet hops into the top
 * corner while the whole conversation scales up out of it and fades in, and closing shrinks it back
 * into the pet as the pet glides home. In landscape it sits beside the docked pet, in portrait
 * under it. Its contents are built while it is closed, so opening only animates.
 */
@Composable
internal fun PetChat(open: Boolean, motion: PetMotion, onClose: () -> Unit, onClosed: () -> Unit) {
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
        val margin = with(density) { 8.dp.toPx() }
        val gap = with(density) { 6.dp.toPx() }
        val wide = w > h
        val panel = if (wide) {
            val right = motion.dockX - gap
            Rect((right - with(density) { 900.dp.toPx() }).coerceAtLeast(margin), motion.dockY, right, h - margin)
        } else {
            Rect(margin, motion.dockY + motion.size + gap, w - margin, h - margin)
        }
        val grow = remember { Animatable(0f) }
        val fade = remember { Animatable(0f) }
        LaunchedEffect(open) {
            if (open) coroutineScope {
                launch { grow.animateTo(1f, Motion.sp(0.82f, 520f)) }
                launch { fade.animateTo(1f, Motion.tw(140)) }
            } else if (fade.value > 0f) {
                coroutineScope {
                    launch { grow.animateTo(0f, Motion.tw(170, easing = androidx.compose.animation.core.FastOutLinearInEasing)) }
                    launch { delay(Motion.ms(40).toLong()); fade.animateTo(0f, Motion.tw(130)) }
                }
                onClosed()
            }
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Color(0x8C000000))
                .clickable(remember { MutableInteractionSource() }, null, enabled = open) { onClose() },
        )
        Column(
            Modifier.offset { IntOffset(panel.left.roundToInt(), panel.top.roundToInt()) }
                .size(with(density) { panel.width.toDp() }, with(density) { panel.height.toDp() })
                .graphicsLayer {
                    // Grows from where the pet is right now, so the two travel together while the pet hops to its corner.
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                        ((motion.x + motion.size / 2 - panel.left) / panel.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                        ((motion.y + motion.size / 2 - panel.top) / panel.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
                    )
                    val s = 0.12f + 0.88f * grow.value
                    scaleX = s
                    scaleY = s
                    alpha = fade.value
                }
                .clip(Shape16).background(colors.background).border(1.dp, pal.line, Shape16)
                .clickable(remember { MutableInteractionSource() }, null, enabled = open) {}
                .padding(start = 12.dp, end = 12.dp, top = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Text(stringResource(R.string.agent_pet_name), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                    modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.weight(1f))
                HeaderAction(Icons.Outlined.Close, stringResource(R.string.agent_close), size = 34.dp, tag = "agent-pet-close", onClick = onClose)
            }
            AgentPage(Modifier.weight(1f).fillMaxWidth(), visible = open, startTyping = AgentHost.entries.isEmpty(), hidesPet = false)
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
