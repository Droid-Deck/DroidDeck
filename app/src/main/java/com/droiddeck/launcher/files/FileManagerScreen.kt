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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

private enum class FileManagerNavigation { EXIT, RETURN_TO_START, BACK }

internal fun canNavigateUp(currentDir: File, currentRoot: File): Boolean =
    currentDir != currentRoot && currentDir.parentFile?.exists() == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    pickMode: Boolean = false,
    pickDirMode: Boolean = false,
    pickExtensions: List<String> = emptyList(),
    initialDir: File? = null,
    pickerTitle: String? = null,
    onPick: ((File) -> Unit)? = null,
    onExit: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val lowerExts = remember(pickExtensions) { pickExtensions.map { it.lowercase() } }
    fun matchesPickExt(file: File): Boolean {
        if (lowerExts.isEmpty()) return true
        val name = file.name.lowercase()
        return lowerExts.any { name.endsWith(".$it") }
    }

    val pickPrefs = remember { context.getSharedPreferences("file_manager", android.content.Context.MODE_PRIVATE) }
    val browsePrefs = pickPrefs
    val rootDir = remember(initialDir) {
        initialDir?.takeIf { it.isDirectory } ?: File("/storage/emulated/0")
    }
    val launchDir = remember(initialDir, pickMode) {
        initialDir?.takeIf { !pickMode }
    }
    var currentDir by remember { mutableStateOf(rootDir) }
    val initialRoot = remember(rootDir) {
        val abs = rootDir.absolutePath
        val internal = "/storage/emulated/0"
        val vol = when {
            abs == internal || abs.startsWith("$internal/") -> File(internal)
            abs.startsWith("/storage/") -> {
                val name = abs.removePrefix("/storage/").substringBefore('/')
                if (name.isNotEmpty() && name != "emulated" && name != "self") File("/storage/$name") else rootDir
            }
            else -> {
                val appFiles = runCatching { context.filesDir.canonicalPath }.getOrNull()
                generateSequence(rootDir) { it.parentFile }
                    .firstOrNull { appFiles != null && runCatching { it.canonicalPath }.getOrNull() == appFiles }
                    ?: rootDir
            }
        }
        vol
    }
    var currentRoot by remember { mutableStateOf(initialRoot) }
    var entries by remember { mutableStateOf(listOf<File>()) }
    var selectedEntry by remember { mutableStateOf<File?>(null) }
    var showMenuFor by remember { mutableStateOf<File?>(null) }
    var clipboardFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isCutOperation by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingConflict by remember { mutableStateOf<File?>(null) }
    var conflictChoice by remember { mutableStateOf<ConflictChoice?>(null) }
    var conflictApplyToAll by remember { mutableStateOf(false) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var cancelledJob by remember { mutableStateOf<Job?>(null) }
    var pendingNavigation by remember { mutableStateOf<FileManagerNavigation?>(null) }
    var cancellingNavigation by remember { mutableStateOf(false) }
    var pendingBulkDelete by remember { mutableStateOf<List<File>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    val visibleEntries = if (searchQuery.isBlank()) entries
    else entries.filter { it.name.contains(searchQuery, ignoreCase = true) }
    var showSearch by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(browsePrefs.getString("fmSortBy", "name") ?: "name") }
    var sortDesc by remember { mutableStateOf(browsePrefs.getBoolean("fmSortDesc", false)) }
    var showHidden by remember { mutableStateOf(browsePrefs.getBoolean("fmShowHidden", true)) }
    var showSortMenu by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(browsePrefs.getBoolean("fmGridView", true)) }
    val showGrid = gridView
    var compactRows by remember { mutableStateOf(browsePrefs.getBoolean("fmCompactRows", false)) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var propertiesTarget by remember { mutableStateOf<File?>(null) }
    var isOperationRunning by remember { mutableStateOf(false) }
    var operationLabel by remember { mutableStateOf("") }
    var operationDeterminate by remember { mutableStateOf(false) }
    var operationProgress by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    var showFavorites by remember { mutableStateOf(false) }
    var favTick by remember { mutableIntStateOf(0) }

    var listingJob by remember { mutableStateOf<Job?>(null) }

    fun loadDirectory(dir: File, resetScroll: Boolean = true) {
        if (cancellingNavigation) return
        currentDir = dir
        if (pickMode) pickPrefs.edit().putString("lastFilePickerDir", dir.absolutePath).apply()
        listingJob?.cancel()
        listingJob = scope.launch {
            val list = withContext(Dispatchers.IO) {
                dir.listFiles()?.toList()
                    ?.filter { if (pickDirMode) it.isDirectory else !pickMode || it.isDirectory || matchesPickExt(it) }
                    ?.filter { showHidden || !it.name.startsWith(".") }
                    ?.sortedWith(comparatorFor(sortBy, sortDesc)) ?: emptyList()
            }
            if (currentDir != dir) return@launch
            entries = list
            if (resetScroll) listState.scrollToItem(0)
        }
    }

    if (pullState.isRefreshing) {
        LaunchedEffect(true) {
            loadDirectory(currentDir, resetScroll = false)
            pullState.endRefresh()
        }
    }

    fun openDrive(dir: File) {
        if (cancellingNavigation) return
        currentRoot = dir
        loadDirectory(dir)
    }

    fun returnToStart() {
        if (cancellingNavigation) return
        val dir = launchDir ?: return
        if (!dir.isDirectory) {
            Toast.makeText(context, R.string.fm_start_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        showFavorites = false
        showSearch = false
        searchQuery = ""
        currentRoot = initialRoot
        loadDirectory(dir)
    }

    LaunchedEffect(Unit) { loadDirectory(rootDir) }

    val canGoUp = currentDir != currentRoot && currentDir.parentFile?.exists() == true
    fun isOperationActive() = isOperationRunning || operationJob?.isCompleted == false || cancelledJob?.isCompleted == false
    val operationActive = isOperationActive()

    fun navigate(action: FileManagerNavigation) {
        if (cancellingNavigation) return
        when (action) {
            FileManagerNavigation.EXIT -> onExit?.invoke()
            FileManagerNavigation.RETURN_TO_START -> returnToStart()
            FileManagerNavigation.BACK -> {
                if (showFavorites) showFavorites = false
                else if (canNavigateUp(currentDir, currentRoot)) currentDir.parentFile?.let { loadDirectory(it) }
                else onExit?.invoke()
            }
        }
    }

    fun requestNavigation(action: FileManagerNavigation) {
        if (cancellingNavigation) return
        if (isOperationActive()) pendingNavigation = action else navigate(action)
    }

    BackHandler(enabled = operationActive || cancellingNavigation || showFavorites || currentDir != currentRoot) {
        requestNavigation(FileManagerNavigation.BACK)
    }

    if (pendingNavigation != null) {
        OutlinedAlertDialog(
            onDismissRequest = { pendingNavigation = null },
            title = { Text(stringResource(R.string.fm_cancel_operation_title)) },
            text = { Text(stringResource(R.string.fm_cancel_operation_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    val action = pendingNavigation ?: return@TextButton
                    pendingNavigation = null
                    val job = operationJob?.takeUnless(Job::isCompleted)
                        ?: cancelledJob?.takeUnless(Job::isCompleted)
                    if (job == null) {
                        navigate(action)
                    } else {
                        cancellingNavigation = true
                        cancelledJob = job
                        job.cancel()
                        scope.launch {
                            job.join()
                            if (operationJob === job) operationJob = null
                            if (cancelledJob === job) cancelledJob = null
                            pendingConflict = null
                            isOperationRunning = false
                            operationDeterminate = false
                            cancellingNavigation = false
                            if (action != FileManagerNavigation.EXIT) loadDirectory(currentDir, resetScroll = false)
                            navigate(action)
                        }
                    }
                }) { Text(stringResource(R.string.fm_accept)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingNavigation = null }) {
                    Text(stringResource(R.string.fm_decline))
                }
            },
        )
    }

    fun uniqueDestination(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!java.nio.file.Files.exists(candidate.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        do {
            candidate = File(dir, "$base ($i)$ext")
            i++
        } while (java.nio.file.Files.exists(candidate.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        return candidate
    }

    fun performDelete(file: File) {
        if (isOperationActive()) return
        operationJob = scope.launch {
            isOperationRunning = true
            operationLabel = context.getString(R.string.fm_op_deleting)
            val job = coroutineContext[Job]
            val ok = withContext(Dispatchers.IO) { FileOps.delete(file) { job?.isActive == false } }
            isOperationRunning = false
            if (operationJob === job) operationJob = null
            loadDirectory(currentDir, resetScroll = false)
            if (!ok) Toast.makeText(context, R.string.fm_delete_failed, Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun askConflict(file: File): ConflictChoice? {
        pendingConflict = file
        conflictChoice = null
        while (pendingConflict != null && conflictChoice == null) kotlinx.coroutines.delay(50)
        return conflictChoice
    }

    fun performPaste() {
        val sources = clipboardFiles
        if (sources.isEmpty() || isOperationActive()) return
        val dstDir = currentDir
        val cut = isCutOperation

        operationJob = scope.launch {
            operationProgress = 0f
            operationDeterminate = true
            operationLabel = context.getString(if (cut) R.string.fm_op_moving else R.string.fm_op_copying)
            isOperationRunning = true
            val job = coroutineContext[Job]

            var applyToAll: ConflictChoice? = null
            var failed = 0
            var skipped = 0
            var done = 0

            for (src in sources) {
                if (src.isDirectory && isWithin(dstDir, src)) {
                    failed++
                    continue
                }
                if (cut && src.parentFile?.absolutePath == dstDir.absolutePath) {
                    skipped++
                    continue
                }

                var dst = File(dstDir, src.name)
                if (java.nio.file.Files.exists(dst.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    val choice = applyToAll ?: askConflict(src)?.also {
                        if (conflictApplyToAll) applyToAll = it
                    } ?: run { skipped++; null } ?: continue
                    when (choice) {
                        ConflictChoice.OVERWRITE, ConflictChoice.MERGE -> Unit
                        ConflictChoice.KEEP_BOTH -> dst = uniqueDestination(dstDir, src.name)
                        ConflictChoice.SKIP -> { skipped++; continue }
                    }
                }

                operationLabel = if (sources.size > 1) {
                    context.getString(if (cut) R.string.fm_op_moving_batch else R.string.fm_op_copying_batch, done + 1, sources.size, src.name)
                } else {
                    context.getString(if (cut) R.string.fm_op_moving_item else R.string.fm_op_copying_item, src.name)
                }
                var lastPct = -1
                val onProgress = FileOps.ProgressCallback { copied, total ->
                    job?.ensureActive()
                    val pct = if (total > 0) ((copied * 100) / total).toInt() else 100
                    if (pct != lastPct) {
                        lastPct = pct
                        operationProgress = pct / 100f
                    }
                }
                val target = dst
                val ok = withContext(Dispatchers.IO) {
                    if (cut) FileOps.moveWithProgress(src, target, onProgress) { job?.isActive == false }
                    else FileOps.copyWithProgress(src, target, onProgress) { job?.isActive == false }
                }
                if (ok) done++ else failed++
            }

            isOperationRunning = false
            operationDeterminate = false
            if (operationJob === job) operationJob = null
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
        if (isOperationActive()) return
        val target = File(file.parentFile, newName)
        if (target.exists()) {
            Toast.makeText(context, context.getString(R.string.fm_already_exists, newName), Toast.LENGTH_SHORT).show()
            return
        }
        operationJob = scope.launch {
            val job = coroutineContext[Job]
            isOperationRunning = true
            operationLabel = context.getString(R.string.fm_op_renaming)
            val ok = withContext(Dispatchers.IO) { file.renameTo(target) }
            isOperationRunning = false
            if (operationJob === job) operationJob = null
            loadDirectory(currentDir, resetScroll = false)
            if (!ok) Toast.makeText(context, R.string.fm_rename_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun createFolder(parent: File, name: String) {
        if (isOperationActive()) return
        val target = File(parent, name)
        if (target.exists()) {
            Toast.makeText(context, context.getString(R.string.fm_already_exists, name), Toast.LENGTH_SHORT).show()
            return
        }
        operationJob = scope.launch {
            val job = coroutineContext[Job]
            isOperationRunning = true
            operationLabel = context.getString(R.string.fm_op_creating_folder)
            val ok = withContext(Dispatchers.IO) { target.mkdirs() }
            isOperationRunning = false
            if (operationJob === job) operationJob = null
            loadDirectory(currentDir, resetScroll = false)
            if (!ok) Toast.makeText(context, R.string.fm_create_folder_failed, Toast.LENGTH_SHORT).show()
        }
    }

    var showDriveMenu by remember { mutableStateOf(false) }
    var storageTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) storageTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val drives = remember(storageTick) { StorageRoots.list(context) }
    val linuxRoot = remember(storageTick) {
        com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context).takeIf { File(it, "usr").isDirectory }
    }


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
                    if (isOperationActive()) return@TextButton
                    pendingBulkDelete = emptyList()
                    selectionMode = false
                    selectedPaths = emptySet()
                    operationJob = scope.launch {
                        isOperationRunning = true
                        val job = coroutineContext[Job]
                        var failed = 0
                        victims.forEachIndexed { i, f ->
                            operationLabel = context.getString(R.string.fm_op_deleting_batch, i + 1, victims.size, f.name)
                            if (!withContext(Dispatchers.IO) { FileOps.delete(f) { job?.isActive == false } }) failed++
                        }
                        isOperationRunning = false
                        if (operationJob === job) operationJob = null
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = { requestNavigation(FileManagerNavigation.BACK) }, enabled = canGoUp && !cancellingNavigation) {
                Icon(Icons.Filled.ArrowBack, stringResource(R.string.fm_back), tint = MaterialTheme.colorScheme.primary)
            }

            val currentDriveLabel = stringResource(describeLocation(currentDir).driveLabel)
            val driveChipAlpha = if (showFavorites) 0.45f else 1f
            Box {
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
                Spacer(Modifier.weight(1f))
            } else {
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

            if (launchDir != null) {
                IconButton(onClick = { requestNavigation(FileManagerNavigation.RETURN_TO_START) }, enabled = !cancellingNavigation) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardReturn, stringResource(R.string.fm_return_to_start), tint = MaterialTheme.colorScheme.primary)
                }
                if (LocalConfiguration.current.screenWidthDp >= 700) Spacer(Modifier.width(56.dp))
            }

            if (!showFavorites) {
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
                    browsePrefs.edit().putBoolean("fmGridView", gridView).apply()
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
                                        if (sortBy == key) sortDesc = !sortDesc else { sortBy = key; sortDesc = false }
                                        browsePrefs.edit().putString("fmSortBy", sortBy)
                                            .putBoolean("fmSortDesc", sortDesc).apply()
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
                                browsePrefs.edit().putBoolean("fmCompactRows", compactRows).apply()
                                showSortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (showHidden) stringResource(R.string.fm_hide_hidden) else stringResource(R.string.fm_show_hidden)) },
                            onClick = {
                                showHidden = !showHidden
                                browsePrefs.edit().putBoolean("fmShowHidden", showHidden).apply()
                                showSortMenu = false
                                loadDirectory(currentDir, resetScroll = false)
                            },
                        )
                    }
                }
            }

            IconButton(onClick = { showFavorites = !showFavorites }) {
                if (showFavorites) {
                    Icon(Icons.Filled.Star, stringResource(R.string.fm_hide_favorites), tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Filled.StarBorder, stringResource(R.string.fm_show_favorites), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!pickMode && onExit != null) {
                IconButton(onClick = { requestNavigation(FileManagerNavigation.EXIT) }, enabled = !cancellingNavigation) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, stringResource(R.string.fm_exit), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (showSearch && !showFavorites) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.fm_filter_folder), fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        val freeSpace = remember(currentDir.absolutePath, entries) {
            runCatching { currentDir.usableSpace }.getOrDefault(0L)
        }
        if (!showFavorites) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                Text(
                    text = elidePathStart(currentDir.absolutePath, 52),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
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
                val allVisibleSelected = visibleEntries.isNotEmpty() && visibleEntries.all { it.absolutePath in selectedPaths }
                val anyVisibleSelected = visibleEntries.any { it.absolutePath in selectedPaths }
                val selBarPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                OutlinedButton(
                    onClick = {
                        val shown = visibleEntries.map { it.absolutePath }
                        selectedPaths = if (allVisibleSelected) selectedPaths - shown.toSet() else selectedPaths + shown
                    },
                    contentPadding = selBarPadding,
                ) { Text(if (allVisibleSelected) stringResource(R.string.fm_select_none) else stringResource(R.string.fm_select_all), fontSize = 12.sp) }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    enabled = anyVisibleSelected,
                    onClick = {
                        clipboardFiles = visibleEntries.filter { it.absolutePath in selectedPaths }
                        isCutOperation = false
                        selectionMode = false
                        selectedPaths = emptySet()
                    },
                    contentPadding = selBarPadding,
                ) { Text(stringResource(R.string.fm_copy), fontSize = 12.sp) }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    enabled = anyVisibleSelected,
                    onClick = {
                        clipboardFiles = visibleEntries.filter { it.absolutePath in selectedPaths }
                        isCutOperation = true
                        selectionMode = false
                        selectedPaths = emptySet()
                    },
                    contentPadding = selBarPadding,
                ) { Text(stringResource(R.string.fm_cut), fontSize = 12.sp) }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    enabled = anyVisibleSelected,
                    onClick = { pendingBulkDelete = visibleEntries.filter { it.absolutePath in selectedPaths } },
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

        if (clipboardFiles.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                    .clickable(enabled = !isOperationRunning) { performPaste() }
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
                    if (operationJob != null) {
                        TextButton(onClick = {
                            cancelledJob = operationJob
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
            linuxRoot?.let { add(locItem(stringResource(R.string.fm_drive_linux), Icons.Filled.Terminal, it)) }
        }
        val quickItems = buildList {
            File("/storage/emulated/0/Download").takeIf { it.isDirectory }?.let { add(locItem(stringResource(R.string.fm_rail_downloads), Icons.Filled.Download, it, stringResource(R.string.fm_rail_downloads_short))) }
            SessionPrefs.romsDir(context).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory }
                ?.let { add(locItem(stringResource(R.string.fm_rail_roms), Icons.Filled.SportsEsports, it)) }
            com.droiddeck.launcher.runtime.LinuxRuntime.logDir(context).takeIf { it.isDirectory }?.let { add(locItem(stringResource(R.string.fm_rail_session_logs), Icons.Filled.Description, it)) }
            File("/storage/emulated/0/Pictures").takeIf { it.isDirectory }?.let { add(locItem(stringResource(R.string.fm_rail_pictures), Icons.Filled.Image, it)) }
        }
        val favItems = remember(favTick) { FavoritesStore.list(context).map(::File).filter { it.exists() } }
            .map { d -> RailItem(d.name, Icons.Filled.Star, false) { showFavorites = false; openDrive(d) } }
        val locationSections = buildList {
            add(RailSection(stringResource(R.string.fm_rail_storage), storageItems))
            if (quickItems.isNotEmpty()) add(RailSection(stringResource(R.string.fm_rail_quick), quickItems))
            if (favItems.isNotEmpty()) add(RailSection(stringResource(R.string.fm_rail_favorites), favItems))
        }

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (!pickMode) {
                CollapsibleRail(state = fmRailState, title = stringResource(R.string.fm_rail_title), sections = locationSections, outlinedItems = true)
            }
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
        if (showFavorites) {
            FavoritesList(
                currentDir = currentDir,
                favTick = favTick,
                onPinCurrent = {
                    FavoritesStore.add(context, currentDir.absolutePath)
                    favTick++
                    Toast.makeText(context, context.getString(R.string.fm_favorite_added, currentDir.name), Toast.LENGTH_SHORT).show()
                },
                onJump = { dir ->
                    showFavorites = false
                    openDrive(dir)
                },
                onUnpin = { dir ->
                    FavoritesStore.remove(context, dir.absolutePath)
                    favTick++
                    Toast.makeText(context, context.getString(R.string.fm_favorite_removed, dir.name), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pullState.nestedScrollConnection),
        ) {
            val shownEntries = visibleEntries

            if (showGrid && entries.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    if (canGoUp) item(key = "..", span = { GridItemSpan(maxLineSpan) }) {
                        ParentFolderRow(compact = compactRows, onTap = { requestNavigation(FileManagerNavigation.BACK) })
                    }
                    items(shownEntries, key = { it.absolutePath }) { file ->
                        val isFav = remember(file.absolutePath, favTick) {
                            FavoritesStore.isFavorite(context, file.absolutePath)
                        }
                        FileGridTile(
                            file = file,
                            selectionMode = selectionMode,
                            selected = file.absolutePath in selectedPaths,
                            onLongPress = {
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
                                        pickPrefs.edit().putString("lastFilePickerDir", currentDir.absolutePath).apply()
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
                                val nowFav = FavoritesStore.toggle(context, file.absolutePath)
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
                    ParentFolderRow(compact = compactRows, onTap = { requestNavigation(FileManagerNavigation.BACK) })
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
                            FavoritesStore.isFavorite(context, file.absolutePath)
                        }
                        FileItemRow(
                            file = file,
                            showActions = !pickMode,
                            compact = compactRows,
                            selectionMode = selectionMode,
                            selected = file.absolutePath in selectedPaths,
                            onLongPress = {
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
                                        pickPrefs.edit().putString("lastFilePickerDir", currentDir.absolutePath).apply()
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
                                val nowFav = FavoritesStore.toggle(context, file.absolutePath)
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
            if (pullState.verticalOffset > 0.5f || pullState.isRefreshing) {
                PullToRefreshContainer(
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
        }
            }
        }
    }
}
