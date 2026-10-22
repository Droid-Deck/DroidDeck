package com.droiddeck.launcher.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.GestureKeys
import com.droiddeck.launcher.input.GesturePrefs
import com.droiddeck.launcher.input.TouchGestureConfig
import com.droiddeck.launcher.input.TouchGestureConfig.Companion.ACTION_LEFT_CLICK
import com.droiddeck.launcher.input.TouchGestureConfig.Companion.ACTION_RIGHT_CLICK
import com.droiddeck.launcher.input.TouchGestureConfig.Companion.ACTION_MIDDLE_CLICK
import com.droiddeck.launcher.input.TouchGestureConfig.Companion.ACTION_SHOW_KEYBOARD
import com.droiddeck.launcher.input.TouchGestureConfig.Companion.ACTION_KEY_TILDE
import com.droiddeck.launcher.input.TouchGestureConfig.Companion.PAN_ACTIONS
import com.droiddeck.launcher.input.TouchGestureConfig.Companion.ZOOM_ACTIONS
import com.droiddeck.launcher.input.TouchGestureConfig.Companion.MOUSE_BEHAVIOR_HOLD
import com.droiddeck.launcher.input.TouchGestureConfig.Companion.MOUSE_BEHAVIOR_CLICK
import com.droiddeck.launcher.input.TouchGestureConfig.Companion.MOUSE_DRAG_MOVEMENT_DIRECT
import com.droiddeck.launcher.input.TouchGestureConfig.Companion.MOUSE_DRAG_MOVEMENT_RELATIVE

