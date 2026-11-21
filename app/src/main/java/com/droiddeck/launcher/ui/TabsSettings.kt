package com.droiddeck.launcher.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.droiddeck.launcher.R
import kotlin.math.roundToInt

private const val MORE_LINE = "|more|"
private val TAB_ROW = 52.dp
private val TAB_HANDLE = 44.dp

internal fun tabRows(bar: List<String>, more: List<String>) = bar + MORE_LINE + more

internal fun moveTabRow(rows: List<String>, key: String, step: Int): List<String>? {
    val from = rows.indexOf(key)
    val to = from + step
    if (from < 0 || to !in rows.indices) return null
    return rows.toMutableList().apply { add(to, removeAt(from)) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TabsSettingsGroup(host: MenuHost, s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    val reveal = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        if (!SetupTabs.focusTabs) return@LaunchedEffect
        SetupTabs.focusTabs = false
        androidx.compose.runtime.withFrameNanos { }
        reveal.bringIntoView()
    }
    val state by rememberUpdatedState(s)
    val rows = {
        val keys = NavTabs.listed(state)
        tabRows(keys.filter { it !in NavTabs.shelved }, keys.filter { it in NavTabs.shelved })
    }
    val commit = { next: List<String> ->
        val split = next.indexOf(MORE_LINE)
        NavTabs.rearrange(ctx, state, next.subList(0, split), next.subList(split + 1, next.size))
    }
    fun setShown(key: String, show: Boolean) {
        NavTabs.setHidden(ctx, key, !show)
    }

    Box(Modifier.bringIntoViewRequester(reveal)) {
        SettingsGroup(stringResource(R.string.setup_group_tabs)) {
            SortableRows(
                rows = rows, commit = commit, movable = { it != MORE_LINE }, rowHeight = TAB_ROW, handleWidth = TAB_HANDLE,
                onActivate = { tab -> if (NavTabs.hideable(tab)) setShown(tab, NavTabs.place(state, tab) == TabPlace.HIDDEN) },
            ) { tab, held, offset, hint, modifier, onFocus ->
                if (tab == MORE_LINE) MoreLine()
                else TabRow(
                    tab, NavTabs.place(s, tab) != TabPlace.HIDDEN, NavTabs.hideable(tab), held, offset, hint, modifier, onFocus,
                ) { host.open = null; setShown(tab, it) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SortableRows(
    rows: () -> List<String>,
    commit: (List<String>) -> Unit,
    movable: (String) -> Boolean,
    rowHeight: androidx.compose.ui.unit.Dp,
    handleWidth: androidx.compose.ui.unit.Dp,
    onActivate: (String) -> Unit,
    row: @Composable (key: String, held: Boolean, offset: Float, hint: String?, modifier: Modifier, onFocus: (Boolean) -> Unit) -> Unit,
) {
    val pad = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var grabbed by remember { mutableStateOf<String?>(null) }
    var before by remember { mutableStateOf<List<String>>(emptyList()) }
    var focused by remember { mutableStateOf<String?>(null) }
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    val handlePx = with(LocalDensity.current) { handleWidth.toPx() }
    val list = rows()
    Column(
        Modifier.fillMaxWidth().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = (down.position.y / rowPx).toInt()
                val key = rows().getOrNull(start)
                if (key == null || !movable(key) || down.position.x > handlePx || grabbed != null) return@awaitEachGesture
                down.consume()
                dragging = key
                dragOffset = 0f
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    val travel = change.position.y - down.position.y
                    val current = rows()
                    val target = (start + (travel / rowPx).roundToInt()).coerceIn(0, current.lastIndex)
                    var at = current.indexOf(key)
                    var next = current
                    while (target != at) {
                        next = moveTabRow(next, key, if (target > at) 1 else -1) ?: break
                        at = next.indexOf(key)
                    }
                    if (next !== current) commit(next)
                    dragOffset = travel - (at - start) * rowPx
                }
                dragging = null
                dragOffset = 0f
            }
        },
    ) {
        list.forEachIndexed { index, item ->
            key(item) {
                if (!movable(item)) row(item, false, 0f, null, Modifier) {}
                else {
                    val bring = remember { BringIntoViewRequester() }
                    LaunchedEffect(index, grabbed) { if (grabbed == item) bring.bringIntoView() }
                    row(
                        item, item == dragging || item == grabbed, if (item == dragging) dragOffset else 0f,
                        if (pad && focused == item) stringResource(if (grabbed == item) R.string.hud_hint_drop else R.string.hud_hint_grab) else null,
                        Modifier.bringIntoViewRequester(bring).onPreviewKeyEvent { event ->
                            val e = event.nativeKeyEvent
                            val down = e.action == KeyEvent.ACTION_DOWN
                            val first = down && e.repeatCount == 0
                            val mine = grabbed == item
                            when (e.keyCode) {
                                KeyEvent.KEYCODE_BUTTON_Y -> {
                                    if (first) {
                                        if (mine) grabbed = null
                                        else if (grabbed == null) { before = rows(); grabbed = item }
                                    }
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    if (mine && down) moveTabRow(rows(), item, if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)?.let(commit)
                                    mine
                                }
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> mine
                                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                                    if (mine && first) { commit(before); grabbed = null }
                                    mine
                                }
                                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                                    if (first) {
                                        if (mine) grabbed = null else onActivate(item)
                                    }
                                    true
                                }
                                else -> false
                            }
                        },
                    ) { if (it) focused = item else if (focused == item) { focused = null; if (grabbed == item) grabbed = null } }
                }
            }
        }
    }
}

@Composable
private fun MoreLine() {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth().height(TAB_ROW).testTag("tab-more-line")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).background(colors.surfaceVariant.copy(alpha = 0.35f)).padding(horizontal = 14.dp),
        ) {
            Icon(railLabelIcon(NavTabs.MORE).second, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.rail_more).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp, color = colors.onSurfaceVariant,
            )
            Box(Modifier.weight(1f).height(1.dp).background(pal.line2))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
    }
}

@Composable
private fun TabRow(
    tab: String, shown: Boolean, hideable: Boolean, held: Boolean, offset: Float, hint: String?,
    modifier: Modifier, onFocus: (Boolean) -> Unit, onShown: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val (labelRes, icon) = railLabelIcon(tab)
    val label = stringResource(labelRes)
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Column(
        modifier.fillMaxWidth().height(TAB_ROW).testTag("tab-row-$tab")
            .zIndex(if (held) 1f else 0f)
            .graphicsLayer { translationY = offset }
            .background(if (held) colors.surfaceVariant else colors.surface)
            .glideBorder(hot, Shape12, pal.signal)
            .onFocusChanged { onFocus(it.isFocused) }
            .hoverable(src)
            .focusable(interactionSource = src),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(end = 10.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(width = TAB_HANDLE, height = TAB_ROW).testTag("tab-handle-$tab")) {
                Icon(Icons.Filled.DragHandle, null, tint = if (held) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).alpha(if (shown) 1f else 0.5f),
            ) {
                Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hint != null) KeyHint("Y", hint)
            ToggleSwitch(
                shown, hideable, label,
                Modifier.testTag("tab-shown-$tab").focusProperties { canFocus = false },
                onChange = onShown,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (held) Color.Transparent else pal.line))
    }
}
