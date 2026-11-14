package com.droiddeck.launcher.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentTab

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TabsSettingsGroup(host: MenuHost, s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    val reveal = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        if (!SetupTabs.focusTabs) return@LaunchedEffect
        SetupTabs.focusTabs = false
        androidx.compose.runtime.withFrameNanos { }
        reveal.bringIntoView()
    }
    val shown = stringResource(R.string.tabs_place_bar)
    val more = stringResource(R.string.tabs_place_more)
    val hidden = stringResource(R.string.tabs_place_hidden)
    val keys = NavTabs.listed(s)
    fun pick(key: String, place: TabPlace) {
        when (key) {
            "stores" -> if ((place != TabPlace.HIDDEN) != s.gameStoresEnabled) a.onGameStoresEnabled(place != TabPlace.HIDDEN)
            "soundtracks" -> if ((place != TabPlace.HIDDEN) != s.soundtracksEnabled) a.onSoundtracksEnabled(place != TabPlace.HIDDEN)
            "agent" -> if ((place != TabPlace.HIDDEN) != AgentTab.shown) AgentTab.set(ctx, place != TabPlace.HIDDEN)
        }
        NavTabs.setPlace(ctx, key, place)
    }
    Box(Modifier.bringIntoViewRequester(reveal)) {
        SettingsGroup(stringResource(R.string.setup_group_tabs)) {
            keys.forEachIndexed { index, tab ->
                key(tab) {
                    val options = buildList {
                        add(TabPlace.BAR to shown)
                        add(TabPlace.MORE to more)
                        if (NavTabs.hideable(tab)) add(TabPlace.HIDDEN to hidden)
                    }
                    TabRow(
                        host, tab, NavTabs.place(s, tab), options,
                        canUp = index > 0, canDown = index < keys.lastIndex,
                        onMove = { step -> NavTabs.move(ctx, s, tab, step) },
                        onPick = { pick(tab, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabRow(
    host: MenuHost, tab: String, place: TabPlace, options: List<Pair<TabPlace, String>>,
    canUp: Boolean, canDown: Boolean, onMove: (Int) -> Unit, onPick: (TabPlace) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val (labelRes, icon) = railLabelIcon(tab)
    val label = stringResource(labelRes)
    val open = host.open == "tab-$tab"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
            .background(if (open) pal.signal.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).alpha(if (place == TabPlace.HIDDEN) 0.5f else 1f),
        ) {
            Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MoveButton(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.tabs_move_up, label), canUp, "tab-up-$tab") { onMove(-1) }
        MoveButton(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.tabs_move_down, label), canDown, "tab-down-$tab") { onMove(1) }
        ChoiceChip(host, "tab-$tab", label, options, place, onPick = onPick)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
private fun MoveButton(icon: ImageVector, description: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.testTag(tag).size(40.dp).clip(CircleShape)
            .background(colors.surfaceVariant)
            .glideBorder(hot, CircleShape, pal.signal, pal.line2)
            .alpha(if (enabled) 1f else 0.35f)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button) { if (enabled) onClick() }
            .controllerConfirm { if (enabled) onClick() }
            .semantics { contentDescription = description },
    ) { Icon(icon, contentDescription = null, tint = if (hot) pal.signal else colors.onBackground, modifier = Modifier.size(20.dp)) }
}
