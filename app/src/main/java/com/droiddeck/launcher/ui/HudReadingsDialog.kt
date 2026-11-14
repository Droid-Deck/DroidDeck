package com.droiddeck.launcher.ui

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.droiddeck.launcher.R
import kotlin.math.roundToInt

private val ROW_HEIGHT = 44.dp
private val HANDLE_WIDTH = 40.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HudReadingsDialog(
    visible: List<String>,
    initialOrder: List<String>,
    initialOn: Map<String, Boolean>,
    onDismiss: () -> Unit,
    onSave: (Map<String, Boolean>, List<String>) -> Unit,
    onReset: () -> Unit,
) {
    val order = remember { mutableStateListOf<String>().apply { addAll(initialOrder.filter { it in visible }) } }
    val enabled = remember { mutableStateMapOf<String, Boolean>() }
    remember(initialOn) { for (key in initialOrder) enabled[key] = initialOn[key] == true; true }
    val merged = {
        val moved = order.toList()
        var i = 0
        initialOrder.map { if (it in visible) moved[i++] else it }
    }
    val shown = rememberShown { onDismiss() }
    val close = { shown.targetState = false }
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    var dragging by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    val panelFocus = remember { FocusRequester() }
    val firstKey = remember { order.firstOrNull() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { panelFocus.requestFocus() }
        repeat(30) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var grabbed by remember { mutableStateOf<String?>(null) }
    val before = remember { mutableStateListOf<String>() }
    val grab = {
        val target = focusedKey ?: order.firstOrNull()
        if (target != null && grabbed == null) {
            before.clear()
            before.addAll(order)
            grabbed = target
        }
    }
    val cancel = {
        val held = grabbed
        if (held != null) {
            order.clear()
            order.addAll(before)
            grabbed = null
        }
    }

    AppDialog(
        shown, close, "hudReadings", wide = false, scrollable = false,
        modifier = Modifier.fillMaxHeight(),
        back = { if (grabbed != null) cancel() else close() },
    ) {
        val state = rememberLazyListState()
        val handlePx = with(LocalDensity.current) { HANDLE_WIDTH.toPx() }
        val save = { onSave(enabled.toMap(), merged()); close() }
        val restore = { onReset(); close() }
        Column(
            Modifier.fillMaxSize().focusRequester(panelFocus).focusable()
                .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                val down = key.action == KeyEvent.ACTION_DOWN
                val first = down && key.repeatCount == 0
                val held = grabbed
                when {
                    key.keyCode == KeyEvent.KEYCODE_BUTTON_Y -> {
                        if (first) {
                            if (held == null) grab() else grabbed = null
                        }
                        true
                    }
                    key.keyCode == KeyEvent.KEYCODE_BUTTON_A -> {
                        val target = focusedKey
                        when {
                            held != null -> {
                                if (first) grabbed = null
                                true
                            }
                            target == null -> false
                            else -> {
                                if (first) enabled[target] = !(enabled[target] == true)
                                true
                            }
                        }
                    }
                    held == null -> false
                    key.keyCode == KeyEvent.KEYCODE_DPAD_UP || key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (down) {
                            val from = order.indexOf(held)
                            val step = if (key.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                            val to = (from + step).coerceIn(0, order.lastIndex)
                            if (to != from) order.add(to, order.removeAt(from))
                        }
                        true
                    }
                    else -> false
                }
            },
        ) {
            val list = @Composable { m: Modifier ->
                LazyColumn(state = state, modifier = m.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val item = state.layoutInfo.visibleItemsInfo.firstOrNull {
                            it.offset <= down.position.y && down.position.y < it.offset + it.size
                        }
                        if (item == null || down.position.x > handlePx) return@awaitEachGesture
                        val start = item.index
                        dragging = start
                        dragOffset = 0f
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            val travel = change.position.y - down.position.y
                            change.consume()
                            if (!change.pressed) break
                            val target = (start + (travel / rowPx).roundToInt()).coerceIn(0, order.lastIndex)
                            while (target != dragging) {
                                val step = if (target > dragging) 1 else -1
                                order.add(dragging + step, order.removeAt(dragging))
                                dragging += step
                            }
                            dragOffset = travel - (dragging - start) * rowPx
                        }
                        dragging = -1
                        dragOffset = 0f
                    }
                }) {
                    itemsIndexed(order, key = { _, key -> key }) { index, key ->
                        val held = index == dragging || key == grabbed
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).testTag("hud-row-$key")
                                .then(if (key == firstKey) Modifier.focusRequester(firstFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused) focusedKey = key else if (focusedKey == key) focusedKey = null }
                                .zIndex(if (held) 1f else 0f)
                                .graphicsLayer { translationY = if (index == dragging) dragOffset else 0f }
                                .clip(Shape12)
                                .background(
                                    if (held) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.surface,
                                )
                                .then(
                                    if (key == focusedKey) Modifier.border(2.dp, LocalPalette.current.signal, Shape12)
                                    else Modifier,
                                )
                                .focusable()
                                .padding(start = 4.dp, end = 6.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(width = HANDLE_WIDTH, height = ROW_HEIGHT).testTag("hud-handle-$key"),
                            ) {
                                Icon(
                                    Icons.Filled.DragHandle, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            val label = hudMetricLabel(key)
                            Text(
                                if (label != null) stringResource(label) else key,
                                fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 1,
                                modifier = Modifier.weight(1f).padding(start = 2.dp),
                            )
                            ToggleSwitch(
                                enabled[key] == true, true, null,
                                modifier = Modifier.testTag("hud-switch-$key").focusProperties { canFocus = false },
                            ) { enabled[key] = !(enabled[key] == true) }
                        }
                    }
                }
            }
            list(Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.common_ok), compact = true, onClick = save)
                SecondaryButton(stringResource(R.string.hud_reset_button), compact = true, onClick = restore)
            }
        }
    }
}
