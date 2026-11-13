package com.droiddeck.launcher.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.glasses.Glasses
import com.droiddeck.launcher.glasses.GlassesState
import com.droiddeck.launcher.glasses.VitureSdkImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

@Composable
internal fun VitureSdkImportControls(glasses: GlassesState, visible: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<VitureSdkImport.Prepared?>(null) }
    var busy by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fallbackError = stringResource(R.string.viture_sdk_import_failed)
    fun discard() {
        val candidate = pending
        pending = null
        if (candidate != null) scope.launch(NonCancellable + Dispatchers.IO) { candidate.close() }
    }
    DisposableEffect(Unit) {
        onDispose {
            val candidate = pending
            pending = null
            if (candidate != null) CoroutineScope(Dispatchers.IO).launch { candidate.close() }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            error = null
            scope.launch {
                var candidate: VitureSdkImport.Prepared? = null
                try {
                    runInterruptible(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            candidate = VitureSdkImport.prepare(context.applicationContext, input)
                        } ?: throw IllegalStateException(context.getString(R.string.viture_sdk_unreadable))
                    }
                    pending = candidate
                    candidate = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.localizedMessage ?: fallbackError
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { candidate?.close() }
                    busy = false
                }
            }
        }
    }
    if (visible && glasses.vitureDetected) SettingsGroup(stringResource(R.string.viture_sdk_title)) {
        VitureSdkImportRow(glasses.vitureDetected, glasses.vitureSdkAvailable, busy, installing) {
            error = null
            runCatching { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                .onFailure { error = it.localizedMessage ?: fallbackError }
        }
    }
    pending?.let { candidate ->
        VitureSdkConfirmation(candidate.version, Formatter.formatShortFileSize(context, candidate.libraryBytes),
            onDismiss = ::discard,
            onConfirm = {
                if (pending === candidate && !busy) {
                    pending = null
                    busy = true
                    installing = true
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            Glasses.installVitureSdk(candidate)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.localizedMessage ?: fallbackError
                        } finally {
                            withContext(NonCancellable + Dispatchers.IO) { candidate.close() }
                            busy = false
                            installing = false
                        }
                    }
                }
            })
    }
    error?.let { message ->
        GlideAlertDialog(
            onDismissRequest = { error = null },
            title = { Text(stringResource(R.string.viture_sdk_import_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { error = null }, modifier = Modifier.focusGlide(CircleShape)) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }
}

@Composable
internal fun VitureSdkImportRow(detected: Boolean, available: Boolean, busy: Boolean, installing: Boolean = false, onImport: () -> Unit) {
    if (!detected) return
    when {
        busy -> SettingsRow(stringResource(R.string.viture_sdk_title),
            stringResource(if (installing) R.string.viture_sdk_installing else R.string.viture_sdk_checking)) {
            LinearProgressIndicator(Modifier.testTag("viture-sdk-progress"))
        }
        available -> SettingsRow(stringResource(R.string.viture_sdk_ready), stringResource(R.string.viture_sdk_ready_hint)) {}
        else -> ActionRow(stringResource(R.string.viture_sdk_title), stringResource(R.string.viture_sdk_import_hint),
            stringResource(R.string.viture_sdk_import), onImport, modifier = Modifier.testTag("viture-sdk-import"))
    }
}

@Composable
internal fun VitureSdkConfirmation(version: String, size: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    GlideAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viture_sdk_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.viture_sdk_confirm_details, version, size))
                Text(stringResource(R.string.viture_sdk_confirm_trust))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.focusGlide(CircleShape).testTag("viture-sdk-confirm")) {
                Text(stringResource(R.string.viture_sdk_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
