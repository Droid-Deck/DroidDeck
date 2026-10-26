package com.droiddeck.launcher.lan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.ui.PrimaryButton
import com.droiddeck.launcher.ui.SecondaryButton
import com.droiddeck.launcher.ui.SettingsGroup

@Composable
internal fun SteamLinkCoopPanel(
    hostAddress: String?,
    hostRunning: Boolean,
    clientInstalled: Boolean,
    onHost: () -> Unit,
    onClient: () -> Unit,
) {
    SettingsGroup(stringResource(R.string.lan_coop_title)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.lan_coop_overview))
            Text(stringResource(R.string.lan_coop_network))
            Text(stringResource(R.string.lan_coop_host))
            SecondaryButton(stringResource(if (hostRunning) R.string.lan_coop_return_host else R.string.lan_coop_open_launcher), onClick = onHost)
            if (hostAddress != null) {
                SelectionContainer { Text(stringResource(R.string.lan_coop_host_address, hostAddress)) }
            } else Text(stringResource(R.string.lan_coop_find_host))
            Text(stringResource(R.string.lan_coop_client))
            PrimaryButton(stringResource(if (clientInstalled) R.string.lan_coop_open_client else R.string.lan_coop_get_client), onClick = onClient)
            Text(stringResource(R.string.lan_coop_controllers))
            Text(stringResource(R.string.lan_coop_offline))
        }
    }
}
