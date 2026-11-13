package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
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
    val rowIds = rows.filterIsInstance<SoundRow.Item>().map { it.track.id }
    var working by remember(rowIds) { mutableStateOf<List<SoundRow>?>(null) }
    val display = working ?: rows
    val canReorder = onReorder != null && sort == SoundtrackTracks.Sort.DEFAULT && query.isBlank() && selecting
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    var dragTo by remember { mutableIntStateOf(0) }
    val numbers = remember(display) {
        val out = HashMap<String, Int>()
        var n = 0
        for (row in display) {
            if (row is SoundRow.Disc) n = 0
            else if (row is SoundRow.Item) out[row.track.id] = ++n
        }
        out
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drag = remember { DragState() }
    fun applyDrag() {
        drag.total = drag.total.coerceIn((drag.lo - drag.from) * drag.size, (drag.hi - drag.from) * drag.size)
        dragTotal = drag.total
        dragTo = dragTarget(drag.total, drag.size, drag.from, drag.lo, drag.hi)
    }
    val finishDrag: () -> Unit = {
        if (dragId != null) {
            val from = drag.from
            val to = dragTo
            if (to != from && from in drag.base.indices) {
                val moved = drag.base.toMutableList().apply { add(to, removeAt(from)) }
                val index = state.firstVisibleItemIndex
                val offset = state.firstVisibleItemScrollOffset
                working = moved
                scope.launch { state.scrollToItem(index, offset) }
                onReorder?.invoke(moved.filterIsInstance<SoundRow.Item>().map { it.track.id })
            }
            dragId = null
            dragTotal = 0f
            dragTo = from
        }
    }
    val rowsNow by rememberUpdatedState(rows)
    val finishNow by rememberUpdatedState(finishDrag)
    val current = if (target.belongs) SoundtrackController.currentId else null
    var scrolledTo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(album, current) {
        if (current == null) {
            scrolledTo = null
            return@LaunchedEffect
        }
        if (dragId != null || current == scrolledTo) return@LaunchedEffect
        val at = rows.indexOfFirst { it is SoundRow.Item && it.track.id == current }
        if (at >= 0) {
            scrolledTo = current
            state.animateScrollToItem(at)
        }
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
            TextButton(onClick = { picked = rowIds.toSet() }, modifier = Modifier.focusGlide(CircleShape).testTag("tracks-select-all")) {
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
                        val draggable = canReorder && id in picked
                        val shift = if (!canReorder) Modifier else Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = when {
                                    dragId == null -> 0f
                                    dragId == id -> dragTotal
                                    else -> {
                                        val at = drag.index[id] ?: -1
                                        val f = drag.from
                                        val t = dragTo
                                        if (at < 0) 0f
                                        else if (f < t && at in (f + 1)..t) -drag.size
                                        else if (t < f && at in t until f) drag.size
                                        else 0f
                                    }
                                }
                            }
                        val dragModifier = if (!draggable) shift else shift
                            .pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        val base = working ?: rowsNow
                                        val from = base.indexOfFirst { it is SoundRow.Item && it.track.id == id }
                                        if (from >= 0) {
                                            val disc = (base[from] as SoundRow.Item).track.disc
                                            var lo = from
                                            while (lo > 0 && (base[lo - 1] as? SoundRow.Item)?.track?.disc == disc) lo--
                                            var hi = from
                                            while (hi < base.lastIndex && (base[hi + 1] as? SoundRow.Item)?.track?.disc == disc) hi++
                                            val visible = state.layoutInfo.visibleItemsInfo
                                            val me = visible.firstOrNull { it.key == id }
                                            val next = visible.firstOrNull { it.index == (me?.index ?: -2) + 1 && it.key != "disc-0" && base.getOrNull(from + 1) is SoundRow.Item }
                                            val prev = visible.firstOrNull { it.index == (me?.index ?: -2) - 1 && base.getOrNull(from - 1) is SoundRow.Item }
                                            val pitch = when {
                                                me != null && next != null -> (next.offset - me.offset).toFloat()
                                                me != null && prev != null -> (me.offset - prev.offset).toFloat()
                                                else -> me?.size?.toFloat() ?: 0f
                                            }
                                            drag.base = base
                                            drag.index = HashMap<String, Int>().also { m -> base.forEachIndexed { i, r -> if (r is SoundRow.Item) m[r.track.id] = i } }
                                            drag.from = from
                                            drag.lo = lo
                                            drag.hi = hi
                                            drag.size = if (pitch > 0f) pitch else 1f
                                            drag.total = 0f
                                            dragTotal = 0f
                                            dragTo = from
                                            dragId = id
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        if (dragId == id) {
                                            drag.total += amount.y
                                            applyDrag()
                                        }
                                    },
                                    onDragEnd = { finishNow() },
                                    onDragCancel = { finishNow() },
                                )
                            }
                        TrackRow(
                            row.track, numbers[id] ?: row.track.number, dragging, dragModifier, id == current, selecting, id in picked,
                            onClick = {
                                if (selecting) flip()
                                else if (id == current) SoundtrackController.toggle(ctx, album, title, target.root, target.collection)
                                else SoundtrackController.play(ctx, album, title, id, root = target.root, collection = target.collection)
                            },
                            onLongClick = if (draggable) null else ({
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

private class DragState {
    var base: List<SoundRow> = emptyList()
    var index: Map<String, Int> = emptyMap()
    var from = 0
    var lo = 0
    var hi = 0
    var size = 1f
    var total = 0f
}

internal fun dragTarget(total: Float, size: Float, from: Int, lo: Int, hi: Int): Int =
    (from + Math.round(total / size)).coerceIn(lo, hi)
