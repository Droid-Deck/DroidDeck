package com.droiddeck.launcher.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.agent.AgentHost
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A DroidDeck window the pet can live in when it may not float over other apps. */
internal interface AgentPetHost {
    /** Something already covers the pet's place, such as the session drawer. */
    val petBlocked: Boolean
    fun openAgent()
}

/**
 * DeeDee, the agent's pet: shows what the agent is doing and opens a chat with it. With the
 * "display over other apps" permission it floats over every app in windows of its own
 * ([PetWindows]) and opens a floating chat; without it, it lives in DroidDeck's own windows and
 * opens the Agent page there.
 */
internal object AgentPet {
    var enabled by mutableStateOf(false)
        private set
    /** May draw over other apps. */
    var allowed by mutableStateOf(false)
        private set
    /** Agent pages on screen; the pet steps aside while the conversation itself is in view. */
    var agentOnScreen by mutableIntStateOf(0)
    var now by mutableLongStateOf(SystemClock.elapsedRealtime())
        private set
    /** When the agent last changed what it was doing. */
    var since by mutableLongStateOf(SystemClock.elapsedRealtime())
        private set
    val floating get() = enabled && allowed
    private var windows: PetWindows? = null
    private val main = Handler(Looper.getMainLooper())
    private const val TAG = "agent-pet"
    private const val KEY = "pet"
    private const val SIDE = "pet_side"
    private const val HEIGHT = "pet_y"

    fun set(context: Context, on: Boolean) {
        enabled = on
        AgentHost.prefs(context).edit().putBoolean(KEY, on).apply()
    }

