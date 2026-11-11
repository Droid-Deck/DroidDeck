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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.Soundtrack
import com.droiddeck.launcher.playback.SoundtrackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private sealed interface SoundRow {
    class Disc(val number: Int, val label: String) : SoundRow
    class Item(val track: Soundtrack.Track) : SoundRow
}

private fun rowsOf(album: Soundtrack.Album): List<SoundRow> = buildList {
    for ((disc, tracks) in album.disc) {
        if (album.multiDisc) add(SoundRow.Disc(disc, tracks.firstOrNull()?.discLabel ?: disc.toString()))
        tracks.forEach { add(SoundRow.Item(it)) }
    }
}

@Composable
internal fun SoundtrackDetail(key: String, s: FrontEndState, a: FrontEndActions, lead: FocusRequester, backLabel: String, onBack: () -> Unit) {
    val appId = remember(key) { key.removePrefix("sound:").removePrefix("app:").toIntOrNull() }
    val game = s.soundtracks.firstOrNull { it.appId == appId }
    LaunchedEffect(game == null, s.soundtracksLoaded) { if (game == null && s.soundtracksLoaded) onBack() }
    if (game == null) return
    val ctx = LocalContext.current
    val narrow = LocalNarrowPane.current
    val stacked = narrow || LocalConfiguration.current.screenWidthDp < 700
    val root = game.gameFiles
    val album by produceState<Soundtrack.Album?>(null, root, game.art) {
        value = root?.let { dir -> withContext(Dispatchers.IO) { Soundtrack.load(ctx, dir, game.art) } }
    }
    LaunchedEffect(Unit) { SoundtrackController.connect(ctx) }
    val inset = LocalDetailInset.current
    val art: Any? = game.art ?: album?.cover
    GameBackdrop(art, art, hueOf(game.name), fadeTop = inset.top < 8.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = inset.horizontal).padding(top = inset.top)) {
            Rise(0) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.glass(CircleShape)) { BackLink(backLabel, compact = true, onClick = onBack) }
                    Spacer(Modifier.weight(1f))
                    SourceBadge(game.source)
                }
            }
            val loaded = album
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Rise(1) { SoundtrackPlayer(game, loaded, art, lead, s, a) }
                if (loaded != null) Rise(2) { TrackList(game, loaded, Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.55f).dp)) }
            } else Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.fillMaxWidth()) {
                Rise(1, Modifier.weight(0.42f)) { SoundtrackPlayer(game, loaded, art, lead, s, a) }
                if (loaded != null) Rise(2, Modifier.weight(0.58f)) {
                    TrackList(game, loaded, Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.72f).dp))
                }
            }
        }
    }
}

