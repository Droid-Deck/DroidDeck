package com.droiddeck.launcher.files

import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalInspectionMode
import com.droiddeck.launcher.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The File Manager's properties dialog.

/**
 * Properties sheet for a single file or folder: basic info plus the two Windows/Wine file attributes
 * the in-container file manager (wfm.exe) also exposes - Read-only and Hidden. Each toggle applies
 * immediately (off the main thread) and refreshes the listing via [onChanged].
 *
 * State is keyed on the file so it always reflects the entry it was opened for. On a filesystem that
 * can't store Wine's DOSATTRIB xattr (the FUSE /storage volumes) only the Hidden toggle is disabled;
 * Read-only keeps working everywhere.
 */
@Composable
internal fun FilePropertiesDialog(
    file: File,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inspecting = LocalInspectionMode.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var attrs by remember(file.absolutePath) { mutableStateOf<FileAttrState?>(if (inspecting) FileAttrState(false, false, false) else null) }
    // Guards against a second toggle landing while the first is still being applied off-thread.
    var busy by remember(file.absolutePath) { mutableStateOf(false) }
    LaunchedEffect(file.absolutePath) {
        if (inspecting) return@LaunchedEffect
        attrs = withContext(Dispatchers.IO) { readFileAttrs(file) }
    }

    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fm_properties)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // ── Basic info ──
                Text(
                    text = file.name,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                PropertyLine(stringResource(R.string.fm_prop_location), file.parent ?: "-")
                PropertyLine(
                    stringResource(R.string.fm_prop_type),
                    if (file.isDirectory) stringResource(R.string.fm_type_folder)
                    else file.extension.uppercase().let { if (it.isBlank()) stringResource(R.string.fm_type_file) else stringResource(R.string.fm_type_ext_file, it) },
                )
                if (!file.isDirectory) PropertyLine(stringResource(R.string.fm_prop_size), FileOps.formatBytes(context, file.length()))
                PropertyLine(stringResource(R.string.fm_prop_modified), dateFormat.format(Date(file.lastModified())))

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(6.dp))

                val state = attrs
                // ── Read-only ── checked when the owner can't write (Wine's FILE_ATTRIBUTE_READONLY).
                AttributeToggleRow(
                    label = stringResource(R.string.fm_read_only),
                    description = stringResource(R.string.fm_read_only_hint),
                    checked = state?.readOnly == true,
                    enabled = state != null && !busy,
                    onToggle = toggle@{ want ->
                        if (inspecting) {
                            attrs = attrs?.copy(readOnly = want)
                            return@toggle
                        }
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { setReadOnly(file, want) }
                            busy = false
                            if (ok) {
                                attrs = attrs?.copy(readOnly = want)
                                onChanged()
                            } else {
                                Toast.makeText(context, R.string.fm_read_only_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
                Spacer(Modifier.height(2.dp))
                // ── Hidden ── Wine's DOS hidden bit in the user.DOSATTRIB xattr.
                val hiddenSupported = state?.hiddenSupported == true
                AttributeToggleRow(
                    label = stringResource(R.string.fm_hidden),
                    description = if (state != null && !hiddenSupported)
                        stringResource(R.string.fm_hidden_unsupported)
                    else stringResource(R.string.fm_hidden_hint),
                    checked = state?.hidden == true,
                    enabled = state != null && hiddenSupported && !busy,
                    onToggle = toggle@{ want ->
                        if (inspecting) {
                            attrs = attrs?.copy(hidden = want)
                            return@toggle
                        }
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { setHidden(file, want) }
                            busy = false
                            if (ok) {
                                attrs = attrs?.copy(hidden = want)
                                onChanged()
                            } else {
                                // The write failed after all - disable the toggle rather than lie.
                                attrs = attrs?.copy(hiddenSupported = false)
                                Toast.makeText(context, R.string.fm_hidden_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.fm_done)) } },
    )
}

// One "label: value" line in the Properties info block.
@Composable
private fun PropertyLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// A labelled attribute switch with a one-line description, used by the Properties sheet.
@Composable
private fun AttributeToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onToggle,
        )
    }
}
