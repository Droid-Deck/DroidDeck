package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties


private val RowShape = RoundedCornerShape(12.dp)
private val GroupShape = RoundedCornerShape(14.dp)

internal fun Modifier.controllerConfirm(enabled: Boolean = true, onClick: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val keyEvent = event.nativeKeyEvent
    if (keyEvent.keyCode != KeyEvent.KEYCODE_BUTTON_A) {
        false
    } else {
        if (enabled && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) onClick()
        true
    }
}

internal fun Modifier.controllerBack(onBack: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val keyEvent = event.nativeKeyEvent
    if (keyEvent.keyCode != KeyEvent.KEYCODE_BUTTON_B && keyEvent.keyCode != KeyEvent.KEYCODE_BACK) {
        false
    } else {
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) onBack()
        true
    }
}

/** LB and RB turn a page's tabs; the keys are taken whenever focus is anywhere inside [this]. */
internal fun Modifier.bumpers(onPrevious: () -> Unit, onNext: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val keyEvent = event.nativeKeyEvent
    val previous = keyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_L1
    if (!previous && keyEvent.keyCode != KeyEvent.KEYCODE_BUTTON_R1) {
        false
    } else {
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) { if (previous) onPrevious() else onNext() }
        true
    }
}

/**
 * A page's tabs with the pad's bumpers drawn beside them. The host turns them with LB/RB (see
 * [bumpers]); the keycaps are for touch and stay out of the d-pad's path. The row scrolls sideways
 * when a narrow page cannot fit every tab.
 */
