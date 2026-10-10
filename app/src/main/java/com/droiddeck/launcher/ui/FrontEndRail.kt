package com.droiddeck.launcher.ui

import androidx.compose.ui.platform.testTag
import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalMall
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.droiddeck.launcher.core.PhantomProcessLimit

// The front end's side rail: the page list and the resume entry under it.

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
        val count = 6 + (if (s.storeEnabled) 1 else 0) + (if (s.gameStoresEnabled) 1 else 0) + (if (s.isHomeApp) 1 else 0)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val fit = (maxHeight / count - 4.dp).coerceIn(44.dp, if (iconOnly) 48.dp else if (compact) 52.dp else 60.dp)
        // A hop in flight moves the selection ahead of the page it is bringing.
        val shown = Hops.railPreview ?: selected
        val keys = buildList {
            add("steam"); add("games")
            if (s.gameStoresEnabled) add("stores")
            add("desktop")
            if (s.storeEnabled) add("store")
            add("components")
            if (s.isHomeApp) add("android-apps")
            add("setup"); add("updates")
        }
        // Where each item sits in the column, for the selection that glides between them.
        val bounds = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
        val tint = androidx.compose.runtime.rememberUpdatedState(LocalPalette.current.signal)
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // One selection for the whole rail, under the items: it glides from item to item as the
        // chips' does, its leading edge first, and a jump of more than one item goes as a drop.
        if (shown in keys) ChipGlide(keys.indexOf(shown), bounds[shown], tint, GlideAxis.Vertical, corner = 14.dp, fill = 0.14f, outline = 0f)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val place: (String) -> Modifier = { key ->
                Modifier.onGloballyPositioned {
                    bounds[key] = it.boundsInParent()
                    Hops.railBounds[key] = it.boundsInRoot()
                }
            }
            RailItem(stringResource(R.string.rail_steam), Icons.Outlined.SportsEsports, "steam", shown == "steam", compact, iconOnly, fit, place = place, onFocus = { onFocusSelect("steam") }) { onSelect("steam") }
            // Always there, so the items below it never move; an empty library says how to fill it.
            RailItem(stringResource(R.string.rail_games), Icons.Outlined.VideoLibrary, "games", shown == "games", compact, iconOnly, fit, place = place, onFocus = { onFocusSelect("games") }) { onSelect("games") }
            // The game stores, right under Games: what they install lands there. Its badge counts
            // the downloads in flight, so a download started here is never out of sight.
            // A section switched on in Setup comes in as the rail's own entrance; off, it folds away.
            RailEntrance(s.gameStoresEnabled) { RailItem(stringResource(R.string.rail_stores), Icons.Outlined.LocalMall, "stores", shown == "stores", compact, iconOnly, fit, count = s.storeDownloadsActive, place = place, onFocus = { onFocusSelect("stores") }) { onSelect("stores") } }
            RailItem(stringResource(R.string.rail_desktop), Icons.Outlined.DesktopWindows, "desktop", shown == "desktop", compact, iconOnly, fit, place = place, onFocus = { onFocusSelect("desktop") }) { onSelect("desktop") }
            RailEntrance(s.storeEnabled) { RailItem(stringResource(R.string.rail_store), Icons.Outlined.Storefront, "store", shown == "store", compact, iconOnly, fit, place = place, onFocus = { onFocusSelect("store") }) { onSelect("store") } }
            RailItem(stringResource(R.string.rail_components), Icons.Outlined.Layers, "components", shown == "components", compact, iconOnly, fit, place = place, onFocus = { onFocusSelect("components") }) { onSelect("components") }
            // Home mode's extra section goes last, so it shifts nothing above it.
            if (s.isHomeApp) RailItem(stringResource(R.string.rail_apps), Icons.Outlined.Apps, "android-apps", shown == "android-apps", compact, iconOnly, fit, place = place, onFocus = { onFocusSelect("android-apps") }) { onSelect("android-apps") }
            RailItem(stringResource(R.string.rail_setup), Icons.Outlined.Tune, "setup", shown == "setup", compact, iconOnly, fit, badge = setupNeedsAttention, place = place, onFocus = { onFocusSelect("setup") }) { onSelect("setup") }
            RailItem(stringResource(R.string.rail_updates), Icons.Outlined.SystemUpdate, "updates", shown == "updates", compact, iconOnly, fit, badge = s.updates.hasUpdate, place = place, onFocus = { onFocusSelect("updates") }) { onSelect("updates") }
        }
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

