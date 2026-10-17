package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.glasses.GlassesMenuMirror

@Composable
internal fun GlassesSetupPanel() {
    val (glasses, settings) = rememberGlasses()
    val host = rememberMenuHost()
    val tabs = glassesTabs(glasses)
    var selectedTab by rememberSaveable { mutableStateOf(GlassesTab.DISPLAY) }
    val tab = selectedTab.takeIf { selected -> tabs.any { it.first == selected } } ?: GlassesTab.DISPLAY
    LaunchedEffect(tab, glasses.present, glasses.canControl, glasses.model) { host.open = null }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Note(stringResource(R.string.setup_glasses_scope))
        if (!glasses.present && !glasses.hasDisplay && !glasses.canControl) {
            Note(stringResource(R.string.setup_glasses_connect))
        } else {
            SegmentedTabs(tabs, tab, modifier = Modifier.fillMaxWidth(),
                tabModifier = { Modifier.testTag("setup-glasses-tab-$it") }) { selectedTab = it }
            GlassesGroups(host, glasses, settings, onRecenter = GlassesMenuMirror::recenter, inSession = false, tab = tab)
        }
    }
}
