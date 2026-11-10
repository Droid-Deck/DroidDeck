package com.droiddeck.launcher.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.AutoAwesome
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentHost

private object BubblePlace {
    var x by mutableStateOf(Float.NaN)
    var y by mutableStateOf(Float.NaN)
    var hidden by mutableStateOf(false)
}

/**
 * The agent's bubble over a session: there once the agent has been used, pulsing while it works,
 * marked when a reply or failure has not been seen. Drag it to either edge; a long press hides it
 * until the next session.
 */
@Composable
internal fun AgentBubble(onOpen: () -> Unit) {
    if (!AgentHost.installed || BubblePlace.hidden || AgentHost.entries.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val density = androidx.compose.ui.platform.LocalDensity.current
    val size = 56.dp
    val sizePx = with(density) { size.toPx() }
    val margin = with(density) { 12.dp.toPx() }
    val working = AgentHost.state == AgentHost.State.WORKING
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "agentPulse")
    val ring by pulse.animateFloat(
        0.35f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "agentRing",
    )
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        if (BubblePlace.x.isNaN()) {
            BubblePlace.x = width - sizePx - margin
            BubblePlace.y = height * 0.3f
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(BubblePlace.x.toInt(), BubblePlace.y.toInt()) }
                .size(size)
                .graphicsLayer { alpha = if (working || AgentHost.unseen) 1f else 0.7f }
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(colors.surface)
                .border(
                    2.dp,
                    if (working) colors.primary.copy(alpha = ring) else if (AgentHost.failed && AgentHost.unseen) colors.error else colors.outlineVariant,
                    androidx.compose.foundation.shape.CircleShape,
                )
                .pointerInput(width, height) {
                    detectDragGestures(onDragEnd = {
                        BubblePlace.x = if (BubblePlace.x + sizePx / 2 < width / 2) margin else width - sizePx - margin
                    }) { change, drag ->
                        change.consume()
                        BubblePlace.x = (BubblePlace.x + drag.x).coerceIn(0f, width - sizePx)
                        BubblePlace.y = (BubblePlace.y + drag.y).coerceIn(0f, height - sizePx)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onOpen() }, onLongPress = { BubblePlace.hidden = true })
                }
                .testTag("agent-bubble"),
        ) {
            Icon(androidx.compose.material.icons.Icons.Outlined.AutoAwesome, stringResource(R.string.rail_agent), tint = colors.primary)
            if (AgentHost.unseen && !working) Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(10.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (AgentHost.failed) colors.error else colors.primary),
            )
        }
    }
}
