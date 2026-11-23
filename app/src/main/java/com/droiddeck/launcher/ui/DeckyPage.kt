package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.DeckyManager
import com.droiddeck.launcher.runtime.DeckyStore

private enum class DeckyTab { INSTALLED, STORE, ZIP }

private const val STORE_PAGE = 30

internal fun deckyStoreMatches(plugins: List<DeckyStore.Plugin>, query: String): List<DeckyStore.Plugin> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return plugins.filter { p ->
        val haystack = (p.name + " " + p.author + " " + p.description + " " + p.tags.joinToString(" ")).lowercase()
        words.all { it in haystack }
    }.sortedByDescending { it.downloads }
}

@Composable
internal fun DeckyGroups(s: ModeSettings, a: ModeSettingsActions, host: MenuHost) {
    val idle = s.deckyStage == null && !s.deckySessionRunning
    SettingsGroup(stringResource(R.string.mode_decky_loader)) {
        val updateAvailable = s.deckyInstalled != null && s.deckyLatestRelease != null &&
            s.deckyInstalled != s.deckyLatestRelease.tag
        val status = when {
            s.deckyStage != null -> s.deckyStage
            s.deckyChecking -> stringResource(R.string.mode_decky_checking)
            s.deckyInstalled == null && s.deckyLatestRelease != null -> stringResource(R.string.mode_decky_ready, s.deckyLatestRelease.tag)
            s.deckyInstalled == null -> stringResource(R.string.mode_decky_none)
            s.deckyLatestRelease == null -> stringResource(R.string.mode_decky_installed, s.deckyInstalled)
            updateAvailable -> stringResource(R.string.mode_decky_update, s.deckyLatestRelease.tag)
            else -> stringResource(R.string.mode_decky_current, s.deckyInstalled)
        }
        SettingsRow(stringResource(R.string.decky_status), status) {
            val action = when {
                s.deckyStage != null -> stringResource(R.string.common_working)
                s.deckyChecking -> stringResource(R.string.common_checking)
                s.deckyInstalled == null && s.deckyLatestRelease != null -> stringResource(R.string.mode_decky_install_latest)
                s.deckyInstalled != null && updateAvailable -> stringResource(R.string.common_update)
                else -> stringResource(R.string.common_check)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(action, enabled = s.deckyStage == null && !s.deckyChecking && !s.deckySessionRunning) {
                    if (s.deckyLatestRelease == null || (s.deckyInstalled != null && !updateAvailable)) a.onDeckyCheck()
                    else s.deckyLatestRelease?.let(a.onDeckyInstall)
                }
                if (s.deckyInstalled != null) DeckyUninstall(enabled = idle, onUninstall = a.onDeckyUninstall)
            }
        }
        if (s.deckyInstalled != null) ToggleRow(
            host, "decky-enabled", stringResource(R.string.decky_start_with_steam),
            if (s.deckyEnabled) stringResource(R.string.mode_decky_on) else stringResource(R.string.mode_decky_off),
            s.deckyEnabled,
            enabled = idle,
            onChange = a.onDeckyEnabled,
        )
        ChoiceRow(
            host, "decky-channel", stringResource(R.string.decky_release_channel),
            stringResource(R.string.decky_release_channel_hint),
            listOf(
                DeckyManager.BRANCH_STABLE to stringResource(R.string.decky_channel_stable),
                DeckyManager.BRANCH_PRERELEASE to stringResource(R.string.decky_channel_prerelease),
            ) + if (s.deckyBranch == DeckyManager.BRANCH_TESTING) listOf(
                DeckyManager.BRANCH_TESTING to stringResource(R.string.decky_channel_testing),
            ) else emptyList(),
            s.deckyBranch,
            enabled = idle,
            onPick = a.onDeckyBranch,
        )
        if (s.deckyStage != null && s.deckyPercent >= 0) {
            LinearProgressIndicator(
                progress = { s.deckyPercent / 100f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
    if (s.deckyInstalled == null) return
    var tab by rememberSaveable { mutableStateOf(DeckyTab.INSTALLED) }
    SettingsGroup(stringResource(R.string.mode_decky_plugins)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
            TabChip(stringResource(R.string.decky_tab_installed, s.deckyPlugins.size), tab == DeckyTab.INSTALLED, Modifier.weight(1f)) { tab = DeckyTab.INSTALLED }
            TabChip(stringResource(R.string.decky_tab_store), tab == DeckyTab.STORE, Modifier.weight(1f)) { tab = DeckyTab.STORE }
            TabChip(stringResource(R.string.decky_tab_zip), tab == DeckyTab.ZIP, Modifier.weight(1f)) { tab = DeckyTab.ZIP }
        }
        LaunchedEffect(tab) { if (tab != DeckyTab.ZIP && s.deckyStore == null && !s.deckyStoreFailed) a.onDeckyStoreRefresh() }
        when (tab) {
            DeckyTab.INSTALLED -> InstalledPlugins(s, a, idle)
            DeckyTab.STORE -> PluginStore(s, a, idle)
            DeckyTab.ZIP -> SettingsRow(
                stringResource(R.string.decky_tab_zip),
                stringResource(R.string.mode_decky_plugins_hint),
            ) {
                SecondaryButton(
                    if (s.deckyStage != null && s.deckyDownloadingBinary) stringResource(R.string.mode_decky_plugin_downloading) else stringResource(R.string.mode_decky_plugin_install_zip),
                    enabled = idle,
                    modifier = Modifier.testTag("decky-install-zip"),
                    onClick = a.onPickDeckyPluginZip,
                )
            }
        }
    }
}

@Composable
private fun InstalledPlugins(s: ModeSettings, a: ModeSettingsActions, idle: Boolean) {
    var removing by remember { mutableStateOf<DeckyStore.Installed?>(null) }
    if (s.deckyPlugins.isEmpty()) {
        Text(stringResource(R.string.decky_none_installed), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
    }
    for (plugin in s.deckyPlugins) {
        val listed = s.deckyStore?.firstOrNull { it.name == plugin.name }
        val update = listed?.takeIf { DeckyStore.updateAvailable(plugin, it) }
        PluginRow(
            plugin.name,
            listOf(plugin.version, plugin.author).filter { it.isNotBlank() }.joinToString(" · ") +
                (update?.latest?.let { " · " + stringResource(R.string.decky_update_to, it.name) } ?: ""),
            listed?.imageUrl.orEmpty(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (update != null) SecondaryButton(stringResource(R.string.common_update), enabled = idle, compact = true) { a.onDeckyStoreInstall(update) }
                SecondaryButton(stringResource(R.string.decky_remove), enabled = idle, compact = true, modifier = Modifier.testTag("decky-remove-${plugin.folder}")) { removing = plugin }
            }
        }
    }
    if (s.deckyPlugins.isNotEmpty()) Text(stringResource(R.string.decky_changes_next_session), fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    removing?.let { plugin ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.decky_remove_confirm, plugin.name)) },
            text = { Text(stringResource(R.string.decky_remove_confirm_body)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { removing = null; a.onDeckyRemove(plugin) }) { Text(stringResource(R.string.decky_remove)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { removing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun PluginStore(s: ModeSettings, a: ModeSettingsActions, idle: Boolean) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var shown by rememberSaveable { mutableIntStateOf(STORE_PAGE) }
    val plugins = s.deckyStore
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                ListRow(tall = true, onClick = { searching = true }) {
                    androidx.compose.material3.Icon(Icons.Filled.Search, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(
                        query.ifEmpty { stringResource(R.string.decky_store_search) }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (query.isNotEmpty()) SecondaryButton(stringResource(R.string.boot_movie_repo_clear), compact = true) { query = ""; shown = STORE_PAGE }
        }
        when {
            plugins == null -> Text(stringResource(R.string.common_working), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            s.deckyStoreFailed -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.decky_store_failed), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.boot_movie_repo_retry), compact = true) { a.onDeckyStoreRefresh() }
            }
            else -> {
                val matches = remember(plugins, query) { deckyStoreMatches(plugins, query) }
                if (matches.isEmpty()) Text(stringResource(R.string.decky_store_empty), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                for (plugin in matches.take(shown)) {
                    val have = s.deckyPlugins.firstOrNull { it.name == plugin.name }
                    val current = have != null && !DeckyStore.updateAvailable(have, plugin)
                    PluginRow(
                        plugin.name,
                        listOf(plugin.latest?.name.orEmpty(), plugin.author, plugin.description).filter { it.isNotBlank() }.joinToString(" · "),
                        plugin.imageUrl,
                    ) {
                        SecondaryButton(
                            stringResource(when { current -> R.string.setup_installed; have != null -> R.string.common_update; else -> R.string.boot_movie_repo_install }),
                            enabled = idle && !current, compact = true,
                            modifier = Modifier.testTag("decky-store-${plugin.name}"),
                        ) { a.onDeckyStoreInstall(plugin) }
                    }
                }
                if (matches.size > shown) SecondaryButton(stringResource(R.string.decky_store_more, matches.size - shown), compact = true) { shown += STORE_PAGE }
            }
        }
    }
    if (searching) TextEntryDialog(
        stringResource(R.string.decky_store_search), query, number = false, confirm = stringResource(R.string.stores_search_open),
        onSubmit = { query = it; shown = STORE_PAGE; searching = false }, onDismiss = { searching = false }, id = "decky-store-entry",
    )
}

@Composable
private fun PluginRow(name: String, detail: String, imageUrl: String, actions: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            val shape = RoundedCornerShape(8.dp)
            Box(Modifier.width(96.dp).aspectRatio(16f / 9f).clip(shape).background(Color(0xFF0E1218))) {
                if (imageUrl.isNotEmpty()) AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { actions() }
    }
}
