package com.droiddeck.launcher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.WowAddonCatalog
import com.droiddeck.launcher.runtime.WowAddons
import com.droiddeck.launcher.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class AddonResults {
    val items = mutableStateListOf<WowAddonCatalog.Addon>()
    var page by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var failed by mutableStateOf<String?>(null)
    var more by mutableStateOf(false)
}

@Composable
internal fun AddonBrowserDialog(targets: List<WowAddons.Target>, initial: WowAddons.Target, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    var target by remember { mutableStateOf(initial) }
    var text by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<WowAddonCatalog.Category?>(null) }
    var sort by remember { mutableStateOf(WowAddonCatalog.Sort.POPULAR) }
    val results = remember(text, category, sort, target) { AddonResults() }
    val installing = remember { mutableStateMapOf<String, Int>() }
    val installed = remember { mutableStateMapOf<String, Boolean>() }
    val categories by produceState(emptyList<WowAddonCatalog.Category>()) {
        value = withContext(Dispatchers.IO) { runCatching { WowAddonCatalog.categories(context) }.getOrDefault(emptyList()) }
    }
    fun load(page: Int) {
        if (results.loading) return
        results.loading = true
        results.failed = null
        val into = target
        val words = text
        val cat = category?.id
        val order = sort
        scope.launch {
            val got = withContext(Dispatchers.IO) {
                runCatching { WowAddonCatalog.search(context, WowAddonCatalog.Query(words, cat, WowAddonCatalog.client(into), order), page) }
            }
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
        if (SessionState.running) {
            Toast.makeText(context, R.string.main_addons_close_session, Toast.LENGTH_LONG).show()
            return
        }
        val into = target
        installing[key] = 0
        scope.launch {
            val message = withContext(Dispatchers.IO) { installAddon(context, addon, into) { pct -> scope.launch { if (key in installing) installing[key] = pct } } }
            installing.remove(key)
            if (message.second) installed[key] = true
            Toast.makeText(context, message.first, Toast.LENGTH_LONG).show()
        }
    }
    val sourceName = stringResource(R.string.addons_source_wowi)
    AppDialog(shown, close, "addonBrowser", wide = true, scrollable = false) {
        DialogTitleRow(stringResource(R.string.addons_download), close)
        ModeHeader(sourceName, text, { text = it }, stringResource(R.string.stores_search_catalog, sourceName)) {
            BrowseChip(stringResource(R.string.addons_source_curse), false) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WowAddonCatalog.curseForgeUrl(text))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        ChipRow {
            BrowseDropdown(sortLabel(sort)) { first, done ->
                WowAddonCatalog.Sort.entries.forEachIndexed { index, option ->
                    MenuItem(sortLabel(option), sort == option, focusRequester = if (index == 0) first else null) { sort = option; done() }
                }
            }
            if (categories.isNotEmpty()) BrowseDropdown(category?.name ?: stringResource(R.string.addons_category_all)) { first, done ->
                MenuItem(stringResource(R.string.addons_category_all), category == null, focusRequester = first) { category = null; done() }
                categories.forEach { option -> MenuItem(option.name, category?.id == option.id) { category = option; done() } }
            }
            if (targets.size > 1) BrowseDropdown(target.flavor) { first, done ->
                targets.forEachIndexed { index, option ->
                    MenuItem(option.flavor, option.addOns == target.addOns, focusRequester = if (index == 0) first else null) { target = option; done() }
                }
            }
        }
        val height = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
        LazyVerticalGrid(
            GridCells.Adaptive(libraryTileWidth() + 40.dp), Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(results.items, key = { it.id }) { addon ->
                AddonCard(addon, installing[installKey(addon, target)], installed[installKey(addon, target)] == true) { install(addon) }
            }
            item(key = "tail", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    when {
                        results.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        results.failed != null -> BrowseChip(stringResource(R.string.stores_dl_retry), false) { load(if (results.items.isEmpty()) 0 else results.page + 1) }
                        results.more -> BrowseChip(stringResource(R.string.stores_itch_load_more), false) { load(results.page + 1) }
                        results.items.isEmpty() -> Text(stringResource(R.string.addons_none_found), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonCard(addon: WowAddonCatalog.Addon, progress: Int?, done: Boolean, onInstall: () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onInstall)
            .controllerConfirm(onClick = onInstall),
    ) {
        ArtCard(addon.name, addon.thumb, null, null, hot, capsule = false, chip = progress?.let { "$it%" }) {
            if (done) Icon(Icons.Filled.Check, stringResource(R.string.stores_installed_chip), tint = pal.good, modifier = Modifier.height(11.dp))
            else Text(stringResource(if (progress != null) R.string.store_installing else R.string.store_install), fontSize = 10.sp, color = pal.signal, maxLines = 1)
        }
        Text(
            listOf(addon.author, formatCount(addon.downloads)).filter { it.isNotEmpty() }.joinToString(" · "),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
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

private fun installAddon(context: Context, addon: WowAddonCatalog.Addon, target: WowAddons.Target, progress: (Int) -> Unit): Pair<String, Boolean> {
    val zip = File(File(context.cacheDir, "wowaddons"), "download-wowi-${addon.id.filter(Char::isLetterOrDigit)}-${java.util.UUID.randomUUID()}.zip")
    return try {
        WowAddonCatalog.download(context, addon, zip, progress)
        if (SessionState.running) return context.getString(R.string.main_addons_close_session) to false
        val names = WowAddons.import(zip, target)
        if (names.isEmpty()) context.getString(R.string.main_addons_none, addon.name) to false
        else context.resources.getQuantityString(R.plurals.main_addons_imported, names.size, names.size, target.flavor) to true
    } catch (_: WowAddons.TooLarge) {
        context.getString(R.string.main_addons_too_large, addon.name) to false
    } catch (e: Exception) {
        context.getString(R.string.stores_dl_failed_reason, e.message ?: e.javaClass.simpleName) to false
    } finally {
        zip.delete()
    }
}

private fun installKey(addon: WowAddonCatalog.Addon, target: WowAddons.Target) = addon.id + "|" + target.addOns.path
