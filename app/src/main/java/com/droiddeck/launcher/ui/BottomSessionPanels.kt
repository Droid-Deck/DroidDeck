package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.input.SecondScreenMode

@Composable
internal fun BottomSessionPanel(mode: SecondScreenMode, actions: BottomPageActions) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.background)) {
        CompositionLocalProvider(LocalChipMinWidth provides 96.dp, LocalCompactSettings provides true) {
            when (mode) {
                SecondScreenMode.GUIDES -> GuidesPanel(actions.runningGame)
                SecondScreenMode.MIRROR -> MirrorPanel(actions)
                SecondScreenMode.STEAM_QAM -> SteamQamPanel()
                SecondScreenMode.ART -> ArtPanel(actions.runningGame)
                else -> Unit
            }
        }
    }
}

@Composable
internal fun PanelColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

@Composable
internal fun PanelRow(
    title: String,
    hint: String? = null,
    tag: String? = null,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) pal.signal.copy(alpha = 0.10f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                color = if (selected) pal.signal else colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!hint.isNullOrEmpty()) Text(hint, fontSize = 11.sp, lineHeight = 14.sp, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        trailing?.invoke()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
internal fun PanelValue(text: String, color: Color = MaterialTheme.colorScheme.onBackground) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
}

@Composable
internal fun PanelNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp))
    }
}
