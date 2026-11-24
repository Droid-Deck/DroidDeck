package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.droiddeck.launcher.R

@Composable
internal fun ManageDrawerPages(offeredNow: List<SessionDrawerPage>, modifier: Modifier = Modifier, releaseSignal: Int = 0, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val current by rememberUpdatedState(offeredNow)
    val previewing = DevPreview.drawer != null
    DisposableEffect(Unit) { onDispose { DrawerPages.heldFrom = null } }
    DisposableEffect(previewing) { onDispose { if (previewing) DrawerPages.load(ctx) } }
    val colors = MaterialTheme.colorScheme
    BackHandler { onDone() }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.drawer_manage_pages), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = colors.onBackground, modifier = Modifier.weight(1f),
            )
            if (!DrawerPages.isDefault()) DrawerOutlineButton(stringResource(R.string.drawer_pages_reset)) { DrawerPages.reset(ctx) }
            Spacer(Modifier.width(8.dp))
            DrawerOutlineButton(stringResource(R.string.drawer_pages_done), onClick = onDone)
        }
        SortableRows(
            rows = { current.map { it.name } },
            commit = { names -> DrawerPages.rearrange(ctx, names.mapNotNull { n -> current.firstOrNull { it.name == n } }) },
            movable = { it != SessionDrawerPage.MORE.name },
            rowHeight = STORE_ROW, handleWidth = STORE_HANDLE,
            releaseSignal = releaseSignal,
            onGrab = { before -> DrawerPages.heldFrom = before?.mapNotNull { n -> current.firstOrNull { it.name == n } } },
            onActivate = { name ->
                current.firstOrNull { it.name == name }?.let { DrawerPages.setHidden(ctx, it, it !in DrawerPages.hidden) }
            },
        ) { name, held, offset, hint, rowModifier, onFocus ->
            val page = current.first { it.name == name }
            // The same divider the launcher's Edit tabs uses: pages above it are tabs, pages below it are kept in More.
            if (page == SessionDrawerPage.MORE) { MoreLine(STORE_ROW); return@SortableRows }
            val shown = page !in DrawerPages.hidden
            val pal = LocalPalette.current
            val label = stringResource(page.title)
            val src = remember { MutableInteractionSource() }
            val hot = rememberHot(src)
            SortRowFrame(held, offset, rowModifier.testTag("drawer-page-row-$name"), hot, src, onFocus) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(width = STORE_HANDLE, height = STORE_ROW)) {
                    Icon(Icons.Filled.DragHandle, null, tint = if (held) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f).alpha(if (shown) 1f else 0.5f),
                ) {
                    Icon(page.icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (hint != null) KeyHint("Y", hint)
                ToggleSwitch(
                    shown, DrawerPages.hideable(page), label,
                    Modifier.testTag("drawer-page-shown-$name").focusProperties { canFocus = false },
                ) { DrawerPages.setHidden(ctx, page, !it) }
            }
        }
    }
}
