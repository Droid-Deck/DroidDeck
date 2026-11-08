package com.droiddeck.launcher.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun InputCommunityPage(library: InputLibrary, profile: String, initialRole: InputRole,
    onInstalled: (InputLibrary) -> Unit) {
    val context = LocalContext.current
    var targetProfile by remember { mutableStateOf(profile) }
    val scope = rememberCoroutineScope()
    val host = rememberMenuHost()
    var role by remember { mutableStateOf(initialRole.takeUnless { it == InputRole.PHYSICAL } ?: InputRole.PRIMARY) }
    var sources by remember { mutableStateOf(ControlSources.read(context)) }
    var selectedSource by remember { mutableStateOf(sources.first()) }
    var addingSource by remember { mutableStateOf(false) }
    var sourceUrl by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(CommunityControls.bundled(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<CommunityControls.Pack?>(null) }
    var exporting by remember { mutableStateOf<ByteArray?>(null) }
    var search by remember { mutableStateOf("") }
    val previews = remember { mutableStateMapOf<String, android.graphics.Bitmap>() }
    DisposableEffect(Unit) { onDispose { previews.values.forEach { it.recycle() } } }
    LaunchedEffect(entries) {
        for (entry in entries) {
            if (entry.preview == null || entry.key in previews) continue
            val bitmap = withContext(Dispatchers.IO) { runCatching { CommunityControls.preview(context, entry) }.getOrNull() }
            if (bitmap != null) previews[entry.key] = bitmap
        }
    }
    fun task(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = null
        scope.launch {
            try { action() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: context.getString(R.string.community_failed) }
            finally { busy = false }
        }
    }
    fun load(source: ControlSources.Source) {
        task {
            val remote = withContext(Dispatchers.IO) { CommunityControls.fetchCatalog(source) }
            val bundled = if (source.id == ControlSources.DROIDDECK.id) CommunityControls.bundled(context) else emptyList()
            entries = (remote + bundled).distinctBy { it.path }
            status = context.getString(R.string.community_refreshed)
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task {
            pending = withContext(Dispatchers.IO) {
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { CommunityControls.readLimited(it, 32 * 1024 * 1024) }
                runCatching { CommunityControls.parse(context, bytes) }.getOrElse {
                    ArmsControlSkins.parse(context, bytes, requireNotNull(library.layout(targetProfile, role)),
                        library.preset(library.profile(targetProfile).binding(role)), "Imported skin")
                }
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = exporting
        exporting = null
        if (uri != null && bytes != null) task {
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
            }
            status = context.getString(R.string.community_exported)
        }
    }
    InputHint(stringResource(R.string.community_description))
    SettingsGroup(stringResource(R.string.community_sources)) {
        ChoiceRow(host, "community-source", stringResource(R.string.community_source), null,
            sources.map { it.id to it.name }, selectedSource.id) { id ->
            if (!busy) {
                selectedSource = sources.first { it.id == id }
                entries = if (id == ControlSources.DROIDDECK.id) CommunityControls.bundled(context) else emptyList()
                load(selectedSource)
            }
        }
        PadTextButton(enabled = !busy, onClick = { sourceUrl = ""; addingSource = true }, modifier = Modifier.padding(8.dp).testTag("community-add-source")) {
            Text(stringResource(R.string.community_add_source))
        }
        if (!selectedSource.builtIn) PadTextButton(enabled = !busy, onClick = { task {
            ControlSources.remove(context, selectedSource)
            sources = ControlSources.read(context)
            selectedSource = sources.first()
            entries = CommunityControls.bundled(context)
        } }, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.community_remove_source)) }
    }
    SettingsGroup(stringResource(R.string.community_target)) {
        ChoiceRow(host, "community-profile", stringResource(R.string.input_profile), null,
            library.profiles.map { it.id to it.name }, targetProfile) { if (!busy) targetProfile = it }
        ChoiceRow(host, "community-screen", stringResource(R.string.community_screen), null,
            listOf(InputRole.PRIMARY, InputRole.SECONDARY).map { it to roleLabel(it) }, role) { if (!busy) role = it }
    }
    SettingsGroup(stringResource(R.string.community_title)) {
        OutlinedTextField(search, { search = it.take(160) }, singleLine = true,
            label = { Text(stringResource(R.string.community_search)) }, modifier = Modifier.fillMaxWidth().padding(12.dp))
        for (entry in entries.filter { it.screen.supports(role) &&
            (search.isBlank() || "${it.name} ${it.author} ${it.game}".contains(search, ignoreCase = true)) }) {
            InputNavigationRow(entry.name, listOf(entry.author, entry.game, entry.license).filter { it.isNotEmpty() }.joinToString(" · "),
                Modifier.testTag("community-pack-${entry.path}"), leading = {
                    previews[entry.key]?.let { Image(it.asImageBitmap(), null, Modifier.width(112.dp).height(76.dp).testTag("community-preview-${entry.path}"), contentScale = ContentScale.Fit) }
                }) {
                task { pending = withContext(Dispatchers.IO) { CommunityControls.download(context, entry, library.layout(targetProfile, role),
                    library.preset(library.profile(targetProfile).binding(role))) } }
            }
        }
        if (entries.none { it.screen.supports(role) && (search.isBlank() || "${it.name} ${it.author} ${it.game}".contains(search, true)) })
            InputHint(stringResource(R.string.community_empty))
        PadTextButton(enabled = !busy, onClick = { load(selectedSource) }, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.community_refresh)) }
        PadTextButton(enabled = !busy, onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
            modifier = Modifier.padding(8.dp).testTag("community-import")) { Text(stringResource(R.string.community_import)) }
    }
    SettingsGroup(stringResource(R.string.community_share)) {
        InputHint(stringResource(R.string.community_submit_hint))
        val layout = library.layout(targetProfile, role)
        if (layout?.editable == true) PadTextButton(enabled = !busy, onClick = { task {
            exporting = withContext(Dispatchers.IO) { CommunityControls.export(context, layout, library.preset(library.profile(targetProfile).binding(role))) }
            exporter.launch(layout.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60) + ".ddcontrols.zip")
        } }, modifier = Modifier.padding(8.dp).testTag("community-export")) { Text(stringResource(R.string.community_export)) }
        if (layout?.editable == true && layout.elements.any { it.image.isNotEmpty() || it.stickImage.isNotEmpty() })
            PadTextButton(enabled = !busy, onClick = { task {
                exporting = withContext(Dispatchers.IO) { ArmsControlSkins.export(context, layout,
                    library.preset(library.profile(targetProfile).binding(role))) }
                exporter.launch(layout.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60) + ".skin.zip")
            } }, modifier = Modifier.padding(8.dp).testTag("community-export-skin")) { Text(stringResource(R.string.community_export_skin)) }
        PadTextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(selectedSource.submit ?: selectedSource.manifest))) }
                .onFailure { status = context.getString(R.string.community_failed) }
        }, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.community_submit)) }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp))
    status?.let { InputHint(it) }
    if (addingSource) AlertDialog(onDismissRequest = { if (!busy) addingSource = false },
        title = { Text(stringResource(R.string.community_add_source)) }, text = { Column {
            Text(stringResource(R.string.community_source_hint))
            OutlinedTextField(sourceUrl, { sourceUrl = it.take(2048) }, singleLine = true,
                label = { Text(stringResource(R.string.community_source_url)) }, modifier = Modifier.testTag("community-source-url"))
            status?.let { Text(it) }
        } }, confirmButton = {
            PadTextButton(enabled = !busy && sourceUrl.isNotBlank(), onClick = { task {
                val (source, remote) = withContext(Dispatchers.IO) {
                    var failure: Exception? = null
                    var found: Pair<ControlSources.Source, List<CommunityControls.Entry>>? = null
                    for (candidate in ControlSources.candidates(sourceUrl)) {
                        try { found = candidate to CommunityControls.fetchCatalog(candidate); break }
                        catch (e: Exception) { failure = e }
                    }
                    found ?: throw (failure ?: IllegalArgumentException(context.getString(R.string.community_failed)))
                }
                ControlSources.add(context, source)
                sources = ControlSources.read(context)
                selectedSource = sources.first { it.id == source.id }
                entries = remote
                addingSource = false
            } }) { Text(stringResource(R.string.community_connect)) }
        }, dismissButton = { PadTextButton(enabled = !busy, onClick = { addingSource = false }) { Text(stringResource(R.string.common_cancel)) } })
    pending?.let { pack ->
        AlertDialog(onDismissRequest = { if (!busy) pending = null }, title = { Text(pack.layout.name) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InputLayoutThumbnail(pack.layout, role, pack.images, Modifier.fillMaxWidth().height(180.dp))
                Text(stringResource(R.string.community_apply_hint, pack.layout.elements.size, library.profile(targetProfile).name, roleLabel(role)))
            } }, confirmButton = {
                PadTextButton(enabled = !busy && pack.layout.supports(role), onClick = { task {
                    val installed = withContext(Dispatchers.IO) { CommunityControls.install(context, pack, targetProfile, role) }
                    onInstalled(installed)
                    pending = null
                    status = context.getString(R.string.community_installed)
                } }) { Text(stringResource(R.string.community_apply)) }
            }, dismissButton = { PadTextButton(enabled = !busy, onClick = { pending = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}
