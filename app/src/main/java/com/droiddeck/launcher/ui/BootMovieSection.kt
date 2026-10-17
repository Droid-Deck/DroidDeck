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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.BootMovie
import com.droiddeck.launcher.session.BootMovieLibrary
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SteamDeckRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class MovieTab { INSTALLED, IMPORT, REPO, SOUND }

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
private fun sourceLabel(source: String): String = stringResource(
    when (source) {
        BootMovieLibrary.SOURCE_DROIDDECK -> R.string.boot_movie_source_droiddeck
        BootMovieLibrary.SOURCE_REPO -> R.string.boot_movie_source_repo
        BootMovieLibrary.SOURCE_IMPORT -> R.string.boot_movie_source_import
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
    val selected = picked ?: snapshot?.selected
    LaunchedEffect(snapshot?.selected) { picked = null }
    AppDialog(shown, close, "boot-movies", wide = false) {
        DialogTitleRow(stringResource(R.string.boot_movie_installed), close)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            items(snapshot?.entries.orEmpty(), key = { it.id }) { entry ->
                ListRow(accent = entry.id == selected, tall = true, onClick = { picked = entry.id; a.onBootMovie(entry.id) }) {
                    MovieCover(entry, Modifier.size(width = 96.dp, height = 54.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(sourceLabel(entry.source), fontSize = 12.sp, color = if (entry.id == selected) LocalPalette.current.signal else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (entry.removable) SmallIconButton(Icons.Filled.Close, stringResource(R.string.boot_movie_remove), MaterialTheme.colorScheme.error) {
                        scope.launch {
                            withContext(Dispatchers.IO) { BootMovieLibrary.remove(app, entry.id) }
                            a.onBootMovieChanged()
                            refresh++
                        }
                    }
                }
            }
        }
    }
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
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var sort by rememberSaveable { mutableStateOf(SteamDeckRepo.Sort.TRENDING) }
    var device by rememberSaveable { mutableStateOf<String?>(null) }
    var typed by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf(setOf<String>()) }
    val progress = remember { mutableStateMapOf<String, Int>() }
    LaunchedEffect(typed) { delay(400); if (query != typed) { query = typed; page = 1 } }
    LaunchedEffect(progress.size) { installed = withContext(Dispatchers.IO) { BootMovieLibrary.installedRepoIds(app) } }
    val result by androidx.compose.runtime.produceState<SteamDeckRepo.Page?>(null, sort, device, query, page, retry) {
        value = null
        value = withContext(Dispatchers.IO) { SteamDeckRepo.fetch(sort, device, query, page) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 12.dp)) {
        androidx.compose.material3.OutlinedTextField(
            typed, { typed = it }, singleLine = true, placeholder = { Text(stringResource(R.string.boot_movie_repo_search)) },
            modifier = Modifier.fillMaxWidth(),
        )
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
                            RepoCard(post, installed = post.id in installed, percent = progress[post.id], modifier = Modifier.weight(1f),
                                onInstall = {
                                    if (post.id !in progress) scope.launch {
                                        progress[post.id] = 0
                                        val outcome = withContext(Dispatchers.IO) { install(app, post) { progress[post.id] = it } }
                                        progress.remove(post.id)
                                        Toast.makeText(app, outcome ?: app.getString(R.string.boot_movie_repo_installed, post.title), Toast.LENGTH_LONG).show()
                                        a.onBootMovieChanged()
                                    }
                                },
                                onUse = {
                                    scope.launch {
                                        val name = withContext(Dispatchers.IO) { BootMovieLibrary.nameForRepo(app, post.id) }
                                        if (name != null) a.onBootMovie(BootMovieLibrary.idOf(name))
                                    }
                                })
                        }
                        if (row.size == 1) Box(Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    SecondaryButton(stringResource(R.string.boot_movie_repo_previous), enabled = page > 1, compact = true) { page-- }
                    Text(stringResource(R.string.boot_movie_repo_page, page), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.boot_movie_repo_next), enabled = r.posts.size >= SteamDeckRepo.PAGE_SIZE, compact = true) { page++ }
                }
            }
        }
    }
}

/** Downloads [post] and puts it among the installed movies; returns a message when it failed, null when it worked. */
private fun install(app: android.content.Context, post: SteamDeckRepo.Post, onProgress: (Int) -> Unit): String? {
    val folder = File(app.cacheDir, "boot-movie-download").apply { mkdirs() }
    val file = File(folder, "${post.id.filter { it.isLetterOrDigit() }.ifEmpty { "movie" }}.webm")
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
private fun RepoCard(post: SteamDeckRepo.Post, installed: Boolean, percent: Int?, modifier: Modifier, onInstall: () -> Unit, onUse: () -> Unit) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier.clip(shape).background(colors.surface.copy(alpha = 0.6f)).border(1.dp, pal.line, shape).padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(7.dp)).background(Color(0xFF0E1218))) {
            if (post.thumbnail.isNotEmpty()) AsyncImage(model = post.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(post.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(post.author, "♥ ${post.likes}", "⇩ ${post.downloads}", if (post.seconds > 0) stringResource(R.string.boot_movie_seconds, post.seconds) else "")
                .filter { it.isNotEmpty() }.joinToString(" · "),
            fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (installed) SecondaryButton(stringResource(R.string.boot_movie_repo_use), compact = true, modifier = Modifier.fillMaxWidth(), onClick = onUse)
        else SecondaryButton(
            if (percent != null) stringResource(R.string.boot_movie_converting, percent) else stringResource(R.string.boot_movie_repo_install),
            enabled = percent == null, compact = true, modifier = Modifier.fillMaxWidth(), onClick = onInstall,
        )
    }
}
