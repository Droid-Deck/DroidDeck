package com.droiddeck.launcher.ui

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
import com.droiddeck.launcher.session.SessionPrefs

class CoreRow(val core: Int, val label: String)

@Composable
fun PerformancePage(
    cores: List<CoreRow>,
    clientOverride: Boolean,
    clientCores: Set<Int>,
    gameCores: Set<Int>,
    tuSysmem: Boolean,
    zinkLazy: Boolean,
    glThread: Boolean,
    noGlError: Boolean,
    noXalia: Boolean,
    gamescopeRealtime: Boolean,
    gpuClockPin: Boolean,
    prootNoSeccomp: Boolean,
    guestHostname: String,
    phantomWarning: String?,
    onClientOverride: (Boolean) -> Unit,
    onTuSysmem: (Boolean) -> Unit,
    onZinkLazy: (Boolean) -> Unit,
    onGlThread: (Boolean) -> Unit,
    onNoGlError: (Boolean) -> Unit,
    onNoXalia: (Boolean) -> Unit,
    onGamescopeRealtime: (Boolean) -> Unit,
    onGpuClockPin: (Boolean) -> Unit,
    onProotNoSeccomp: (Boolean) -> Unit,
    onGuestHostname: (String) -> Unit,
    onClientCore: (Int, Boolean) -> Unit,
    onGameCore: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val host = rememberMenuHost()
    val colors = MaterialTheme.colorScheme
    val coreItems = cores.map { it.core to it.label }
    SettingsPage(
        host, title = "Performance",
        lede = "CPU cores, session fixes and the host name. Applies next session.",
        onBack = onDismiss,
    ) {
        SettingsGroup("Steam client cores") {
            ToggleRow(
                host, "override", "Override Steam's own core choice",
                "Pins Steam, its UI helper, and gamescope to the cores below. Reapplied every few seconds.",
                clientOverride, onChange = onClientOverride,
            )
            MultiRow(
                host, "clientCores", "Client cores", if (clientOverride) "Choose cores for Steam." else "Enable the override to choose.",
                coreItems, clientCores, enabled = clientOverride, onToggle = onClientCore,
            )
        }
        SettingsGroup("Game cores") {
            MultiRow(
                host, "gameCores", "Game cores",
                "Choose cores for games. All selected lets Android schedule across every core.",
                coreItems, gameCores, onToggle = onGameCore,
            )
        }
        SettingsGroup("Client interface") {
            ToggleRow(
                host, "glthread", "Threaded GL",
                "May improve Steam menu responsiveness. Device impact is unverified.",
                glThread, onChange = onGlThread,
            )
            ToggleRow(
                host, "zink", "Zink: lazy descriptors",
                "Lazy, compact descriptors. Recommended for drivers without descriptor buffers.",
                zinkLazy, onChange = onZinkLazy,
            )
            ToggleRow(
                host, "noglerror", "Skip GL error checks",
                "Disables per-call GL validation.",
                noGlError, onChange = onNoGlError,
            )
            ToggleRow(
                host, "gsrealtime", "gamescope: realtime GPU queue",
                "Gives the compositor's GPU work priority over the game's. Can smooth frame pacing, but can cost games GPU time.",
                gamescopeRealtime, onChange = onGamescopeRealtime,
            )
        }
        SettingsGroup("GPU") {
            ToggleRow(
                host, "gpuclock", "Hold the GPU at its top clock",
                "Adreno only. Fewer hitches from the clock ramping up, at the cost of battery and heat. Released when the session ends.",
                gpuClockPin, onChange = onGpuClockPin,
            )
        }
        SettingsGroup("Session fixes") {
            ToggleRow(
                host, "sysmem", "Turnip: sysmem rendering",
                "On by default: faster for the Steam interface and most games. Required on Adreno 710/720/722.",
                tuSysmem, onChange = onTuSysmem,
            )
            ToggleRow(
                host, "xalia", "Skip Steam's xalia helper",
                "Disables Proton's gamepad navigation helper, which costs every game CPU time. Turn off only if a game needs it.",
                noXalia, onChange = onNoXalia,
            )
            ToggleRow(
                host, "seccomp", "Run proot without seccomp",
                "May fix missing syscall errors on some kernels, but can reduce performance.",
                prootNoSeccomp, onChange = onProotNoSeccomp,
            )
        }
        SettingsGroup("Session identity") {
            var draft by remember(guestHostname) { mutableStateOf(guestHostname) }
            val valid = SessionPrefs.validGuestHostname(draft) != null
            SettingsRow(
                "Host name",
                if (valid || draft.isBlank()) "What the session and Steam report as this machine's name. Blank restores ${SessionPrefs.DEFAULT_GUEST_HOSTNAME}."
                else "Letters, digits and inner hyphens only, up to 63 characters. Not saved until it is valid.",
            ) {
                OutlinedTextField(
                    draft, { v ->
                        draft = v.take(63)
                        if (draft.isBlank() || SessionPrefs.validGuestHostname(draft) != null) onGuestHostname(draft)
                    },
                    singleLine = true, isError = !valid && draft.isNotBlank(),
                    placeholder = { Text(SessionPrefs.DEFAULT_GUEST_HOSTNAME) },
                    modifier = Modifier.width(220.dp),
                )
            }
        }
        if (phantomWarning != null) {
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.error.copy(alpha = 0.08f))
                    .border(1.dp, colors.error.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(12.dp),
            ) {
                Text("Android is set to kill this session", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.error)
                Text(phantomWarning, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
