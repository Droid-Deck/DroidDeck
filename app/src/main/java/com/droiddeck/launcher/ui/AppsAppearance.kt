package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.HomeIcons
import com.droiddeck.launcher.IconPack
import com.droiddeck.launcher.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PanelWidth = 348.dp
private val PanelHeight = 316.dp
private val TabsWidth = 204.dp

@Composable
internal fun AppsAppearanceButton(a: FrontEndActions) {
    val pal = LocalPalette.current
    val title = stringResource(R.string.apps_appearance)
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        HeaderIconButton(title, Icons.Filled.Palette, "apps-appearance") {
            val from = at ?: return@HeaderIconButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = pal.signal, pillCorner = 8.dp, items = 2, maxWidth = PanelWidth,
                handle = { Icon(Icons.Filled.Palette, null, tint = pal.signal, modifier = Modifier.size(18.dp)) },
            ) {
                StepTitle(title)
                Column(
                    Modifier.stepItem(1).fillMaxWidth().height(minOf(PanelHeight, (maxHeight - 64.dp).coerceAtLeast(44.dp))).verticalScroll(rememberScrollState()),
                ) { AppearanceRows(a) }
            })
        }
    }
}

@Composable
private fun StepOutScope.AppearanceRows(a: FrontEndActions) {
    val ctx = LocalContext.current
    val host = rememberMenuHost()
    var packsOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(IconPack.selected(ctx)) }
    var appColors by remember { mutableStateOf(IconPack.appColors(ctx)) }
    val installed by produceState(emptyList<IconPack.Pack>()) {
        value = withContext(Dispatchers.IO) { runCatching { IconPack.installed(ctx) }.getOrDefault(emptyList()) }
    }
    val hasBack by produceState(false, selected) { value = withContext(Dispatchers.IO) { IconPack.active(ctx)?.hasBack == true } }
    CompositionLocalProvider(LocalCompactSettings provides true) {
        val packs = listOf(IconPack.Pack(IconPack.NONE, stringResource(R.string.apps_icon_pack_system))) + installed
        val current = packs.find { it.packageName == selected } ?: packs.first()
        Box(Modifier.focusRequester(first)) {
            LinkRow(
                stringResource(R.string.apps_icon_pack), current.label, "", "icon-pack",
                arrow = if (packsOpen) "▴" else "▾",
            ) { packsOpen = !packsOpen }
        }
        if (packsOpen) packs.forEach { (value, text) ->
            MenuItem(text, checked = value == current.packageName) {
                selected = value
                IconPack.select(ctx, value)
                a.onAppIconsChanged()
                packsOpen = false
                runCatching { first.requestFocus() }
            }
        }
        SettingsRow(stringResource(R.string.apps_icon_size), null) {
            SegmentedTabs(
                listOf(
                    AppsGrid.SMALL to stringResource(R.string.apps_icon_small),
                    AppsGrid.MEDIUM to stringResource(R.string.apps_icon_medium),
                    AppsGrid.LARGE to stringResource(R.string.apps_icon_large),
                ),
                AppsGrid.iconDp, Modifier.width(TabsWidth),
            ) { AppsGrid.setIconDp(ctx, it) }
        }
        SettingsRow(stringResource(R.string.apps_spacing), null) {
            SegmentedTabs(
                listOf(
                    AppsGrid.TIGHT to stringResource(R.string.apps_spacing_tight),
                    AppsGrid.NORMAL to stringResource(R.string.apps_spacing_normal),
                    AppsGrid.ROOMY to stringResource(R.string.apps_spacing_roomy),
                ),
                AppsGrid.spacing, Modifier.width(TabsWidth),
            ) { AppsGrid.setSpacing(ctx, it) }
        }
        ToggleRow(host, "app-names", stringResource(R.string.apps_hide_names), null, !AppsGrid.labels) { AppsGrid.setLabels(ctx, !it) }
        if (hasBack) ToggleRow(host, "app-colors", stringResource(R.string.apps_icon_app_colors), null, appColors) {
            appColors = it
            IconPack.setAppColors(ctx, it)
            a.onAppIconsChanged()
        }
        if (HomeIcons.overrides.isNotEmpty()) ActionRow(stringResource(R.string.apps_icons_custom), null, stringResource(R.string.ctrl_reset), {
            HomeIcons.clearAll(ctx)
            runCatching { first.requestFocus() }
        })
    }
}
