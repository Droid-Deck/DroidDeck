package com.droiddeck.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R

internal object DownloadsNav {
    var showSettings = false
}

@Composable
internal fun DownloadsPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    var settings by rememberSaveable { mutableStateOf(DownloadsNav.showSettings) }
    DownloadsNav.showSettings = false
    var hostAt by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { StoresMotion.drop() } }
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().onGloballyPositioned { hostAt = it.positionInRoot() }) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageHeader(stringResource(R.string.stores_tab_downloads)) {
            Spacer(Modifier.weight(1f))
            SmallIconButton(
                Icons.Filled.Settings, stringResource(R.string.downloads_settings),
                tint = if (settings) LocalPalette.current.signal else MaterialTheme.colorScheme.onSurfaceVariant,
            ) { settings = !settings }
        }
        AnimatedVisibility(settings, enter = expandVertically(Motion.sp(1f)) + fadeIn(Motion.sp(1f)), exit = shrinkVertically(Motion.sp(1f)) + fadeOut(Motion.sp(1f))) {
            SettingsGroup(stringResource(R.string.downloads_settings)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp).testTag("downloads-settings")) { DownloadControls(s, a) }
            }
        }
        StoresDownloadsPane(s, a)
    }
    StoresMotion.step?.let { ask ->
        androidx.compose.runtime.key(ask) {
            StepOut(
                open = StoresMotion.stepOpen, pill = ask.anchor.translate(-hostAt), preferredSide = ask.side, accent = ask.accent,
                onDismiss = { StoresMotion.fold() }, onClosed = { StoresMotion.closed(ask) },
                pillCorner = ask.pillCorner, maxWidth = ask.maxWidth, items = ask.items, handle = ask.handle, content = ask.content,
            )
        }
    }
    }
}
