package com.droiddeck.launcher.ui

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

class DualPanelModel(
    val keys: List<String>,
    val selected: String,
    val badges: Set<String>,
    val storeDownloads: Int,
    val running: String?,
    val theme: String,
    val appScale: Int,
    val onSelect: (String) -> Unit,
    val onResume: () -> Unit,
)

object DualScreenPanel {
    var model by mutableStateOf<DualPanelModel?>(null)
    private var displayId = -1
    private var activity: DualScreenActivity? = null
        set(value) { field = value; available = value != null || launching }
    private var launching = false
        set(value) { field = value; available = value || activity != null }
    private var ownerTask = -1
    private var ownerYielded = false

    var typing by mutableStateOf<TypingRequest?>(null)
        private set
    var typingShown by mutableStateOf(false)
        private set

    val shownOn: Int get() = if (activity != null || launching) displayId else -1
    val attached: Boolean get() = activity != null
    var available by mutableStateOf(false)
        private set

    fun startTyping(request: TypingRequest) {
        typing = request
        typingShown = true
    }

    fun stopTyping() {
        typing = null
        typingShown = false
    }

    fun stopTyping(request: TypingRequest) {
        if (typing === request) stopTyping()
    }

    fun showTyping() {
        if (typing != null) typingShown = true
    }

    fun finishTyping() {
        val request = typing
        hideTyping()
        request?.close()
    }

    fun hideTyping() {
        typingShown = false
    }

    fun updateTyping(value: TextFieldValue) {
        val request = typing ?: return
        if (request.value != value) request.value = value
    }

    fun show(context: Context, display: Int, task: Int) {
        if (shownOn == display) { ownerTask = task; return }
        hide()
        displayId = display
        ownerTask = task
        ownerYielded = false
        launching = true
        val intent = Intent(context, DualScreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching {
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
        }.onFailure { launching = false; displayId = -1 }
    }

    fun hide() {
        stopTyping()
        launching = false
        displayId = -1
        activity?.finishAndRemoveTask()
        activity = null
    }

    fun hide(task: Int) {
        if (ownerTask == task || ownerTask < 0) hide()
    }

    internal fun attach(host: DualScreenActivity): Boolean {
        if (!launching && activity == null) return false
        if (activity !== host) activity?.finishAndRemoveTask()
        activity = host
        launching = false
        return true
    }

    // The panel resumes when an app opens over the owner on the primary screen; pulling the owner back would hide that app.
    fun yieldTo(task: Int, app: Boolean) {
        if (ownerTask == task) ownerYielded = app
    }

    internal fun returnFocus(context: Context) {
        if (ownerYielded) return
        val task = ownerTask.takeIf { it >= 0 } ?: return
        runCatching { (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).moveTaskToFront(task, 0) }
    }

    internal fun reclaimFocus(context: Context) {
        if (ownerYielded) return
        val task = ownerTask.takeIf { it >= 0 } ?: return
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        runCatching {
            manager.appTasks.firstOrNull {
                @Suppress("DEPRECATION")
                (if (android.os.Build.VERSION.SDK_INT >= 29) it.taskInfo.taskId else it.taskInfo.persistentId) == task
            }?.moveToFront() ?: manager.moveTaskToFront(task, 0)
        }
    }

    internal fun detach(host: DualScreenActivity) {
        if (activity === host) {
            activity = null
            displayId = -1
        }
    }
}

class DualScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DualScreenPanel.attach(this)) {
            finishAndRemoveTask()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()
        setContent {
            val model = DualScreenPanel.model
            val request = DualScreenPanel.typing
            val typingOn = request != null && DualScreenPanel.typingShown
            LaunchedEffect(typingOn) { acceptFocus(typingOn) }
            DroidDeckTheme(model?.theme, appScale = model?.appScale ?: com.droiddeck.launcher.core.AppUiPrefs.DEFAULT_SCALE) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (request != null && typingOn) TypingPanel(request) { if (DualScreenPanel.typing === request) DualScreenPanel.finishTyping() }
                    else if (model != null) DualPanelContent(model)
                }
            }
        }
    }

    private fun acceptFocus(on: Boolean) {
        if (on) {
            unfocusPending = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            runCatching { (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).moveTaskToFront(taskId, 0) }
        } else {
            unfocusPending = true
            DualScreenPanel.returnFocus(this)
            window.decorView.postDelayed({
                if (!unfocusPending || DualScreenPanel.typingShown) return@postDelayed
                if (window.decorView.hasWindowFocus()) DualScreenPanel.returnFocus(this) else dropFocus()
            }, 400)
        }
    }

    private var unfocusPending = false

    private fun dropFocus() {
        unfocusPending = false
    }

    override fun onResume() {
        super.onResume()
        if (!DualScreenPanel.typingShown) DualScreenPanel.returnFocus(this)
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isTopResumedActivity && !DualScreenPanel.typingShown) DualScreenPanel.returnFocus(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
            if (!DualScreenPanel.typingShown) DualScreenPanel.reclaimFocus(this)
        } else if (unfocusPending && !DualScreenPanel.typingShown) dropFocus()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (DualScreenPanel.typingShown) DualScreenPanel.finishTyping()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (DualScreenPanel.typingShown && event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) DualScreenPanel.finishTyping()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        DualScreenPanel.detach(this)
        super.onDestroy()
    }

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}

