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
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
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
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.itch.ItchApi
import com.droiddeck.launcher.stores.itch.ItchAuth
import com.droiddeck.launcher.stores.itch.ItchBuild
import com.droiddeck.launcher.stores.itch.ItchGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class BuildList(val gameId: String, val builds: List<ItchBuild>)

@Composable
internal fun ItchBuildPicker(item: CatalogItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val colors = MaterialTheme.colorScheme
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    var loaded by remember(item) { mutableStateOf<BuildList?>(null) }
    var failed by remember(item) { mutableStateOf(false) }

    val install: (BuildList, ItchBuild) -> Unit = install@{ list, build ->
        if (!shown.targetState) return@install
        val tagged = item.copy(
            extra = item.extra + mapOf("gameId" to list.gameId, "uploadId" to build.uploadId),
            sizeBytes = build.size.takeIf { it > 0 } ?: item.sizeBytes,
        )
        close()
        StoresState.requestInstall(ctx, tagged)
    }

    LaunchedEffect(item) {
        val result = withContext(Dispatchers.IO) {
            val key = ItchAuth.key(app)
            val id = if (key.isBlank()) null else ItchGame.resolveId(item)
            val uploads = id?.let { ItchApi.uploads(key, it, item.extra["downloadKeyId"].orEmpty()) }
            if (id == null || uploads == null) null else BuildList(id, ItchGame.builds(uploads))
        }
        if (result == null) { failed = true; return@LaunchedEffect }
        loaded = result
        if (shown.targetState) result.builds.singleOrNull()?.takeIf { it.installable }?.let { install(result, it) }
    }

    AppDialog(shown, close, "itchBuilds", wide = false, maxWidth = 460.dp) {
        DialogTitleRow(stringResource(R.string.stores_itch_pick_title), close)
        Text(item.title, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val list = loaded
        when {
            failed -> Text(stringResource(R.string.stores_itch_pick_failed), fontSize = 13.sp, color = colors.onSurfaceVariant)
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            list.builds.isEmpty() -> Text(stringResource(R.string.stores_itch_pick_none), fontSize = 13.sp, color = colors.onSurfaceVariant)
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (build in list.builds) key(build.uploadId) { BuildRow(item, build) { install(list, build) } }
            }
        }
    }
}

@Composable
private fun BuildRow(item: CatalogItem, build: ItchBuild, onInstall: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val linux = "linux" in build.platforms && !build.installable
    val detail = listOfNotNull(
        build.platforms.map { platformName(it) }.joinToString(", "),
        build.size.takeIf { it > 0 }?.let { formatBytes(it) },
        build.updatedAt.take(10).takeIf { it.isNotBlank() },
        if (build.demo) stringResource(R.string.stores_itch_build_demo) else null,
    ).joinToString(" · ")
    ListRow(enabled = !linux, onClick = { if (build.installable) onInstall() else openStoreUrl(ctx, item) }) {
        Column(Modifier.weight(1f)) {
            Text(build.filename.ifBlank { item.title }, fontSize = 13.sp, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val label = when {
            build.installable -> stringResource(R.string.stores_install)
            linux -> stringResource(R.string.stores_itch_build_linux_soon)
            else -> stringResource(R.string.stores_itch_build_open)
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
        "android" -> R.string.stores_itch_build_android
        else -> R.string.stores_itch_build_web
    },
)
