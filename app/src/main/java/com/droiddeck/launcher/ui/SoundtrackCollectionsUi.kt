package com.droiddeck.launcher.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.Soundtrack
import com.droiddeck.launcher.frontend.SoundtrackCollections
import com.droiddeck.launcher.frontend.SoundtrackIndex
import com.droiddeck.launcher.frontend.SoundtrackTracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SavedDialog(pending: List<SoundtrackCollections.TrackRef>, onOpen: ((Int) -> Unit)?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    remember { SoundtrackCollections.load(ctx) }
    val snapshot = SoundtrackCollections.snapshot
    val favoritesLabel = stringResource(R.string.soundtracks_favorites)
    var naming by remember { mutableStateOf(false) }
    val rows = listOf(Triple(SoundtrackCollections.FAVORITES_ID, favoritesLabel, snapshot.favorites)) +
        snapshot.collections.map { Triple(it.id, it.name, it.tracks) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.soundtracks_saved), modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Bookmark, contentDescription = null, tint = colors.onSurfaceVariant)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SavedRow(stringResource(R.string.soundtracks_create_collection), stringResource(R.string.soundtracks_organise), null, "saved-create", null) {
                    TextButton(onClick = { naming = true }, modifier = Modifier.focusGlide(CircleShape).testTag("saved-new")) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.soundtracks_new))
                    }
                }
                for ((id, name, tracks) in rows) {
                    val cover = tracks.firstNotNullOfOrNull { t -> t.cover.takeIf { it.isNotEmpty() }?.let { java.io.File(it) }?.takeIf { it.isFile } }
                    val subtitle = stringResource(R.string.soundtracks_length_value, tracks.size, Soundtrack.formatLength(tracks.sumOf { it.durationMs }))
                    val added = pending.isNotEmpty() && pending.all { p -> tracks.any { it.path == p.path } }
                    SavedRow(name, subtitle, cover, "saved-$id", if (pending.isEmpty() && onOpen != null) ({ onDismiss(); onOpen(id) }) else null) {
                        if (pending.isNotEmpty()) CircleAction(if (added) Icons.Filled.Check else Icons.Filled.Add, name, "saved-add-$id") {
                            if (added) SoundtrackCollections.remove(ctx, id, pending.map { it.path }.toSet())
                            else SoundtrackCollections.add(ctx, id, pending)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_close)) } },
    )
    if (naming) NameDialog(
        stringResource(R.string.soundtracks_create_collection), "", stringResource(R.string.soundtracks_create),
        onConfirm = { name ->
            val id = SoundtrackCollections.create(ctx, name, pending)
            if (id != null && pending.isEmpty() && onOpen != null) { onDismiss(); onOpen(id) }
            id != null
        },
        onDismiss = { naming = false },
    )
}

@Composable
private fun SavedRow(title: String, subtitle: String, cover: Any?, tag: String, onClick: (() -> Unit)?, trailing: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && onClick != null
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().testTag(tag).clip(shape).background(if (hot) pal.signal.copy(alpha = 0.12f) else Color.Transparent)
            .glideBorder(hot, shape, pal.signal, Color.Transparent)
            .then(
                if (onClick != null) Modifier.hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
                    .controllerConfirm(onClick = onClick)
                else Modifier,
            )
            .padding(vertical = 4.dp),
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f))) {
            if (cover != null) AsyncImage(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun CircleAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.testTag(tag).size(40.dp).clip(CircleShape).background(if (hot) pal.signal.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (hot) pal.signal else colors.onSurface.copy(alpha = 0.7f), CircleShape)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = label },
    ) { Icon(icon, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(20.dp)) }
}

