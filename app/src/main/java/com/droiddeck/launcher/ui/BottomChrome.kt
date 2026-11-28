package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R

/** Whether the bottom screen's bar is shown. Panels that need the whole screen ask for it hidden; a pull tab brings it back. */
internal object BottomChrome {
    private val requests = mutableIntStateOf(0)

    /** The user's choice, which wins until a panel's request changes; null follows the requests. */
    private val chosen = mutableStateOf<Boolean?>(null)

    val barShown: Boolean get() = chosen.value ?: (requests.intValue == 0)

    fun show(shown: Boolean) {
        chosen.value = shown
    }

    fun reset() {
        chosen.value = null
    }

    internal fun request(on: Boolean) {
        requests.intValue = (requests.intValue + if (on) 1 else -1).coerceAtLeast(0)
        chosen.value = null
    }
}

/** Asks for the bar hidden while this is composed, as art, a page being read or a map being viewed do. */
@Composable
internal fun FullScreenChrome() {
    DisposableEffect(Unit) {
        BottomChrome.request(true)
        onDispose { BottomChrome.request(false) }
    }
}

private const val PULL_PX = 6f

/** The tab at the top edge that brings the hidden bar back, by a tap or a pull down. */
@Composable
internal fun PullTab() {
    val description = stringResource(R.string.bottom_bar_show)
    val shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.testTag("bottom-pull-tab").size(width = 72.dp, height = 26.dp)
                .semantics { contentDescription = description }
                .clip(shape).background(Color.Black.copy(alpha = 0.55f)).border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                .pointerInput(Unit) {
                    var pulled = 0f
                    detectVerticalDragGestures(onDragStart = { pulled = 0f }, onDragEnd = { pulled = 0f }, onDragCancel = { pulled = 0f }) { change, drag ->
                        pulled = (pulled + drag).coerceAtLeast(0f)
                        if (pulled > PULL_PX) { change.consume(); pulled = 0f; BottomChrome.show(true) }
                    }
                }
                .clickable(role = Role.Button) { BottomChrome.show(true) },
        ) {
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
        }
    }
}
