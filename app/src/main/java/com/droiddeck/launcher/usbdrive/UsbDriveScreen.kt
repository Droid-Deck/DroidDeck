package com.droiddeck.launcher.usbdrive

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.files.FileOps
import com.droiddeck.launcher.files.InAppFilePicker
import com.droiddeck.launcher.files.OutlinedAlertDialog
import com.droiddeck.launcher.files.ParentFolderRow
import com.droiddeck.launcher.files.elidePathStart
import java.io.File
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Stage {
    data object Starting : Stage
    data class Permission(val denied: Boolean) : Stage
    data object ConfirmInUse : Stage
    data object Opening : Stage
    data class Failed(val message: String) : Stage
    data object Ready : Stage
}

/** Hands an opened session to the screen, or closes it when the screen already went away. */
private class SessionHolder {
    private var session: UsbDriveSession? = null
    private var disposed = false

    @Synchronized fun publish(opened: UsbDriveSession): Boolean {
        if (disposed) return false
        session = opened
        return true
    }

    @Synchronized fun take(): UsbDriveSession? = session.also { session = null }

    @Synchronized fun dispose(): UsbDriveSession? {
        disposed = true
        return take()
    }
}

private fun closeInBackground(session: UsbDriveSession?) {
    if (session != null) thread(name = "usbdrive-close") { runCatching { session.close() } }
}

@Composable
internal fun UsbDriveScreen(deviceName: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember { SessionHolder() }
    var stage by remember { mutableStateOf<Stage>(Stage.Starting) }
    var session by remember { mutableStateOf<UsbDriveSession?>(null) }
    val device = remember { UsbDrives.find(context, deviceName) }
    val label = remember { device?.let { UsbDrives.label(context, it) } ?: context.getString(R.string.usbdrive_default_name) }

    fun open(target: UsbDevice) {
        stage = Stage.Opening
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val opened = UsbDrives.open(context, target)
                    if (holder.publish(opened)) {
                        opened
                    } else {
                        opened.close()
                        null
                    }
                }
            }
            result
                .onSuccess { opened -> if (opened != null) { session = opened; stage = Stage.Ready } }
                .onFailure { stage = Stage.Failed(it.message ?: it.toString()) }
        }
    }

    fun proceed(target: UsbDevice) {
        if (UsbDrives.androidMayBeUsingUsbVolume(context)) stage = Stage.ConfirmInUse else open(target)
    }

    fun start() {
        val target = device
        when {
            target == null -> stage = Stage.Failed(context.getString(R.string.usbdrive_disconnected))
            !UsbDrives.supported -> stage = Stage.Failed(context.getString(R.string.usbdrive_not_in_build))
            UsbDrives.hasPermission(context, target) -> proceed(target)
            else -> {
                stage = Stage.Permission(denied = false)
                UsbDrives.requestPermission(context, target) { granted ->
                    if (granted) {
                        proceed(target)
                    } else {
                        stage = Stage.Permission(denied = true)
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { start() }

    DisposableEffect(deviceName) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val gone: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (gone?.deviceName != deviceName) return
                closeInBackground(holder.take())
                session = null
                stage = Stage.Failed(context.getString(R.string.usbdrive_disconnected))
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            closeInBackground(holder.dispose())
        }
    }

    val current = session
    if (stage == Stage.Ready && current != null) {
        DriveBrowser(session = current, driveLabel = label, onClose = onClose)
        return
    }

    BackHandler(onBack = onClose)
    Column(modifier = Modifier.fillMaxSize()) {
        Header(title = label, onBack = onClose)
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            when (val s = stage) {
                Stage.Starting, Stage.Opening, Stage.Ready -> Waiting(stringResource(R.string.usbdrive_opening))
                is Stage.Permission -> if (s.denied) {
                    Message(stringResource(R.string.usbdrive_permission_denied), stringResource(R.string.usbdrive_ask_again)) { start() }
                } else {
                    Waiting(stringResource(R.string.usbdrive_permission_waiting))
                }
                is Stage.Failed -> Message(s.message, stringResource(R.string.usbdrive_retry).takeIf { device != null }) { start() }
                Stage.ConfirmInUse -> OutlinedAlertDialog(
                    onDismissRequest = onClose,
                    title = { Text(stringResource(R.string.usbdrive_in_use_title)) },
                    text = { Text(stringResource(R.string.usbdrive_in_use_body, label)) },
                    confirmButton = {
                        TextButton(onClick = { device?.let(::open) }) { Text(stringResource(R.string.usbdrive_in_use_confirm)) }
                    },
                    dismissButton = { TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) } },
                )
            }
        }
    }
}

private data class CopyState(val counting: Boolean, val progress: NtfsCopier.Progress?, val finished: String?)

