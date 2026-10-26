package com.droiddeck.launcher.lan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.core.AppLanguage
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.ui.ActionRow
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.Note
import com.droiddeck.launcher.ui.PrimaryButton
import com.droiddeck.launcher.ui.SecondaryButton
import com.droiddeck.launcher.ui.SettingsGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class LocalLanActivity : ComponentActivity() {
    private var clientInstalled by mutableStateOf(false)
    private var hostRunning by mutableStateOf(false)

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DroidDeckTheme(SessionPrefs.theme(this)) {
                Surface(Modifier.fillMaxSize()) { LanSetup() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        clientInstalled = SteamLinkCoop.clientIntent(this) != null
        hostRunning = SessionState.running && SessionState.mode == SessionService.MODE_STEAM
    }

    @Composable
    private fun LanSetup() {
        val hotspot by LocalLan.hotspot.collectAsState()
        val selected by LocalLan.selected.collectAsState()
        var interfaces by remember { mutableStateOf(emptyList<LanInterface>()) }
        var denied by remember { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            denied = !granted
            if (granted) startHotspot()
        }
        LaunchedEffect(Unit) {
            while (true) {
                interfaces = withContext(Dispatchers.IO) { LocalLan.interfaces() }
                hostRunning = SessionState.running && SessionState.mode == SessionService.MODE_STEAM
                delay(2000)
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.lan_title), style = MaterialTheme.typography.headlineSmall)
            Note(stringResource(R.string.lan_experimental))
            SteamLinkCoopPanel(
                hostAddress = interfaces.firstOrNull { it.name == selected }?.addresses?.firstOrNull()?.address?.hostAddress,
                hostRunning = hostRunning,
                clientInstalled = clientInstalled,
                onHost = {
                    if (SessionState.running && SessionState.mode == SessionService.MODE_STEAM) {
                        startActivity(Intent(this@LocalLanActivity, SessionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    } else finish()
                },
                onClient = {
                    if (!SteamLinkCoop.openClientOrStore(this@LocalLanActivity)) {
                        android.widget.Toast.makeText(this@LocalLanActivity, R.string.lan_coop_client_unavailable, android.widget.Toast.LENGTH_LONG).show()
                    }
                },
            )
            SettingsGroup(stringResource(R.string.lan_wireless)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.lan_wireless_help))
                    if (hotspot.ssid != null) {
                        SelectionContainer { Text(stringResource(R.string.lan_credentials, hotspot.ssid!!, hotspot.password.orEmpty())) }
                    }
                    if (hotspot.starting || hotspot.ssid != null) {
                        SecondaryButton(stringResource(R.string.lan_stop)) { stopService(Intent(this@LocalLanActivity, LocalHotspotService::class.java)) }
                    } else {
                        PrimaryButton(stringResource(R.string.lan_host)) {
                            denied = false
                            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startHotspot()
                            else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    }
                    if (hotspot.starting) Text(stringResource(R.string.lan_starting))
                    hotspot.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    if (denied) {
                        Text(stringResource(R.string.lan_permission_needed))
                        SecondaryButton(stringResource(R.string.lan_app_settings)) {
                            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))
                        }
                    }
                    SecondaryButton(stringResource(R.string.lan_wifi_settings)) { openSettings(Settings.ACTION_WIFI_SETTINGS) }
                    SecondaryButton(stringResource(R.string.lan_location_settings)) { openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
                }
            }
            SettingsGroup(stringResource(R.string.lan_wired)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.lan_wired_help))
                    SecondaryButton(stringResource(R.string.lan_network_settings)) { openSettings(Settings.ACTION_WIRELESS_SETTINGS) }
                }
            }
            SettingsGroup(stringResource(R.string.lan_interface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.lan_interface_help))
                    if (interfaces.isEmpty()) Text(stringResource(R.string.lan_no_interfaces))
                    if (selected != null && interfaces.none { it.name == selected }) Text(stringResource(R.string.lan_interface_lost, selected!!), color = MaterialTheme.colorScheme.error)
                }
                interfaces.forEach { network ->
                    ActionRow(network.name, network.addresses.joinToString { it.address.hostAddress.orEmpty() },
                        stringResource(if (selected == network.name) R.string.lan_selected else R.string.lan_use), onClick = { LocalLan.select(network.name, this@LocalLanActivity) })
                }
                ActionRow(stringResource(R.string.lan_default), null,
                    stringResource(if (selected == null) R.string.lan_selected else R.string.lan_use), onClick = { LocalLan.select(null) })
            }
            SecondaryButton(stringResource(R.string.common_back)) { finish() }
        }
    }

    private fun startHotspot() = startForegroundService(Intent(this, LocalHotspotService::class.java))

    private fun openSettings(action: String, data: android.net.Uri? = null) {
        val intent = Intent(action, data)
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
    }
}
