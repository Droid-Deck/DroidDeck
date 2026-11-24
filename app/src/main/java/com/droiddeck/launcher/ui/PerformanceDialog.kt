package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.core.WirelessAdbFix
import com.droiddeck.launcher.session.GpuClockPin
import com.droiddeck.launcher.session.SessionPrefs

class CoreRow(val core: Int, val label: String)

@Composable
fun PerformancePage(
    cores: List<CoreRow>,
    clientOverride: Boolean,
    clientCores: Set<Int>,
    gameCores: Set<Int>,
    paceCores: Boolean,
    tuSysmem: Boolean,
    zinkLazy: Boolean,
    glThread: Boolean,
    noGlError: Boolean,
    noXalia: Boolean,
    fastSync: Boolean,
    syncFallback: Boolean,
    fsyncFirst: Boolean,
    gamescopeRealtime: Boolean,
    gpuClockPin: Boolean,
    gameMode: String,
    prootNoSeccomp: Boolean,
    prootFastPath: Boolean,
    nativeUserland: Boolean,
    cursorComposite: Boolean,
    guestHostname: String,
    phantomWarning: String?,
    onClientOverride: (Boolean) -> Unit,
    onPaceCores: (Boolean) -> Unit,
    onTuSysmem: (Boolean) -> Unit,
    onZinkLazy: (Boolean) -> Unit,
    onGlThread: (Boolean) -> Unit,
    onNoGlError: (Boolean) -> Unit,
    onNoXalia: (Boolean) -> Unit,
    onFastSync: (Boolean) -> Unit,
    onSyncFallback: (Boolean) -> Unit,
    onFsyncFirst: (Boolean) -> Unit,
    onGamescopeRealtime: (Boolean) -> Unit,
    onGpuClockPin: (Boolean) -> Unit,
    onGameMode: (String) -> Unit,
    onProotNoSeccomp: (Boolean) -> Unit,
    onProotFastPath: (Boolean) -> Unit,
    onNativeUserland: (Boolean) -> Unit,
    onCursorComposite: (Boolean) -> Unit,
    onGuestHostname: (String) -> Unit,
    onClientCore: (Int, Boolean) -> Unit,
    onGameCore: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
    embedded: Boolean = false,
    games: List<Library.SteamGame> = emptyList(),
) {
    val host = rememberMenuHost()
    val colors = MaterialTheme.colorScheme
    val coreItems = cores.map { it.core to it.label }
    val body: @Composable () -> Unit = {
        SettingsGroup(stringResource(R.string.perf_client_cores_group)) {
            ToggleRow(
                host, "override", stringResource(R.string.perf_override),
                stringResource(R.string.perf_override_hint),
                clientOverride, onChange = onClientOverride,
            )
            MultiRow(
                host, "clientCores", stringResource(R.string.perf_client_cores), if (clientOverride) stringResource(R.string.perf_client_cores_on) else stringResource(R.string.perf_client_cores_off),
                coreItems, clientCores, enabled = clientOverride, onToggle = onClientCore,
            )
        }
        SettingsGroup(stringResource(R.string.perf_game_cores_group)) {
            MultiRow(
                host, "gameCores", stringResource(R.string.perf_game_cores),
                stringResource(R.string.perf_game_cores_hint),
                coreItems, gameCores, onToggle = onGameCore,
            )
            ToggleRow(
                host, "paceCores", stringResource(R.string.perf_pace_cores),
                stringResource(R.string.perf_pace_cores_hint),
                paceCores, onChange = onPaceCores,
            )
        }
        SettingsGroup(stringResource(R.string.perf_client_ui)) {
            ToggleRow(
                host, "glthread", stringResource(R.string.perf_glthread),
                stringResource(R.string.perf_glthread_hint),
                glThread, onChange = onGlThread,
            )
            ToggleRow(
                host, "zink", stringResource(R.string.perf_zink),
                stringResource(R.string.perf_zink_hint),
                zinkLazy, onChange = onZinkLazy,
            )
            ToggleRow(
                host, "noglerror", stringResource(R.string.perf_noglerror),
                stringResource(R.string.perf_noglerror_hint),
                noGlError, onChange = onNoGlError,
            )
            ToggleRow(
                host, "gsrealtime", stringResource(R.string.perf_gsrealtime),
                stringResource(R.string.perf_gsrealtime_hint),
                gamescopeRealtime, onChange = onGamescopeRealtime,
            )
        }
        if (GpuClockPin.supported) GpuClockGroup(host, gpuClockPin, onChange = onGpuClockPin)
        if (Build.VERSION.SDK_INT >= 31) GameModeGroup(host, gameMode, onChange = onGameMode)
        ShaderPrecompGroup(host, games)
        SettingsGroup(stringResource(R.string.perf_fixes)) {
            ToggleRow(
                host, "sysmem", stringResource(R.string.perf_sysmem),
                stringResource(R.string.perf_sysmem_hint),
                tuSysmem, onChange = onTuSysmem,
            )
            ToggleRow(
                host, "xalia", stringResource(R.string.perf_xalia),
                stringResource(R.string.perf_xalia_hint),
                noXalia, onChange = onNoXalia,
            )
            ToggleRow(
                host, "syncfallback", stringResource(R.string.perf_esync),
                stringResource(R.string.perf_esync_hint),
                syncFallback, onChange = onSyncFallback,
            )
            ToggleRow(
                host, "fsyncfirst", stringResource(R.string.perf_fsync),
                stringResource(R.string.perf_fsync_hint),
                fsyncFirst, onChange = onFsyncFirst,
            )
            ToggleRow(
                host, "fastsync", stringResource(R.string.perf_fastsync),
                stringResource(R.string.perf_fastsync_hint),
                fastSync, onChange = onFastSync,
            )
            ToggleRow(
                host, "seccomp", stringResource(R.string.perf_seccomp),
                stringResource(R.string.perf_seccomp_hint),
                prootNoSeccomp, onChange = onProotNoSeccomp,
            )
            ToggleRow(
                host, "fastpath", stringResource(R.string.perf_fastpath),
                when {
                    nativeUserland -> stringResource(R.string.perf_fastpath_needs_proot)
                    prootNoSeccomp -> stringResource(R.string.perf_fastpath_needs_seccomp)
                    else -> stringResource(R.string.perf_fastpath_hint)
                },
                prootFastPath && !prootNoSeccomp && !nativeUserland, enabled = !prootNoSeccomp && !nativeUserland,
                onChange = onProotFastPath,
            )
            ToggleRow(
                host, "nativeuserland", stringResource(R.string.perf_native_userland),
                stringResource(R.string.perf_native_userland_hint),
                nativeUserland, onChange = onNativeUserland,
            )
            ToggleRow(
                host, "cursorcomposite", stringResource(R.string.perf_cursor_composite),
                stringResource(R.string.perf_cursor_composite_hint),
                cursorComposite, onChange = onCursorComposite,
            )
        }
        SettingsGroup(stringResource(R.string.perf_identity)) {
            var draft by remember(guestHostname) { mutableStateOf(guestHostname) }
            val valid = SessionPrefs.validGuestHostname(draft) != null
            SettingsRow(
                stringResource(R.string.perf_hostname),
                if (valid || draft.isBlank()) stringResource(R.string.perf_hostname_hint, SessionPrefs.DEFAULT_GUEST_HOSTNAME)
                else stringResource(R.string.perf_hostname_invalid),
            ) {
                OutlinedTextField(
                    draft, { v ->
                        draft = v.take(63)
                        if (draft.isBlank() || SessionPrefs.validGuestHostname(draft) != null) onGuestHostname(draft)
                    },
                    singleLine = true, isError = !valid && draft.isNotBlank(),
                    placeholder = { Text(SessionPrefs.DEFAULT_GUEST_HOSTNAME) },
                    modifier = Modifier.width(220.dp).focusGlide(),
                )
            }
        }
        if (phantomWarning != null) {
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.error.copy(alpha = 0.08f))
                    .border(1.dp, colors.error.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(12.dp),
            ) {
                Text(stringResource(R.string.perf_phantom_warning), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.error)
                Text(phantomWarning, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (embedded) Column {
        Text(stringResource(R.string.perf_lede), fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
        body()
    } else SettingsPage(
        host, title = stringResource(R.string.perf_title),
        lede = stringResource(R.string.perf_lede),
        onBack = onDismiss,
    ) { body() }
}

@Composable
internal fun GpuClockGroup(host: MenuHost, on: Boolean, chipModifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    SettingsGroup(stringResource(R.string.perf_gpu)) {
        ToggleRow(
            host, "gpuclock", stringResource(R.string.perf_gpuclock),
            null,
            on, chipModifier = chipModifier, onChange = onChange,
        )
    }
}

@Composable
internal fun GameModeGroup(host: MenuHost, mode: String, chipModifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val paired = remember { WirelessAdbFix.hasSavedPairing(context) }
    val hint = stringResource(R.string.perf_game_mode_hint) +
        if (paired) "" else " " + stringResource(R.string.perf_game_mode_unpaired)
    SettingsGroup(stringResource(R.string.perf_game_mode_group)) {
        ChoiceRow(
            host, "gameMode", stringResource(R.string.perf_game_mode),
            hint,
            listOf(
                SessionPrefs.GAME_MODE_SYSTEM to stringResource(R.string.perf_game_mode_system),
                SessionPrefs.GAME_MODE_BATTERY to stringResource(R.string.perf_game_mode_battery),
                SessionPrefs.GAME_MODE_STANDARD to stringResource(R.string.perf_game_mode_standard),
                SessionPrefs.GAME_MODE_PERFORMANCE to stringResource(R.string.perf_game_mode_performance),
            ),
            mode, chipModifier = chipModifier, onPick = onChange,
        )
    }
}
