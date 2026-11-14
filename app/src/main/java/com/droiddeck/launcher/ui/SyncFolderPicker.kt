package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.sync.Syncthing
import java.io.File

internal class SyncLocation(val root: File, val label: String, val icon: ImageVector)

internal fun containerHome(context: Context) = File(LinuxRuntime.rootDir(context), "root")

internal fun syncLocations(context: Context): List<SyncLocation> {
    val volumes = pickVolumes(context)
    val roots = Syncthing.storageRoots(context)
    val shared = roots.drop(1).mapIndexed { i, root ->
        val volume = volumes.firstOrNull { root.path.startsWith(it.root.path + "/") }
        if (i == 0) SyncLocation(root, context.getString(R.string.sync_location_shared), Icons.Outlined.Smartphone)
        else SyncLocation(root, context.getString(R.string.sync_location_card, volume?.label ?: root.path), Icons.Outlined.SdCard)
    }
    val home = containerHome(context).takeIf { it.isDirectory }?.let { SyncLocation(it, context.getString(R.string.sync_location_home), Icons.Outlined.Home) }
    return listOfNotNull(home, SyncLocation(Syncthing.folderRoot(context), context.getString(R.string.sync_location_sync), Icons.Outlined.FolderSpecial)) +
        shared + SyncLocation(roots.first(), context.getString(R.string.sync_location_app), Icons.Outlined.Folder)
}

@Composable
internal fun SyncFolderPicker(title: String, suggestedName: String?, onDismiss: () -> Unit, onPick: (File) -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    val locations = remember { syncLocations(ctx) }
    val guard = remember { Syncthing.guard(ctx) }
    fun locationOf(d: File) = locations.filter { d.path == it.root.path || d.path.startsWith(it.root.path + "/") }.maxByOrNull { it.root.path.length }
    var dir by remember { mutableStateOf<File?>(containerHome(ctx).takeIf { it.isDirectory } ?: Syncthing.folderRoot(ctx).also { it.mkdirs() }) }
    var refresh by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    val location = dir?.let { locationOf(it) }
    val up = { dir = dir?.let { d -> if (location == null || d.path == location.root.path) null else d.parentFile } }
    val back = { if (dir == null) close() else up() }
    val first = remember(dir) { FocusRequester() }
    LaunchedEffect(dir) { focusWithinFrames({ false }) { first } }
    val folders = remember(dir, refresh) {
        dir?.listFiles()?.filter { it.isDirectory && it.canRead() && guard.browsable(it) }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
    }
    var locked by remember { mutableStateOf<Dp?>(null) }
    if (naming) {
        TextEntryDialog(stringResource(R.string.sync_new_folder), suggestedName.orEmpty(), onDismiss = { naming = false }) { name ->
            val parent = dir
            if (parent != null && validName(name)) {
                val created = File(parent, name)
                if (created.isDirectory || created.mkdirs()) { dir = created; refresh++ }
            }
        }
    }
    AppDialog(
        shown, close, "syncFolderPicker", wide = false, maxWidth = 520.dp, width = locked, fitContent = locked == null, back = back,
        modifier = Modifier.onSizeChanged { if (locked == null && it.width > 0) locked = with(density) { it.width.toDp() } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            DialogTitleRow(title, onClose = close)
            if (dir != null && location != null) {
                val crumbs = listOf(location.label) + dir!!.path.removePrefix(location.root.path).split('/').filter { it.isNotEmpty() }
                Text(crumbs.joinToString(" › "), fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(top = 4.dp),
            ) {
                if (dir == null) {
                    locations.forEachIndexed { i, l ->
                        key(l.root.path) { PickerRow(l.icon, l.label, null, tall = true, focus = if (i == 0) first else null) { dir = l.root.also { it.mkdirs() } } }
                    }
                } else {
                    PickerRow(Icons.AutoMirrored.Outlined.ArrowBack, "..", null, focus = first, onClick = up)
                    PickerRow(Icons.Filled.Check, stringResource(R.string.sync_use_folder), null, accent = true, enabled = guard.allows(dir!!.path)) {
                        onPick(dir!!); close()
                    }
                    PickerRow(Icons.Outlined.CreateNewFolder, stringResource(R.string.sync_new_folder), null) { naming = true }
                    for (f in folders) key(f.path) {
                        PickerRow(Icons.Outlined.Folder, f.name, null) { dir = f }
                    }
                }
            }
        }
    }
}

private fun validName(name: String) = name.isNotBlank() && '/' !in name && name != "." && name != ".."
