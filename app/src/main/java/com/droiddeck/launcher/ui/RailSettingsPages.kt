package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

internal object MoreTabs {
    var editing by mutableStateOf(false)
    var inside by mutableStateOf(false)
    val button = FocusRequester()
}

@Composable
internal fun StorageTabPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageHeader(stringResource(R.string.rail_storage))
        StoragePanel(s, a)
    }
}

@Composable
internal fun SyncTabPage(s: FrontEndState, modifier: Modifier) {
    val host = rememberMenuHost()
    var advanced by rememberSaveable { mutableStateOf(false) }
    if (advanced) {
        BackHandler { advanced = false }
        Box(modifier) { SyncAdvancedPage(host) { advanced = false } }
        return
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageHeader(stringResource(R.string.rail_sync))
        SyncPanel(host, s.ready && !s.busy && !s.removalPending) { advanced = true }
    }
}

@Composable
private fun ResetTabsRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val title = stringResource(R.string.tabs_reset_title)
    val body = stringResource(R.string.tabs_reset_body)
    val keep = stringResource(R.string.common_cancel)
    val reset = stringResource(R.string.tabs_reset)
    var at by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(Modifier.padding(top = 12.dp)) { SettingsRow(stringResource(R.string.tabs_reset_title), stringResource(R.string.tabs_reset_hint)) {
        Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
            SecondaryButton(reset, modifier = Modifier.testTag("tabs-reset")) {
                val from = at ?: run { NavTabs.reset(ctx); return@SecondaryButton }
                Steps.ask(StepAsk(
                    anchor = from, side = StepSide.Left, accent = pal.signal, items = 3, pillCorner = 20.dp, maxWidth = 360.dp,
                    handle = { androidx.compose.material3.Text(reset, fontSize = 13.sp, color = pal.signal) },
                ) {
                    StepTitle(title)
                    androidx.compose.material3.Text(body, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.stepItem(1).padding(start = 4.dp, bottom = 12.dp))
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.stepItem(2)) {
                        StepChoice(keep, enabled = open, modifier = Modifier.focusRequester(first)) { Steps.fold() }
                        StepChoice(reset, enabled = open) { Steps.fold(); NavTabs.reset(ctx) }
                    }
                })
            }
        }
    } }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun EditTabsSheet(s: FrontEndState, a: FrontEndActions) {
    val pal = LocalPalette.current
    BackHandler(enabled = MoreTabs.editing) { MoreTabs.editing = false }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { MoreTabs.editing = false } }
    var wasOpen by remember { mutableStateOf(false) }
    val closeInput = androidx.compose.ui.platform.LocalInputModeManager.current
    LaunchedEffect(MoreTabs.editing) {
        if (MoreTabs.editing) wasOpen = true
        else if (wasOpen && closeInput.inputMode == androidx.compose.ui.input.InputMode.Keyboard) focusSoon { MoreTabs.button }
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(MoreTabs.editing, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                    .focusProperties { canFocus = false }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { MoreTabs.editing = false },
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            val width = minOf(560.dp, maxWidth * 0.92f)
            AnimatedVisibility(MoreTabs.editing, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
                val inside = remember { FocusRequester() }
                var holds by remember { mutableStateOf(false) }
                val openInput = androidx.compose.ui.platform.LocalInputModeManager.current
                // The launcher only takes focus in controller mode, so opening by touch switches to it like startup does.
                LaunchedEffect(Unit) {
                    repeat(60) {
                        androidx.compose.runtime.withFrameNanos { }
                        if (holds || !MoreTabs.editing) return@LaunchedEffect
                        if (openInput.inputMode != androidx.compose.ui.input.InputMode.Keyboard) openInput.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                        runCatching { inside.requestFocus() }
                    }
                }
                Column(
                    Modifier.width(width).fillMaxHeight()
                        .onFocusChanged { holds = it.hasFocus }
                        .focusRequester(inside)
                        .focusProperties { exit = { if (MoreTabs.editing) FocusRequester.Cancel else FocusRequester.Default } }
                        .focusGroup()
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, pal.line).padding(horizontal = 18.dp, vertical = 16.dp).testTag("edit-tabs-sheet"),
                ) {
                    PageHeader(stringResource(R.string.more_edit_tabs)) {
                        Spacer(Modifier.weight(1f))
                        SmallIconButton(Icons.Filled.Close, stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.onSurfaceVariant) {
                            MoreTabs.editing = false
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        TabsSettingsGroup(rememberMenuHost(), s, a)
                        if (!NavTabs.isDefault()) ResetTabsRow()
                    }
                }
            }
        }
    }
}