@Composable
private fun DriveBrowser(session: UsbDriveSession, driveLabel: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val disk = session.disk
    val ntfs = remember(disk) { disk.partitions.filter { it.isNtfs } }
    var partition by remember { mutableStateOf(ntfs.firstOrNull()) }
    var volume by remember { mutableStateOf<NtfsVolume?>(null) }
    var info by remember { mutableStateOf<NtfsVolumeInfo?>(null) }
    var mountError by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf(NtfsPaths.ROOT) }
    var entries by remember { mutableStateOf<List<NtfsEntry>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val selected = remember { mutableStateMapOf<String, NtfsEntry>() }
    var copy by remember { mutableStateOf<CopyState?>(null) }
    val cancelCopy = remember { AtomicBoolean(false) }

    LaunchedEffect(partition) {
        val target = partition ?: return@LaunchedEffect
        volume = null
        info = null
        mountError = null
        path = NtfsPaths.ROOT
        selected.clear()
        withContext(Dispatchers.IO) { runCatching { session.mount(target).let { it to it.info() } } }
            .onSuccess { (mounted, details) -> volume = mounted; info = details }
            .onFailure { mountError = it.message ?: it.toString() }
    }

    DisposableEffect(volume) {
        val mounted = volume
        onDispose { if (mounted != null) thread(name = "usbdrive-unmount") { runCatching { mounted.close() } } }
    }

    LaunchedEffect(volume, path, reload) {
        val mounted = volume ?: return@LaunchedEffect
        entries = null
        listError = null
        withContext(Dispatchers.IO) { runCatching { mounted.list(path).sortedWith(ntfsEntryOrder) } }
            .onSuccess { entries = it }
            .onFailure { listError = it.message ?: it.toString() }
    }

    fun format(bytes: Long) = FileOps.formatBytes(context, bytes)

    fun startCopy(destination: File) {
        val mounted = volume ?: return
        val items = selected.map { (itemPath, entry) -> NtfsItem(itemPath, entry) }
        if (items.isEmpty()) return
        cancelCopy.set(false)
        copy = CopyState(counting = true, progress = null, finished = null)
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                val worker = this
                val stop = { cancelCopy.get() || !worker.isActive }
                try {
                    val copier = NtfsCopier(mounted)
                    val plan = copier.plan(items, stop)
                    if (stop()) return@withContext context.getString(R.string.usbdrive_copy_cancelled)
                    val needed = copier.bytesNeeded(plan, destination)
                    val free = StatFs(destination.path).availableBytes
                    if (needed > free) return@withContext context.getString(R.string.usbdrive_no_space, format(needed), format(free))
                    var shown = 0L
                    val outcome = copier.copy(plan, destination, stop) { progress ->
                        val now = SystemClock.uptimeMillis()
                        if (now - shown >= 100) {
                            shown = now
                            copy = CopyState(counting = false, progress = progress, finished = null)
                        }
                    }
                    if (outcome.cancelled) {
                        context.getString(R.string.usbdrive_copy_cancelled)
                    } else {
                        val res = context.resources
                        buildList {
                            add(res.getQuantityString(R.plurals.usbdrive_copy_done, outcome.copied, outcome.copied))
                            if (outcome.skipped > 0) add(res.getQuantityString(R.plurals.usbdrive_copy_skipped, outcome.skipped, outcome.skipped))
                        }.joinToString(" ")
                    }
                } catch (e: Exception) {
                    context.getString(R.string.usbdrive_copy_failed, e.message ?: e.toString())
                }
            }
            copy = CopyState(counting = false, progress = copy?.progress, finished = message)
            selected.clear()
        }
    }

    val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedFile(result.data)?.let(::startCopy)
    }

    BackHandler {
        when {
            selected.isNotEmpty() -> selected.clear()
            path != NtfsPaths.ROOT -> path = NtfsPaths.parent(path) ?: NtfsPaths.ROOT
            else -> onClose()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Header(title = driveLabel, subtitle = format(disk.sizeBytes), onBack = onClose)
        PartitionSummary(disk = disk, current = partition, onPick = { partition = it })

        info?.let { details ->
            Text(
                text = listOfNotNull(
                    details.label.takeIf { it.isNotBlank() },
                    stringResource(R.string.usbdrive_read_only),
                    stringResource(R.string.usbdrive_free_of, format(details.free), format(details.size)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (details.dirty) Warning(stringResource(R.string.usbdrive_dirty))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = elidePathStart(path, 48),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.usbdrive_show_hidden), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Switch(checked = showHidden, onCheckedChange = { showHidden = it })
        }
        HorizontalDivider()

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val shown = entries?.filter { showHidden || !it.hidden }
            when {
                ntfs.isEmpty() -> Message(stringResource(R.string.usbdrive_no_ntfs), null) {}
                mountError != null -> Message(mountError.orEmpty(), null) {}
                listError != null -> Message(listError.orEmpty(), stringResource(R.string.usbdrive_retry)) { reload++ }
                shown == null -> Waiting(null)
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (path != NtfsPaths.ROOT) {
                        item(key = "parent") { ParentFolderRow { path = NtfsPaths.parent(path) ?: NtfsPaths.ROOT } }
                    }
                    if (shown.isEmpty()) {
                        item(key = "placeholder") {
                            Text(
                                stringResource(R.string.usbdrive_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    items(shown, key = { "entry:${it.name}" }) { entry ->
                        val entryPath = NtfsPaths.child(path, entry.name)
                        val isSelected = selected.containsKey(entryPath)
                        fun toggle() {
                            if (isSelected) selected.remove(entryPath) else selected[entryPath] = entry
                        }
                        EntryRow(
                            entry = entry,
                            detail = if (entry.isDirectory) null else "${format(entry.size)} · ${formatDate(context, entry.modified)}",
                            selecting = selected.isNotEmpty(),
                            selected = isSelected,
                            onTap = {
                                when {
                                    selected.isNotEmpty() || !entry.isDirectory -> toggle()
                                    else -> path = entryPath
                                }
                            },
                            onLongPress = ::toggle,
                        )
                    }
                }
            }
        }

        if (selected.isNotEmpty()) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    context.resources.getQuantityString(R.plurals.usbdrive_selected, selected.size, selected.size),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { selected.clear() }) { Text(stringResource(R.string.usbdrive_clear_selection)) }
                Button(onClick = {
                    pickDestination.launch(
                        InAppFilePicker.buildDirIntent(context, title = context.getString(R.string.usbdrive_copy_destination)),
                    )
                }) { Text(stringResource(R.string.usbdrive_copy_to)) }
            }
        }
    }

    copy?.let { state ->
        CopyDialog(
            state = state,
            format = ::format,
            onCancel = { cancelCopy.set(true) },
            onDismiss = { copy = null },
        )
    }
}

@Composable
private fun CopyDialog(state: CopyState, format: (Long) -> String, onCancel: () -> Unit, onDismiss: () -> Unit) {
    OutlinedAlertDialog(
        onDismissRequest = { if (state.finished != null) onDismiss() },
        title = { Text(stringResource(R.string.usbdrive_copy_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val progress = state.progress
                when {
                    state.finished != null -> Text(state.finished)
                    state.counting || progress == null -> {
                        Text(stringResource(R.string.usbdrive_copy_counting))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        Text(progress.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(
                            progress = { if (progress.bytesTotal > 0) progress.bytesDone.toFloat() / progress.bytesTotal else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.usbdrive_copy_progress, format(progress.bytesDone), format(progress.bytesTotal)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state.finished != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            } else {
                TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun PartitionSummary(disk: UsbDiskInfo, current: UsbPartition?, onPick: (UsbPartition) -> Unit) {
    val context = LocalContext.current
    val ntfsCount = disk.partitions.count { it.isNtfs }
    disk.partitions.forEach { part ->
        val size = FileOps.formatBytes(context, disk.bytesOf(part))
        val name = part.label.ifBlank { stringResource(R.string.usbdrive_partition_name, part.index) }
        val detail = when (part.fs) {
            FS_NTFS -> stringResource(R.string.usbdrive_partition_ntfs, size)
            FS_EXFAT -> stringResource(R.string.usbdrive_partition_android, "exFAT", size)
            FS_FAT -> stringResource(R.string.usbdrive_partition_android, "FAT", size)
            else -> stringResource(R.string.usbdrive_partition_unknown, size)
        }
        val pickable = part.isNtfs && ntfsCount > 1
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (pickable) Modifier.combinedClickableCompat { onPick(part) } else Modifier)
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$name — $detail",
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    part == current -> MaterialTheme.colorScheme.primary
                    part.isNtfs -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onLongPress: (() -> Unit)? = null, onTap: () -> Unit): Modifier =
    combinedClickable(onClick = onTap, onLongClick = onLongPress)

@Composable
private fun EntryRow(
    entry: NtfsEntry,
    detail: String?,
    selecting: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickableCompat(onLongPress = onLongPress, onTap = onTap)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onTap() })
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit, subtitle: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
        Icon(Icons.Filled.Usb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Warning(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun Waiting(text: String?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        if (text != null) {
            Spacer(Modifier.height(16.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Message(text: String, action: String?, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

private fun formatDate(context: Context, millis: Long): String =
    if (millis <= 0) "" else DateFormat.getDateFormat(context).format(Date(millis)) + " " + DateFormat.getTimeFormat(context).format(Date(millis))
