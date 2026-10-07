package com.droiddeck.launcher.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalInspectionMode
import com.droiddeck.launcher.R
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    // Pick mode (issue #73): reuse this File Manager as a themed file picker. When on, editing/run
    // features are gated off and tapping a matching file returns it via [onPick]. Defaults keep the
    // full-featured File Manager nav destination unchanged.
    pickMode: Boolean = false,
    // Directory-pick mode (issue #70): only folders are listed, files are hidden, and a
    // "Select this folder" action returns the current directory via [onPick]. Implies pickMode.
    pickDirMode: Boolean = false,
    pickExtensions: List<String> = emptyList(),
    initialDir: File? = null,
    pickerTitle: String? = null,
    onPick: ((File) -> Unit)? = null,
    preview: FileManagerPreviewState? = null,
) {
    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    val sample = preview.takeIf { inspecting }
    val scope = rememberCoroutineScope()

    // Only matching files are shown in pick mode (directories are always shown). Empty = all files.
    val lowerExts = remember(pickExtensions) { pickExtensions.map { it.lowercase() } }
    fun matchesPickExt(file: File): Boolean {
        if (lowerExts.isEmpty()) return true
        val name = file.name.lowercase()
        return lowerExts.any { name.endsWith(".$it") }
    }

    val pickPrefs = remember { if (inspecting) null else context.getSharedPreferences("file_manager", android.content.Context.MODE_PRIVATE) }
    val browsePrefs = pickPrefs
    val rootDir = remember {
        // Both modes: honour an explicit caller-supplied start dir (e.g. Log Manager's game-log
        // folder), else open at the INTERNAL STORAGE ROOT. Selection screens (drive-folder pick,
        // local component pick, imports) previously defaulted to Download which - combined with the
        // currentRoot floor below - trapped users in Download with no way up (reported bug).
        if (inspecting) sample?.directory ?: File("/storage/emulated/0")
        else initialDir?.takeIf { it.isDirectory } ?: File("/storage/emulated/0")
    }

    var currentDir by remember { mutableStateOf(rootDir) }
    // The up/back FLOOR - back + the up-arrow are disabled while currentDir == currentRoot. It MUST be
    // the VOLUME ROOT of the start dir (internal /storage/emulated/0, or an SD card /storage/XXXX-XXXX),
    // NOT the start dir itself: otherwise opening at any subfolder disables up/back and traps the user
    // there. (Mirrors the volume-root logic in favLocationOf above.)
    var currentRoot by remember {
        val abs = rootDir.absolutePath
        val internal = "/storage/emulated/0"
        val vol = when {
            abs == internal || abs.startsWith("$internal/") -> File(internal)
            abs.startsWith("/storage/") -> {
                val name = abs.removePrefix("/storage/").substringBefore('/')
                if (name.isNotEmpty() && name != "emulated" && name != "self") File("/storage/$name") else rootDir
            }
            // A game's own folder or Proton prefix lives in the app's private storage, which has no
            // volume above it: floor at the app's files dir so up/back still reach the library roots.
            else -> {
                val appFiles = runCatching { context.filesDir.canonicalPath }.getOrNull()
                generateSequence(rootDir) { it.parentFile }
                    .firstOrNull { appFiles != null && runCatching { it.canonicalPath }.getOrNull() == appFiles }
                    ?: rootDir
            }
        }
        mutableStateOf(vol)
    }
    var entries by remember { mutableStateOf(sample?.entries.orEmpty()) }
    var selectedEntry by remember { mutableStateOf<File?>(null) }
    var showMenuFor by remember { mutableStateOf<File?>(null) }
    // Clipboard holds a LIST so one paste can carry a whole selection. Cut/copy semantics are a
    // flag on the batch rather than per item - mixing the two in one clipboard has no sane meaning.
    var clipboardFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isCutOperation by remember { mutableStateOf(false) }
    // Multi-select. Keyed by absolute path rather than File so a directory reload (which builds
    // fresh File objects) doesn't silently drop the selection.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Paste conflict resolution, surfaced from the IO coroutine and answered by the dialog.
    var pendingConflict by remember { mutableStateOf<File?>(null) }
    var conflictChoice by remember { mutableStateOf<ConflictChoice?>(null) }
    var conflictApplyToAll by remember { mutableStateOf(false) }
    // Set while a copy/move runs so the progress UI can offer a cancel.
    var operationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var pendingBulkDelete by remember { mutableStateOf<List<File>>(emptyList()) }
    // Browse controls. Persisted so the list doesn't reset its order every time you open a folder.
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(browsePrefs?.getString("fmSortBy", "name") ?: "name") }
    var sortDesc by remember { mutableStateOf(browsePrefs?.getBoolean("fmSortDesc", false) ?: false) }
    var showHidden by remember { mutableStateOf(browsePrefs?.getBoolean("fmShowHidden", true) ?: true) }
    var showSortMenu by remember { mutableStateOf(false) }
    // View mode: list of cards (default) or a thumbnail grid. Density applies to the list only -
    // a grid tile has no second line to compact.
    // The grid/list toggle is the SOURCE OF TRUTH in BOTH orientations (it drives the view and its
    // choice persists across rotation). Grid is the default - most useful in landscape, and in
    // portrait GridCells.Adaptive naturally renders fewer columns (~2). Do NOT force portrait to list:
    // that broke the toggle on-device (tapping it did nothing in portrait).
    var gridView by remember { mutableStateOf(sample?.grid ?: (browsePrefs?.getBoolean("fmGridView", true) ?: true)) }
    val showGrid = gridView
    var compactRows by remember { mutableStateOf(browsePrefs?.getBoolean("fmCompactRows", false) ?: false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    // Properties sheet (basic info + Read-only / Hidden toggles) target; null when closed.
    var propertiesTarget by remember { mutableStateOf<File?>(null) }
    var isOperationRunning by remember { mutableStateOf(false) }
    var operationLabel by remember { mutableStateOf("") }
    var operationDeterminate by remember { mutableStateOf(false) }
    var operationProgress by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    // Favorites view: when on, a dedicated bookmarks list replaces the file list.
    // favTick is bumped on any add/remove/toggle so the favorites view + per-row star recompute.
    var showFavorites by remember { mutableStateOf(sample?.favorites == true) }
    var favTick by remember { mutableIntStateOf(0) }

    // resetScroll: jump to the top of the list (true for navigation; false for in-place reloads
    // after delete/paste/rename/refresh so the user keeps their scroll position).
    fun loadDirectory(dir: File, resetScroll: Boolean = true) {
        currentDir = dir
        // Remember the browsed directory so the next pick resumes here.
        if (inspecting) return
        if (pickMode) pickPrefs?.edit()?.putString("lastFilePickerDir", dir.absolutePath)?.apply()
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                dir.listFiles()?.toList()
                    // Dir-pick mode: folders only. File-pick: folders + matching files. Else: all.
                    ?.filter { if (pickDirMode) it.isDirectory else !pickMode || it.isDirectory || matchesPickExt(it) }
                    // Dotfiles are noise in a storage root (.aya, .$recycle_bin$) but occasionally
                    // the thing you came for, so it's a toggle rather than a permanent filter.
                    ?.filter { showHidden || !it.name.startsWith(".") }
                    ?.sortedWith(comparatorFor(sortBy, sortDesc)) ?: emptyList()
            }
            entries = list
            if (resetScroll) listState.scrollToItem(0)
        }
    }

    // Pull-to-refresh: re-list the current directory, keeping scroll position.
    if (pullState.isRefreshing) {
        LaunchedEffect(true) {
            loadDirectory(currentDir, resetScroll = false)
            pullState.endRefresh()
        }
    }

    // Jump to a drive's root; pins the Back boundary so we don't climb above it.
    fun openDrive(dir: File) {
        currentRoot = dir
        loadDirectory(dir)
    }

    // Open the start dir without openDrive(): that pins the floor to the start dir itself, which
    // greyed out up/back whenever the manager was opened at a game's folder.
    LaunchedEffect(Unit) { loadDirectory(rootDir) }

    // System/gesture Back: while the Favorites view is open it closes that first; otherwise
    // it goes up one directory. Only at the current drive's root with Favorites closed is it
    // disabled, letting Back propagate to close the File Manager.
    BackHandler(enabled = showFavorites || currentDir != currentRoot) {
        if (showFavorites) {
            showFavorites = false
            return@BackHandler
        }
        val parent = currentDir.parentFile
        if (parent != null && parent.exists()) loadDirectory(parent)
    }

    // One level up, never above the current drive's root: shared by the toolbar arrow and the
    // ".." row at the top of the list.
    val canGoUp = currentDir != currentRoot && currentDir.parentFile?.exists() == true
    fun goUp() {
        val parent = currentDir.parentFile
        if (currentDir != currentRoot && parent != null && parent.exists()) loadDirectory(parent)
    }

    // Resolve a non-colliding destination in [dir] for [name] (foo.txt -> "foo (1).txt").
    fun uniqueDestination(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        do {
            candidate = File(dir, "$base ($i)$ext")
            i++
        } while (candidate.exists())
        return candidate
    }

    fun performDelete(file: File) {
        if (inspecting) return
        scope.launch {
            isOperationRunning = true
            operationLabel = context.getString(R.string.fm_op_deleting)
            val ok = withContext(Dispatchers.IO) { FileOps.delete(file) }
            isOperationRunning = false
            loadDirectory(currentDir, resetScroll = false)
            if (!ok) Toast.makeText(context, R.string.fm_delete_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Waits for the user to answer the conflict dialog for [file]; null if they cancelled it. */
    suspend fun askConflict(file: File): ConflictChoice? {
        pendingConflict = file
        conflictChoice = null
        // Poll rather than plumb a CompletableDeferred through Compose state - the dialog answers
        // by setting conflictChoice, and this coroutine is already off the critical path.
        while (pendingConflict != null && conflictChoice == null) kotlinx.coroutines.delay(50)
        return conflictChoice
    }

    fun performPaste() {
        if (inspecting) return
        val sources = clipboardFiles
        if (sources.isEmpty()) return
        val dstDir = currentDir
        val cut = isCutOperation

        operationJob = scope.launch {
            operationProgress = 0f
            operationDeterminate = true
            operationLabel = context.getString(if (cut) R.string.fm_op_moving else R.string.fm_op_copying)
            isOperationRunning = true

            var applyToAll: ConflictChoice? = null
            var failed = 0
            var skipped = 0
            var done = 0

            for (src in sources) {
                // Pasting a folder into itself or its own subtree would recurse forever.
                if (src.isDirectory && isWithin(dstDir, src)) {
                    failed++
                    continue
                }
                // Moving into the folder it already sits in is a no-op.
                if (cut && src.parentFile?.absolutePath == dstDir.absolutePath) {
                    skipped++
                    continue
                }

                var dst = File(dstDir, src.name)
                if (dst.exists()) {
                    val choice = applyToAll ?: askConflict(src)?.also {
                        if (conflictApplyToAll) applyToAll = it
                    } ?: run { skipped++; null } ?: continue
                    when (choice) {
                        // Overwrite and Merge both paste onto the real destination: copyWithProgress
                        // recurses into an existing directory and truncates existing files, so the
                        // two differ only in what the user expects, not in what we call.
                        ConflictChoice.OVERWRITE, ConflictChoice.MERGE -> Unit
                        ConflictChoice.KEEP_BOTH -> dst = uniqueDestination(dstDir, src.name)
                        ConflictChoice.SKIP -> { skipped++; continue }
                    }
                }

                // Progress is per item; with a batch the label carries the overall position.
                operationLabel = if (sources.size > 1) {
                    context.getString(if (cut) R.string.fm_op_moving_batch else R.string.fm_op_copying_batch, done + 1, sources.size, src.name)
                } else {
                    context.getString(if (cut) R.string.fm_op_moving_item else R.string.fm_op_copying_item, src.name)
                }
                var lastPct = -1
                val onProgress = FileOps.ProgressCallback { copied, total ->
                    val pct = if (total > 0) ((copied * 100) / total).toInt() else 100
                    if (pct != lastPct) {
                        lastPct = pct
                        operationProgress = pct / 100f
                    }
                }
                val target = dst
                val ok = withContext(Dispatchers.IO) {
                    if (cut) FileOps.moveWithProgress(src, target, onProgress)
                    else FileOps.copyWithProgress(src, target, onProgress)
                }
                if (ok) done++ else failed++
            }

            isOperationRunning = false
            operationDeterminate = false
            operationJob = null
            clipboardFiles = emptyList()
            isCutOperation = false
            selectionMode = false
            selectedPaths = emptySet()
            loadDirectory(currentDir, resetScroll = false)

            val message = when {
                failed > 0 -> context.getString(R.string.fm_paste_failed, done, failed)
                skipped > 0 -> context.getString(R.string.fm_paste_skipped, done, skipped)
                sources.size > 1 -> context.resources.getQuantityString(if (cut) R.plurals.fm_paste_moved else R.plurals.fm_paste_copied, done, done)
                else -> null
            }
            if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun performRename(file: File, newName: String) {
        if (inspecting) return
        val target = File(file.parentFile, newName)
        if (target.exists()) {
            Toast.makeText(context, context.getString(R.string.fm_already_exists, newName), Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            isOperationRunning = true
            operationLabel = context.getString(R.string.fm_op_renaming)
            val ok = withContext(Dispatchers.IO) { file.renameTo(target) }
            isOperationRunning = false
            loadDirectory(currentDir, resetScroll = false)
            if (!ok) Toast.makeText(context, R.string.fm_rename_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun createFolder(parent: File, name: String) {
        if (inspecting) return
        val target = File(parent, name)
        if (target.exists()) {
            Toast.makeText(context, context.getString(R.string.fm_already_exists, name), Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            isOperationRunning = true
            operationLabel = context.getString(R.string.fm_op_creating_folder)
            val ok = withContext(Dispatchers.IO) { target.mkdirs() }
            isOperationRunning = false
            loadDirectory(currentDir, resetScroll = false)
            if (!ok) Toast.makeText(context, R.string.fm_create_folder_failed, Toast.LENGTH_SHORT).show()
        }
    }

    var showDriveMenu by remember { mutableStateOf(false) }
    // Re-enumerated whenever we come back to the screen: returning from a container can leave this
    // process on a stale storage view, and the volume set has to be re-read rather than cached.
    var storageTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) storageTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val drives = remember(storageTick) { if (inspecting) emptyList() else StorageRoots.list(context) }
    val linuxRoot = remember(storageTick) {
        if (inspecting) null else com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context).takeIf { File(it, "usr").isDirectory }
    }

    // ── Dialogs ──

    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        OutlinedAlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(stringResource(R.string.fm_new_folder)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.fm_folder_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewFolderDialog = false
                    if (folderName.isNotBlank()) createFolder(currentDir, folderName)
                }) { Text(stringResource(R.string.fm_create)) }
            },
            dismissButton = { TextButton(onClick = { showNewFolderDialog = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (renameTarget != null) {
        var newName by remember(renameTarget) { mutableStateOf(renameTarget?.name ?: "") }
        OutlinedAlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.fm_rename)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.fm_new_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val file = renameTarget
                    renameTarget = null
                    if (file != null && newName.isNotBlank()) performRename(file, newName)
                }) { Text(stringResource(R.string.fm_rename)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    propertiesTarget?.let { file ->
        FilePropertiesDialog(
            file = file,
            onDismiss = { propertiesTarget = null },
            // Attribute changes affect Hidden/read-only which the listing filters/sorts on, so refresh
            // in place (keeping scroll) after any toggle applies.
            onChanged = { loadDirectory(currentDir, resetScroll = false) },
        )
    }

    if (selectedEntry != null && selectedEntry != showMenuFor) {
        val file = selectedEntry ?: return
        OutlinedAlertDialog(
            onDismissRequest = { selectedEntry = null },
            title = { Text(stringResource(R.string.fm_delete_title)) },
            text = { Text(stringResource(R.string.fm_delete_text, file.name)) },
            confirmButton = {
                TextButton(onClick = {
                    selectedEntry = null
                    performDelete(file)
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { selectedEntry = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (pendingBulkDelete.isNotEmpty()) {
        val victims = pendingBulkDelete
        OutlinedAlertDialog(
            onDismissRequest = { pendingBulkDelete = emptyList() },
            title = { Text(pluralStringResource(R.plurals.fm_delete_items_title, victims.size, victims.size)) },
            text = {
                Column {
                    Text(stringResource(R.string.fm_cannot_undo))
                    Spacer(Modifier.height(6.dp))
                    // Name a few so an accidental Select-All is obvious before it's too late.
                    victims.take(5).forEach {
                        Text("• ${it.name}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    if (victims.size > 5) {
                        Text(
                            pluralStringResource(R.plurals.fm_and_more, victims.size - 5, victims.size - 5),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingBulkDelete = emptyList()
                    selectionMode = false
                    selectedPaths = emptySet()
                    if (inspecting) return@TextButton
                    operationJob = scope.launch {
                        isOperationRunning = true
                        var failed = 0
                        victims.forEachIndexed { i, f ->
                            operationLabel = context.getString(R.string.fm_op_deleting_batch, i + 1, victims.size, f.name)
                            if (!withContext(Dispatchers.IO) { FileOps.delete(f) }) failed++
                        }
                        isOperationRunning = false
                        operationJob = null
                        loadDirectory(currentDir, resetScroll = false)
                        if (failed > 0) {
                            Toast.makeText(context, context.resources.getQuantityString(R.plurals.fm_delete_some_failed, failed, failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingBulkDelete = emptyList() }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    // Paste conflict - one per colliding item, with "apply to all" for a long batch.
    pendingConflict?.let { conflict ->
        val isDir = conflict.isDirectory
        OutlinedAlertDialog(
            onDismissRequest = { pendingConflict = null },
            title = { Text(stringResource(R.string.fm_already_exists, conflict.name)) },
            text = {
                Column {
                    Text(
                        if (isDir) stringResource(R.string.fm_conflict_merge_hint)
                        else stringResource(R.string.fm_conflict_overwrite_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    if (clipboardFiles.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = conflictApplyToAll,
                                onCheckedChange = { conflictApplyToAll = it },
                            )
                            Text(stringResource(R.string.fm_conflict_apply_all), fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    listOf(
                        (if (isDir) ConflictChoice.MERGE else ConflictChoice.OVERWRITE) to
                            (if (isDir) stringResource(R.string.fm_conflict_merge) else stringResource(R.string.fm_conflict_overwrite)),
                        ConflictChoice.KEEP_BOTH to stringResource(R.string.fm_conflict_keep_both),
                        ConflictChoice.SKIP to stringResource(R.string.fm_conflict_skip),
                    ).forEach { (choice, label) ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { conflictChoice = choice; pendingConflict = null },
                        ) { Text(label, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { conflictChoice = ConflictChoice.SKIP; pendingConflict = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Pick-mode title ──
        if (pickMode && !pickerTitle.isNullOrEmpty()) {
            Text(
                text = pickerTitle,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        // ── Dir-pick action bar: confirm the currently-browsed folder ──
        if (pickDirMode) {
            Button(
                onClick = { onPick?.invoke(currentDir) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.fm_select_this_folder))
            }
        }
        // ── Path bar ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = { goUp() }, enabled = canGoUp) {
                Icon(Icons.Filled.ArrowBack, stringResource(R.string.fm_back), tint = MaterialTheme.colorScheme.primary)
            }

            val currentDriveLabel = stringResource(describeLocation(currentDir).driveLabel)
            // Dim the drive chip while the Favorites list is open (it's not the active context).
            val driveChipAlpha = if (showFavorites) 0.45f else 1f
            Box {
                // The drive/location selector opens the drive dropdown, so give it the same outlined
                // look as the "New Folder" button + the rail location items - it reads as a button, not
                // plain text. Border uses the theme accent token; behaviour unchanged.
                val driveChipShape = RoundedCornerShape(8.dp)
                Text(
                    text = "  $currentDriveLabel  ▾",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = driveChipAlpha),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(driveChipShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), driveChipShape)
                        .clickable { showDriveMenu = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                DropdownMenu(
                    expanded = showDriveMenu,
                    onDismissRequest = { showDriveMenu = false },
                    modifier = Modifier.outlinedMenuCard(),
                ) {
                    drives.forEachIndexed { i, drive ->
                        if (i > 0) MenuItemDivider()
                        DropdownMenuItem(
                            text = { Text(drive.label) },
                            leadingIcon = {
                                Icon(
                                    if (drive.removable) Icons.Filled.SdStorage else Icons.Filled.Storage,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = {
                                showDriveMenu = false
                                if (drive.readable) {
                                    openDrive(drive.dir)
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.fm_drive_not_readable, drive.label),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )
                    }
                    linuxRoot?.let { root ->
                        MenuItemDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.fm_drive_linux)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Terminal, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            },
                            onClick = { showDriveMenu = false; openDrive(root) },
                        )
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            if (showFavorites) {
                Text(
                    text = stringResource(R.string.fm_favorites),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) {
                // PORTRAIT: hide the current-folder name - it's redundant with the path bar directly
                // below (which shows the full path). The spacer keeps the action icons right-aligned.
                Spacer(Modifier.weight(1f))
            } else {
                // LANDSCAPE: the CURRENT FOLDER, not the full path. A path ellipsised on the right
                // hides its tail - the only part that says where you are ("…/Games/Racing/Dir…"). The
                // full path moves to the line below, where it has room.
                Text(
                    text = currentDir.name.ifBlank { currentDir.absolutePath },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            if (!showFavorites) {
                // Keep New Folder in the toolbar so the file list uses the full height.
                if (!pickMode) {
                    OutlinedButton(
                        onClick = { showNewFolderDialog = true },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Icon(Icons.Filled.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(R.string.fm_new_folder), color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp)
                    }
                }
                IconButton(onClick = {
                    gridView = !gridView
                    browsePrefs?.edit()?.putBoolean("fmGridView", gridView)?.apply()
                }) {
                    Icon(
                        if (gridView) Icons.Filled.ViewList else Icons.Filled.GridView,
                        if (gridView) stringResource(R.string.fm_list_view) else stringResource(R.string.fm_grid_view),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showSearch = !showSearch; if (!showSearch) searchQuery = "" }) {
                    Icon(Icons.Filled.Search, stringResource(R.string.fm_search), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.Filled.Sort, stringResource(R.string.fm_sort), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        listOf("name" to stringResource(R.string.fm_sort_name), "date" to stringResource(R.string.fm_sort_date), "size" to stringResource(R.string.fm_prop_size))
                            .forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(if (sortBy == key) "$label  ${if (sortDesc) "↓" else "↑"}" else label)
                                    },
                                    onClick = {
                                        // Tapping the active field flips direction; a different
                                        // field switches to it ascending.
                                        if (sortBy == key) sortDesc = !sortDesc else { sortBy = key; sortDesc = false }
                                        browsePrefs?.edit()?.putString("fmSortBy", sortBy)
                                            ?.putBoolean("fmSortDesc", sortDesc)?.apply()
                                        showSortMenu = false
                                        loadDirectory(currentDir, resetScroll = false)
                                    },
                                )
                            }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        DropdownMenuItem(
                            text = { Text(if (compactRows) stringResource(R.string.fm_rows_comfortable) else stringResource(R.string.fm_rows_compact)) },
                            onClick = {
                                compactRows = !compactRows
                                browsePrefs?.edit()?.putBoolean("fmCompactRows", compactRows)?.apply()
                                showSortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (showHidden) stringResource(R.string.fm_hide_hidden) else stringResource(R.string.fm_show_hidden)) },
                            onClick = {
                                showHidden = !showHidden
                                browsePrefs?.edit()?.putBoolean("fmShowHidden", showHidden)?.apply()
                                showSortMenu = false
                                loadDirectory(currentDir, resetScroll = false)
                            },
                        )
                    }
                }
            }

            // Star toggle: open/close the dedicated Favorites list.
            IconButton(onClick = { showFavorites = !showFavorites }) {
                if (showFavorites) {
                    Icon(Icons.Filled.Star, stringResource(R.string.fm_hide_favorites), tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Filled.StarBorder, stringResource(R.string.fm_show_favorites), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ── Search field ── filters the current folder only; it is not a recursive search.
        if (showSearch && !showFavorites) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.fm_filter_folder), fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // Free space on the volume being browsed - worth knowing before starting a 60 GB copy.
        val freeSpace = remember(currentDir.absolutePath, entries) {
            runCatching { currentDir.usableSpace }.getOrDefault(0L)
        }
        if (!showFavorites) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                Text(
                    // Elided from the LEFT: the deepest part of a path is the informative part, so
                    // when it doesn't fit we drop the /storage/emulated/0 prefix, not the tail.
                    text = elidePathStart(currentDir.absolutePath, 52),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    // fill = true: the path takes all remaining width, so the free-space figure
                    // is pinned to the right edge instead of sliding around with the path length.
                    modifier = Modifier.weight(1f),
                )
                if (freeSpace > 0) {
                    Text(
                        stringResource(R.string.fm_free_space, FileOps.formatBytes(context, freeSpace)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // ── Selection bar ── replaces the paste banner while picking items.
        if (selectionMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    stringResource(R.string.fm_selected_count, selectedPaths.size),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                // Compact outlined buttons so all five fit one row alongside the count.
                val selBarPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                OutlinedButton(
                    onClick = {
                        selectedPaths = if (selectedPaths.size == entries.size) emptySet()
                        else entries.map { it.absolutePath }.toSet()
                    },
                    contentPadding = selBarPadding,
                ) { Text(if (selectedPaths.size == entries.size) stringResource(R.string.fm_select_none) else stringResource(R.string.fm_select_all), fontSize = 12.sp) }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    enabled = selectedPaths.isNotEmpty(),
                    onClick = {
                        clipboardFiles = entries.filter { it.absolutePath in selectedPaths }
                        isCutOperation = false
                        selectionMode = false
                        selectedPaths = emptySet()
                    },
                    contentPadding = selBarPadding,
                ) { Text(stringResource(R.string.fm_copy), fontSize = 12.sp) }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    enabled = selectedPaths.isNotEmpty(),
                    onClick = {
                        clipboardFiles = entries.filter { it.absolutePath in selectedPaths }
                        isCutOperation = true
                        selectionMode = false
                        selectedPaths = emptySet()
                    },
                    contentPadding = selBarPadding,
                ) { Text(stringResource(R.string.fm_cut), fontSize = 12.sp) }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    enabled = selectedPaths.isNotEmpty(),
                    onClick = { pendingBulkDelete = entries.filter { it.absolutePath in selectedPaths } },
                    contentPadding = selBarPadding,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    onClick = { selectionMode = false; selectedPaths = emptySet() },
                    contentPadding = selBarPadding,
                ) { Text(stringResource(R.string.fm_done), fontSize = 12.sp) }
            }
        }

        // ── Paste banner ──
        if (clipboardFiles.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                    .clickable { performPaste() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.ContentPaste, stringResource(R.string.fm_paste), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                val what = if (clipboardFiles.size == 1) clipboardFiles.first().name
                else pluralStringResource(R.plurals.fm_items, clipboardFiles.size, clipboardFiles.size)
                Text(
                    if (isCutOperation) stringResource(R.string.fm_paste_move_here, what) else stringResource(R.string.fm_paste_here, what),
                    color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { clipboardFiles = emptyList(); isCutOperation = false }) {
                    Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }

        // ── Progress overlay ──
        if (isOperationRunning) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val pctText = if (operationDeterminate) "  ${(operationProgress * 100).toInt()}%" else ""
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$operationLabel$pctText",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // A multi-gigabyte copy onto a slow card is exactly when you discover you
                    // picked the wrong folder; without this the only way out was killing the app.
                    if (operationJob != null) {
                        TextButton(onClick = {
                            operationJob?.cancel()
                            operationJob = null
                            isOperationRunning = false
                            operationDeterminate = false
                            loadDirectory(currentDir, resetScroll = false)
                        }) { Text(stringResource(R.string.common_cancel), fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (operationDeterminate) {
                    LinearProgressIndicator(
                        progress = { operationProgress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        // ── Left locations rail (mockup Option 2) + content ──
        // Shared collapsible rail: landscape expanded by default, portrait collapsed icon-only. Not
        // shown in pick mode (the themed picker keeps its slim layout). Built each recompose (cheap)
        // so it tracks the current drive/favourites without stale click lambdas.
        val fmRailState = rememberRailState("filemanager")
        fun locItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, dir: File, shortLabel: String? = null) =
            RailItem(label, icon, !showFavorites && currentRoot.absolutePath == dir.absolutePath, shortLabel = shortLabel) {
                showFavorites = false; openDrive(dir)
            }
        val storageItems = buildList {
            add(locItem(stringResource(R.string.fm_drive_internal), Icons.Filled.Smartphone, File("/storage/emulated/0")))
            drives.filter { it.removable }.forEach { d ->
                add(RailItem(d.label, Icons.Filled.SdStorage, !showFavorites && currentRoot.absolutePath == d.dir.absolutePath) {
                    showFavorites = false; if (d.readable) openDrive(d.dir)
                })
            }
            // The Linux system the sessions run in: its own / (the desktop's root), once installed.
            linuxRoot?.let { add(locItem(stringResource(R.string.fm_drive_linux), Icons.Filled.Terminal, it)) }
        }
        val quickItems = buildList {
            File("/storage/emulated/0/Download").takeIf { !inspecting && it.isDirectory }?.let { add(locItem(stringResource(R.string.fm_rail_downloads), Icons.Filled.Download, it, stringResource(R.string.fm_rail_downloads_short))) }
            // The ROMs folder chosen on the main screen: what the session shows as /root/ROMs.
            (if (inspecting) "" else SessionPrefs.romsDir(context)).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory }
                ?.let { add(locItem(stringResource(R.string.fm_rail_roms), Icons.Filled.SportsEsports, it)) }
            File("/storage/emulated/0/Download/DroidDeck").takeIf { !inspecting && it.isDirectory }?.let { add(locItem(stringResource(R.string.fm_rail_session_logs), Icons.Filled.Description, it)) }
            File("/storage/emulated/0/Pictures").takeIf { !inspecting && it.isDirectory }?.let { add(locItem(stringResource(R.string.fm_rail_pictures), Icons.Filled.Image, it)) }
        }
        val favItems = remember(favTick) { if (inspecting) sample?.entries.orEmpty().filter { it.isDirectory } else FavoritesStore.list(context).map(::File).filter { it.exists() } }
            .map { d -> RailItem(d.name, Icons.Filled.Star, false) { showFavorites = false; openDrive(d) } }
        val locationSections = buildList {
            add(RailSection(stringResource(R.string.fm_rail_storage), storageItems))
            if (quickItems.isNotEmpty()) add(RailSection(stringResource(R.string.fm_rail_quick), quickItems))
            if (favItems.isNotEmpty()) add(RailSection(stringResource(R.string.fm_rail_favorites), favItems))
        }

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // The slim picker keeps its layout: no rail.
            if (!pickMode) {
                CollapsibleRail(state = fmRailState, title = stringResource(R.string.fm_rail_title), sections = locationSections, outlinedItems = true)
            }
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
        // ── Favorites list OR file list ──
        if (showFavorites) {
            FavoritesList(
                currentDir = currentDir,
                favTick = favTick,
                previewFavorites = sample?.entries.orEmpty().filter { it.isDirectory },
                onPinCurrent = {
                    if (!inspecting) FavoritesStore.add(context, currentDir.absolutePath)
                    favTick++
                    Toast.makeText(context, context.getString(R.string.fm_favorite_added, currentDir.name), Toast.LENGTH_SHORT).show()
                },
                onJump = { dir ->
                    showFavorites = false
                    openDrive(dir)
                },
                onUnpin = { dir ->
                    if (!inspecting) FavoritesStore.remove(context, dir.absolutePath)
                    favTick++
                    Toast.makeText(context, context.getString(R.string.fm_favorite_removed, dir.name), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
        // ── File list (pull down to refresh) ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pullState.nestedScrollConnection),
        ) {
            val shownEntries = if (searchQuery.isBlank()) entries
            else entries.filter { it.name.contains(searchQuery, ignoreCase = true) }

            if (showGrid && entries.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    if (canGoUp) item(key = "..", span = { GridItemSpan(maxLineSpan) }) {
                        ParentFolderRow(compact = compactRows, onTap = { goUp() })
                    }
                    items(shownEntries, key = { it.absolutePath }) { file ->
                        val isFav = remember(file.absolutePath, favTick) {
                            (!inspecting && FavoritesStore.isFavorite(context, file.absolutePath))
                        }
                        FileGridTile(
                            file = file,
                            selectionMode = selectionMode,
                            selected = file.absolutePath in selectedPaths,
                            onLongPress = {
                                if (!pickMode) {
                                    // In selection mode a long-press toggles; otherwise it opens the
                                    // same context menu the list rows show (the tile has no ⋮ button).
                                    if (selectionMode) {
                                        selectedPaths = if (file.absolutePath in selectedPaths)
                                            selectedPaths - file.absolutePath
                                        else selectedPaths + file.absolutePath
                                    } else {
                                        showMenuFor = file
                                    }
                                }
                            },
                            onToggleSelect = {
                                selectedPaths = if (file.absolutePath in selectedPaths)
                                    selectedPaths - file.absolutePath
                                else selectedPaths + file.absolutePath
                            },
                            onTap = {
                                if (file.isDirectory) loadDirectory(file)
                                else if (pickMode) {
                                    if (matchesPickExt(file)) {
                                        pickPrefs?.edit()?.putString("lastFilePickerDir", currentDir.absolutePath)?.apply()
                                        onPick?.invoke(file)
                                    }
                                }
                            },
                            onMenu = { showMenuFor = file },
                            menuExpanded = showMenuFor == file,
                            onDismissMenu = { showMenuFor = null },
                            isFavorite = isFav,
                            onSelect = {
                                selectionMode = true
                                selectedPaths = selectedPaths + file.absolutePath
                                showMenuFor = null
                            },
                            onRename = { renameTarget = file; showMenuFor = null },
                            onCopy = { clipboardFiles = listOf(file); isCutOperation = false; showMenuFor = null },
                            onCut = { clipboardFiles = listOf(file); isCutOperation = true; showMenuFor = null },
                            onDelete = { selectedEntry = file; showMenuFor = null },
                            onToggleFavorite = {
                                val nowFav = if (inspecting) false else FavoritesStore.toggle(context, file.absolutePath)
                                favTick++
                                showMenuFor = null
                                Toast.makeText(
                                    context,
                                    if (nowFav) context.getString(R.string.fm_favorite_added, file.name)
                                    else context.getString(R.string.fm_favorite_removed, file.name),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onProperties = { propertiesTarget = file; showMenuFor = null },
                        )
                    }
                }
            } else
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (canGoUp) item(key = "..") {
                    ParentFolderRow(compact = compactRows, onTap = { goUp() })
                }
                if (entries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.fm_empty_directory), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    val shown = shownEntries
                    items(shown, key = { it.absolutePath }) { file ->
                        val isFav = remember(file.absolutePath, favTick) {
                            (!inspecting && FavoritesStore.isFavorite(context, file.absolutePath))
                        }
                        FileItemRow(
                            file = file,
                            showActions = !pickMode,
                            compact = compactRows,
                            selectionMode = selectionMode,
                            selected = file.absolutePath in selectedPaths,
                            onLongPress = {
                                // In selection mode a long-press toggles; otherwise it opens the
                                // per-item context menu (matching the grid tiles).
                                if (!pickMode) {
                                    if (selectionMode) {
                                        selectedPaths = if (file.absolutePath in selectedPaths)
                                            selectedPaths - file.absolutePath
                                        else selectedPaths + file.absolutePath
                                    } else {
                                        showMenuFor = file
                                    }
                                }
                            },
                            onToggleSelect = {
                                selectedPaths = if (file.absolutePath in selectedPaths)
                                    selectedPaths - file.absolutePath
                                else selectedPaths + file.absolutePath
                            },
                            onTap = {
                                if (file.isDirectory) loadDirectory(file)
                                else if (pickMode) {
                                    if (matchesPickExt(file)) {
                                        pickPrefs?.edit()?.putString("lastFilePickerDir", currentDir.absolutePath)?.apply()
                                        onPick?.invoke(file)
                                    }
                                }
                            },
                            onMenu = { showMenuFor = file },
                            menuExpanded = showMenuFor == file,
                            onDismissMenu = { showMenuFor = null },
                            onSelect = {
                                selectionMode = true
                                selectedPaths = selectedPaths + file.absolutePath
                                showMenuFor = null
                            },
                            onCopy = { clipboardFiles = listOf(file); isCutOperation = false; showMenuFor = null },
                            onCut = { clipboardFiles = listOf(file); isCutOperation = true; showMenuFor = null },
                            onDelete = { selectedEntry = file; showMenuFor = null },
                            onRename = { renameTarget = file; showMenuFor = null },
                            isFavorite = isFav,
                            onToggleFavorite = {
                                val nowFav = if (inspecting) false else FavoritesStore.toggle(context, file.absolutePath)
                                favTick++
                                showMenuFor = null
                                Toast.makeText(
                                    context,
                                    if (nowFav) context.getString(R.string.fm_favorite_added, file.name)
                                    else context.getString(R.string.fm_favorite_removed, file.name),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onProperties = { propertiesTarget = file; showMenuFor = null },
                        )
                    }
                }
            }
            // material3 1.2.0's PullToRefreshContainer draws its indicator even at rest;
            // only show it while the user is actively pulling or a refresh is running.
            if (pullState.verticalOffset > 0.5f || pullState.isRefreshing) {
                PullToRefreshContainer(
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
        }
            } // end content Box (beside the rail)
        } // end rail + content Row
    }
}
