package com.droiddeck.launcher.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.hud.DroidDeckHudConfig

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
    val enabled = remember {
        mutableStateMapOf<String, Boolean>().apply { for (key in initialOrder) put(key, initialOn[key] == true) }
    }
    fun save() = onSave(enabled.toMap(), order.toList())

    AppDialog(shown, close, "hudReadings", wide = true) {
        DialogHeader(
            eyebrow = stringResource(R.string.hud_group_metrics),
            title = stringResource(R.string.hud_readings_title),
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(stringResource(R.string.hud_readings_restore), onClick = {
                        onSave(resetSwitches(), DroidDeckHudConfig.ORDER)
                        close()
                    })
                    PrimaryButton(stringResource(R.string.common_ok), onClick = { save(); close() })
                }
            },
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 620.dp
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { HudReadingsOn(order, enabled) }
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) { HudReadingsOff(order, enabled) }
            } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.25f).heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { HudReadingsOn(order, enabled) }
                Column(Modifier.weight(1f).heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { HudReadingsOff(order, enabled) }
            }
        }
    }
}

private fun resetSwitches(): Map<String, Boolean> = DroidDeckHudConfig.DEFAULTS.mapValues { it.value == "1" }

@Composable
private fun HudReadingsOn(order: MutableList<String>, enabled: MutableMap<String, Boolean>) {
    val on = order.filter { enabled[it] == true }
    var rowHeight by remember { mutableStateOf(0f) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HudSectionLabel(stringResource(R.string.hud_readings_on))
        if (on.isEmpty()) HudEmptyLine()
        for (key in on) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(Shape12).background(MaterialTheme.colorScheme.surface)
                    .onSizeChanged { if (rowHeight <= 0f) rowHeight = it.height.toFloat() }
                    .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)) {
                HudDragHandle(rowHeight) { step ->
                    val at = order.indexOf(key)
                    val to = at + step
                    if (at >= 0 && to in order.indices) order.add(to, order.removeAt(at))
                }
                HudIconButton(Icons.Filled.Close, stringResource(R.string.hud_readings_hide), true) { enabled[key] = false }
            }
        }
    }
}

@Composable
private fun HudReadingsOff(order: MutableList<String>, enabled: MutableMap<String, Boolean>) {
    val off = order.filter { enabled[it] != true }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HudSectionLabel(stringResource(R.string.hud_readings_off))
        if (off.isEmpty()) HudEmptyLine()
        for (key in off) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(Shape12).background(MaterialTheme.colorScheme.surface)
                    .clickable(role = Role.Button) { enabled[key] = true }
                    .padding(horizontal = 10.dp, vertical = 12.dp)) {
                Text("+", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                MetricName(key, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricName(key: String, modifier: Modifier) {
    val label = hudMetricLabel(key)
    Text(if (label != null) stringResource(label) else key,
        fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, modifier = modifier)
}

@Composable
private fun HudSectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun HudEmptyLine() {
    Text("—", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun HudDragHandle(rowHeight: Float, onStep: (Int) -> Unit) {
    val step = rememberUpdatedState(onStep)
    val height = rememberUpdatedState(rowHeight)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(32.dp).pointerInput(Unit) {
            var travel = 0f
            detectDragGestures(
                onDragStart = { travel = 0f },
                onDragEnd = { travel = 0f },
                onDragCancel = { travel = 0f },
            ) { change, drag ->
                change.consume()
                val h = height.value
                if (h <= 0f) return@detectDragGestures
                travel += drag.y
                val threshold = h * 0.6f
                while (travel > threshold) { step.value(1); travel -= h }
                while (travel < -threshold) { step.value(-1); travel += h }
            }
        },
    ) { Icon(Icons.Filled.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun HudIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector, description: String,
    enabled: Boolean, onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(36.dp).alpha(if (enabled) 1f else 0.28f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
    ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(19.dp)) }
}