@Composable
internal fun TabStrip(
    tabs: List<String>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, focusRequesters: List<FocusRequester>? = null,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        BumperKey("LB", stringResource(R.string.widgets_prev_tab)) { onSelect((selected + tabs.size - 1) % tabs.size) }
        Row(
            modifier = Modifier.weight(1f, fill = false).clip(shape).background(colors.surfaceVariant).border(1.dp, pal.line2, shape)
                .horizontalScroll(rememberScrollState()).padding(3.dp),
        ) {
            tabs.forEachIndexed { i, label ->
                val on = i == selected
                val src = remember { MutableInteractionSource() }
                val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
                val pick = { onSelect(i) }
                val tabShape = RoundedCornerShape(9.dp)
                Text(
                    label, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1,
                    color = if (on) pal.onSignal else if (hot) colors.onBackground else colors.onSurfaceVariant,
                    modifier = Modifier
                        .then(focusRequesters?.getOrNull(i)?.let { Modifier.focusRequester(it) } ?: Modifier)
                        .clip(tabShape)
                        .background(if (on) pal.signal else if (hot) pal.signal.copy(alpha = 0.16f) else Color.Transparent)
                        .glideBorder(hot, tabShape, if (on) colors.onBackground else pal.signal)
                        .hoverable(src).clickable(interactionSource = src, indication = null, role = Role.Tab, onClick = pick)
                        .controllerConfirm(onClick = pick)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        BumperKey("RB", stringResource(R.string.widgets_next_tab)) { onSelect((selected + 1) % tabs.size) }
    }
}

/** A bumper drawn as a keycap, 44dp to touch; the pad presses the real one. */
@Composable
private fun BumperKey(label: String, description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val cap = RoundedCornerShape(7.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(44.dp)
            .focusProperties { canFocus = false }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant,
            modifier = Modifier.clip(cap).background(colors.surfaceVariant).border(1.dp, pal.line2, cap)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/** The narrowest a setting's value box gets; the session menu's sheet sets it lower. */
val LocalChipMinWidth = androidx.compose.runtime.compositionLocalOf { 150.dp }

class MenuHost {
    var open by mutableStateOf<String?>(null)
}

@Composable
fun rememberMenuHost(): MenuHost = remember { MenuHost() }

private class BelowEndProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        var x = anchorBounds.right - popupContentSize.width
        if (x < 8) x = 8
        var y = anchorBounds.bottom + gap
        if (y + popupContentSize.height > windowSize.height - 8) y = anchorBounds.top - gap - popupContentSize.height
        if (y < 8) y = 8
        return IntOffset(x, y)
    }
}

@Composable
fun AnchoredMenu(open: Boolean, onDismiss: () -> Unit, title: String? = null, note: String? = null, content: @Composable ColumnScope.(FocusRequester) -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = open
    if (!state.currentState && !state.targetState && state.isIdle) return
    val firstItemFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(open, inputMode) {
        if (open && inputMode == InputMode.Keyboard) {
            repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            runCatching { firstItemFocus.requestFocus() }
            android.util.Log.i("AnchoredMenu", "requested first option focus")
        }
    }
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val provider = remember(gap) { BelowEndProvider(gap) }
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            visibleState = state,
            enter = fadeIn(Motion.tw(180)) + scaleIn(Motion.sp(0.7f), initialScale = 0.94f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(Motion.tw(140)) + scaleOut(Motion.tw(140), targetScale = 0.96f, transformOrigin = TransformOrigin(1f, 0f)),
            label = "menu",
        ) {
            // A long list (a driver menu with its downloads) must not run off the screen: the menu is
            // capped at most of the window's height and its entries scroll under the fixed title.
            val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.86f).dp
            Column(
                modifier = Modifier
                    .widthIn(min = 220.dp, max = 340.dp)
                    .heightIn(max = maxHeight)
                    .shadow(24.dp, RowShape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(RowShape)
                    .background(pal.surfaceVariant.copy(alpha = 0.95f))
                    .border(1.dp, pal.signal.copy(alpha = 0.22f), RowShape)
                    .controllerBack(onDismiss)
                    .padding(6.dp),
            ) {
                if (title != null) Text(
                    title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                )
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content(firstItemFocus) }
                if (note != null) {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
                    Text(note, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
fun MenuItem(
    label: String, checked: Boolean, enabled: Boolean = true, detail: String? = null,
    trailing: (@Composable () -> Unit)? = null, leading: (@Composable () -> Unit)? = null,
    focusRequester: FocusRequester? = null, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val shift by animateFloatAsState(if (hot) 2f else 0f, Motion.sp(0.5f), label = "miShift")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
            .graphicsLayer { translationX = shift.dp.toPx() }
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hot && enabled) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .alpha(if (enabled) 1f else 0.4f)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text("✓", fontSize = 12.sp, color = pal.signal, modifier = Modifier.width(18.dp).alpha(if (checked) 1f else 0f))
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.5.sp, color = if (checked) pal.signal else colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

@Composable
fun ValueChip(text: String, open: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val pal = LocalPalette.current
    val rot by animateFloatAsState(if (open) 180f else 0f, Motion.sp(0.6f), label = "chipCaret")
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .widthIn(min = LocalChipMinWidth.current)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceVariant)
            .glideBorder(hot, RoundedCornerShape(10.dp), pal.signal, if (open) pal.signal else pal.line2)
            .alpha(if (enabled) 1f else 0.5f)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = { (fadeIn(Motion.tw(260)) + slideInVertically(Motion.tw(260)) { it / 2 }) togetherWith (fadeOut(Motion.tw(160)) + slideOutVertically(Motion.tw(160)) { -it / 2 }) },
            label = "chipText",
        ) { t -> Text(t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.width(8.dp))
        Text("▾", fontSize = 10.sp, color = colors.onSurfaceVariant, modifier = Modifier.rotate(rot))
    }
}

@Composable
fun SettingsGroup(title: String, compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 16.dp, bottom = if (compact) 3.dp else 6.dp),
    ) {
        Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = colors.onSurfaceVariant)
        Box(modifier = Modifier.weight(1f).height(1.dp).background(pal.line))
    }
    Column(modifier = Modifier.fillMaxWidth().clip(GroupShape).background(colors.surface).border(1.dp, pal.line, GroupShape)) { content() }
}

@Composable
fun SettingsRow(label: String, hint: String?, highlighted: Boolean = false, control: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val bg by animateColorAsState(if (highlighted) pal.signal.copy(alpha = 0.10f) else Color.Transparent, Motion.tw(200), label = "rowBg")
    // A narrow page stacks the control under its label, so neither squeezes the other.
    if (LocalNarrowPane.current) Column(
        modifier = Modifier.fillMaxWidth().background(bg).padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 12.dp),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
        if (hint != null) Text(hint, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        Box(Modifier.padding(top = 8.dp)) { control() }
    } else Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            if (hint != null) Text(hint, fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        control()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
fun <T> ChoiceRow(
    host: MenuHost, key: String, label: String, hint: String?,
    options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, note: String? = null,
    /** For the box itself - a page's FocusRequester for its first control. */
    chipModifier: Modifier = Modifier,
    onPick: (T) -> Unit,
) {
    val open = host.open == key
    SettingsRow(label, hint, highlighted = open) {
        Box {
            ValueChip(options.firstOrNull { it.first == selected }?.second ?: "-", open, enabled, modifier = chipModifier) { host.open = if (open) null else key }
            AnchoredMenu(open, onDismiss = { if (host.open == key) host.open = null }, title = label, note = note) { firstItemFocus ->
                options.forEachIndexed { index, (value, text) ->
                    MenuItem(text, checked = value == selected, focusRequester = if (index == 0) firstItemFocus else null) {
                        onPick(value)
                        host.open = null
                    }
                }
            }
        }
    }
}

@Composable
fun ToggleRow(host: MenuHost, key: String, label: String, hint: String?, checked: Boolean, enabled: Boolean = true, chipModifier: Modifier = Modifier, onChange: (Boolean) -> Unit) =
    SettingsRow(label, hint) {
        ToggleSwitch(checked, enabled, label, chipModifier) { host.open = null; onChange(it) }
    }

/** An on/off switch: one tap or one A press flips it, where a menu of On and Off took three. */
@Composable
fun ToggleSwitch(checked: Boolean, enabled: Boolean = true, label: String? = null, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val track by animateColorAsState(if (checked) pal.signal else colors.surfaceVariant, Motion.tw(180), label = "switchTrack")
    val knob by animateFloatAsState(if (checked) 1f else 0f, Motion.sp(0.7f), label = "switchKnob")
    val flip = { onChange(!checked) }
    val onLabel = stringResource(R.string.widgets_on)
    val offLabel = stringResource(R.string.widgets_off)
    val shape = RoundedCornerShape(99.dp)
    // 48dp tall to touch; the visible track sits inside it.
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 48.dp).widthIn(min = 64.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .hoverable(src)
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Switch, onClick = flip)
            .controllerConfirm(enabled = enabled, onClick = flip)
            .semantics { stateDescription = if (checked) onLabel else offLabel; if (label != null) contentDescription = label },
    ) {
        Box(
            Modifier.size(width = 52.dp, height = 30.dp).clip(shape).background(track)
                .glideBorder(hot, shape, if (checked) colors.onBackground else pal.signal, if (checked) pal.signal else pal.line2),
        ) {
            Box(
                Modifier.padding(4.dp).size(22.dp)
                    .graphicsLayer { translationX = knob * 22.dp.toPx() }
                    .clip(CircleShape)
                    .background(if (checked) pal.onSignal else colors.onSurfaceVariant),
            )
        }
    }
}

@Composable
fun MultiRow(
    host: MenuHost, key: String, label: String, hint: String?,
    items: List<Pair<Int, String>>, selected: Set<Int>, enabled: Boolean = true, note: String? = null,
    onToggle: (Int, Boolean) -> Unit,
) {
    val open = host.open == key
    val summary = when {
        selected.size >= items.size -> stringResource(R.string.widgets_all_n, items.size)
        selected.isEmpty() -> stringResource(R.string.widgets_none)
        else -> stringResource(R.string.widgets_n_of_m, selected.size, items.size)
    }
    SettingsRow(label, hint, highlighted = open) {
        Box {
            ValueChip(summary, open, enabled) { host.open = if (open) null else key }
            AnchoredMenu(open, onDismiss = { if (host.open == key) host.open = null }, title = label, note = note) { firstItemFocus ->
                items.forEachIndexed { index, (value, text) ->
                    val on = value in selected
                    MenuItem(text, checked = on, focusRequester = if (index == 0) firstItemFocus else null) { onToggle(value, !on) }
                }
            }
        }
    }
}

@Composable
fun ActionRow(label: String, hint: String?, button: String, onClick: () -> Unit) {
    SettingsRow(label, hint) { SecondaryButton(button, onClick = onClick) }
}

@Composable
fun SettingsPage(
    host: MenuHost,
    title: String,
    onBack: () -> Unit,
    eyebrow: String? = null,
    lede: String? = null,
    /** A small control at the right of the title (the driver pages' refresh button). */
    action: (@Composable () -> Unit)? = null,
    /** Pass one held above the page to keep its scroll position across a page opened over it. */
    scroll: androidx.compose.foundation.ScrollState? = null,
    scrollContent: Boolean = true,
    compactLayout: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = scroll ?: rememberScrollState()
    val colors = MaterialTheme.colorScheme
    val dim by animateFloatAsState(if (host.open != null) 0.6f else 1f, Motion.tw(220), label = "pageDim")
    val narrow = LocalNarrowPane.current && !compactLayout
    Column(
        modifier = Modifier.fillMaxSize().padding(
            horizontal = if (compactLayout) 14.dp else if (narrow) 16.dp else 22.dp,
            vertical = if (compactLayout) 6.dp else if (narrow) 12.dp else 18.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(if (compactLayout) 2.dp else 0.dp),
    ) {
        if (narrow) {
            // Back and the title share one line: a 4:3 screen has no height to spare.
            Rise(0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    BackLink(stringResource(R.string.mode_back), compact = true, onClick = onBack)
                    Text(
                        title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (action != null) action()
                }
            }
            if (lede != null) Rise(1) { Lede(lede) }
        } else {
            Rise(0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BackLink(stringResource(R.string.mode_back), compact = compactLayout, onClick = onBack)
                    if (eyebrow != null) {
                        Spacer(Modifier.width(10.dp))
                        Eyebrow(eyebrow)
                    }
                }
            }
            Rise(1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (compactLayout) {
                            Text(
                                title,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.onBackground,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        } else {
                            Title(title)
                        }
                    }
                    if (action != null) action()
                }
            }
            if (lede != null) Rise(2) {
                if (compactLayout) {
                    Text(
                        lede,
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Lede(lede)
                }
            }
        }
        Rise(3, Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = dim }
                    .then(if (scrollContent) Modifier.verticalScroll(scrollState) else Modifier)
                    .padding(bottom = if (compactLayout) 0.dp else 24.dp),
            ) { content() }
        }
    }
}