    /** Opens the system page where the user lets DroidDeck draw over other apps. */
    fun requestFloating(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    internal fun place(context: Context) = AgentHost.prefs(context).let { it.getBoolean(SIDE, true) to it.getFloat(HEIGHT, 0.3f) }

    internal fun save(context: Context, right: Boolean, height: Float) {
        AgentHost.prefs(context).edit().putBoolean(SIDE, right).putFloat(HEIGHT, height).apply()
    }

    fun install(app: Application) {
        enabled = AgentHost.prefs(app).getBoolean(KEY, false)
        allowed = PetWindows.allowed(app)
        val scope = MainScope()
        scope.launch {
            snapshotFlow { Triple(AgentHost.state, AgentHost.entries.size, AgentHost.lab?.running) }.collect { since = SystemClock.elapsedRealtime() }
        }
        scope.launch {
            snapshotFlow { floating && AgentHost.installed }.collect { show ->
                if (show && windows == null) windows = runCatching { PetWindows(app) }.getOrNull()
                if (!show) windows?.close()?.also { windows = null }
            }
        }
        tick(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                allowed = PetWindows.allowed(activity)
                attach(activity)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun tick(app: Application) {
        now = SystemClock.elapsedRealtime()
        // The permission can be granted or taken back in Settings while DroidDeck is away.
        if (enabled) allowed = PetWindows.allowed(app)
        main.postDelayed({ tick(app) }, 1_000)
    }

    private fun attach(activity: Activity) {
        val host = activity as? AgentPetHost ?: return
        val decor = activity.window.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(TAG) != null) return
        val layer = ComposeView(activity).apply {
            tag = TAG
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setContent { PetLayer(host) }
        }
        decor.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

private const val SLEEP_AFTER_MS = 5 * 60_000L
private const val CHEER_MS = 10_000L
internal const val PREVIEW_MS = 6_000L

/** What the agent is up to, as the pet shows it; [since] is when the agent last changed what it was doing. */
internal fun petState(now: Long, since: Long): PetState {
    if (AgentHost.state == AgentHost.State.WORKING || AgentHost.lab?.running == true) return PetState.WORKING
    val last = AgentHost.entries.lastOrNull { it is AgentHost.Entry.Finished } as? AgentHost.Entry.Finished
    if (AgentHost.unseen && last != null && !last.stopped) {
        if (last.error != null) return PetState.ERROR
        val said = (AgentHost.entries.lastOrNull { it is AgentHost.Entry.Said } as? AgentHost.Entry.Said)?.text?.trimEnd().orEmpty()
        if (last.planned || said.endsWith("?")) return PetState.QUESTION
        if (now - since < CHEER_MS) return PetState.DONE
    }
    return if (now - since > SLEEP_AFTER_MS) PetState.SLEEP else PetState.IDLE
}

internal fun petStateNow() = petState(AgentPet.now, AgentPet.since)

/** The line the preview bubble shows while the pet has news, or null when it has none. */
internal fun petBubbleText(): String? {
    if (!AgentHost.unseen) return null
    val state = petStateNow()
    if (state != PetState.DONE && state != PetState.QUESTION && state != PetState.ERROR) return null
    val last = AgentHost.entries.lastOrNull { it is AgentHost.Entry.Finished } as? AgentHost.Entry.Finished
    last?.error?.let { return it }
    val said = AgentHost.entries.lastOrNull { it is AgentHost.Entry.Said } as? AgentHost.Entry.Said ?: return null
    return said.text.lineSequence().map { it.trim().trimStart('#', '*', '-', ' ') }.firstOrNull { it.isNotEmpty() }
}

/** DeeDee in [state], with a dot while a reply waits to be read. */
@Composable
internal fun PetFigure(state: PetState, modifier: Modifier) {
    Box(modifier.testTag("agent-pet")) {
        Box(Modifier.fillMaxSize().padding(4.dp).clip(CircleShape).background(Color(0xEB0E141B)).border(1.dp, Color(0x29FFFFFF), CircleShape))
        DeeDee(state, Modifier.fillMaxSize().padding(6.dp))
        if (AgentHost.unseen && (state == PetState.IDLE || state == PetState.SLEEP)) Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(14.dp).clip(CircleShape).background(Color(0xFF1A9FFF)).border(2.dp, Color(0xFF0E141B), CircleShape),
        )
    }
}

@Composable
internal fun PetPreview(text: String, modifier: Modifier, onTap: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Text(
        text, fontSize = 14.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.widthIn(max = 260.dp).clip(shape).background(Color(0xF0171D25)).border(1.dp, Color(0x33FFFFFF), shape)
            .pointerInput(Unit) { detectTapGestures { onTap() } }.padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** The pet inside one DroidDeck window, for when it may not float over other apps. */
@Composable
private fun PetLayer(host: AgentPetHost) {
    val shown = AgentPet.enabled && !AgentPet.floating && AgentHost.installed && AgentPet.agentOnScreen == 0 && !host.petBlocked
    if (!shown) return
    val context = LocalContext.current
    val motion = remember { PetMotion(context) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        motion.fit(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        var bubble by remember { mutableStateOf<String?>(null) }
        val news = petBubbleText()
        LaunchedEffect(news) {
            bubble = news
            if (news != null) {
                delay(PREVIEW_MS)
                bubble = null
            }
        }
        if (motion.dragging) Box(Modifier.offset {
            val (tx, ty) = motion.target
            IntOffset((tx - motion.size / 2).roundToInt(), (ty - motion.size / 2).roundToInt())
        }) { PetCloseTarget(motion.overTarget) }
        val text = bubble
        if (text != null && !motion.dragging) {
            var size by remember { mutableStateOf(IntSize.Zero) }
            val gap = with(density) { 10.dp.toPx() }
            PetPreview(text, Modifier.onSizeChanged { size = it }.offset {
                val x = if (motion.right) motion.x - gap - size.width else motion.x + motion.size + gap
                IntOffset(x.roundToInt(), (motion.y + motion.size / 2 - size.height / 2).roundToInt())
            }) {
                bubble = null
                host.openAgent()
            }
        }
        PetFigure(
            petStateNow(),
            Modifier.offset { IntOffset(motion.x.roundToInt(), motion.y.roundToInt()) }
                .size(with(density) { motion.size.toDp() })
                .pointerInput(Unit) { detectTapGestures { bubble = null; host.openAgent() } }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { bubble = null; motion.start() },
                        onDrag = { change, amount -> change.consume(); motion.move(amount.x, amount.y) },
                        onDragEnd = { if (motion.end()) AgentPet.set(context, false) },
                        onDragCancel = { motion.end() },
                    )
                },
        )
    }
}
