package com.droiddeck.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.delay

private const val HINT_SESSIONS = 3
private const val HINT_MS = 6_000L
private var hintedSession: Any? = null

@Composable
internal fun DrawerEntryHint(steam: Boolean, inverted: Boolean, onOpen: () -> Unit) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val session = com.droiddeck.launcher.session.SessionState.sessionId
        if (hintedSession == session || SessionPrefs.drawerHintShown(context) >= HINT_SESSIONS) return@LaunchedEffect
        hintedSession = session
        SessionPrefs.countDrawerHint(context)
        shown = true
        delay(HINT_MS)
        shown = false
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(shown, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
                    .background(Color(0xE6101216))
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.drawer_handle)) { shown = false; onOpen() }
                    .padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
                    .widthIn(max = 260.dp)
                    .testTag("drawer-hint"),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Color.White, modifier = Modifier.size(20.dp))
                val bound = remember {
                    com.droiddeck.launcher.input.PhysicalShortcuts.read(context)
                        .filter { it.action == com.droiddeck.launcher.input.PhysicalShortcuts.Action.DROIDDECK }
                        .map { it.button.label }.distinct().joinToString(" / ")
                }
                Text(
                    if (bound.isNotEmpty()) stringResource(R.string.drawer_hint_button, bound)
                    else stringResource(if (!steam) R.string.drawer_hint else if (inverted) R.string.drawer_hint_steam_inverted else R.string.drawer_hint_steam),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                )
            }
        }
    }
}

@Composable
internal fun DrawerCornerTap(onOpen: () -> Unit) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout), contentAlignment = Alignment.TopEnd) {
        Box(
            Modifier.size(64.dp).testTag("drawer-corner")
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onOpen() }) },
        )
    }
}
