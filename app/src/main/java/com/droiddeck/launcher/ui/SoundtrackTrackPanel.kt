package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Soundtrack
import com.droiddeck.launcher.frontend.SoundtrackCollections
import com.droiddeck.launcher.frontend.SoundtrackTracks
import com.droiddeck.launcher.playback.SoundtrackController
import kotlinx.coroutines.launch

internal class PlayTarget(val root: java.io.File?, val collection: Int?) {
    val belongs: Boolean get() = if (collection != null) SoundtrackController.belongsCollection(collection) else SoundtrackController.belongs(root)
}

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
private fun sortLabel(sort: SoundtrackTracks.Sort): String = stringResource(
    when (sort) {
        SoundtrackTracks.Sort.DEFAULT -> R.string.soundtracks_sort_default
        SoundtrackTracks.Sort.TITLE_ASC -> R.string.stores_sort_title_asc
        SoundtrackTracks.Sort.TITLE_DESC -> R.string.stores_sort_title_desc
        SoundtrackTracks.Sort.LONGEST -> R.string.soundtracks_sort_longest
        SoundtrackTracks.Sort.SHORTEST -> R.string.soundtracks_sort_shortest
    },
)

@Composable
internal fun TrackPanel(
    album: Soundtrack.Album, title: String, target: PlayTarget, refOf: (Soundtrack.Track) -> SoundtrackCollections.TrackRef,
    collectionId: Int?, selectRequest: Int, onReorder: ((List<String>) -> Unit)?, modifier: Modifier,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SoundtrackTracks.Sort.DEFAULT) }
    var sortMenu by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var saving by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        selecting = false
        picked = emptySet()
    }
    LaunchedEffect(selectRequest) { if (selectRequest > 0) selecting = true }
    BackHandler(enabled = selecting) { leave() }
    val rows: List<SoundRow> = remember(album, query, sort) {
        if (sort == SoundtrackTracks.Sort.DEFAULT && query.isBlank()) rowsOf(album)
        else SoundtrackTracks.sort(
            album.tracks.filter { SoundtrackTracks.matches(query, it.title, it.artist) }, sort, { it.title }, { it.durationMs },
        ).map { SoundRow.Item(it) }
    }
    var working by remember(rows) { mutableStateOf<List<SoundRow>?>(null) }
    val display = working ?: rows
    val shown = rows.filterIsInstance<SoundRow.Item>().map { it.track }
    val canReorder = onReorder != null && sort == SoundtrackTracks.Sort.DEFAULT && query.isBlank() && !selecting
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val numbers = remember(display) {
        val out = HashMap<String, Int>()
        var n = 0
        for (row in display) {
            if (row is SoundRow.Disc) n = 0
            else if (row is SoundRow.Item) out[row.track.id] = ++n
        }
        out
    }
    val finishDrag: () -> Unit = {
        val list = working
        dragId = null
        dragOffset = 0f
        if (list != null) {
            val now = list.filterIsInstance<SoundRow.Item>().map { it.track.id }
            if (now == shown.map { it.id }) working = null else onReorder?.invoke(now)
        }
    }
    val current = if (target.belongs) SoundtrackController.currentId else null
    val state = rememberLazyListState()
    LaunchedEffect(album, current) {
        val at = rows.indexOfFirst { it is SoundRow.Item && it.track.id == current }
        if (at >= 0 && dragId == null) state.animateScrollToItem(at)
    }
    val chosen = album.tracks.filter { it.id in picked }.map(refOf)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                AdbTextField(
                    value = query, onValueChange = { query = it }, label = "", keyboardType = KeyboardType.Text, imeAction = ImeAction.Search,
                    placeholder = stringResource(R.string.soundtracks_search_tracks), compact = true,
                )
            }
            Box {
                HeaderIconButton(stringResource(R.string.stores_sort), Icons.Filled.Sort, "tracks-sort") { sortMenu = !sortMenu }
                AnchoredMenu(sortMenu, onDismiss = { sortMenu = false }, title = stringResource(R.string.stores_sort)) { first ->
                    SoundtrackTracks.Sort.values().forEachIndexed { i, option ->
                        MenuItem(sortLabel(option), checked = option == sort, focusRequester = if (i == 0) first else null) {
                            sort = option
                            sortMenu = false
                        }
                    }
                }
            }
            HeaderIconButton(stringResource(R.string.soundtracks_select), Icons.Filled.CheckBox, "tracks-select") {
                if (selecting) leave() else selecting = true
            }
        }
        if (selecting) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.soundtracks_selected, picked.size), fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
            TextButton(onClick = { picked = shown.map { it.id }.toSet() }, modifier = Modifier.focusGlide(CircleShape).testTag("tracks-select-all")) {
                Text(stringResource(R.string.soundtracks_select_all))
            }
            TextButton(onClick = { picked = emptySet() }, modifier = Modifier.focusGlide(CircleShape).testTag("tracks-deselect-all")) {
                Text(stringResource(R.string.soundtracks_deselect_all))
            }
            TextButton(enabled = picked.isNotEmpty(), onClick = { saving = true }, modifier = Modifier.focusGlide(CircleShape).testTag("tracks-add-collection")) {
                Text(stringResource(R.string.soundtracks_add_to_collection))
            }
            TextButton(
                enabled = picked.isNotEmpty(),
                onClick = {
                    SoundtrackCollections.add(ctx, SoundtrackCollections.FAVORITES_ID, chosen)
                    leave()
                },
                modifier = Modifier.focusGlide(CircleShape).testTag("tracks-add-favorites"),
            ) { Text(stringResource(R.string.soundtracks_add_favorites)) }
            if (collectionId != null) TextButton(
                enabled = picked.isNotEmpty(),
                onClick = {
                    SoundtrackCollections.remove(ctx, collectionId, picked)
                    leave()
                },
                modifier = Modifier.focusGlide(CircleShape).testTag("tracks-remove"),
            ) { Text(stringResource(R.string.soundtracks_remove_from_collection)) }
            TextButton(onClick = leave, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) }
        }
        if (display.isEmpty()) Text(
            stringResource(if (album.tracks.isEmpty()) R.string.soundtracks_no_tracks else R.string.stores_nothing_matches),
            fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
        )
        else LazyColumn(
            state = state,
            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.28f)).testTag("soundtrack-tracks"),
        ) {
            items(display, key = { if (it is SoundRow.Item) it.track.id else "disc-${(it as SoundRow.Disc).number}" }) { row ->
                when (row) {
                    is SoundRow.Disc -> Text(
                        stringResource(R.string.soundtracks_disc, row.label), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                        modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    is SoundRow.Item -> {
                        val id = row.track.id
                        val flip = { picked = if (id in picked) picked - id else picked + id }
                        val dragging = dragId == id
                        val dragModifier = if (!canReorder) Modifier else Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                            .pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragId = id
                                        dragOffset = 0f
                                        if (working == null) working = rows
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val list = working ?: return@detectDragGesturesAfterLongPress
                                        val visible = state.layoutInfo.visibleItemsInfo
                                        val me = visible.firstOrNull { it.key == id } ?: return@detectDragGesturesAfterLongPress
                                        val center = me.offset + me.size / 2f + dragOffset
                                        val over = visible.firstOrNull { it.key != id && center >= it.offset && center < it.offset + it.size }
                                        if (over != null) {
                                            val from = list.indexOfFirst { it is SoundRow.Item && it.track.id == id }
                                            val to = list.indexOfFirst { it is SoundRow.Item && it.track.id == over.key }
                                            val a = list.getOrNull(from) as? SoundRow.Item
                                            val b = list.getOrNull(to) as? SoundRow.Item
                                            if (a != null && b != null && a.track.disc == b.track.disc) {
                                                working = list.toMutableList().apply { add(to, removeAt(from)) }
                                                dragOffset -= (over.offset - me.offset)
                                            }
                                        }
                                        val edge = 80f
                                        val pos = me.offset + me.size / 2f + dragOffset
                                        val step = when {
                                            pos < edge -> -24f
                                            pos > state.layoutInfo.viewportEndOffset - edge -> 24f
                                            else -> 0f
                                        }
                                        if (step != 0f) scope.launch { dragOffset += state.scrollBy(step) }
                                    },
                                    onDragEnd = { finishDrag() },
                                    onDragCancel = { finishDrag() },
                                )
                            }
                        TrackRow(
                            row.track, numbers[id] ?: row.track.number, dragging, dragModifier, id == current, selecting, id in picked,
                            onClick = {
                                if (selecting) flip()
                                else if (id == current) SoundtrackController.toggle(ctx, album, title, target.root, target.collection)
                                else SoundtrackController.play(ctx, album, title, id, root = target.root, collection = target.collection)
                            },
                            onLongClick = if (canReorder) null else ({
                                if (selecting) flip()
                                else {
                                    selecting = true
                                    picked = setOf(id)
                                }
                            }),
                        )
                    }
                }
            }
        }
    }
    if (saving) SavedDialog(chosen, null) {
        saving = false
        leave()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: Soundtrack.Track, number: Int, dragging: Boolean, extra: Modifier, current: Boolean, selecting: Boolean, selected: Boolean,
    onClick: () -> Unit, onLongClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(8.dp)
    val tint = if (current) pal.signal else colors.onBackground
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = extra.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp).clip(shape)
            .background(if (dragging) pal.signal.copy(alpha = 0.32f) else if (selected) pal.signal.copy(alpha = 0.24f) else if (current) pal.signal.copy(alpha = 0.14f) else if (hot) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .glideBorder(hot, shape, pal.signal, Color.Transparent)
            .hoverable(src)
            .combinedClickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                selecting -> Icon(
                    if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, contentDescription = null,
                    tint = if (selected) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp),
                )
                current && SoundtrackController.playing -> Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                else -> Text("$number.", fontSize = 13.sp, color = if (current) pal.signal else colors.onSurfaceVariant, maxLines = 1)
            }
        }
        Text(
            track.title, fontSize = 14.sp, color = tint, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (track.durationMs > 0) Text(Soundtrack.formatLength(track.durationMs), fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}
