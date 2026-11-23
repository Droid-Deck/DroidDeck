package com.droiddeck.launcher.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.runtime.CurseForgeApp
import com.droiddeck.launcher.runtime.UserApps
import com.droiddeck.launcher.runtime.WowAddonCatalog
import com.droiddeck.launcher.runtime.WowAddons
import com.droiddeck.launcher.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private class AddonResults {
    val items = mutableStateListOf<WowAddonCatalog.Addon>()
    var page by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var failed by mutableStateOf<String?>(null)
    var more by mutableStateOf(false)
}

private enum class AddonStatus { INSTALL, UPDATE, INSTALLED }

private enum class AddonSource { WOWI, CURSE }

internal class CurseState(val notice: Int? = null) : Exception()

internal class CurseLink(val app: UserApps.App) {
    class Linked(val inspector: String, val guid: String)

    private val lock = Object()
    @Volatile private var closed = false
    @Volatile private var hold: AtomicBoolean? = null
    @Volatile private var inspector: String? = null
    private val guids = HashMap<String, String>()

    fun <T> call(context: Context, target: WowAddons.Target, launch: Boolean = true, block: (Linked) -> T): T {
        val linked = synchronized(lock) { link(context, target, launch) }
        try {
            return block(linked)
        } catch (e: CurseForgeApp.Failure) {
            if (e.gone) synchronized(lock) {
                if (inspector == linked.inspector) {
                    inspector = null
                    guids.clear()
                    current.compareAndSet(this, null)
                }
            }
            throw e
        }
    }

    private fun link(context: Context, target: WowAddons.Target, launch: Boolean): Linked {
        if (closed) throw CurseState()
        val url = inspector ?: run {
            if (!launch) throw CurseState()
            val hold = AtomicBoolean(false)
            this.hold = hold
            if (closed) throw CurseState()
            val done = CurseForgeApp.start(context, app, hold)
            when (done.state) {
                CurseForgeApp.Start.READY -> {
                    inspector = done.inspector
                    if (!closed) current.set(this)
                    done.inspector ?: throw CurseState()
                }
                CurseForgeApp.Start.BUSY -> throw CurseState(R.string.addons_curse_busy)
                else -> throw CurseState()
            }
        }
        if (closed) throw CurseState()
        guids[target.addOns.path]?.let { return Linked(url, it) }
        val root = WowAddons.root(target)?.let { AddedGames.guestPath(context, it) } ?: throw CurseState()
        val flavor = WowAddons.flavorDir(target)?.let { AddedGames.guestPath(context, it) } ?: throw CurseState()
        val guid = CurseForgeApp.instance(context, url, root, flavor)
        guids[target.addOns.path] = guid
        return Linked(url, guid)
    }

    fun uninstall(context: Context, target: WowAddons.Target, folder: String): Boolean = call(context, target, launch = false) { linked ->
        val project = CurseForgeApp.owner(CurseForgeApp.projects(context, linked.inspector, linked.guid, rescan = true), folder) ?: return@call false
        if (!CurseForgeApp.uninstall(context, linked.inspector, linked.guid, project)) throw CurseState()
        true
    }

    fun close(context: Context) {
        closed = true
        current.compareAndSet(this, null)
        val launch = hold ?: return
        Thread {
            CurseForgeApp.stop(context, app, inspector, launch)
            synchronized(lock) {
                hold = null
                inspector = null
            }
        }.start()
    }

    companion object {
        private val current = AtomicReference<CurseLink?>()

        fun live(): CurseLink? = current.get()
    }
}

private const val PAGES_AHEAD = 8
private const val CURSE_UPDATE = "update"

private fun curseMark(project: CurseForgeApp.Project): String? = CURSE_UPDATE.takeIf { project.update }

