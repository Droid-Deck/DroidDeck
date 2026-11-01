package com.droiddeck.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.droiddeck.launcher.R
import com.droiddeck.launcher.hud.DroidDeckHudConfig

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HudReadingsDialog(
    initialOrder: List<String>,
    initialOn: Map<String, Boolean>,
    onDismiss: () -> Unit,
    onSave: (Map<String, Boolean>, List<String>) -> Unit,
) {
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    val order = remember { mutableStateListOf<String>().apply { addAll(initialOrder) } }
    val enabled = remember { mutableStateMapOf<String, Boolean>() }
    remember(initialOn) { for (key in initialOrder) enabled[key] = initialOn[key] == true; true }

    AppDialog(shown, close, "hudReadings", wide = false, scrollable = false) {
        val state = rememberLazyListState()
        var draggingKey by remember { mutableStateOf<String?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var rowHeight by remember { mutableFloatStateOf(0f) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val listMax = (maxHeight * 0.62f).coerceAtLeast(120.dp)
        val beside = maxWidth >= 600.dp || maxWidth > maxHeight
        val save = { onSave(enabled.toMap(), order.toList()); close() }
        val restore = {
            onSave(DroidDeckHudConfig.DEFAULTS.mapValues { it.value == "1" }, DroidDeckHudConfig.ORDER)
            close()
        }
        val list = @Composable { m: Modifier ->
            LazyColumn(state = state, modifier = m.heightIn(max = listMax)) {
            itemsIndexed(order, key = { _, key -> key }) { index, key ->
                val dragged = draggingKey == key
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT)
                        .graphicsLayer { translationY = if (dragged) dragOffset else 0f }
                        .zIndex(if (dragged) 1f else 0f)
                        .clip(Shape12)
                        .background(if (dragged) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .onSizeChanged { if (rowHeight <= 0f) rowHeight = it.height.toFloat() }
                        .padding(start = 4.dp, end = 6.dp),
                ) {
                    DragHandle(onStart = { draggingKey = key; dragOffset = 0f },
                        onDrag = { amount ->
                            dragOffset += amount
                            val h = rowHeight
                            if (h <= 0f) return@DragHandle
                            while (dragOffset > h * 0.5f) {
                                val at = order.indexOf(key)
                                if (at < 0 || at >= order.size - 1) break
                                order.add(at + 1, order.removeAt(at))
                                dragOffset -= h
                            }
                            while (dragOffset < -h * 0.5f) {
                                val at = order.indexOf(key)
                                if (at <= 0) break
                                order.add(at - 1, order.removeAt(at))
                                dragOffset += h
                            }
                        },
                        onEnd = { draggingKey = null; dragOffset = 0f })
                    val label = hudMetricLabel(key)
                    Text(if (label != null) stringResource(label) else key,
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 1,
                        modifier = Modifier.weight(1f).padding(start = 2.dp))
                    ToggleSwitch(enabled[key] == true, true, null) { enabled[key] = !(enabled[key] == true) }
                }
            }
            }
        }
        val ok = @Composable { PrimaryButton(stringResource(R.string.common_ok), compact = true, onClick = save) }
        val reset = @Composable { SecondaryButton(stringResource(R.string.hud_reset_button), compact = true, onClick = restore) }
        if (beside) Row {
            list(Modifier.weight(1f))
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { ok(); reset() }
        } else Column {
            list(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)); ok(); reset()
            }
        }
        }
    }
}

@Composable
private fun DragHandle(onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit) {
    val start = rememberUpdatedState(onStart)
    val drag = rememberUpdatedState(onDrag)
    val end = rememberUpdatedState(onEnd)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(width = 40.dp, height = ROW_HEIGHT).pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { start.value() },
                onDragEnd = { end.value() },
                onDragCancel = { end.value() },
            ) { change, amount ->
                change.consume()
                drag.value(amount.y)
            }
        },
    ) {
        Icon(Icons.Filled.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

private val ROW_HEIGHT = 44.dp
