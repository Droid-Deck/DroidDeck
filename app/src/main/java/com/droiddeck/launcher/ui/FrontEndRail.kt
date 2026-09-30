package com.droiddeck.launcher.ui

import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.PhantomProcessLimit

// The front end's side rail: the page list, the resume entry and the controller hints under it.

/** What the pad's face buttons do here, along the bottom edge as on a console. */
@Composable
internal fun ControllerHints(select: String = "Select", tabs: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.End),
            modifier = Modifier.fillMaxWidth().height(34.dp).background(colors.surface).padding(horizontal = 20.dp),
        ) {
            if (tabs) HintGlyph("LB RB", "Tabs")
            HintGlyph("A", select)
            HintGlyph("B", "Back")
        }
    }
}

@Composable
private fun HintGlyph(button: String, action: String) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Face buttons are round; bumpers are a wider keycap.
        val shape = if (button.length == 1) CircleShape else RoundedCornerShape(6.dp)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.height(20.dp).widthIn(min = 20.dp).clip(shape).background(colors.onBackground).padding(horizontal = if (button.length == 1) 0.dp else 6.dp),
        ) {
            Text(button, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.background)
        }
        Text(action, fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}

internal val AttentionAmber = Color(0xFFFFB547)

/**
 * The launcher's sections, always on screen down the left edge: the app is landscape-only, so the
 * width a hamburger menu would save is better spent keeping every section one press away.
 */
@Composable
internal fun SideRail(
    s: FrontEndState, selected: String,
    onSelect: (String) -> Unit, onFocusSelect: (String) -> Unit, a: FrontEndActions, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val compact = LocalConfiguration.current.screenHeightDp < 420
    // Under 600dp wide (4:3 and square screens) the labels would cost a fifth of the width.
    val iconOnly = isNarrowScreen()
    val setupNeedsAttention = !s.ready || (s.available != null && s.available != s.installed) ||
        PhantomProcessLimit.blocksSteam(s.phantomProcessStatus)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(if (iconOnly) 64.dp else 92.dp).background(colors.surface).padding(vertical = if (compact) 8.dp else 12.dp),
    ) {
        // Every section on screen at once: items shrink (to 44dp, still a touch target) before the rail scrolls.
        val count = 6 + (if (s.storeEnabled) 1 else 0) + (if (s.isHomeApp) 1 else 0)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val fit = (maxHeight / count - 4.dp).coerceIn(44.dp, if (iconOnly) 48.dp else if (compact) 52.dp else 60.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            RailItem("Steam", Icons.Outlined.SportsEsports, "steam", selected == "steam", compact, iconOnly, fit, onFocus = { onFocusSelect("steam") }) { onSelect("steam") }
            // Always there, so the items below it never move; an empty library says how to fill it.
            RailItem("Games", Icons.Outlined.VideoLibrary, "games", selected == "games", compact, iconOnly, fit, onFocus = { onFocusSelect("games") }) { onSelect("games") }
            RailItem("Desktop", Icons.Outlined.DesktopWindows, "desktop", selected == "desktop", compact, iconOnly, fit, onFocus = { onFocusSelect("desktop") }) { onSelect("desktop") }
            if (s.storeEnabled) RailItem("Store", Icons.Outlined.Storefront, "store", selected == "store", compact, iconOnly, fit, onFocus = { onFocusSelect("store") }) { onSelect("store") }
            RailItem("Components", Icons.Outlined.Layers, "components", selected == "components", compact, iconOnly, fit, onFocus = { onFocusSelect("components") }) { onSelect("components") }
            // Home mode's extra section goes last, so it shifts nothing above it.
            if (s.isHomeApp) RailItem("Apps", Icons.Outlined.Apps, "android-apps", selected == "android-apps", compact, iconOnly, fit, onFocus = { onFocusSelect("android-apps") }) { onSelect("android-apps") }
            RailItem("Setup", Icons.Outlined.Tune, "setup", selected == "setup", compact, iconOnly, fit, badge = setupNeedsAttention, onFocus = { onFocusSelect("setup") }) { onSelect("setup") }
            RailItem("Updates", Icons.Outlined.SystemUpdate, "updates", selected == "updates", compact, iconOnly, fit, badge = s.updates.hasUpdate, onFocus = { onFocusSelect("updates") }) { onSelect("updates") }
        }
        }
        AnimatedVisibility(s.busy, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 8.dp)) {
                val barWidth = if (iconOnly) 44.dp else 60.dp
                if (s.percent >= 0) LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.width(barWidth))
                else LinearProgressIndicator(modifier = Modifier.width(barWidth))
                Text(if (s.percent >= 0) "${s.percent}%" else if (iconOnly) "…" else "Working", fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        var lastRunning by remember { mutableStateOf("") }
        if (s.running != null) lastRunning = s.running
        AnimatedVisibility(
            s.running != null,
            enter = expandVertically(Motion.sp(0.75f)) + fadeIn(Motion.tw(300)),
            exit = shrinkVertically(Motion.tw(220)) + fadeOut(Motion.tw(180)),
        ) { ResumeRailItem(lastRunning, compact, iconOnly, a.onResume) }
    }
}

