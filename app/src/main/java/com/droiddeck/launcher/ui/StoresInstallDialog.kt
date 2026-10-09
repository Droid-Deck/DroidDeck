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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.formatBytes

/**
 * "Install <game>:" over one row per place - internal storage, the card - each the action
 * itself: icon, name, free space. Asked only when a card is there. The row picked last time
 * starts focused, so a pad confirms the usual place with one press; nothing is chosen for the user.
 */
@Composable
internal fun InstallWhereDialog(item: CatalogItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    val targets = remember { StoreInstallRoot.targets(ctx) }
    val remembered = remember { SessionPrefs.storesInstallTarget(ctx) }
    val defaultIndex = targets.indexOfFirst { it.root.absolutePath == remembered }.coerceAtLeast(0)
    val focus = remember { List(targets.size) { FocusRequester() } }
    LaunchedEffect(Unit) { focusWithinFrames({ false }) { focus[defaultIndex] } }
    AppDialog(shown, close, "installWhere", wide = false, maxWidth = 440.dp) {
        Rise(0) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.stores_install_title, item.title), fontSize = 15.sp, color = colors.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.common_cancel), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant,
                    modifier = Modifier.clip(Shape12).clickable(role = Role.Button, onClick = close).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Rise(1) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                targets.forEachIndexed { i, t ->
                    TargetRow(
                        label = if (t.removable) t.label else stringResource(R.string.stores_target_internal),
                        free = stringResource(R.string.stores_target_free, formatBytes(t.freeBytes)),
                        removable = t.removable, focus = focus[i],
                    ) {
                        SessionPrefs.setStoresInstallTarget(ctx, t.root.absolutePath)
                        StoresState.install(ctx, item, t.root)
                        close()
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetRow(label: String, free: String, removable: Boolean, focus: FocusRequester, onPick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().focusRequester(focus).clip(Shape12)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else colors.surface.copy(alpha = 0.6f))
            .glideBorder(hot, Shape12, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onPick)
            .controllerConfirm(onClick = onPick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(if (removable) Icons.Outlined.SdCard else Icons.Outlined.Smartphone, null, tint = if (hot) pal.signal else colors.onBackground, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(free, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}
