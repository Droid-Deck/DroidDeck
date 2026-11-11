package com.droiddeck.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.droiddeck.launcher.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    AppDialog(shown, close, "hudReadings", wide = false, scrollable = false, modifier = Modifier.fillMaxHeight()) {
        val state = rememberLazyListState()
        val handlePx = with(LocalDensity.current) { HANDLE_WIDTH.toPx() }
        val save = { onSave(enabled.toMap(), merged()); close() }
        val restore = { onReset(); close() }
        Column(Modifier.fillMaxSize()) {
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
                        val held = index == dragging
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).testTag("hud-row-$key")
                                .zIndex(if (held) 1f else 0f)
                                .graphicsLayer { translationY = if (held) dragOffset else 0f }
                                .clip(Shape12)
                                .background(
                                    if (held) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.surface,
                                )
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
                            ToggleSwitch(enabled[key] == true, true, null) { enabled[key] = !(enabled[key] == true) }
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
