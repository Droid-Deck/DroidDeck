package com.droiddeck.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.BootMovie
import com.droiddeck.launcher.session.BootMovieLibrary
import com.droiddeck.launcher.session.BootMovieReport
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SteamDeckRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class MovieTab { INSTALLED, IMPORT, REPO, SOUND }

/** Downloads run here rather than in a tab, so switching tabs or closing the settings does not cancel one halfway. */
private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** The downloads under way, by post id and percent; kept here so they are still known when the tab or the settings come back. */
private val installProgress = mutableStateMapOf<String, Int>()

/** What the movie list looks like right now: every movie and the one Steam will play. */
private class MovieSnapshot(val entries: List<BootMovieLibrary.Entry>, val selected: String)

/** The movie list, read again every couple of seconds so a movie picked, added or removed inside Steam shows up here too. */
@Composable
private fun rememberMovies(refresh: Int, onSelectionChanged: () -> Unit): MovieSnapshot? {
    val app = LocalContext.current.applicationContext
    var snapshot by remember { mutableStateOf<MovieSnapshot?>(null) }
    val changed by androidx.compose.runtime.rememberUpdatedState(onSelectionChanged)
    LaunchedEffect(refresh) {
        while (true) {
            val before = snapshot?.selected
            snapshot = withContext(Dispatchers.IO) {
                BootMovie.syncFromSteam(app)
                MovieSnapshot(BootMovieLibrary.list(app), BootMovie.choice(app))
            }
            if (before != null && before != snapshot?.selected) changed()
            delay(2_000)
        }
    }
    return snapshot
}

@Composable
private fun TabChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.clip(shape)
            .background(if (selected) pal.signal.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.03f))
            .border(if (selected) 2.dp else 1.dp, if (selected) pal.signal else pal.line, shape)
            .glideBorder(hot, shape, pal.signal)
            .hoverable(src)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Tab, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The "Boot movie" settings group: Installed movies, Import your own movies, SteamDeckRepo and Sound. */
@Composable
internal fun BootMovieGroup(s: ModeSettings, a: ModeSettingsActions, host: MenuHost) {
    var tab by rememberSaveable { mutableStateOf(MovieTab.INSTALLED) }
    SettingsGroup(stringResource(R.string.boot_movie)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
            TabChip(stringResource(R.string.boot_movie_tab_installed), tab == MovieTab.INSTALLED, Modifier.weight(1f)) { tab = MovieTab.INSTALLED }
            TabChip(stringResource(R.string.boot_movie_tab_import), tab == MovieTab.IMPORT, Modifier.weight(1f)) { tab = MovieTab.IMPORT }
            TabChip(stringResource(R.string.boot_movie_tab_repo), tab == MovieTab.REPO, Modifier.weight(1f)) { tab = MovieTab.REPO }
            TabChip(stringResource(R.string.boot_movie_tab_sound), tab == MovieTab.SOUND, Modifier.weight(1f)) { tab = MovieTab.SOUND }
        }
        when (tab) {
            MovieTab.INSTALLED -> InstalledTab(a)
            MovieTab.IMPORT -> ImportTab(s, a)
            MovieTab.REPO -> RepoTab(a)
            MovieTab.SOUND -> SoundTab(s, a, host)
        }
    }
}

