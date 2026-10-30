package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

@Composable
internal fun SteamImportDialog(games: List<String>, onAnswer: (Boolean) -> Unit) {
    val now = remember { booleanArrayOf(false) }
    val shown = rememberShown { onAnswer(now[0]) }
    val close = { shown.targetState = false }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val names = if (games.size <= 3) games.joinToString(", ")
        else stringResource(R.string.steam_import_names_more, games.take(3).joinToString(", "), games.size - 3)
    AppDialog(shown, close, "steamImport", wide = false, maxWidth = 520.dp) {
        DialogTitleRow(stringResource(R.string.steam_import_title), close) {
            Icon(Icons.Filled.RestartAlt, null, tint = LocalPalette.current.signal, modifier = Modifier.size(20.dp))
        }
        Text(
            stringResource(R.string.steam_import_body, names),
            fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.End), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(stringResource(R.string.steam_import_later)) { now[0] = false; close() }
            PrimaryButton(stringResource(R.string.steam_import_now), modifier = Modifier.focusRequester(focus), onClick = { now[0] = true; close() })
        }
    }
}
