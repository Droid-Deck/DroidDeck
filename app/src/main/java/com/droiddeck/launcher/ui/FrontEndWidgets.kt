package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Small building blocks shared by the front end's pages: text styles, chips and buttons.

internal val Shape14 = RoundedCornerShape(14.dp)

@Composable
internal fun Eyebrow(t: String) {
    val pal = LocalPalette.current
    val rule = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rule.animateTo(1f, Motion.tw(600, 120)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.width(18.dp).height(1.5.dp).graphicsLayer { scaleX = rule.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }.background(pal.signal))
        Text(t.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = pal.signal)
    }
}

@Composable internal fun Title(t: String) = Text(t, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))

@Composable internal fun Lede(t: String) = Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))

@Composable
internal fun SectionTitle(t: String, detail: String?) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
        Text(t.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = colors.onSurfaceVariant)
        if (detail != null) Text(detail, fontSize = 12.sp, color = colors.onBackground)
        Box(modifier = Modifier.weight(1f).height(1.dp).background(LocalPalette.current.line))
    }
}

@Composable internal fun Note(t: String) = Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().clip(Shape12).background(MaterialTheme.colorScheme.surface).border(1.dp, LocalPalette.current.line2, Shape12).padding(12.dp))

/** A page's buttons: they wrap onto a second line rather than run off a narrow page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Actions(content: @Composable () -> Unit) = FlowRow(
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
) { content() }

/** A status chip in a row of buttons, centred on their height. */
@Composable
internal fun ActionChip(t: String, ok: Boolean) = Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 44.dp)) { Chip(t, ok) }

internal val Shape16 = RoundedCornerShape(16.dp)

