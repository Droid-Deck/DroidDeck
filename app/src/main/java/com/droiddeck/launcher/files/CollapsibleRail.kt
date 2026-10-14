package com.droiddeck.launcher.files

import android.content.res.Configuration
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.annotation.DrawableRes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class RailItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val badge: Int = 0,
    val shortLabel: String? = null,
    val onClick: () -> Unit,
)

data class RailSection(val header: String?, val items: List<RailItem>)

private fun collapsedLabel(label: String): String {
    when (label) {
        "ENVIROMENT" -> return "ENVIRON"
        "WIN COMPONENTS" -> return "WIN COMP"
    }
    if (label.contains(' ')) return label
    return if (label.length > 9) label.take(8) + "…" else label
}

@Composable
private fun RailItemIcon(item: RailItem, tint: androidx.compose.ui.graphics.Color) {
    if (item.badge > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Text(if (item.badge > 9) "9+" else item.badge.toString(), fontSize = 8.sp) }
            },
        ) {
            Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(20.dp))
        }
    } else {
        Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(20.dp))
    }
}

data class RailLink(val label: String, val icon: ImageVector, val onClick: () -> Unit)

class RailState internal constructor(
    val collapsed: Boolean,
    val portraitLocked: Boolean,
    val onToggle: () -> Unit,
)

@Composable
fun rememberRailState(screenKey: String): RailState {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("file_manager", android.content.Context.MODE_PRIVATE) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val choseKey = "rail_${screenKey}_userChose"
    val collapsedKey = "rail_${screenKey}_collapsed"

    var userChose by rememberSaveable(screenKey) { mutableStateOf(prefs.getBoolean(choseKey, false)) }
    var landscapeCollapsed by rememberSaveable(screenKey) {
        mutableStateOf(if (prefs.getBoolean(choseKey, false)) prefs.getBoolean(collapsedKey, false) else false)
    }

    if (!isLandscape) {
        return RailState(collapsed = true, portraitLocked = true, onToggle = {})
    }
    return RailState(
        collapsed = landscapeCollapsed,
        portraitLocked = false,
        onToggle = {
            landscapeCollapsed = !landscapeCollapsed
            userChose = true
            prefs.edit()
                .putBoolean(choseKey, true)
                .putBoolean(collapsedKey, landscapeCollapsed)
                .apply()
        },
    )
}

@Composable
fun CollapsibleRail(
    state: RailState,
    title: String,
    sections: List<RailSection>,
    modifier: Modifier = Modifier,
    links: List<RailLink> = emptyList(),
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    @DrawableRes headerIcon: Int? = null,
    outlinedItems: Boolean = false,
) {
    val collapsed = state.collapsed
    val width by animateDpAsState(if (collapsed) 58.dp else 190.dp, label = "railWidth")

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (collapsed) 0.dp else 10.dp, vertical = 8.dp)
        ) {
            if (headerIcon != null) {
                Icon(
                    painter = painterResource(headerIcon),
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            if (!collapsed) {
                Spacer(Modifier.width(9.dp))
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!state.portraitLocked) {
                    IconButton(onClick = state.onToggle, modifier = Modifier.size(28.dp)) {
                        Text("‹‹", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    }
                }
            }
        }
        if (collapsed && !state.portraitLocked) {
            IconButton(onClick = state.onToggle, modifier = Modifier.fillMaxWidth().height(24.dp)) {
                Text("››", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            }
        }

        if (links.isNotEmpty()) {
            if (collapsed) {
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    links.forEach { link ->
                        IconButton(onClick = link.onClick, modifier = Modifier.size(32.dp)) {
                            Icon(link.icon, link.label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            } else {
                links.forEach { link ->
                    TextButton(onClick = link.onClick, modifier = Modifier.padding(start = 4.dp)) {
                        Icon(link.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(link.label, fontSize = 12.sp)
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
        sections.forEach { section ->
            if (!collapsed && section.header != null) {
                Text(
                    section.header,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp),
                )
            } else if (collapsed && section.header != null && section != sections.first()) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            section.items.forEach { item ->
                val active = item.selected
                val shape = RoundedCornerShape(9.dp)
                val base = Modifier.fillMaxWidth()
                val styled = if (outlinedItems) {
                    base
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                        .clip(shape)
                        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
                        .border(
                            1.dp,
                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            shape,
                        )
                        .clickable(onClick = item.onClick)
                        .padding(horizontal = if (collapsed) 2.dp else 11.dp, vertical = if (collapsed) 6.dp else 9.dp)
                } else {
                    base
                        .clickable(onClick = item.onClick)
                        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(horizontal = if (collapsed) 2.dp else 13.dp, vertical = if (collapsed) 7.dp else 11.dp)
                }
                val iconTint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                val labelColor = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                if (collapsed) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = styled,
                    ) {
                        RailItemIcon(item, iconTint)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.shortLabel ?: collapsedLabel(item.label),
                            color = labelColor,
                            fontSize = 7.5.sp,
                            lineHeight = 8.5.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                        modifier = styled,
                    ) {
                        RailItemIcon(item, iconTint)
                        Spacer(Modifier.width(11.dp))
                        Text(
                            item.label,
                            color = labelColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        }

        if (footer != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            footer()
        }
    }
}

@Composable
fun RailTopTabs(
    items: List<RailItem>,
    modifier: Modifier = Modifier,
    links: List<RailLink> = emptyList(),
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                TopTabCell(
                    icon = item.icon,
                    label = item.label,
                    selected = item.selected,
                    onClick = item.onClick,
                    modifier = Modifier.weight(1f),
                )
            }
            if (links.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .width(1.dp)
                        .height(30.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                )
                links.forEach { link ->
                    TopTabCell(link.icon, link.label, selected = false, onClick = link.onClick, modifier = Modifier.weight(1f))
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun TopTabCell(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 1.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = tint,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(if (selected) 16.dp else 0.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}
