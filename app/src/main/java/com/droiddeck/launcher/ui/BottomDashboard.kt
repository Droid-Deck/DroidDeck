package com.droiddeck.launcher.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

/** A page the bottom screen can show while the game runs on the main screen. */
class BottomPage(
    val id: String,
    @StringRes val label: Int,
    val icon: ImageVector,
    val content: @Composable () -> Unit,
)

internal fun pickBottomPage(remembered: String?, ids: List<String>): String? =
    ids.firstOrNull { it == remembered } ?: ids.firstOrNull()

/** Tabs show their names while every tab still fits at [TAB_WIDTH]; otherwise only the open tab keeps its name. */
internal fun bottomTabsWithLabels(widthDp: Float, tabs: Int): Boolean = tabs * TAB_WIDTH + 2 * BAR_BUTTON <= widthDp

private const val TAB_WIDTH = 132f
private const val BAR_BUTTON = 56f

@Composable
fun BottomDashboard(
    pages: List<BottomPage>,
    remembered: String?,
    onPage: (String) -> Unit,
    onClose: () -> Unit,
    onMenu: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    var current by remember { mutableStateOf(pickBottomPage(remembered, pages.map { it.id })) }
    val page = pages.firstOrNull { it.id == current } ?: pages.firstOrNull()
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val labels = bottomTabsWithLabels(maxWidth.value, pages.size)
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BarButton(Icons.Outlined.Close, stringResource(R.string.deck_close), "bottom-close", onClose)
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pages.forEach { p ->
                        PageTab(p, p.id == page?.id, labels) {
                            current = p.id
                            onPage(p.id)
                        }
                    }
                }
                if (onMenu != null) BarButton(Icons.Outlined.Menu, stringResource(R.string.touch_droiddeck_menu), "bottom-menu", onMenu)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (page != null) page.content()
                else Text(stringResource(R.string.bottom_dashboard_empty), fontSize = 15.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(pal.surface).border(1.dp, pal.line, RoundedCornerShape(22.dp))
            .semantics { contentDescription = label }.clickable(role = Role.Button, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PageTab(page: BottomPage, selected: Boolean, label: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    val name = stringResource(page.label)
    Row(
        Modifier.height(44.dp).clip(shape)
            .background(if (selected) pal.signal else Color.Transparent)
            .border(1.dp, if (selected) pal.signal else pal.line, shape)
            .semantics { contentDescription = name }
            .clickable(role = Role.Tab, onClick = onClick).testTag("bottom-page-${page.id}")
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tint = if (selected) colors.background else colors.onBackground
        Icon(page.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        if (label || selected) Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