@Composable
fun TouchGestureDialog(game: String?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    // Keep an open editor attached to the game it was opened for.
    val targetGame = remember { game }
    val initialDefaults = remember { GesturePrefs.read(context) }
    val initialGame = remember { targetGame?.takeIf { GesturePrefs.hasOverride(context, it) }?.let { GesturePrefs.read(context, it) } }
    var defaults by remember { mutableStateOf(initialDefaults) }
    var gameDraft by remember { mutableStateOf(initialGame) }
    var forGame by rememberSaveable { mutableStateOf(targetGame != null) }
    val config = if (forGame) gameDraft ?: defaults else defaults
    val change: (TouchGestureConfig) -> Unit = { if (forGame) gameDraft = it else defaults = it }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(R.string.gesture_tab_one, R.string.gesture_tab_two, R.string.gesture_tab_three, R.string.gesture_tab_options)
    val mouse = listOf(ACTION_LEFT_CLICK, ACTION_RIGHT_CLICK, ACTION_MIDDLE_CLICK)
    val actions = mouse + ACTION_SHOW_KEYBOARD + GestureKeys.codes.keys.map { "key_$it" } + ACTION_KEY_TILDE
    val p = LocalPalette.current

    GestureDialogFrame(onDismiss,
        footer = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.gesture_cancel)) }
                Spacer(Modifier.width(12.dp))
                Button(onClick = {
                    if (defaults != initialDefaults) GesturePrefs.save(context, null, defaults)
                    if (targetGame != null && gameDraft != initialGame) {
                        gameDraft?.let { GesturePrefs.save(context, targetGame, it) } ?: GesturePrefs.clear(context, targetGame)
                    }
                    onSaved()
                }, modifier = Modifier.testTag("gesture-save")) { Text(stringResource(R.string.gesture_save_changes)) }
            }
        }) {
        ScrollableTabRow(tab, edgePadding = 12.dp, containerColor = p.surface, divider = { HorizontalDivider(color = p.line) }) {
            tabs.forEachIndexed { index, title ->
                Tab(tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) }, selectedContentColor = p.onBackground,
                    unselectedContentColor = p.onSurfaceVariant, modifier = Modifier.testTag("gesture-tab-$index"))
            }
        }
        key(tab) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GestureFilterChip(!forGame, onClick = { forGame = false }, label = { Text(stringResource(R.string.gesture_defaults)) }, modifier = Modifier.testTag("gesture-scope-default"))
                        if (targetGame != null) GestureFilterChip(forGame, onClick = { forGame = true }, label = { Text(stringResource(R.string.gesture_game)) }, modifier = Modifier.testTag("gesture-scope-game"))
                    }
                    if (forGame && gameDraft != null) TextButton(onClick = { gameDraft = null }, modifier = Modifier.testTag("gesture-inherit")) { Text(stringResource(R.string.gesture_use_defaults)) }
                }
                when (tab) {
                    0 -> {
                        GestureSection(R.string.gesture_tab_one)
                        GestureCard(R.string.gesture_tap, config.tapEnabled, { change(config.copy(tapEnabled = it)) }) {
                            GestureActionField("tap", R.string.gesture_tap, config.tapAction, actions) { change(config.copy(tapAction = it)) }
                        }
                        GestureCard(R.string.gesture_drag, config.dragEnabled, { change(config.copy(dragEnabled = it)) }) {
                            GestureActionField("drag", R.string.gesture_drag, config.dragAction, PAN_ACTIONS) { change(config.copy(dragAction = it)) }
                        }
                        GestureCard(R.string.gesture_long_press, config.longPressEnabled, { change(config.copy(longPressEnabled = it)) }) {
                            GestureActionField("long-press", R.string.gesture_long_press, config.longPressAction, actions) { change(config.copy(longPressAction = it)) }
                            GestureHoldOptions(config.longPressAction, config.longPressMouseBehavior, config.longPressDelay, { change(config.copy(longPressMouseBehavior = it)) }, { change(config.copy(longPressDelay = it)) })
                        }
                        GestureCard(R.string.gesture_double_tap, config.doubleTapEnabled, { change(config.copy(doubleTapEnabled = it)) }) {
                            GestureDelay(stringResource(R.string.gesture_double_delay), config.doubleTapDelay) { change(config.copy(doubleTapDelay = it)) }
                        }
                    }
                    1 -> {
                        GestureSection(R.string.gesture_tab_two)
                        GestureCard(R.string.gesture_two_tap, config.twoFingerTapEnabled, { change(config.copy(twoFingerTapEnabled = it)) }) {
                            GestureActionField("two-tap", R.string.gesture_two_tap, config.twoFingerTapAction, actions) { change(config.copy(twoFingerTapAction = it)) }
                        }
                        GestureCard(R.string.gesture_two_drag, config.twoFingerDragEnabled, { change(config.copy(twoFingerDragEnabled = it)) }) {
                            GestureActionField("two-drag", R.string.gesture_two_drag, config.twoFingerDragAction, PAN_ACTIONS) { change(config.copy(twoFingerDragAction = it)) }
                        }
                        GestureCard(R.string.gesture_two_hold, config.twoFingerHoldEnabled, { change(config.copy(twoFingerHoldEnabled = it)) }) {
                            GestureActionField("two-hold", R.string.gesture_two_hold, config.twoFingerHoldAction, actions) { change(config.copy(twoFingerHoldAction = it)) }
                            GestureHoldOptions(config.twoFingerHoldAction, config.twoFingerHoldMouseBehavior, config.twoFingerHoldDelay, { change(config.copy(twoFingerHoldMouseBehavior = it)) }, { change(config.copy(twoFingerHoldDelay = it)) })
                        }
                        GestureCard(R.string.gesture_pinch, config.pinchEnabled, { change(config.copy(pinchEnabled = it)) }) {
                            GestureActionField("pinch", R.string.gesture_pinch, config.pinchAction, ZOOM_ACTIONS) { change(config.copy(pinchAction = it)) }
                        }
                    }
                    2 -> {
                        GestureSection(R.string.gesture_tab_three)
                        GestureCard(R.string.gesture_three_tap, config.threeFingerTapEnabled, { change(config.copy(threeFingerTapEnabled = it)) }) {
                            GestureActionField("three-tap", R.string.gesture_three_tap, config.threeFingerTapAction, actions) { change(config.copy(threeFingerTapAction = it)) }
                        }
                        GestureCard(R.string.gesture_three_drag, config.threeFingerDragEnabled, { change(config.copy(threeFingerDragEnabled = it)) }) {
                            GestureActionField("three-drag", R.string.gesture_three_drag, config.threeFingerDragAction, PAN_ACTIONS) { change(config.copy(threeFingerDragAction = it)) }
                        }
                        GestureCard(R.string.gesture_three_hold, config.threeFingerHoldEnabled, { change(config.copy(threeFingerHoldEnabled = it)) }) {
                            GestureActionField("three-hold", R.string.gesture_three_hold, config.threeFingerHoldAction, actions) { change(config.copy(threeFingerHoldAction = it)) }
                            GestureHoldOptions(config.threeFingerHoldAction, config.threeFingerHoldMouseBehavior, config.threeFingerHoldDelay, { change(config.copy(threeFingerHoldMouseBehavior = it)) }, { change(config.copy(threeFingerHoldDelay = it)) })
                        }
                    }
                    3 -> {
                        GestureSection(R.string.gesture_tab_options)
                        GestureSettingsCard(R.string.gesture_movement) {
                            GestureChoiceChips(config.mouseDragMovementMode, listOf(MOUSE_DRAG_MOVEMENT_DIRECT to stringResource(R.string.gesture_direct), MOUSE_DRAG_MOVEMENT_RELATIVE to stringResource(R.string.gesture_relative))) { change(config.copy(mouseDragMovementMode = it)) }
                        }
                        GestureSettingsCard(R.string.gesture_recognition) {
                            Text(stringResource(R.string.gesture_threshold, config.gestureThreshold), style = MaterialTheme.typography.bodyMedium)
                            Slider(config.gestureThreshold.toFloat(), { change(config.copy(gestureThreshold = it.toInt())) }, valueRange = 10f..100f, steps = 17)
                        }
                        GestureSettingsCard(R.string.gesture_feedback) {
                            GestureToggle(R.string.gesture_debug, config.showGestureDebugOverlay) { change(config.copy(showGestureDebugOverlay = it)) }
                        }
                        OutlinedButton(onClick = { change(TouchGestureConfig()) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.gesture_reset)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GestureDialogFrame(onDismiss: () -> Unit,
                               footer: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    // The app window does not opt into RTL, so these dialogs follow their resource locale explicitly.
    val direction = if (LocalConfiguration.current.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 1040.dp).fillMaxWidth().fillMaxHeight(), shape = RoundedCornerShape(22.dp), color = p.background,
                    contentColor = p.onBackground, border = BorderStroke(1.dp, p.line2)) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.End) {
                            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Close, stringResource(R.string.gesture_close)) }
                        }
                        content()
                        HorizontalDivider(color = p.line)
                        footer()
                    }
                }
            }
        }
    }
}