/** A rail item that comes and goes with a setting: it opens in (fade, rising) and folds away. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.RailEntrance(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = expandVertically(Motion.sp(0.75f)) + fadeIn(Motion.tw(300, 80)) + androidx.compose.animation.slideInVertically(Motion.tw(420, 80)) { it / 3 },
        exit = shrinkVertically(Motion.tw(260, 120)) + fadeOut(Motion.tw(180)),
    ) { content() }
}

@Composable
private fun RailItem(
    label: String, icon: ImageVector, key: String, current: Boolean, compact: Boolean, iconOnly: Boolean, height: Dp,
    badge: Boolean = false,
    /** A number on the item (downloads in flight); 0 shows nothing. */
    count: Int = 0,
    /** Reports where the item sits, for the rail's gliding selection and a hop's dot. */
    place: (String) -> Modifier = { Modifier },
    onFocus: () -> Unit = {}, onClick: () -> Unit,
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
    // The selected look is the rail's glide under the item, so the item draws only hover here.
    val fill by animateColorAsState(
        if (current) Color.Transparent else if (focused || hovered) Color.White.copy(alpha = 0.05f) else Color.Transparent,
        Motion.tw(220), label = "railFill",
    )
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "railScale")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(if (frontFocus != null) Modifier.focusRequester(frontFocus.railFor(key)) else Modifier)
            .testTag("rail-$key")
            .then(place(key))
            // Pops as a hop's dot lands on it.
            .landingPop(Hops.railLanded[key] ?: 0)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(width = if (iconOnly) 52.dp else 80.dp, height = height)
            .clip(Shape14)
            .background(fill)
            .glideBorder(focused, Shape14, pal.signal)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .then(if (iconOnly) Modifier.semantics { contentDescription = label } else Modifier),
    ) {
        if (iconOnly) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (height < 56.dp) 20.dp else 22.dp))
            FitText(label, maxSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp), fontWeight = FontWeight.SemiBold, color = fg)
        }
        if (badge) Box(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = if (iconOnly) 7.dp else 8.dp, end = if (iconOnly) 9.dp else 18.dp)
                .size(8.dp).clip(CircleShape).background(AttentionAmber),
        )
        if (count > 0) Text(
            if (count > 9) "9+" else count.toString(),
            fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pal.onSignal, maxLines = 1,
            // Pops with the Downloads chip's count as an install's dot lands there.
            modifier = Modifier.align(Alignment.TopEnd).padding(top = if (iconOnly) 4.dp else 6.dp, end = if (iconOnly) 5.dp else 12.dp)
                .landingPop().clip(RoundedCornerShape(8.dp)).background(pal.signal).padding(horizontal = 5.dp, vertical = 1.dp),
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
    val resumeDescription = stringResource(R.string.rail_resume_named, name)
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ringScale by pulse.animateFloat(0.4f, 1.6f, infiniteRepeatable(tween(1600, easing = Motion.Ease), RepeatMode.Restart), label = "ring")
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
            .glideBorder(focused, Shape14, pal.signal)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onResume)
            .semantics { contentDescription = resumeDescription }
            .padding(vertical = if (iconOnly) 15.dp else if (compact) 6.dp else 9.dp, horizontal = 4.dp),
    ) {
        Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (!still) Box(modifier = Modifier.size(14.dp).graphicsLayer { scaleX = ringScale; scaleY = ringScale; alpha = (1.6f - ringScale) / 1.2f }.border(1.5.dp, pal.good, CircleShape))
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(pal.good))
        }
        // Icons only: the live dot alone says something is running; its description says what.
        if (!iconOnly) {
            Text(stringResource(R.string.resume_session), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, softWrap = false)
            Text(name, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