@Composable
private fun SoundtrackPlayer(game: Library.SteamGame, album: Soundtrack.Album?, art: Any?, lead: FocusRequester, s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val belongs = SoundtrackController.belongs(game.gameFiles)
    val playing = belongs && SoundtrackController.playing
    val currentTrack = if (belongs) SoundtrackController.currentId else null
    val trackArt by produceState<java.io.File?>(null, currentTrack) {
        value = currentTrack?.let { id -> withContext(Dispatchers.IO) { Soundtrack.trackCover(ctx, java.io.File(id)) } }
    }
    val shownArt: Any? = trackArt ?: art
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(120.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.35f))) {
                if (shownArt != null) AsyncImage(shownArt, contentDescription = game.name, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize().testTag("soundtrack-cover"))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Text(
                    game.name, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Black, color = colors.onBackground,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val artist = album?.artist.orEmpty()
                if (artist.isNotBlank()) Text(artist, fontSize = 14.sp, color = colors.onBackground.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (album != null && album.tracks.isNotEmpty()) {
                    Text(
                        stringResource(R.string.soundtracks_total_length).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                        color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        stringResource(R.string.soundtracks_length_value, album.tracks.size, Soundtrack.formatLength(album.totalMs)),
                        fontSize = 13.sp, color = colors.onBackground,
                    )
                }
            }
        }
        Seek(game, album)
        val shuffleLabel = stringResource(R.string.soundtracks_shuffle)
        val previousLabel = stringResource(R.string.soundtracks_previous)
        val playLabel = stringResource(if (playing) R.string.soundtracks_pause else R.string.soundtracks_play)
        val nextLabel = stringResource(R.string.soundtracks_next)
        val repeatLabel = stringResource(
            when (SoundtrackController.repeat) {
                Player.REPEAT_MODE_ALL -> R.string.soundtracks_repeat_all
                Player.REPEAT_MODE_ONE -> R.string.soundtracks_repeat_one
                else -> R.string.soundtracks_repeat_off
            },
        )
        val loaded = album?.takeIf { it.tracks.isNotEmpty() }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TransportButton(Icons.Filled.Shuffle, shuffleLabel, "sound:shuffle", on = belongs && SoundtrackController.shuffle, enabled = loaded != null) {
                if (loaded == null) return@TransportButton
                if (belongs) SoundtrackController.setShuffle(ctx, !SoundtrackController.shuffle)
                else SoundtrackController.play(ctx, loaded, game.name, null, shuffled = true, root = game.gameFiles)
            }
            TransportButton(Icons.Filled.SkipPrevious, previousLabel, "sound:previous", enabled = loaded != null) {
                if (loaded == null) return@TransportButton
                if (belongs) SoundtrackController.previous(ctx) else SoundtrackController.play(ctx, loaded, game.name, null, root = game.gameFiles)
            }
            TransportButton(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, playLabel, "sound:play", big = true, enabled = loaded != null,
                modifier = Modifier.focusRequester(lead),
            ) {
                if (loaded != null) SoundtrackController.toggle(ctx, loaded, game.name, game.gameFiles)
            }
            TransportButton(Icons.Filled.SkipNext, nextLabel, "sound:next", enabled = loaded != null) {
                if (loaded == null) return@TransportButton
                if (belongs) SoundtrackController.next(ctx) else SoundtrackController.play(ctx, loaded, game.name, null, root = game.gameFiles)
            }
            TransportButton(
                if (SoundtrackController.repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, repeatLabel, "sound:repeat",
                on = SoundtrackController.repeat != Player.REPEAT_MODE_OFF, enabled = loaded != null,
            ) { SoundtrackController.cycleRepeat(ctx) }
        }
        Volume()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            SoundtrackActions(game, s, a)
        }
    }
}

