package com.droiddeck.launcher.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.artifact.RepositorySources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private data class SourceDraft(
    val key: String,
    val repository: String,
    val hidden: Boolean,
    val catalogBranch: String,
    val original: RepositorySources.Source? = null,
) {
    val builtIn: Boolean get() = original?.builtIn == true
}

@Composable
internal fun RepositorySourcesButton(
    scopeKey: String,
    builtIns: List<String>,
    enabled: Boolean = true,
    title: String = stringResource(R.string.sources_component_title),
    showCatalogBranch: Boolean = false,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val palette = LocalPalette.current
    var menuOpen by remember(scopeKey) { mutableStateOf(false) }
    val anchor = remember { arrayOfNulls<Rect>(1) }
    var menu by remember(scopeKey) { mutableStateOf<StepAsk?>(null) }
    var loading by remember(scopeKey) { mutableStateOf(false) }
    var loaded by remember(scopeKey) { mutableStateOf(false) }
    var saving by remember(scopeKey) { mutableStateOf(false) }
    var rows by remember(scopeKey) { mutableStateOf<List<SourceDraft>>(emptyList()) }
    var error by remember(scopeKey) { mutableStateOf<String?>(null) }
    val loadError = stringResource(R.string.sources_load_error)
    val saveError = stringResource(R.string.sources_save_error)
    val invalidRepository = stringResource(R.string.sources_invalid_repository)
    val invalidCatalogBranch = stringResource(R.string.sources_invalid_catalog_branch)
    DisposableEffect(scopeKey) {
        onDispose { if (menu != null && Steps.step === menu) Steps.drop() }
    }
    LaunchedEffect(menuOpen, scopeKey) {
        if (!menuOpen) return@LaunchedEffect
        loading = true
        loaded = false
        error = null
        try {
            rows = withContext(ioDispatcher) {
                RepositorySources.list(context, scopeKey, builtIns).map {
                    SourceDraft(it.id, it.repository, it.hidden, it.catalogBranch.orEmpty(), it)
                }
            }
            loaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = loadError
        } finally {
            loading = false
        }
    }
    val content: @Composable StepOutScope.() -> Unit = {
        StepTitle(title)
        val inputMode = LocalInputModeManager.current.inputMode
        LaunchedEffect(open, loaded, inputMode) {
            if (open && loaded && inputMode == InputMode.Keyboard) {
                repeat(2) { androidx.compose.runtime.withFrameNanos { } }
                runCatching { first.requestFocus() }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().stepItem(1).padding(10.dp))
        Column(Modifier.fillMaxWidth().stepItem(1), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEachIndexed { index, row ->
                key(row.key) {
                    val editable = open && loaded && !loading && !saving
                    val remove = { rows = rows.filterNot { it.key == row.key } }
                    val toggle = { rows = rows.map { if (it.key == row.key) it.copy(hidden = !it.hidden) else it } }
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!row.builtIn) IconButton(
                                onClick = remove,
                                enabled = editable,
                                modifier = Modifier.size(48.dp).controllerConfirm(enabled = editable, onClick = remove),
                            ) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.sources_remove, row.repository), tint = palette.error)
                            }
                            if (row.builtIn) {
                                Text(
                                    row.repository,
                                    modifier = Modifier.weight(1f).border(1.dp, palette.line, Shape12)
                                        .padding(horizontal = 10.dp, vertical = 12.dp),
                                    color = if (row.hidden) colors.onSurfaceVariant else colors.onBackground,
                                    fontSize = 14.sp,
                                    textDecoration = if (row.hidden) TextDecoration.LineThrough else TextDecoration.None,
                                )
                            } else {
                                OutlinedTextField(
                                    value = row.repository,
                                    onValueChange = { text ->
                                        rows = rows.map { if (it.key == row.key) it.copy(repository = text) else it }
                                        error = null
                                    },
                                    enabled = editable,
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    label = { Text(stringResource(R.string.sources_repository)) },
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        textDecoration = if (row.hidden) TextDecoration.LineThrough else TextDecoration.None,
                                    ),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            IconButton(
                                onClick = toggle,
                                enabled = editable,
                                modifier = Modifier.size(48.dp).then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                    .controllerConfirm(enabled = editable, onClick = toggle),
                            ) {
                                Icon(
                                    if (row.hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    stringResource(if (row.hidden) R.string.sources_show else R.string.sources_hide, row.repository),
                                    tint = if (row.hidden) colors.onSurfaceVariant else colors.onBackground,
                                )
                            }
                        }
                        if (showCatalogBranch) Column(
                            Modifier.fillMaxWidth().padding(horizontal = 56.dp, vertical = 2.dp),
                        ) {
                            OutlinedTextField(
                                value = row.catalogBranch,
                                onValueChange = { text ->
                                    rows = rows.map { if (it.key == row.key) it.copy(catalogBranch = text) else it }
                                    error = null
                                },
                                enabled = editable,
                                singleLine = true,
                                label = { Text(stringResource(R.string.sources_catalog_branch)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(R.string.sources_catalog_branch_default),
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            val canAdd = open && loaded && !loading && !saving && rows.size < 100
            val add = { rows = rows + SourceDraft(UUID.randomUUID().toString(), "", false, "") }
            TextButton(
                onClick = add,
                enabled = canAdd,
                modifier = Modifier.heightIn(min = 48.dp).then(if (rows.isEmpty()) Modifier.focusRequester(first) else Modifier)
                    .controllerConfirm(enabled = canAdd, onClick = add),
            ) {
                Icon(Icons.Outlined.AddCircle, null, tint = palette.good)
                Text(stringResource(R.string.sources_add), color = palette.good, modifier = Modifier.padding(start = 8.dp))
            }
            error?.let { Text(it, color = palette.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp)) }
            Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.End) {
                PrimaryButton(
                    stringResource(if (saving) R.string.sources_saving else R.string.sources_save),
                    enabled = open && loaded && !loading && !saving,
                ) {
                    val branches = try {
                        rows.map { RepositorySources.normalizeCatalogBranch(it.catalogBranch) }
                    } catch (_: IllegalArgumentException) {
                        error = invalidCatalogBranch
                        return@PrimaryButton
                    }
                    val sources = try {
                        rows.zip(branches).map { (row, branch) ->
                            if (row.builtIn) row.original!!.copy(hidden = row.hidden, catalogBranch = branch)
                            else RepositorySources.source(row.repository, row.hidden, branch)
                        }
                    } catch (_: IllegalArgumentException) {
                        error = invalidRepository
                        return@PrimaryButton
                    }
                    saving = true
                    error = null
                    coroutineScope.launch {
                        try {
                            withContext(ioDispatcher) { RepositorySources.save(context, scopeKey, builtIns, sources) }
                            saving = false
                            if (Steps.step === menu) Steps.fold()
                            onSaved()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = saveError
                        } finally {
                            saving = false
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.sources_hidden_note), color = colors.onSurfaceVariant, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().stepItem(1).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
    Box(Modifier.onGloballyPositioned { anchor[0] = it.boundsInRoot() }) {
        ToolIcon(Icons.Outlined.CallSplit, title, enabled = enabled && scopeKey.isNotBlank()) {
            if (menu != null && Steps.step === menu) {
                Steps.fold()
            } else {
                val from = anchor[0] ?: return@ToolIcon
                menuOpen = true
                loading = true
                loaded = false
                val ask = StepAsk(
                    anchor = from, side = StepSide.Left, accent = palette.signal, pillCorner = 12.dp,
                    items = 2, maxWidth = 520.dp, canDismiss = { !saving },
                    onDismiss = { menuOpen = false; menu = null },
                    handle = { Icon(Icons.Outlined.CallSplit, null, tint = colors.onBackground, modifier = Modifier.size(20.dp)) },
                    content = content,
                )
                menu = ask
                Steps.ask(ask)
            }
        }
    }
}
