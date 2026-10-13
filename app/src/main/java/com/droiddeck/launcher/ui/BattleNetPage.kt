package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.BattleNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Battle.net's page, and its card's key in Desktop's dock. */
internal const val BATTLENET_PAGE = "bnet"

/** Battle.net's blue, on its card and page. */
internal val BattleNetBlue = Color(0xFF148EFF)

/**
 * Battle.net's page, out of its card on Desktop: Install or Open (the desktop runs Blizzard's
 * installer, or the client, in a window of its own), the FEX build its prefix runs on, Remove, which
 * asks first, the games it has installed - in Steam too, each a tile to its page on Games - and the
 * titles it offers, each opening Battle.net on the desktop with that game's install.
 */
@Composable
internal fun BattleNetPage(s: FrontEndState, a: FrontEndActions, onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val narrow = LocalNarrowPane.current
    val installed = s.battleNetInstalled
    // Set by a start, from the Nightlies: read again as a session ends.
    val fex by produceState<String?>(null, s.sessionRunning, installed) {
        value = withContext(Dispatchers.IO) { runCatching { BattleNet.fexInUse(ctx) }.getOrNull() }
    }
    // A desktop running opens it there; any other session would have to end first.
    val elsewhere = s.sessionRunning && !s.desktopRunning
    val games = s.steamGames.filter { it.source == BattleNet.SOURCE }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val store = remember(games) { BattleNet.STORE.filter { BattleNet.notInstalled(it, games.map { g -> g.storeId }) } }
    // The store's covers, fetched once in the background (Steam's store, else SteamGridDB).
    var coversFetched by remember { mutableStateOf(0) }
    val covers by produceState(emptyMap<String, java.io.File>(), coversFetched) {
        value = withContext(Dispatchers.IO) { BattleNet.STORE.mapNotNull { t -> BattleNet.cover(ctx, t)?.let { t.uid to it } }.toMap() }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (withContext(Dispatchers.IO) { runCatching { BattleNet.fetchCovers(ctx) }.getOrDefault(false) }) coversFetched++
    }
    val canGet = !s.busy && !elsewhere && !s.battleNetRemoving
    Rise(1) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        ) {
            BattleNetIcon(52)
            Column {
                Text(stringResource(R.string.bnet_name), fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                Text(
                    stringResource(if (installed) R.string.bnet_subtitle else R.string.bnet_subtitle_missing),
                    fontSize = 14.sp, color = colors.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.bnet_fex, fex?.removeSuffix(".wcp") ?: stringResource(R.string.bnet_fex_pending)),
                    fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Rise(3) {
        Actions {
            PrimaryButton(
                stringResource(if (installed) R.string.bnet_open else R.string.bnet_install),
                enabled = !s.busy && !elsewhere && !s.battleNetRemoving, main = true, onClick = a.onBattleNet,
            )
            BusyChip(s)
            // The games it installed, read again (the Games tab and Steam's list follow).
            if (installed) SecondaryButton(stringResource(R.string.bnet_refresh), enabled = !s.battleNetRemoving, onClick = a.onLibraryChanged)
            if (installed) RemoveBattleNetButton(enabled = !s.busy && !s.sessionRunning && !s.battleNetRemoving, onRemove = a.onRemoveBattleNet)
            when {
                s.battleNetRemoving -> ActionChip(stringResource(R.string.bnet_removing), ok = false)
                elsewhere -> ActionChip(stringResource(R.string.bnet_stop_session), ok = false)
                installed && s.sessionRunning -> ActionChip(stringResource(R.string.user_apps_stop_session), ok = false)
            }
        }
    }
    Rise(4) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Note(stringResource(if (installed) R.string.bnet_about else R.string.bnet_install_note))
        }
    }
    if (installed) {
        Rise(5) { SectionTitle(stringResource(R.string.content_games), games.size.toString()) }
        if (games.isEmpty()) Rise(6) { Note(stringResource(R.string.bnet_no_games)) }
        else Rise(6, Modifier.fillMaxWidth()) {
            val sub = stringResource(R.string.bnet_name)
            ArtGrid(games.map { g ->
                val key = "app:${g.appId}"
                Tile(g.name, sub, g.art, key) {
                    TileBounds.mark(key, g.art, 12f * density)
                    onSelect(key)
                }
            })
        }
    }
    // What Battle.net offers that is not installed: a tile opens Battle.net with the game's install.
    if (store.isEmpty()) return
    Rise(7) { SectionTitle(stringResource(R.string.bnet_store), store.size.toString()) }
    Rise(8) { Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) { Note(stringResource(R.string.bnet_store_note)) } }
    Rise(9, Modifier.fillMaxWidth()) {
        val sub = stringResource(R.string.bnet_get)
        ArtGrid(store.map { t ->
            Tile(t.name, sub, covers[t.uid], "bnet:${t.uid}", dim = !canGet) { if (canGet) a.onBattleNetInstall(t.uid) }
        })
    }
}

/** Battle.net's mark here: a controller in its blue. */
@Composable
internal fun BattleNetIcon(size: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 5).dp)).background(BattleNetBlue.copy(alpha = 0.25f)),
    ) { Icon(Icons.Outlined.SportsEsports, null, tint = BattleNetBlue, modifier = Modifier.size((size / 2).dp)) }
}

/** Remove, which asks first: are you sure steps out of it in red, Keep first and focused. */
@Composable
private fun RemoveBattleNetButton(enabled: Boolean, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.user_apps_remove)
    val title = stringResource(R.string.bnet_remove_title)
    val note = stringResource(R.string.bnet_remove_text)
    val keep = stringResource(R.string.flathub_keep)
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        SecondaryButton(label, enabled = enabled) {
            val from = at ?: return@SecondaryButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Right, accent = colors.error, pillCorner = 12.dp, items = 3,
                handle = { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.error, maxLines = 1) },
            ) {
                StepTitle(title)
                Text(note, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.stepItem(1).padding(start = 4.dp, bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.stepItem(2)) {
                    StepChoice(keep, enabled = open, modifier = Modifier.focusRequester(first)) { Steps.fold() }
                    StepChoice(label, danger = true, enabled = open) { Steps.fold(); onRemove() }
                }
            })
        }
    }
}