@Composable
private fun GestureSection(@StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun GestureCard(@StringRes title: Int, checked: Boolean, onToggle: (Boolean) -> Unit,
                        content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = p.surface,
        border = BorderStroke(1.dp, if (checked) p.line2 else p.line)) {
        Column {
            Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onToggle)
                .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Switch(checked, onCheckedChange = null)
            }
            if (checked) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun GestureActionField(id: String, @StringRes gesture: Int, selected: String, choices: List<String>, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val p = LocalPalette.current
    Surface(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("gesture-choice-$id"), shape = RoundedCornerShape(10.dp),
        color = p.surfaceVariant, contentColor = p.onBackground, border = BorderStroke(1.dp, p.line2)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.gesture_action_label), color = p.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(gestureActionTitle(selected), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(19.dp), tint = p.onSurfaceVariant)
        }
    }
    if (open) GestureActionPicker(choices, selected, onDismiss = { open = false }) { open = false; onChoose(it) }
}

@Composable
internal fun GestureActionPicker(choices: List<String>, selected: String, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by remember { mutableStateOf<GestureActionGroup?>(null) }
    val catalog = choices.distinct().map { GestureAction(it, gestureActionTitle(it), gestureActionGroup(it)) }
    val groups = GestureActionGroup.entries.filter { group -> catalog.any { it.group == group } }
    val shown = filterGestureActions(catalog, query, category)
    val p = LocalPalette.current
    GestureDialogFrame(onDismiss) {
        OutlinedTextField(query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("gesture-action-search"),
            singleLine = true, shape = RoundedCornerShape(12.dp), placeholder = { Text(stringResource(R.string.gesture_search_actions)) },
            leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.gesture_clear_search)) }
            })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GestureFilterChip(category == null, onClick = { category = null }, label = { Text(stringResource(R.string.gesture_all_actions)) })
            if (groups.size > 1) groups.forEach { group ->
                GestureFilterChip(category == group, onClick = { category = group }, label = { Text(stringResource(group.title)) }, modifier = Modifier.testTag("gesture-category-${group.name}"))
            }
        }
        if (shown.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.gesture_no_actions), color = p.onSurfaceVariant)
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("gesture-action-list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { group ->
                val entries = shown.filter { it.group == group }
                if (entries.isNotEmpty()) {
                    item(key = group.name) { Text(stringResource(group.title), style = MaterialTheme.typography.labelLarge, color = p.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 5.dp)) }
                    items(entries, key = { it.id }) { action ->
                        val isSelected = action.id == selected
                        Surface(shape = RoundedCornerShape(12.dp), color = if (isSelected) p.primary.copy(alpha = 0.12f) else p.surface,
                            border = BorderStroke(1.dp, if (isSelected) p.primary else p.line)) {
                            Row(Modifier.fillMaxWidth().selectable(isSelected, role = Role.RadioButton, onClick = { onChoose(action.id) })
                                .testTag("gesture-action-${action.id}").padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(when (group) { GestureActionGroup.MOUSE -> Icons.Default.Mouse; GestureActionGroup.APP -> Icons.Default.TouchApp; else -> Icons.Default.Keyboard },
                                    null, tint = if (isSelected) p.primary else p.onSurfaceVariant, modifier = Modifier.size(22.dp))
                                Text(action.title, Modifier.weight(1f).padding(horizontal = 14.dp), style = MaterialTheme.typography.bodyLarge)
                                RadioButton(isSelected, onClick = null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GestureHoldOptions(action: String, behavior: String, delay: Int, onBehavior: (String) -> Unit, onDelay: (Int) -> Unit) {
    if (TouchGestureConfig.isMouseButtonAction(action)) {
        Text(stringResource(R.string.gesture_mouse_behavior), style = MaterialTheme.typography.labelMedium, color = LocalPalette.current.onSurfaceVariant)
        GestureChoiceChips(behavior, listOf(MOUSE_BEHAVIOR_HOLD to stringResource(R.string.gesture_hold_button), MOUSE_BEHAVIOR_CLICK to stringResource(R.string.gesture_click_once)), onBehavior)
    }
    GestureDelay(stringResource(R.string.gesture_hold_delay), delay, onDelay)
}

@Composable
private fun GestureFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    FilterChip(selected, onClick, label, modifier = modifier, colors = FilterChipDefaults.filterChipColors(
        containerColor = p.surface, labelColor = p.onSurfaceVariant,
        selectedContainerColor = p.primary.copy(alpha = 0.16f), selectedLabelColor = p.onBackground))
}

@Composable
private fun GestureChoiceChips(selected: String, options: List<Pair<String, String>>, choose: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) -> GestureFilterChip(selected == id, onClick = { choose(id) }, label = { Text(label) }) }
    }
}

@Composable
private fun GestureSettingsCard(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Surface(shape = RoundedCornerShape(16.dp), color = p.surface, border = BorderStroke(1.dp, p.line)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun GestureToggle(@StringRes title: Int, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = change).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, null)
    }
}

@Composable
private fun GestureDelay(label: String, value: Int, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.gesture_ms, value), Modifier.background(LocalPalette.current.surfaceVariant, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelLarge)
    }
    Slider(value.toFloat().coerceIn(100f, 1000f), { change((it / 50).toInt() * 50) }, valueRange = 100f..1000f, steps = 17)
}
