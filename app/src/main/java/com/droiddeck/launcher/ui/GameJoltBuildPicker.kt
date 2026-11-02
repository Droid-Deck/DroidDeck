package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.gamejolt.GameJoltBuild
import com.droiddeck.launcher.stores.gamejolt.GameJoltBuilds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CHOOSE = "choose"

@Composable
internal fun GameJoltVersionButton(installed: InstalledStoreGame, enabled: Boolean) {
    SecondaryButton(stringResource(R.string.stores_itch_pick_title), enabled = enabled) {
        val sidecar = installed.sidecar
        val paid = sidecar.extra["paid"] == "1"
        val owned = StoresState.library[Store.GAMEJOLT].orEmpty().any { it.id == sidecar.id && it.owned }
        StoresState.gameJoltPicking = CatalogItem(
            store = Store.GAMEJOLT, id = sidecar.id, title = sidecar.title, imageUrl = sidecar.cover, isFree = !paid, owned = owned,
            extra = mapOf("gameId" to (sidecar.extra["gameId"] ?: sidecar.id), CHOOSE to "1"),
        )
    }
}

@Composable
internal fun GameJoltBuildPicker(item: CatalogItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val colors = MaterialTheme.colorScheme
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    var loaded by remember(item) { mutableStateOf<List<GameJoltBuild>?>(null) }
    var failed by remember(item) { mutableStateOf(false) }

    val install: (GameJoltBuild) -> Unit = install@{ build ->
        if (!shown.targetState) return@install
        val tagged = item.copy(
            extra = item.extra + mapOf("buildId" to build.id),
            sizeBytes = build.size.takeIf { it > 0 } ?: item.sizeBytes,
        )
        close()
        StoresState.requestInstall(ctx, tagged)
    }

    LaunchedEffect(item) {
        val gameId = item.extra["gameId"].orEmpty().ifEmpty { item.id }
        val result = withContext(Dispatchers.IO) { GameJoltBuilds.load(app, gameId) }
        if (result == null) { failed = true; return@LaunchedEffect }
        loaded = result
        if ((item.isFree || item.owned) && item.extra[CHOOSE] != "1" && shown.targetState) result.singleOrNull()?.takeIf { it.installable }?.let { install(it) }
    }

    AppDialog(shown, close, "gameJoltBuilds", wide = false, maxWidth = 460.dp) {
        DialogTitleRow(stringResource(R.string.stores_itch_pick_title), close)
        Text(item.title, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val list = loaded
        when {
            failed -> Text(stringResource(R.string.stores_gamejolt_pick_failed), fontSize = 13.sp, color = colors.onSurfaceVariant)
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            list.isEmpty() -> Text(stringResource(R.string.stores_itch_pick_none), fontSize = 13.sp, color = colors.onSurfaceVariant)
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (build in list) key(build.id) { BuildRow(item, build) { install(build) } }
            }
        }
    }
}

@Composable
private fun BuildRow(item: CatalogItem, build: GameJoltBuild, onInstall: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val linux = "linux" in build.platforms && !build.installable
    val canInstall = build.installable && (item.isFree || item.owned)
    val names = build.platforms.map { platformName(it) }
    val detail = listOfNotNull(
        names.joinToString(", "),
        build.size.takeIf { it > 0 }?.let { formatBytes(it) },
        build.version.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    ListRow(enabled = !linux, onClick = { if (canInstall) onInstall() else openStoreUrl(ctx, item) }) {
        Column(Modifier.weight(1f)) {
            Text(build.filename.ifBlank { build.packageTitle.ifBlank { item.title } }, fontSize = 13.sp, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val label = when {
            canInstall -> stringResource(R.string.stores_install)
            linux -> stringResource(R.string.stores_itch_build_linux_soon)
            else -> stringResource(R.string.stores_gamejolt_build_open)
        }
        Text(label, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun platformName(platform: String): String = stringResource(
    when (platform) {
        "windows" -> R.string.stores_itch_build_windows
        "linux" -> R.string.stores_itch_build_linux
        "osx" -> R.string.stores_itch_build_macos
        "other" -> R.string.stores_gamejolt_os_other
        else -> R.string.stores_itch_build_web
    },
)