/** One level up, as Back and B do: a real button, big enough to hit ([compact]: for the tightest layouts). */
@Composable
internal fun BackLink(label: String, compact: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(22.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.paneItem("back:$label").heightIn(min = if (compact) 44.dp else 48.dp)
            .clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f))
            .glideBorder(hot, shape, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(start = 8.dp, end = 16.dp),
    ) {
        Icon(Icons.Filled.ChevronLeft, contentDescription = null, tint = if (hot) pal.signal else colors.onBackground, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun Chip(t: String, ok: Boolean) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Text(
        t, fontSize = 12.sp, color = if (ok) pal.good else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(colors.surfaceVariant).border(1.dp, if (ok) pal.good.copy(alpha = 0.3f) else pal.line, RoundedCornerShape(99.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
internal fun rememberHot(src: MutableInteractionSource): Boolean = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value

@Composable
internal fun PrimaryButton(
    text: String,
    enabled: Boolean = true,
    main: Boolean = false,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    /** The Steam tab's own Play: bigger than any other button on a page. */
    large: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    // The page's main button is where the pane is entered from the rail.
    val frontFocus = if (main) LocalFrontFocus.current else null
    if (frontFocus != null) DisposableEffect(Unit) {
        frontFocus.primaryAttached++
        onDispose { frontFocus.primaryAttached-- }
    }
    val track = (if (frontFocus == null) Modifier.paneItem("btn:$text") else Modifier).downToFirstTile()
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.955f else if (hot) 1.02f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "btnScale")
    val lift by animateFloatAsState(if (hot) 14f else 6f, Motion.tw(300), label = "btnLift")
    // Where it sits, so a session it starts can grow out of it (LaunchFlood).
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.then(track)
            .onGloballyPositioned { placed[0] = it }
            // 48dp to touch on a handheld; compact rows keep 44.
            .heightIn(min = if (compact) 44.dp else 48.dp)
            .then(if (frontFocus != null) Modifier.focusRequester(frontFocus.primary).onFocusChanged { if (it.isFocused) frontFocus.last = FrontFocus.PRIMARY } else Modifier)
            .graphicsLayer { alpha = if (LaunchOrigin.flooding === placed) 0f else 1f; scaleX = scale; scaleY = scale; shadowElevation = if (enabled) lift.dp.toPx() else 0f; shape = Shape12; clip = false; ambientShadowColor = pal.signal; spotShadowColor = pal.signal }
            .clip(Shape12)
            .background(if (enabled) Brush.linearGradient(listOf(colors.primary, pal.primary2)) else Brush.linearGradient(listOf(colors.surfaceVariant, colors.surfaceVariant)))
            .shine(hot, 0.45f)
            // Off, its fill can match the surface it sits on: the outline keeps it a button.
            .then(if (enabled) Modifier else Modifier.border(1.dp, pal.line2, Shape12))
            // The grow and shine alone barely show on the light fill: outline it when a
            // controller is on it, as the other controls are.
            .glideBorder(hot, Shape12, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled) {
                placed[0]?.takeIf { it.isAttached }?.let { LaunchOrigin.mark(it.boundsInRoot(), placed) }
                onClick()
            }
            .padding(
                start = if (large) 20.dp else if (compact) 10.dp else if (icon != null) 14.dp else 18.dp,
                end = if (large) 26.dp else if (compact) 10.dp else 18.dp,
                top = if (large) 16.dp else if (compact) 7.dp else 11.dp,
                bottom = if (large) 16.dp else if (compact) 7.dp else 11.dp,
            ),
    ) {
        val fg = if (enabled) colors.onPrimary else colors.onSurfaceVariant
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (large) 20.dp else 17.dp))
        Text(
            text, fontSize = if (large) 17.sp else if (compact) 12.sp else 15.sp,
            fontWeight = if (large) FontWeight.Bold else FontWeight.SemiBold, letterSpacing = 0.5.sp, color = fg, maxLines = 1,
        )
    }
}

/**
 * [label] while idle; while [progress] is set, a spinner and how far along it is in its place.
 * The label stays laid out underneath, invisible, so the button keeps its width. The spinner
 * turns until there is a first figure to show.
 */
@Composable
internal fun ProgressLabel(label: @Composable (Modifier) -> Unit, progress: Float?, fontSize: androidx.compose.ui.unit.TextUnit = 15.sp) {
    val pal = LocalPalette.current
    Box(contentAlignment = Alignment.Center) {
        label(Modifier.alpha(if (progress == null) 1f else 0f))
        if (progress != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (progress > 0f) CircularProgressIndicator(progress = { progress }, strokeWidth = 2.dp, color = pal.signal, trackColor = pal.signal.copy(alpha = 0.22f), modifier = Modifier.size(16.dp))
            else CircularProgressIndicator(strokeWidth = 2.dp, color = pal.signal, modifier = Modifier.size(16.dp))
            Text("${(progress * 100).toInt()}%", fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1,
                color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
internal fun SecondaryButton(text: String, enabled: Boolean = true, compact: Boolean = false, modifier: Modifier = Modifier, progress: Float? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.955f else if (hot) 1.02f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "secScale")
    val pal = LocalPalette.current
    val fill by animateColorAsState(if (hot) pal.signal.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.03f), Motion.tw(250), label = "secFill")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.paneItem("btn:$text").downToFirstTile().heightIn(min = if (compact) 44.dp else 48.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(Shape12).background(fill).glideBorder(hot, Shape12, pal.signal, pal.line2)
            .alpha(if (enabled) 1f else 0.5f)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 7.dp else 11.dp),
    ) {
        ProgressLabel({ m -> Text(text, fontSize = if (compact) 12.sp else 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, color = colors.onBackground, maxLines = 1, modifier = m) },
            progress, fontSize = if (compact) 12.sp else 15.sp)
    }
}

@Composable
internal fun Cog(size: androidx.compose.ui.unit.Dp = 48.dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    // A press spins it on a half turn as the page it opens blooms out of it (PageOrigin).
    var spins by remember { mutableIntStateOf(0) }
    val rot by animateFloatAsState((if (hot) 90f else 0f) + spins * 180f, Motion.sp(0.55f), label = "cog")
    val pal = LocalPalette.current
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val cornerPx = with(androidx.compose.ui.platform.LocalDensity.current) { (if (size > 48.dp) 14.dp else 12.dp).toPx() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("cog").downToFirstTile().size(size).onGloballyPositioned { placed[0] = it }.clip(if (size > 48.dp) Shape14 else Shape12).background(Color.White.copy(alpha = 0.03f)).glideBorder(hot, if (size > 48.dp) Shape14 else Shape12, pal.signal, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current) {
                placed[0]?.takeIf { it.isAttached }?.let { PageOrigin.mark(Origin(it.boundsInRoot(), cornerPx)) }
                spins++
                onClick()
            },
    ) { Icon(Icons.Filled.Settings, stringResource(R.string.drawer_page_settings), tint = if (hot) pal.signal else colors.onBackground, modifier = Modifier.size(if (size > 48.dp) 20.dp else 18.dp).rotate(rot)) }
}