@Composable
private fun Seek(game: Library.SteamGame, album: Soundtrack.Album?) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val belongs = SoundtrackController.belongs(game.gameFiles)
    var position by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val duration = if (belongs) SoundtrackController.durationMs else 0L
    LaunchedEffect(belongs, SoundtrackController.currentId) {
        if (!belongs) {
            if (!dragging) position = 0f
            return@LaunchedEffect
        }
        while (true) {
            if (!dragging) position = SoundtrackController.position().toFloat()
            delay(400)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = if (duration > 0) (position / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = true; position = it * duration },
            onValueChangeFinished = { dragging = false; SoundtrackController.seekTo(ctx, position.toLong()) },
            enabled = belongs && duration > 0 && album != null,
            colors = SliderDefaults.colors(thumbColor = pal.signal, activeTrackColor = pal.signal, inactiveTrackColor = colors.onBackground.copy(alpha = 0.18f)),
            modifier = Modifier.fillMaxWidth().testTag("soundtrack-seek"),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(Soundtrack.formatLength(position.toLong()), fontSize = 11.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(Soundtrack.formatLength(duration), fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun Volume() {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var level by remember { mutableFloatStateOf(SoundtrackController.volume) }
    LaunchedEffect(SoundtrackController.volume) { level = SoundtrackController.volume }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Icon(if (level <= 0.001f) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, contentDescription = stringResource(R.string.soundtracks_volume), tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Slider(
            value = level, onValueChange = { level = it; SoundtrackController.setVolume(ctx, it) },
            colors = SliderDefaults.colors(thumbColor = pal.signal, activeTrackColor = pal.signal, inactiveTrackColor = colors.onBackground.copy(alpha = 0.18f)),
            modifier = Modifier.weight(1f).testTag("soundtrack-volume"),
        )
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector, label: String, id: String, big: Boolean = false, on: Boolean = false, enabled: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val size = if (big) 60.dp else 44.dp
    val fill = when {
        big -> pal.signal.copy(alpha = if (hot) 1f else 0.9f)
        hot -> pal.signal.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.paneItem(id).testTag(id).size(size).clip(CircleShape).background(fill)
            .glideBorder(hot, CircleShape, pal.signal, if (big) Color.Transparent else Color.Transparent)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Icon(
            icon, contentDescription = null,
            tint = when {
                big -> colors.background
                !enabled -> colors.onSurfaceVariant.copy(alpha = 0.4f)
                on -> pal.signal
                else -> colors.onBackground
            },
            modifier = Modifier.size(if (big) 34.dp else 26.dp),
        )
    }
}

@Composable
private fun SeeMoreButton(label: String, id: String, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(10.dp)
    val accent = if (danger) colors.error else pal.signal
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxWidth().paneItem(id).testTag(id).heightIn(min = 44.dp).clip(shape)
            .background(if (hot) accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.09f))
            .glideBorder(hot, shape, accent, Color.White.copy(alpha = 0.10f))
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            color = if (danger && hot) colors.error else colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SoundtrackActions(game: Library.SteamGame, s: FrontEndState, a: FrontEndActions) {
    var options by remember { mutableStateOf(false) }
    SeeMoreButton(stringResource(R.string.soundtracks_options), "sound:options") { options = true }
    if (options) SoundtrackOptionsDialog { options = false }
    game.gameFiles?.takeIf { it.isDirectory }?.let { dir ->
        SeeMoreButton(stringResource(R.string.games_files), "sound:files") { a.onBrowseFiles(dir) }
    }
    var menu by remember(game.appId) { mutableStateOf(false) }
    Box {
        SeeMoreButton(stringResource(R.string.game_shortcut), "sound:shortcut") { menu = !menu }
        AnchoredMenu(menu, onDismiss = { menu = false }, title = stringResource(R.string.game_shortcut)) { first ->
            MenuItem(stringResource(R.string.game_shortcut_add), checked = false, focusRequester = first) { menu = false; a.onGameShortcut(game) }
            MenuItem(stringResource(R.string.game_link_copy), checked = false) { menu = false; a.onCopyGameLink(game) }
        }
    }
    if (game.library != Library.ADDED) SeeMoreButton(stringResource(R.string.game_edit_art), "sound:art") { GamesDialogState.steamArt = game }
    var asking by remember(game.appId) { mutableStateOf(false) }
    val added = game.library == Library.ADDED
    val label = stringResource(if (added) R.string.edit_remove else R.string.common_uninstall)
    SeeMoreButton(label, "sound:remove", danger = true) { asking = true }
    if (asking) ConfirmDialog(
        title = if (added) stringResource(R.string.games_remove_title, game.name) else stringResource(R.string.stores_uninstall_title, game.name),
        text = if (added) stringResource(R.string.games_remove_body) else stringResource(R.string.games_uninstall_body),
        confirm = label,
        onConfirm = { a.onSoundtrackRemoved(game) },
        onDismiss = { asking = false },
    )
}

@Composable
private fun TrackList(game: Library.SteamGame, album: Soundtrack.Album, modifier: Modifier) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val rows = remember(album) { rowsOf(album) }
    val state = rememberLazyListState()
    val belongs = SoundtrackController.belongs(game.gameFiles)
    val current = if (belongs) SoundtrackController.currentId else null
    LaunchedEffect(album, current) {
        val at = rows.indexOfFirst { it is SoundRow.Item && it.track.id == current }
        if (at >= 0) state.animateScrollToItem(at)
    }
    if (rows.isEmpty()) {
        Text(stringResource(R.string.soundtracks_no_tracks), fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = modifier.padding(top = 12.dp))
        return
    }
    LazyColumn(
        state = state, modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.28f)).testTag("soundtrack-tracks"),
    ) {
        items(rows, key = { if (it is SoundRow.Item) it.track.id else "disc-${(it as SoundRow.Disc).number}" }) { row ->
            when (row) {
                is SoundRow.Disc -> Text(
                    stringResource(R.string.soundtracks_disc, row.label), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                    modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 8.dp),
                )
                is SoundRow.Item -> TrackRow(row.track, row.track.id == current) {
                    if (row.track.id == current) SoundtrackController.toggle(ctx, album, game.name, game.gameFiles)
                    else SoundtrackController.play(ctx, album, game.name, row.track.id, root = game.gameFiles)
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Soundtrack.Track, current: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(8.dp)
    val tint = if (current) pal.signal else colors.onBackground
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp).clip(shape)
            .background(if (current) pal.signal.copy(alpha = 0.14f) else if (hot) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .glideBorder(hot, shape, pal.signal, Color.Transparent)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
            if (current && SoundtrackController.playing) Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            else Text("${track.number}.", fontSize = 13.sp, color = if (current) pal.signal else colors.onSurfaceVariant, maxLines = 1)
        }
        Text(
            track.title, fontSize = 14.sp, color = tint, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (track.durationMs > 0) Text(Soundtrack.formatLength(track.durationMs), fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}