@Composable
private fun InstalledTab(a: ModeSettingsActions) {
    var browsing by remember { mutableStateOf(false) }
    val snapshot = rememberMovies(0, a.onBootMovieChanged)
    val current = snapshot?.let { snap -> snap.entries.firstOrNull { it.id == snap.selected }?.title }
    val hint = current ?: stringResource(R.string.common_working)
    SettingsRow(stringResource(R.string.boot_movie_installed), hint) {
        SecondaryButton(stringResource(R.string.boot_movie_browse), onClick = { browsing = true })
    }
    Text(
        stringResource(R.string.boot_movie_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp),
    )
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    // The report is for bug reports, so it shows only while Debugging tools is on (read each time this tab opens).
    val debugging = remember { com.droiddeck.launcher.agent.AgentAccess.commandsEnabled(app) }
    if (debugging) {
        SettingsRow(stringResource(R.string.boot_movie_report), stringResource(R.string.boot_movie_report_hint)) {
            SecondaryButton(stringResource(R.string.boot_movie_report_save), enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    val file = withContext(Dispatchers.IO) { BootMovieReport.write(app) }
                    saving = false
                    Toast.makeText(
                        app,
                        file?.let { app.getString(R.string.boot_movie_report_saved, it.path) } ?: app.getString(R.string.boot_movie_report_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            })
        }
    }
    if (browsing) InstalledMoviesDialog(a) { browsing = false }
}

@Composable
private fun MovieCover(entry: BootMovieLibrary.Entry, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext
    val cover by androidx.compose.runtime.produceState<File?>(null, entry.id, entry.title) {
        value = withContext(Dispatchers.IO) { BootMovieLibrary.cover(app, entry) }
    }
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0E1218))) {
        cover?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun movieSourceLabel(source: String): String = stringResource(
    when (source) {
        BootMovieLibrary.SOURCE_DROIDDECK -> R.string.boot_movie_source_droiddeck
        BootMovieLibrary.SOURCE_REPO -> R.string.boot_movie_source_repo
        BootMovieLibrary.SOURCE_IMPORT -> R.string.boot_movie_source_import
        BootMovieLibrary.SOURCE_POINTSHOP -> R.string.boot_movie_source_pointshop
        else -> R.string.boot_movie_source_steam
    },
)

/** The installed movies as a list with covers; the one Steam plays is highlighted, and the list follows changes made in Steam. */
@Composable
private fun InstalledMoviesDialog(a: ModeSettingsActions, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    var refresh by remember { mutableIntStateOf(0) }
    val snapshot = rememberMovies(refresh, a.onBootMovieChanged)
    var picked by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<BootMovieLibrary.Entry?>(null) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = picked ?: snapshot?.selected
    LaunchedEffect(snapshot?.selected) { picked = null }
    removing?.let { entry ->
        RemoveMovieDialog(
            entry,
            onConfirm = {
                removing = null
                scope.launch {
                    withContext(Dispatchers.IO) { BootMovieLibrary.remove(app, entry.id) }
                    a.onBootMovieChanged()
                    refresh++
                }
            },
            onDismiss = { removing = null },
        )
    }
    val firstRow = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(snapshot != null, filter) {
        if (snapshot != null && inputMode.inputMode == InputMode.Keyboard) focusSoon { firstRow }
    }
    AppDialog(shown, close, "boot-movies", wide = false, scrollable = false) {
        val filters = listOf(
            null to stringResource(R.string.store_all),
            BootMovieLibrary.SOURCE_DROIDDECK to movieSourceLabel(BootMovieLibrary.SOURCE_DROIDDECK),
            BootMovieLibrary.SOURCE_STEAM to movieSourceLabel(BootMovieLibrary.SOURCE_STEAM),
            BootMovieLibrary.SOURCE_POINTSHOP to movieSourceLabel(BootMovieLibrary.SOURCE_POINTSHOP),
            BootMovieLibrary.SOURCE_REPO to movieSourceLabel(BootMovieLibrary.SOURCE_REPO),
            BootMovieLibrary.SOURCE_IMPORT to movieSourceLabel(BootMovieLibrary.SOURCE_IMPORT),
        )
        var filterOpen by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Text(
                stringResource(R.string.boot_movie_installed), fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Box {
                androidx.compose.runtime.CompositionLocalProvider(LocalChipMinWidth provides 100.dp) {
                    ValueChip(filters.first { it.first == filter }.second, filterOpen) { filterOpen = !filterOpen }
                }
                AnchoredMenu(filterOpen, onDismiss = { filterOpen = false }, compact = true) { firstItemFocus ->
                    filters.forEachIndexed { index, (source, label) ->
                        MenuItem(label, checked = filter == source, focusRequester = if (index == 0) firstItemFocus else null) {
                            filter = source
                            filterOpen = false
                        }
                    }
                }
            }
            SmallIconButton(Icons.Filled.Close, stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.error, onClick = close)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            itemsIndexed(snapshot?.entries.orEmpty().filter { filter == null || it.source == filter }, key = { _, entry -> entry.id }) { index, entry ->
                ListRow(accent = entry.id == selected, tall = true, focus = if (index == 0) firstRow else null, onClick = { picked = entry.id; a.onBootMovie(entry.id) }) {
                    MovieCover(entry, Modifier.size(width = 96.dp, height = 54.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(movieSourceLabel(entry.source), fontSize = 12.sp, color = if (entry.id == selected) LocalPalette.current.signal else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (entry.removable) SmallIconButton(Icons.Filled.Close, stringResource(R.string.boot_movie_remove), MaterialTheme.colorScheme.error) {
                        removing = entry
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoveMovieDialog(entry: BootMovieLibrary.Entry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.boot_movie_remove_confirm, entry.title)) },
        text = { Text(stringResource(R.string.boot_movie_remove_confirm_body)) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onConfirm) { Text(stringResource(R.string.boot_movie_remove)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ImportTab(s: ModeSettings, a: ModeSettingsActions) {
    SettingsRow(
        stringResource(R.string.boot_movie_custom),
        stringResource(R.string.boot_movie_custom_hint, (BootMovie.MAX_BYTES shr 20).toInt(), BootMovie.MAX_CONVERT_SECONDS),
    ) {
        SecondaryButton(
            when {
                s.bootMovieProgress >= 0 -> stringResource(R.string.boot_movie_converting, s.bootMovieProgress)
                s.bootMovieImporting -> stringResource(R.string.common_working)
                else -> stringResource(R.string.boot_movie_import)
            },
            enabled = !s.bootMovieImporting,
            progress = s.bootMovieProgress.takeIf { it >= 0 }?.let { it / 100f },
            onClick = a.onImportBootMovie,
        )
    }
}

@Composable
private fun SoundTab(s: ModeSettings, a: ModeSettingsActions, host: MenuHost) {
    val app = LocalContext.current.applicationContext
    var volume by remember { mutableIntStateOf(SessionPrefs.bootMovieVolume(app)) }
    var seconds by remember { mutableIntStateOf(SessionPrefs.bootMovieSeconds(app)) }
    val duration by androidx.compose.runtime.produceState(0, s.bootMovie) {
        value = withContext(Dispatchers.IO) { BootMovieLibrary.durationSeconds(BootMovie.currentFile(app)) }
    }
    ToggleRow(
        host, "boot-movie-sound", stringResource(R.string.boot_movie_sound),
        stringResource(R.string.common_applies_next_session), s.bootMovieSound, onChange = a.onBootMovieSound,
    )
    Box(Modifier.padding(horizontal = 6.dp)) {
        ValueSlider(
            volume, 0..100, 5, s.bootMovieSound, stringResource(R.string.boot_movie_volume), { "$it%" },
        ) { volume = it; SessionPrefs.setBootMovieVolume(app, it) }
    }
    Box(Modifier.padding(horizontal = 6.dp)) {
        val shownSeconds = if (seconds in 1 until duration) seconds else duration
        ValueSlider(
            shownSeconds, 1..duration.coerceAtLeast(2), 1, duration > 1, stringResource(R.string.boot_movie_length),
            { value -> app.getString(R.string.boot_movie_seconds, value) },
        ) { next ->
            val stored = if (next >= duration) 0 else next
            seconds = stored
            SessionPrefs.setBootMovieSeconds(app, stored)
        }
    }
    Text(
        stringResource(R.string.boot_movie_length_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp),
    )
}

@Composable
private fun RepoTab(a: ModeSettingsActions) {
    val progress = installProgress
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var sort by rememberSaveable { mutableStateOf(SteamDeckRepo.Sort.TRENDING) }
    var device by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var jumping by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf<SteamDeckRepo.Post?>(null) }
    var installed by remember { mutableStateOf(setOf<String>()) }
    val chosen = rememberMovies(0, a.onBootMovieChanged)?.selected
    val inUse by androidx.compose.runtime.produceState<String?>(null, chosen) {
        value = withContext(Dispatchers.IO) {
            chosen?.takeIf { it.startsWith(BootMovieLibrary.PREFIX) }?.let { BootMovieLibrary.repoId(app, it.removePrefix(BootMovieLibrary.PREFIX)) }
        }
    }
    LaunchedEffect(progress.size) { installed = withContext(Dispatchers.IO) { BootMovieLibrary.installedRepoIds(app) } }
    val result by androidx.compose.runtime.produceState<SteamDeckRepo.Page?>(null, sort, device, query, page, retry) {
        value = null
        value = withContext(Dispatchers.IO) { SteamDeckRepo.fetch(sort, device, query, page) }
    }
    val last by androidx.compose.runtime.produceState<Int?>(null, sort, device, query, retry) {
        value = null
        value = withContext(Dispatchers.IO) { SteamDeckRepo.lastPage(sort, device, query) }
    }
    LaunchedEffect(last) { last?.let { if (page > it) page = it } }
    val installPost: (SteamDeckRepo.Post) -> Unit = { post ->
        if (progress.isEmpty()) {
            progress[post.id] = 0
            installScope.launch {
                val outcome = try {
                    withContext(Dispatchers.IO) { install(app, post) { progress[post.id] = it } }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.getString(R.string.boot_movie_repo_download_failed)
                } finally {
                    progress.remove(post.id)
                }
                Toast.makeText(app, outcome ?: app.getString(R.string.boot_movie_repo_installed, post.title), Toast.LENGTH_LONG).show()
                a.onBootMovieChanged()
            }
        }
    }
    val usePost: (SteamDeckRepo.Post) -> Unit = { post ->
        scope.launch {
            val name = withContext(Dispatchers.IO) { BootMovieLibrary.nameForRepo(app, post.id) }
            if (name != null) a.onBootMovie(BootMovieLibrary.idOf(name))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                ListRow(tall = true, onClick = { searching = true }) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(
                        query.ifEmpty { stringResource(R.string.boot_movie_repo_search) }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (query.isNotEmpty()) SecondaryButton(stringResource(R.string.boot_movie_repo_clear), compact = true) { query = ""; page = 1 }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabChip(stringResource(R.string.boot_movie_repo_all), device == null) { device = null; page = 1 }
            TabChip(stringResource(R.string.boot_movie_repo_deck), device == "steam_deck") { device = "steam_deck"; page = 1 }
            TabChip(stringResource(R.string.boot_movie_repo_machine), device == "steam_machine") { device = "steam_machine"; page = 1 }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SteamDeckRepo.Sort.values().forEach { option ->
                TabChip(
                    stringResource(
                        when (option) {
                            SteamDeckRepo.Sort.TRENDING -> R.string.boot_movie_repo_trending
                            SteamDeckRepo.Sort.DOWNLOADS -> R.string.boot_movie_repo_downloads
                            SteamDeckRepo.Sort.LIKES -> R.string.boot_movie_repo_likes
                            SteamDeckRepo.Sort.NEWEST -> R.string.boot_movie_repo_newest
                        },
                    ),
                    sort == option,
                ) { sort = option; page = 1 }
            }
        }
        when (val r = result) {
            null -> Text(stringResource(R.string.common_working), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            is SteamDeckRepo.Page.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.boot_movie_repo_failed), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.boot_movie_repo_retry), compact = true) { retry++ }
            }
            is SteamDeckRepo.Page.Ok -> {
                if (r.posts.isEmpty()) Text(stringResource(R.string.boot_movie_repo_empty), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                r.posts.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { post ->
                            RepoCard(
                                post, installed = post.id in installed, inUse = post.id == inUse, percent = progress[post.id], busy = progress.isNotEmpty(),
                                modifier = Modifier.weight(1f), onPreview = { previewing = post }, onInstall = { installPost(post) }, onUse = { usePost(post) },
                            )
                        }
                        if (row.size == 1) Box(Modifier.weight(1f))
                    }
                }
                RepoPager(page, last, hasMore = r.posts.size >= SteamDeckRepo.PAGE_SIZE, onPage = { page = it }, onJump = { jumping = true })
            }
        }
    }
    if (searching) TextEntryDialog(
        stringResource(R.string.boot_movie_repo_search), query, number = false, confirm = stringResource(R.string.stores_search_open),
        onSubmit = { value -> if (value != query) { query = value; page = 1 } }, onDismiss = { searching = false },
    )
    if (jumping) TextEntryDialog(
        stringResource(R.string.boot_movie_repo_go_to_page), page.toString(), number = true, confirm = stringResource(R.string.common_ok),
        onSubmit = { value -> value.toIntOrNull()?.let { n -> page = n.coerceIn(1, maxOf(1, last ?: n)) } }, onDismiss = { jumping = false },
    )
    previewing?.let { post ->
        PreviewDialog(
            post, installed = post.id in installed, inUse = post.id == inUse, percent = progress[post.id], busy = progress.isNotEmpty(),
            onInstall = { installPost(post) }, onUse = { usePost(post) }, onDismiss = { previewing = null },
        )
    }
}

/** Downloads [post] and puts it among the installed movies; returns a message when it failed, null when it worked. */
private fun install(app: android.content.Context, post: SteamDeckRepo.Post, onProgress: (Int) -> Unit): String? {
    val folder = File(app.cacheDir, "boot-movie-download").apply { mkdirs() }
    // Leftovers of a download that was cut short are cleared, and every download has a file of its own.
    folder.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L) it.delete() }
    val file = File.createTempFile("movie-", ".webm", folder)
    return try {
        if (!SteamDeckRepo.download(post, file, onProgress)) return app.getString(R.string.boot_movie_repo_download_failed)
        val cover = if (post.thumbnail.isNotEmpty()) SteamDeckRepo.bytes(post.thumbnail) else null
        when (BootMovie.import(app, file, onProgress = onProgress, title = post.title, source = BootMovieLibrary.SOURCE_REPO, repoId = post.id, cover = cover, use = false)) {
            is BootMovie.Import.Done -> null
            BootMovie.Import.TooLong -> app.getString(R.string.boot_movie_too_long, BootMovie.MAX_CONVERT_SECONDS)
            is BootMovie.Import.Unsupported -> app.getString(R.string.boot_movie_unsupported)
            BootMovie.Import.Failed -> app.getString(R.string.boot_movie_import_failed)
        }
    } finally {
        file.delete()
    }
}

@Composable
private fun RepoCard(
    post: SteamDeckRepo.Post, installed: Boolean, inUse: Boolean, percent: Int?, busy: Boolean, modifier: Modifier,
    onPreview: () -> Unit, onInstall: () -> Unit, onUse: () -> Unit,
) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val thumbShape = RoundedCornerShape(7.dp)
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val previewLabel = stringResource(R.string.boot_movie_repo_preview)
    Column(
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier.clip(shape)
            .background(if (inUse) pal.signal.copy(alpha = 0.14f) else colors.surface.copy(alpha = 0.6f))
            .border(if (inUse) 2.dp else 1.dp, if (inUse) pal.signal else pal.line, shape).padding(6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(thumbShape).background(Color(0xFF0E1218))
                .glideBorder(hot, thumbShape, pal.signal)
                .hoverable(src).clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onPreview)
                .controllerConfirm(onClick = onPreview)
                .semantics { contentDescription = previewLabel },
        ) {
            if (post.thumbnail.isNotEmpty()) AsyncImage(model = post.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
        Text(post.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(repoInfo(post), fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        RepoAction(installed, inUse, percent, busy, Modifier.fillMaxWidth(), onInstall, onUse)
    }
}

@Composable
private fun repoInfo(post: SteamDeckRepo.Post): String =
    listOf(post.author, "♥ ${post.likes}", "⇩ ${post.downloads}", if (post.seconds > 0) stringResource(R.string.boot_movie_seconds, post.seconds) else "")
        .filter { it.isNotEmpty() }.joinToString(" · ")

private const val AFTER_OPEN = 3
private const val AFTER_INSTALL = 1
private const val AFTER_USE = 2

/** Install and Use are different buttons in turn, and the button is disabled while installing; it takes focus back for the controller. */
@Composable
private fun RepoAction(
    installed: Boolean, inUse: Boolean, percent: Int?, busy: Boolean, modifier: Modifier, onInstall: () -> Unit, onUse: () -> Unit,
    takeFocus: Boolean = false,
) {
    val requester = remember { FocusRequester() }
    val focused = remember { booleanArrayOf(false) }
    var pending by remember { mutableIntStateOf(if (takeFocus) AFTER_OPEN else 0) }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(installed, inUse, busy, percent != null, pending) {
        if (pending == 0) return@LaunchedEffect
        if (inputMode.inputMode != InputMode.Keyboard) { pending = 0; return@LaunchedEffect }
        focusWithinFrames({ focused[0] }) { requester }
        when (pending) {
            AFTER_OPEN -> pending = 0
            AFTER_INSTALL -> if (installed && focused[0]) pending = 0
            AFTER_USE -> if (inUse && focused[0]) pending = 0
            else -> Unit
        }
    }
    val m = modifier.focusRequester(requester).onFocusChanged { focused[0] = it.hasFocus || it.isFocused }
    if (inUse) SecondaryButton(stringResource(R.string.boot_movie_in_use), compact = true, modifier = m, onClick = {})
    else if (installed) SecondaryButton(stringResource(R.string.boot_movie_repo_use), compact = true, modifier = m, onClick = { pending = AFTER_USE; onUse() })
    else SecondaryButton(
        if (percent != null) stringResource(R.string.boot_movie_converting, percent) else stringResource(R.string.boot_movie_repo_install),
        enabled = !busy, compact = true, modifier = m, onClick = { pending = AFTER_INSTALL; onInstall() },
    )
}

/** A movie from SteamDeckRepo played straight from the site, so it can be checked before it is installed. */
@Composable
private fun PreviewDialog(
    post: SteamDeckRepo.Post, installed: Boolean, inUse: Boolean, percent: Int?, busy: Boolean,
    onInstall: () -> Unit, onUse: () -> Unit, onDismiss: () -> Unit,
) {
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    val maxVideoHeight = (LocalConfiguration.current.screenHeightDp - 200).coerceAtLeast(120).dp
    AppDialog(shown, close, "boot-movie-preview", wide = false, maxWidth = 620.dp) {
        DialogTitleRow(post.title, close)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MoviePreview(post.video, Modifier.heightIn(max = maxVideoHeight).aspectRatio(16f / 9f))
        }
        Text(repoInfo(post), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        RepoAction(installed, inUse, percent, busy, Modifier.fillMaxWidth(), onInstall, onUse, takeFocus = true)
    }
}

@Composable
private fun MoviePreview(url: String, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    var state by remember(url) { mutableIntStateOf(0) }
    var ratio by remember(url) { mutableFloatStateOf(16f / 9f) }
    val player = remember(url) { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(url) {
        onDispose {
            runCatching { player[0]?.release() }
            player[0] = null
        }
    }
    val toggle = { runCatching { player[0]?.let { if (it.isPlaying) it.pause() else it.start() } }; Unit }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.clip(shape).background(Color.Black)
            .glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = null, role = Role.Button, onClick = toggle)
            .controllerConfirm(onClick = toggle),
    ) {
        AndroidView(
            modifier = Modifier.aspectRatio(ratio),
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            val mp = MediaPlayer()
                            player[0] = mp
                            try {
                                mp.setDataSource(viewContext, Uri.parse(url), mapOf("User-Agent" to "DroidDeck"))
                                mp.setSurface(Surface(texture))
                                mp.isLooping = true
                                mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) ratio = w.toFloat() / h }
                                mp.setOnPreparedListener { it.start(); state = 1 }
                                mp.setOnErrorListener { _, _, _ -> state = 2; true }
                                mp.prepareAsync()
                            } catch (e: Exception) {
                                state = 2
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            runCatching { player[0]?.release() }
                            player[0] = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            },
        )
        if (state == 0) Text(stringResource(R.string.common_working), fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
        if (state == 2) Text(stringResource(R.string.boot_movie_repo_preview_failed), fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(12.dp))
    }
}

/** A dialog with one text box, for a search term or a page number; the Android keyboard comes up with it. */
@Composable
private fun TextEntryDialog(
    title: String, initial: String, number: Boolean, confirm: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit,
) {
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    var text by remember { mutableStateOf(initial) }
    val submit = { onSubmit(text.trim()); close() }
    AppDialog(shown, close, "boot-movie-entry", wide = false, maxWidth = 460.dp) {
        DialogTitleRow(title, close)
        AdbTextField(
            value = text, onValueChange = { text = it }, label = "", keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
            imeAction = ImeAction.Done, placeholder = title, focusOnShow = true, onDone = { submit() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(stringResource(R.string.common_cancel), modifier = Modifier.weight(1f), onClick = close)
            SecondaryButton(confirm, modifier = Modifier.weight(1f)) { submit() }
        }
    }
}

/** Page buttons around the current page, which opens a box to type another page into; first and last page show their numbers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepoPager(page: Int, last: Int?, hasMore: Boolean, onPage: (Int) -> Unit, onJump: () -> Unit) {
    val reach = last ?: if (hasMore) Int.MAX_VALUE else page
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SecondaryButton(stringResource(R.string.boot_movie_repo_previous), enabled = page > 1, compact = true) { onPage(page - 1) }
        if (page - 3 > 1) SecondaryButton("1", compact = true) { onPage(1) }
        for (n in maxOf(1, page - 3) until page) SecondaryButton("$n", compact = true) { onPage(n) }
        TabChip("$page", true, Modifier.widthIn(min = 52.dp).heightIn(min = 44.dp), onJump)
        for (n in page + 1..minOf(page + 3, reach)) SecondaryButton("$n", compact = true) { onPage(n) }
        if (last != null && page + 3 < last) SecondaryButton("$last", compact = true) { onPage(last) }
        SecondaryButton(stringResource(R.string.boot_movie_repo_next), enabled = page < reach, compact = true) { onPage(page + 1) }
    }
}
