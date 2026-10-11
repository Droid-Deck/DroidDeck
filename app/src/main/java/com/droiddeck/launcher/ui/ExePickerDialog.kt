package com.droiddeck.launcher.ui

import android.content.Context
import android.os.Environment
import android.os.StatFs
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.stores.formatBytes
import java.io.File

/** What a picker lists besides folders. */
internal enum class PickKind(val extensions: Set<String>) {
    EXE(setOf("exe")),
    IMAGE(setOf("png", "jpg", "jpeg", "webp", "ico")),
}

/** A place a file can be picked from: internal storage or a card, at its root. */
internal class PickVolume(val root: File, val label: String, val removable: Boolean) {
    val free: Long get() = runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L)
}

internal fun pickVolumes(context: Context): List<PickVolume> =
    listOf(PickVolume(Environment.getExternalStorageDirectory(), context.getString(R.string.games_internal), removable = false)) +
        GameStorage.options(context).mapNotNull { o ->
            File(o.path.substringBefore("/Android/")).takeIf { it.isDirectory }?.let { PickVolume(it, o.name, removable = true) }
        }.distinctBy { it.root.absolutePath }

/** A folder in a listing; [readable] false = shown greyed, not opened. */
internal class PickFolder(val dir: File, val readable: Boolean)

internal class PickListing(val folders: List<PickFolder>, val files: List<File>)

/**
 * Every folder of [dir] (dot-folders left out, the unreadable ones marked), then the files [kind]
 * takes; each A-Z.
 */
internal fun pickListing(dir: File, kind: PickKind): PickListing {
    val all = dir.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty().sortedBy { it.name.lowercase() }
    return PickListing(
        all.filter { it.isDirectory }.map { PickFolder(it, it.canRead() && it.list() != null) },
        all.filter { it.isFile && it.extension.lowercase() in kind.extensions },
    )
}

/**
 * The compact in-app file picker: [title] and a red X on one line; the storage rows (internal
 * storage, each card, free space); inside one, its root first, a breadcrumb, "..", every folder
 * and the [kind] files, scrolling in the dialog. Its width is what the first step needed and stays;
 * long names are cut short. B / Back goes up a folder, and closes from the storage rows. [start]
 * opens in a folder. [onAddAll] adds a row on top of a folder with subfolders.
 */
@Composable
internal fun ExePickerDialog(
    title: String, kind: PickKind = PickKind.EXE, start: File? = null,
    onAddAll: ((File) -> Unit)? = null, onPick: (File) -> Unit, onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    val volumes = remember { pickVolumes(ctx) }
    fun volumeOf(d: File) = volumes.filter { d.path == it.root.path || d.path.startsWith(it.root.path + "/") }.maxByOrNull { it.root.path.length }
    var dir by remember { mutableStateOf(start?.takeIf { it.isDirectory && volumeOf(it) != null }) }
    val volume = dir?.let { volumeOf(it) }
    val up = { dir = dir?.let { d -> if (volume == null || d.path == volume.root.path) null else d.parentFile } }
    val back = { if (dir == null) close() else up() }
    val first = remember(dir) { FocusRequester() }
    LaunchedEffect(dir) { focusWithinFrames({ false }) { first } }
    val listing = remember(dir) { dir?.let { pickListing(it, kind) } }
    var locked by remember { mutableStateOf<Dp?>(null) }
    AppDialog(
        shown, close, "filePicker", wide = false, maxWidth = 520.dp, width = locked, fitContent = locked == null, back = back,
        modifier = Modifier.onSizeChanged { if (locked == null && it.width > 0) locked = with(density) { it.width.toDp() } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            DialogTitleRow(title, onClose = close)
            if (dir != null && volume != null) {
                val crumbs = listOf(volume.label) + dir!!.path.removePrefix(volume.root.path).split('/').filter { it.isNotEmpty() }
                Text(crumbs.joinToString(" › "), fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(top = 4.dp),
            ) {
                if (dir == null) {
                    volumes.forEachIndexed { i, v ->
                        key(v.root.path) {
                            PickerRow(if (v.removable) Icons.Outlined.SdCard else Icons.Outlined.Smartphone, v.label,
                                stringResource(R.string.stores_target_free, formatBytes(v.free)), tall = true, focus = if (i == 0) first else null) { dir = v.root }
                        }
                    }
                } else {
                    val shownListing = listing ?: PickListing(emptyList(), emptyList())
                    PickerRow(Icons.AutoMirrored.Outlined.ArrowBack, "..", null, focus = first, onClick = up)
                    if (onAddAll != null && shownListing.folders.any { it.readable }) {
                        PickerRow(Icons.Filled.Add, stringResource(R.string.games_add_all), null, accent = true) { onAddAll(dir!!); close() }
                    }
                    for (f in shownListing.folders) key(f.dir.path) {
                        PickerRow(Icons.Outlined.Folder, f.dir.name, null, enabled = f.readable) { dir = f.dir }
                    }
                    for (f in shownListing.files) key(f.path) {
                        PickerRow(if (kind == PickKind.IMAGE) Icons.Outlined.Image else Icons.Outlined.InsertDriveFile, f.name, formatBytes(f.length()), file = true) {
                            onPick(f); close()
                        }
                    }
                }
            }
        }
    }
}

/** A compact dialog's title line: [leading] (a back arrow), the title, a small red X. */
@Composable
internal fun DialogTitleRow(title: String, onClose: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        leading?.invoke()
        Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        SmallIconButton(Icons.Filled.Close, stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.error, onClick = onClose)
    }
}

/** A 32 dp icon button for a dialog's title line. */
@Composable
internal fun SmallIconButton(icon: ImageVector, description: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(32.dp).clip(Shape12)
            .background(if (hot) tint.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
            .glideBorder(hot, Shape12, tint)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** One row of a compact dialog's list, the Install dialog's style: [content] laid out in a row. */
@Composable
internal fun ListRow(
    enabled: Boolean = true, accent: Boolean = false, tall: Boolean = false, focus: FocusRequester? = null,
    onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.(hot: Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier).clip(Shape12)
            .alpha(if (enabled) 1f else 0.4f)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else colors.surface.copy(alpha = 0.6f))
            .glideBorder(hot, Shape12, pal.signal, if (accent) pal.signal else pal.line)
            .then(
                if (enabled) Modifier.hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
                    .controllerConfirm(onClick = onClick) else Modifier,
            )
            .padding(horizontal = 12.dp, vertical = if (tall) 11.dp else 9.dp),
    ) { content(hot) }
}

/** A list row with an icon, a name (cut short when long) and a detail on the right. */
@Composable
internal fun PickerRow(
    icon: ImageVector?, name: String, detail: String?, tall: Boolean = false, file: Boolean = false, accent: Boolean = false,
    enabled: Boolean = true, focus: FocusRequester? = null, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    ListRow(enabled, accent, tall, focus, onClick) { hot ->
        if (icon != null) Icon(icon, null, tint = if (hot || file || accent) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(name, fontSize = 14.sp, fontWeight = if (tall) FontWeight.SemiBold else FontWeight.Normal, color = colors.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (detail != null) Text(detail, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
}