@Composable
internal fun DualPanelContent(model: DualPanelModel) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.signal)
            Spacer(Modifier.size(12.dp))
            val (label, _) = railLabelIcon(model.selected)
            Text(stringResource(label), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            PanelClock()
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = if (model.keys.size > 6) 4 else 3
            val rows = (model.keys.size + columns - 1) / columns
            val gap = 12.dp
            val tileHeight = ((maxHeight - gap * (rows - 1)) / rows).coerceAtLeast(64.dp)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                for (row in model.keys.chunked(columns)) {
                    Row(Modifier.fillMaxWidth().height(tileHeight), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (key in row) PanelTile(key, key == model.selected, key in model.badges,
                            if (key == "stores") model.storeDownloads else 0, Modifier.weight(1f)) { model.onSelect(key) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (model.running != null) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(pal.good.copy(alpha = 0.18f))
                    .clickable(role = Role.Button, onClick = model.onResume).testTag("dual-resume").padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(pal.good))
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.rail_resume_named, model.running), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PanelTile(key: String, current: Boolean, badge: Boolean, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val (label, icon) = railLabelIcon(key)
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.fillMaxSize().clip(shape)
            .background(if (current) pal.signal.copy(alpha = 0.16f) else colors.surface)
            .border(if (current) 2.dp else 1.dp, if (current) pal.signal else Color.White.copy(alpha = 0.06f), shape)
            .clickable(role = Role.Tab, onClick = onClick).testTag("dual-rail-$key"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = if (current) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(34.dp))
            Text(stringResource(label), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = if (current) pal.signal else colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp))
        }
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(10.dp).clip(CircleShape).background(AttentionAmber))
        if (count > 0) Text(
            if (count > 9) "9+" else count.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pal.onSignal,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(pal.signal)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun PanelClock() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000L - now % 60_000L)
        }
    }
    Text(android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)), fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun DualPanelPublisher(s: FrontEndState, selected: String, onSelect: (String) -> Unit, a: FrontEndActions) {
    val badges = buildSet {
        if (setupNeedsAttention(s)) add("setup")
        if (s.updates.hasUpdate) add("updates")
    }
    val model = DualPanelModel(navigationKeys(s), selected, badges, s.storeDownloadsActive, s.running, s.theme, s.appScale, onSelect, a.onResume)
    androidx.compose.runtime.SideEffect { DualScreenPanel.model = model }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { DualScreenPanel.model = null } }
}

internal fun dualPageStep(event: android.view.KeyEvent): Int? = when (event.keyCode) {
    android.view.KeyEvent.KEYCODE_BUTTON_L2 -> -1
    android.view.KeyEvent.KEYCODE_BUTTON_R2 -> 1
    else -> null
}
