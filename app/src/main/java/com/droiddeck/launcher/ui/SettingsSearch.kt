package com.droiddeck.launcher.ui

import androidx.compose.ui.unit.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.focusGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics

// Grows out of the search button over the section pills, with the same spring as the other page searches.
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun SettingsPillSearch(
    modifier: Modifier, searching: Boolean, query: String, onQuery: (String) -> Unit,
    onOpen: () -> Unit, onClose: () -> Unit, pills: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val open by animateFloatAsState(if (searching) 1f else 0f, Motion.sp(0.75f, 420f), label = "settingsSearch")
    val icon = remember { FocusRequester() }
    var toIcon by remember { mutableStateOf(false) }
    var focusOnOpen by remember { mutableStateOf(false) }
    val close = { toIcon = true; focusOnOpen = false; onClose() }
    BackHandler(enabled = searching) { close() }
    LaunchedEffect(toIcon, searching, open <= 0.01f) {
        if (searching) toIcon = false
        else if (toIcon && open <= 0.01f) { focusSoon { icon }; toIcon = false }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterEnd) {
        val full = maxWidth
        Box(
            Modifier.fillMaxWidth().padding(end = 52.dp)
                .graphicsLayer { alpha = 1f - open; translationX = -24.dp.toPx() * open }
                .focusProperties { enter = { if (searching) FocusRequester.Cancel else FocusRequester.Default } }
                .focusGroup()
                .then(if (searching) Modifier.semantics { invisibleToUser() } else Modifier)
                .then(if (searching) Modifier.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                } else Modifier),
        ) { pills() }
        if (searching || open > 0.01f) {
            val w = lerp(44.dp, (full - 40.dp).coerceIn(44.dp, 640.dp), open)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(w).graphicsLayer { alpha = (open * 1.6f).coerceAtMost(1f) }) {
                    AdbTextField(
                        value = query, onValueChange = onQuery, label = "", keyboardType = KeyboardType.Text, imeAction = ImeAction.Search,
                        placeholder = stringResource(R.string.settings_search), compact = true, showLabel = false, focusOnShow = focusOnOpen,
                        clearFocus = !searching,
                        onFocusChange = { focused -> if (!focused && query.isEmpty() && searching) { focusOnOpen = false; onClose() } },
                    )
                }
                SmallIconButton(Icons.Filled.Close, stringResource(R.string.settings_search_clear), tint = colors.onSurfaceVariant) { close() }
            }
        } else {
            Box(Modifier.focusRequester(icon)) { SearchIcon(stringResource(R.string.settings_search)) { focusOnOpen = true; onOpen() } }
        }
    }
}

@Composable
internal fun SettingsSearchResults(query: String, onOpen: (SettingsSection) -> Unit) {
    val ctx = LocalContext.current
    val found = remember(query) { SettingsIndex.search(ctx, query) }
    if (found.isEmpty()) {
        Note(stringResource(R.string.settings_search_none, query.trim()))
        return
    }
    Column {
        found.groupBy { it.place }.forEach { (place, entries) ->
            SettingsGroup(stringResource(place.title)) {
                entries.forEach { entry ->
                    val within = entry.within?.let { stringResource(it) }
                    val title = stringResource(entry.title)
                    val section = entry.section
                    val tab = entry.tab
                    if (section != null) {
                        ActionRow(title, within, stringResource(R.string.store_open), onClick = { onOpen(section) })
                    } else if (tab != null) {
                        ActionRow(title, within, stringResource(R.string.store_open), onClick = {
                            if (tab == NavTabs.MORE) MoreTabs.editing = true
                            if (tab == "downloads") DownloadsNav.showSettings = true
                            RailRequest.key = tab
                        })
                    } else {
                        SettingsRow(title, entry.path(ctx).dropLast(1).joinToString(" › ")) {}
                    }
                }
            }
        }
    }
}
