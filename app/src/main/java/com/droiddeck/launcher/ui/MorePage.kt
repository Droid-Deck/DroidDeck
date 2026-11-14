package com.droiddeck.launcher.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

@Composable
internal fun MorePage(s: FrontEndState, a: FrontEndActions, onSelect: (String) -> Unit, modifier: Modifier) {
    val keys = NavTabs.more(s)
    val open: (String) -> Unit = { key -> if (key == "components") a.onComponents(true) else onSelect(key) }
    Column(modifier.verticalScroll(rememberScrollState())) {
        PageHeader(stringResource(R.string.rail_more)) {
            Spacer(Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.more_edit_tabs), compact = true) {
                SetupTabs.openLauncher = true
                SetupTabs.focusTabs = true
                onSelect("setup")
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 12.dp
            val columns = ((maxWidth + gap) / (160.dp + gap)).toInt().coerceIn(2, 5)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                keys.chunked(columns).forEachIndexed { r, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEachIndexed { c, key ->
                            MoreTile(s, key, first = r == 0 && c == 0, Modifier.weight(1f)) { open(key) }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreTile(s: FrontEndState, key: String, first: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val (label, icon) = railLabelIcon(key)
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(18.dp)
    val count = railCount(s, key)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(if (first) Modifier.paneEntry() else Modifier.paneItem("more:$key"))
            .testTag("more-$key")
            .height(112.dp).clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.12f) else colors.surface)
            .glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .controllerConfirm(onClick = onClick),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(30.dp))
            Text(stringResource(label), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (railBadge(s, key)) Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(9.dp).clip(CircleShape).background(AttentionAmber))
        if (count > 0) Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { CountPill(count) }
    }
}
