package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R

@Composable
internal fun SettingsSearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        AdbTextField(
            value = query, onValueChange = onQuery, label = "", keyboardType = KeyboardType.Text, imeAction = ImeAction.Search,
            placeholder = stringResource(R.string.settings_search), compact = true, showLabel = false, focusOnShow = true,
            modifier = Modifier.weight(1f),
        )
        SmallIconButton(Icons.Filled.Close, stringResource(R.string.settings_search_clear), tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = onClose)
    }
}

@Composable
internal fun SettingsSearchResults(query: String, onOpen: (SettingsSection) -> Unit) {
    val ctx = LocalContext.current
    val found = remember(query) { SettingsIndex.search(ctx, query) }
    if (found.isEmpty()) {
        Note(stringResource(R.string.settings_search_none, query.trim()))
        return
    }
    Column {
        found.groupBy { it.place }.forEach { (place, entries) ->
            SettingsGroup(stringResource(place.title)) {
                entries.forEach { entry ->
                    val within = entry.within?.let { stringResource(it) }
                    val title = stringResource(entry.title)
                    val section = entry.section
                    val tab = entry.tab
                    if (section != null) {
                        ActionRow(title, within, stringResource(R.string.store_open), onClick = { onOpen(section) })
                    } else if (tab != null) {
                        ActionRow(title, within, stringResource(R.string.store_open), onClick = {
                            if (tab == NavTabs.MORE) MoreTabs.editing = true
                            if (tab == "downloads") DownloadsNav.showSettings = true
                            RailRequest.key = tab
                        })
                    } else {
                        SettingsRow(title, entry.path(ctx).dropLast(1).joinToString(" › ")) {}
                    }
                }
            }
        }
    }
}
