package com.droiddeck.launcher.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.droiddeck.launcher.HomeApp
import com.droiddeck.launcher.HomeIcons
import com.droiddeck.launcher.IconPack
import com.droiddeck.launcher.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CELL_ICON = 44.dp

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.focusTrap(): Modifier = focusProperties {
    exit = { dir ->
        if (dir == FocusDirection.Up || dir == FocusDirection.Down || dir == FocusDirection.Left || dir == FocusDirection.Right) FocusRequester.Cancel
        else FocusRequester.Default
    }
}.focusGroup()

private fun normalized(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

internal fun iconMatches(names: List<String>, label: String, query: String): List<String> {
    val q = normalized(query)
    if (q.isNotEmpty()) return names.filter { q in normalized(it) }
    val hint = normalized(label)
    val (likely, rest) = names.partition { hint in normalized(it) }
    return likely + rest
}

@Composable
internal fun AppIconPicker(app: HomeApp.LaunchableApp, padH: androidx.compose.ui.unit.Dp, onClose: () -> Unit) {
    SwipeLock()
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pack = remember { IconPack.selected(ctx) }
    val names by produceState<List<String>?>(null, pack) { value = withContext(Dispatchers.IO) { IconPack.names(ctx, pack) } }
    var query by remember { mutableStateOf("") }
    val shown = remember(names, query) { names?.let { iconMatches(it, app.label, query) } }
    val cache = remember { LruCache<String, Bitmap>(256) }
    val inputMode = LocalInputModeManager.current.inputMode
    val ff = LocalFrontFocus.current
    val first = remember { FocusRequester() }
    var closing by remember { mutableStateOf(false) }
    val close = {
        closing = true
        onClose()
        if (inputMode == InputMode.Keyboard && ff != null) {
            runCatching { ff.items.getValue("tile:${app.key}").requestFocus() }
                .onFailure { runCatching { ff.firstTile.requestFocus() } }
        }
    }
    LaunchedEffect(names != null, inputMode) {
        if (names != null && !closing && inputMode == InputMode.Keyboard) {
            repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            ff?.last = "icon:${shown?.firstOrNull()}"
            if (!closing) runCatching { first.requestFocus() }
        }
    }
    BackHandler { close() }
    Column(
        Modifier.fillMaxSize().imePadding().background(colors.background)
            .blockTouches()
            .focusTrap(),
    ) {
        val title = stringResource(R.string.touch_icon_for, app.label)
        Box(Modifier.fillMaxWidth().padding(start = padH, end = padH, top = 8.dp)) {
            ModeHeader(
                title, query, { query = it }, placeholder = stringResource(R.string.apps_icons_search),
                lead = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BackLink(stringResource(R.string.rail_apps), compact = true, onClick = close)
                        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                inlineWhenStacked = true,
            ) { }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(64.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = padH, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                shown == null -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { Note(stringResource(R.string.apps_icons_loading)) }
                shown.isEmpty() -> item(key = "none", span = { GridItemSpan(maxLineSpan) }) { Note(stringResource(R.string.apps_icons_none)) }
                else -> items(shown, key = { it }) { name ->
                    PackIconCell(
                        pack, name, cache,
                        modifier = if (name == shown.first()) Modifier.focusRequester(first) else Modifier,
                    ) {
                        HomeIcons.set(ctx, app.key, pack, name)
                        close()
                    }
                }
            }
        }
    }
}

@Composable
private fun PackIconCell(
    pack: String, name: String, cache: LruCache<String, Bitmap>, modifier: Modifier, onPick: () -> Unit,
) {
    val ctx = LocalContext.current
    val iconPx = with(LocalDensity.current) { CELL_ICON.roundToPx() }
    val bitmap by produceState(cache.get(name), name) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { IconPack.drawable(ctx, pack, name, ctx.resources.displayMetrics.densityDpi)?.toBitmap(iconPx, iconPx) }.getOrNull()?.also { cache.put(name, it) }
        }
    }
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxWidth().paneItem("icon:$name").clip(Shape12)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
            .glideBorder(hot, Shape12, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onPick)
            .controllerConfirm(onClick = onPick)
            .semantics { contentDescription = name.replace('_', ' ') }
            .padding(10.dp),
    ) {
        val icon = bitmap
        if (icon != null) Image(icon.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.size(CELL_ICON))
        else Spacer(Modifier.size(CELL_ICON))
    }
}
