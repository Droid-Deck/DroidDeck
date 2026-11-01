package com.droiddeck.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.input.pointer.pointerInput
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
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    val order = remember { mutableStateListOf<String>().apply { addAll(initialOrder.filter { it in visible }) } }
    val enabled = remember { mutableStateMapOf<String, Boolean>() }
    remember(initialOn) { for (key in initialOrder) enabled[key] = initialOn[key] == true; true }
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    var dragging by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    AppDialog(shown, close, "hudReadings", wide = false, scrollable = false, modifier = Modifier.fillMaxHeight()) {
        val state = rememberLazyListState()
        // Holding the row past an edge keeps pulling the list; the finger has nowhere left to go.
        LaunchedEffect(dragging) {
            if (dragging < 0) return@LaunchedEffect
            while (isActive) {
                val info = state.layoutInfo
                val top = info.viewportStartOffset
                val bottom = info.viewportEndOffset
                val item = info.visibleItemsInfo.firstOrNull { it.index == dragging }
                val step = when {
                    item == null -> 0f
                    item.offset + dragOffset < top -> -rowPx / 4f
                    item.offset + dragOffset + item.size > bottom -> rowPx / 4f
                    else -> 0f
                }
                if (step != 0f) {
                    val moved = state.scrollBy(step)
                    if (moved != 0f) dragOffset -= moved
                }
                delay(16)
            }
        }
        // Rows this size does not draw keep their slots: only the visible ones are re-sequenced, so
        // switching to another size finds its own order untouched.
        val merged = {
            val moved = order.toList()
            var i = 0
            initialOrder.map { if (it in visible) moved[i++] else it }
        }
        val save = { onSave(enabled.toMap(), merged()); close() }
        val restore = { onReset(); close() }
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
            val beside = maxWidth >= 600.dp || maxWidth > maxHeight
            val list = @Composable { m: Modifier ->
                LazyColumn(state = state, modifier = m) {
                    itemsIndexed(order, key = { _, key -> key }) { index, key ->
                        val held = index == dragging
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).testTag("hud-row-$key")
                                .zIndex(if (held) 1f else 0f)
                                .graphicsLayer { translationY = if (held) dragOffset else 0f }
                                .then(if (held) Modifier else Modifier.animateItemPlacement())
                                .clip(Shape12)
                                .background(
                                    if (held) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.surface,
                                )
                                .padding(start = 4.dp, end = 6.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(width = 40.dp, height = ROW_HEIGHT).testTag("hud-handle-$key")
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragStart = { dragging = index; dragOffset = 0f },
                                            onDragEnd = { dragging = -1; dragOffset = 0f },
                                            onDragCancel = { dragging = -1; dragOffset = 0f },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount.y
                                                val at = dragging
                                                if (at < 0) return@detectDragGestures
                                                when {
                                                    dragOffset > rowPx / 2f && at < order.lastIndex -> {
                                                        order.add(at + 1, order.removeAt(at))
                                                        dragging = at + 1
                                                        dragOffset -= rowPx
                                                    }
                                                    dragOffset < -rowPx / 2f && at > 0 -> {
                                                        order.add(at - 1, order.removeAt(at))
                                                        dragging = at - 1
                                                        dragOffset += rowPx
                                                    }
                                                }
                                            },
                                        )
                                    },
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
            val ok = @Composable { PrimaryButton(stringResource(R.string.common_ok), compact = true, onClick = save) }
            val reset = @Composable { SecondaryButton(stringResource(R.string.hud_reset_button), compact = true, onClick = restore) }
            if (beside) Row(Modifier.fillMaxSize()) {
                list(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { ok(); reset() }
            } else Column(Modifier.fillMaxSize()) {
                list(Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)); ok(); reset()
                }
            }
        }
    }
}