@Composable
internal fun AddonBrowserDialog(targets: List<WowAddons.Target>, initial: WowAddons.Target, curseForge: UserApps.App?, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    var target by remember { mutableStateOf(initial) }
    var text by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<WowAddonCatalog.Category?>(null) }
    var sort by remember { mutableStateOf(WowAddonCatalog.Sort.POPULAR) }
    var source by remember { mutableStateOf(AddonSource.WOWI) }
    val curse = if (source == AddonSource.CURSE) curseForge else null
    val link = remember(curseForge?.key) { curseForge?.let(::CurseLink) }
    DisposableEffect(link) {
        onDispose { link?.close(context) }
    }
    var notice by remember { mutableStateOf<Int?>(null) }
    var curseInstalls by remember { mutableIntStateOf(0) }
    val results = remember(text, category, sort, target, curse) { AddonResults() }
    val installing = remember { mutableStateMapOf<String, Int>() }
    val installed = remember { mutableStateMapOf<String, Pair<String, String>>() }
    val categories by produceState(emptyList<WowAddonCatalog.Category>()) {
        value = withContext(Dispatchers.IO) { runCatching { WowAddonCatalog.categories(context) }.getOrDefault(emptyList()) }
    }
    val presentKey = curse to target.addOns
    val snapshot by produceState(presentKey to emptyMap<String, String?>(), target.addOns, WowAddons.imports, curse, curseInstalls, results.items.isEmpty()) {
        val into = target
        val key = curse to into.addOns
        value = key to withContext(Dispatchers.IO) {
            if (curse != null && link != null) runCatching {
                link.call(context, into) { CurseForgeApp.projects(context, it.inspector, it.guid, rescan = true) }.associate { it.id to curseMark(it) }
            }.getOrDefault(emptyMap())
            else WowAddons.details(into).mapNotNull { item -> item.wowiId?.let { it to item.version } }.toMap()
        }
    }
    val present = snapshot.second.takeIf { snapshot.first == presentKey } ?: emptyMap()
    fun status(addon: WowAddonCatalog.Addon): AddonStatus {
        if (addon.id !in present) return AddonStatus.INSTALL
        if (curse != null) return if (present[addon.id] == CURSE_UPDATE) AddonStatus.UPDATE else AddonStatus.INSTALLED
        val have = present[addon.id]
        val latest = installed[installKey(addon, target)]?.takeIf { it.first == addon.summary }?.second ?: addon.summary
        return if (have == null || latest.isBlank() || WowAddonCatalog.sameVersion(have, latest)) AddonStatus.INSTALLED else AddonStatus.UPDATE
    }
    fun load(page: Int) {
        if (results.loading) return
        results.loading = true
        results.failed = null
        val into = target
        val words = text
        val cat = category?.id
        val order = sort
        val via = link?.takeIf { curse != null }
        notice = null
        scope.launch {
            val got = withContext(Dispatchers.IO) {
                runCatching {
                    if (via != null) via.call(context, into) { CurseForgeApp.search(context, it.inspector, it.guid, words, order, page) }
                    else WowAddonCatalog.search(context, WowAddonCatalog.Query(words, cat, WowAddonCatalog.client(into), order), page)
                }
            }
            if (results.items.isEmpty()) notice = (got.exceptionOrNull() as? CurseState)?.notice
            got.onSuccess {
                if (page == 0) results.items.clear()
                results.items.addAll(it.items.filter { addon -> results.items.none { seen -> seen.id == addon.id } })
                results.page = page
                results.more = it.more
            }.onFailure { results.failed = it.message ?: it.javaClass.simpleName }
            results.loading = false
        }
    }
    LaunchedEffect(results) {
        if (text.isNotEmpty()) delay(350)
        load(0)
    }
    fun install(addon: WowAddonCatalog.Addon) {
        val key = installKey(addon, target)
        if (key in installing) return
        val via = link?.takeIf { curse != null }
        if (via != null) {
            val into = target
            val updating = status(addon) == AddonStatus.UPDATE
            installing[key] = 0
            scope.launch {
                val done = withContext(Dispatchers.IO) {
                    runCatching {
                        via.call(context, into) {
                            if (updating) CurseForgeApp.update(context, it.inspector, it.guid, addon.id)
                            else CurseForgeApp.install(context, it.inspector, it.guid, addon.id)
                        }
                    }
                }
                installing.remove(key)
                val ok = done.getOrNull() == true
                WowAddons.changed()
                curseInstalls++
                val message = when {
                    ok && updating -> context.getString(R.string.user_apps_updated, addon.name)
                    ok -> context.resources.getQuantityString(R.plurals.main_addons_imported, 1, 1, into.flavor)
                    else -> (done.exceptionOrNull() as? CurseState)?.notice?.let(context::getString)
                        ?: context.getString(R.string.stores_dl_failed_reason, done.exceptionOrNull()?.message ?: addon.name)
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (SessionState.running) {
            Toast.makeText(context, R.string.main_addons_close_session, Toast.LENGTH_LONG).show()
            return
        }
        val into = target
        installing[key] = 0
        scope.launch {
            val message = withContext(Dispatchers.IO) { installAddon(context, addon, into) { pct -> scope.launch { if (key in installing) installing[key] = pct } } }
            installing.remove(key)
            message.second?.let { installed[key] = addon.summary to it }
            Toast.makeText(context, message.first, Toast.LENGTH_LONG).show()
        }
    }
    val sourceName = stringResource(if (curse != null) R.string.addons_source_curse else R.string.addons_source_wowi)
    val grid = rememberLazyGridState()
    LaunchedEffect(results, grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index to grid.layoutInfo.totalItemsCount }.collect { (last, total) ->
            if (last != null && results.more && results.failed == null && last >= total - PAGES_AHEAD) load(results.page + 1)
        }
    }
    val firstAction = remember(results) { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(results, results.items.isNotEmpty()) {
        if (results.items.isNotEmpty() && !searching && inputMode.inputMode == InputMode.Keyboard) focusSoon { firstAction }
    }
    AppDialog(shown, close, "addonBrowser", wide = true, maxWidth = 980.dp, scrollable = false, fillHeight = true, modifier = Modifier.releaseTextFieldOnTap()) {
        val sortChip: @Composable () -> Unit = {
            BrowseDropdown(sortLabel(sort)) { first, done ->
                WowAddonCatalog.Sort.entries.forEachIndexed { index, option ->
                    MenuItem(sortLabel(option), sort == option, focusRequester = if (index == 0) first else null) { sort = option; done() }
                }
            }
        }
        val sourceChip: @Composable () -> Unit = {
            if (curseForge != null) BrowseDropdown(sourceName) { first, done ->
                MenuItem(stringResource(R.string.addons_source_wowi), source == AddonSource.WOWI, focusRequester = first) { source = AddonSource.WOWI; done() }
                MenuItem(stringResource(R.string.addons_source_curse), source == AddonSource.CURSE) { source = AddonSource.CURSE; done() }
            }
        }
        val categoryChip: @Composable () -> Unit = {
            if (curse == null && categories.isNotEmpty()) BrowseDropdown(category?.name ?: stringResource(R.string.addons_category_all)) { first, done ->
                MenuItem(stringResource(R.string.addons_category_all), category == null, focusRequester = first) { category = null; done() }
                categories.forEach { option -> MenuItem(option.name, category?.id == option.id) { category = option; done() } }
            }
        }
        val flavorChip: @Composable () -> Unit = {
            if (targets.size > 1) BrowseDropdown(target.flavor) { first, done ->
                targets.forEachIndexed { index, option ->
                    MenuItem(option.flavor, option.addOns == target.addOns, focusRequester = if (index == 0) first else null) { target = option; done() }
                }
            }
        }
        val search: @Composable (Modifier) -> Unit = { modifier ->
            AdbTextField(
                value = text, onValueChange = { text = it }, label = "", keyboardType = KeyboardType.Text, imeAction = ImeAction.Search,
                placeholder = stringResource(R.string.stores_search_catalog, sourceName), compact = true, showLabel = false, modifier = modifier, onFocusChange = { searching = it },
            )
        }
        val closeButton: @Composable () -> Unit = {
            SmallIconButton(Icons.Filled.Close, stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.error, onClick = close)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 640.dp) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                search(Modifier.weight(1f))
                sourceChip()
                sortChip()
                categoryChip()
                flavorChip()
                closeButton()
            } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    search(Modifier.weight(1f))
                    closeButton()
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    sourceChip()
                    sortChip()
                    categoryChip()
                    flavorChip()
                }
            }
        }
        val thumb = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        LazyVerticalGrid(
            GridCells.Adaptive(400.dp), Modifier.fillMaxWidth().weight(1f).scrollThumb(grid, thumb), state = grid,
            contentPadding = PaddingValues(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(results.items, key = { it.id }) { addon ->
                val key = installKey(addon, target)
                AddonRow(addon, status(addon), installing[key], if (addon.id == results.items.firstOrNull()?.id) Modifier.focusRequester(firstAction) else Modifier) { install(addon) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        results.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        results.failed != null && notice != null -> {
                            Text(stringResource(notice!!), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            BrowseChip(stringResource(R.string.stores_dl_retry), false) { load(0) }
                        }
                        results.failed != null -> {
                            Text(stringResource(R.string.addons_unreachable, sourceName), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            BrowseChip(stringResource(R.string.stores_dl_retry), false) { load(if (results.items.isEmpty()) 0 else results.page + 1) }
                        }
                        results.items.isEmpty() -> Text(stringResource(R.string.addons_none_found), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonRow(addon: WowAddonCatalog.Addon, status: AddonStatus, progress: Int?, action: Modifier, onInstall: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().clip(Shape12).background(colors.surface.copy(alpha = 0.6f)).border(1.dp, pal.line, Shape12)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(Modifier.size(44.dp).clip(Shape12).background(artBrush(hueOf(addon.name)))) {
            if (addon.thumb != null) AsyncImage(model = addon.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        Column(Modifier.weight(1f)) {
            Text(addon.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(addon.author, addon.summary, formatCount(addon.downloads)).filter { it.isNotEmpty() }.joinToString(" · "),
                fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val size = action
        when {
            progress != null -> SecondaryButton(stringResource(R.string.store_installing), compact = true, modifier = size, progress = progress / 100f, onClick = onInstall)
            status == AddonStatus.INSTALLED -> SecondaryButton(stringResource(R.string.stores_installed_chip), enabled = false, compact = true, modifier = size) {}
            else -> PrimaryButton(stringResource(if (status == AddonStatus.UPDATE) R.string.common_update else R.string.store_install), compact = true, modifier = size, onClick = onInstall)
        }
    }
}

private fun Modifier.scrollThumb(state: LazyGridState, color: Color): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty() || visible.size >= info.totalItemsCount) return@drawWithContent
    val total = info.totalItemsCount.toFloat()
    val length = (size.height * visible.size / total).coerceAtLeast(24.dp.toPx())
    val top = (size.height - length) * (visible.first().index / (total - visible.size).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val width = 3.dp.toPx()
    drawRoundRect(color, Offset(size.width - width, top), Size(width, length), CornerRadius(width / 2))
}

@Composable
private fun sortLabel(sort: WowAddonCatalog.Sort): String = stringResource(
    when (sort) {
        WowAddonCatalog.Sort.POPULAR -> R.string.stores_itch_sort_popular
        WowAddonCatalog.Sort.UPDATED -> R.string.stores_itch_sort_new
        WowAddonCatalog.Sort.NAME -> R.string.addons_sort_name
        WowAddonCatalog.Sort.DOWNLOADS -> R.string.addons_sort_downloads
    },
)

private fun formatCount(count: Long): String = when {
    count <= 0 -> ""
    count >= 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format(java.util.Locale.getDefault(), "%.1fK", count / 1_000.0)
    else -> count.toString()
}

internal fun installAddon(context: Context, addon: WowAddonCatalog.Addon, target: WowAddons.Target, progress: (Int) -> Unit): Pair<String, String?> {
    val zip = File(File(context.cacheDir, "wowaddons"), "download-wowi-${addon.id.filter(Char::isLetterOrDigit)}-${java.util.UUID.randomUUID()}.zip")
    return try {
        val version = WowAddonCatalog.download(context, addon, zip, progress) ?: addon.summary
        if (SessionState.running) return context.getString(R.string.main_addons_close_session) to null
        val names = WowAddons.import(zip, target, WowAddons.Origin(addon.id, version).takeIf { version.isNotBlank() }, context)
        if (names.isEmpty()) context.getString(R.string.main_addons_none, addon.name) to null
        else context.resources.getQuantityString(R.plurals.main_addons_imported, names.size, names.size, target.flavor) to version
    } catch (_: WowAddons.TooLarge) {
        context.getString(R.string.main_addons_too_large, addon.name) to null
    } catch (e: Exception) {
        context.getString(R.string.stores_dl_failed_reason, e.message ?: e.javaClass.simpleName) to null
    } finally {
        zip.delete()
    }
}

internal fun deleteAddon(context: Context, target: WowAddons.Target, folder: String, link: CurseLink? = CurseLink.live()) {
    val managed = link?.let { runCatching { it.uninstall(context, target, folder) }.getOrDefault(false) } == true
    if (!managed) WowAddons.delete(target, folder) else WowAddons.changed()
}

private fun installKey(addon: WowAddonCatalog.Addon, target: WowAddons.Target) = addon.id + "|" + target.addOns.path