@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Boolean, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var invalid by remember { mutableStateOf(false) }
    val submit: () -> Unit = {
        val ok = onConfirm(text)
        if (ok) onDismiss()
        invalid = !ok
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdbTextField(
                    value = text, onValueChange = { text = it; invalid = false }, label = stringResource(R.string.soundtracks_collection_name),
                    keyboardType = KeyboardType.Text, imeAction = ImeAction.Done, focusOnShow = true, onDone = submit,
                )
                if (invalid) Text(stringResource(R.string.soundtracks_name_invalid), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = submit, modifier = Modifier.focusGlide(CircleShape).testTag("name-confirm")) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
internal fun ManageDialog(id: Int, games: List<Library.SteamGame>, onRemoveMode: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val name = SoundtrackCollections.snapshot.collections.firstOrNull { it.id == id }?.name.orEmpty()
    val favorites = id == SoundtrackCollections.FAVORITES_ID
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.soundtracks_manage)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!favorites) TextButton(onClick = { renaming = true }, modifier = Modifier.fillMaxWidth().focusGlide(CircleShape).testTag("manage-rename")) {
                    Text(stringResource(R.string.soundtracks_rename))
                }
                TextButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().focusGlide(CircleShape).testTag("manage-add")) {
                    Text(stringResource(R.string.soundtracks_add_tracks))
                }
                TextButton(onClick = { onDismiss(); onRemoveMode() }, modifier = Modifier.fillMaxWidth().focusGlide(CircleShape).testTag("manage-remove")) {
                    Text(stringResource(R.string.soundtracks_remove_tracks))
                }
                if (!favorites) TextButton(onClick = { deleting = true }, modifier = Modifier.fillMaxWidth().focusGlide(CircleShape).testTag("manage-delete")) {
                    Text(stringResource(R.string.soundtracks_delete_collection), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_close)) } },
    )
    if (renaming) NameDialog(
        stringResource(R.string.soundtracks_rename), name, stringResource(R.string.soundtracks_rename),
        onConfirm = { SoundtrackCollections.rename(ctx, id, it) }, onDismiss = { renaming = false },
    )
    if (adding) TrackPickerDialog(id, games) { adding = false }
    if (deleting) ConfirmDialog(
        title = stringResource(R.string.soundtracks_delete_title, name), text = stringResource(R.string.soundtracks_delete_body),
        confirm = stringResource(R.string.common_delete),
        onConfirm = { SoundtrackCollections.delete(ctx, id); onDismiss() },
        onDismiss = { deleting = false },
    )
}

@Composable
internal fun TrackPickerDialog(id: Int, games: List<Library.SteamGame>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val hits by produceState<List<SoundtrackIndex.Hit>?>(null, games) { value = withContext(Dispatchers.IO) { SoundtrackIndex.build(ctx, games) } }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val have = SoundtrackCollections.tracksOf(id).mapTo(HashSet()) { it.path }
    val list = hits?.filter { it.ref.path !in have && SoundtrackTracks.matches(query, it.ref.title, it.ref.artist, it.ref.album) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.soundtracks_add_tracks)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdbTextField(
                    value = query, onValueChange = { query = it }, label = "", keyboardType = KeyboardType.Text, imeAction = ImeAction.Search,
                    placeholder = stringResource(R.string.soundtracks_search_tracks), compact = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picked = picked + list.orEmpty().map { it.ref.path } }, modifier = Modifier.focusGlide(CircleShape)) {
                        Text(stringResource(R.string.soundtracks_select_all))
                    }
                    TextButton(onClick = { picked = emptySet() }, modifier = Modifier.focusGlide(CircleShape)) {
                        Text(stringResource(R.string.soundtracks_deselect_all))
                    }
                }
                if (list == null) Text(stringResource(R.string.store_section_loading), fontSize = 13.sp, color = colors.onSurfaceVariant)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp).testTag("track-picker")) {
                    items(list, key = { it.ref.path }) { hit ->
                        val on = hit.ref.path in picked
                        val src = remember { MutableInteractionSource() }
                        val hot = rememberHot(src)
                        val toggle = { picked = if (on) picked - hit.ref.path else picked + hit.ref.path }
                        Row(
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) pal.signal.copy(alpha = 0.16f) else if (hot) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                                .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Checkbox, onClick = toggle)
                                .controllerConfirm(onClick = toggle).padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                if (on) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, contentDescription = null,
                                tint = if (on) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(hit.ref.title, fontSize = 14.sp, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(hit.ref.album, hit.ref.artist).filter { it.isNotBlank() }.joinToString(" · "),
                                    fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (hit.ref.durationMs > 0) Text(Soundtrack.formatLength(hit.ref.durationMs), fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked.isNotEmpty(),
                onClick = {
                    SoundtrackCollections.add(ctx, id, hits.orEmpty().filter { it.ref.path in picked }.map { it.ref })
                    onDismiss()
                },
                modifier = Modifier.focusGlide(CircleShape).testTag("picker-add"),
            ) { Text(stringResource(R.string.soundtracks_add_count, picked.size)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) } },
    )
}