@Composable
private fun RailItem(
    label: String, icon: ImageVector, key: String, current: Boolean, compact: Boolean, iconOnly: Boolean, height: Dp,
    badge: Boolean = false, onFocus: () -> Unit = {}, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val frontFocus = LocalFrontFocus.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    if (frontFocus != null) LaunchedEffect(focused) {
        if (focused) {
            frontFocus.focusedRail = key
            onFocus()
        }
        else if (frontFocus.focusedRail == key) frontFocus.focusedRail = null
    }
    val fg by animateColorAsState(
        if (current) pal.signal else if (focused || hovered) colors.onBackground else colors.onSurfaceVariant,
        Motion.tw(220), label = "railFg",
    )
    val fill by animateColorAsState(
        if (current) pal.signal.copy(alpha = 0.14f) else if (focused || hovered) Color.White.copy(alpha = 0.05f) else Color.Transparent,
        Motion.tw(220), label = "railFill",
    )
    val ring by animateColorAsState(if (focused) pal.signal else Color.Transparent, Motion.tw(180), label = "railRing")
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "railScale")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(if (frontFocus != null) Modifier.focusRequester(frontFocus.railFor(key)) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(width = if (iconOnly) 52.dp else 80.dp, height = height)
            .clip(Shape14)
            .background(fill)
            .border(2.dp, ring, Shape14)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .then(if (iconOnly) Modifier.semantics { contentDescription = label } else Modifier),
    ) {
        if (iconOnly) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (height < 56.dp) 20.dp else 22.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, softWrap = false)
        }
        if (badge) Box(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = if (iconOnly) 7.dp else 8.dp, end = if (iconOnly) 9.dp else 18.dp)
                .size(8.dp).clip(CircleShape).background(AttentionAmber),
        )
    }
}

/** The running session, one press from anywhere in the launcher. */
@Composable
private fun ResumeRailItem(name: String, compact: Boolean, iconOnly: Boolean, onResume: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val hovered by src.collectIsHoveredAsState()
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ringScale by pulse.animateFloat(0.4f, 1.6f, infiniteRepeatable(tween(Motion.ms(1600).coerceAtLeast(1), easing = Motion.Ease), RepeatMode.Restart), label = "ring")
    // Animations off: the live dot alone, no pulsing ring.
    val still = Motion.scale == 0f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .padding(top = 6.dp)
            .width(if (iconOnly) 52.dp else 80.dp)
            .clip(Shape14)
            .background(pal.good.copy(alpha = if (focused || hovered) 0.20f else 0.12f))
            .border(2.dp, if (focused) pal.signal else Color.Transparent, Shape14)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onResume)
            .semantics { contentDescription = "Resume $name" }
            .padding(vertical = if (iconOnly) 15.dp else if (compact) 6.dp else 9.dp, horizontal = 4.dp),
    ) {
        Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (!still) Box(modifier = Modifier.size(14.dp).graphicsLayer { scaleX = ringScale; scaleY = ringScale; alpha = (1.6f - ringScale) / 1.2f }.border(1.5.dp, pal.good, CircleShape))
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(pal.good))
        }
        // Icons only: the live dot alone says something is running; its description says what.
        if (!iconOnly) {
            Text("Resume", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, softWrap = false)
            Text(name, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
