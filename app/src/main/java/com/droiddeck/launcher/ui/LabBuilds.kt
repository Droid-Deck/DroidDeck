package com.droiddeck.launcher.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.WirelessAdbFix
import com.droiddeck.launcher.lab.LabOverlay

/**
 * Lab builds on this device: the one in use, one waiting for a restart, and the kept ones.
 * [onRebuild] offers to build a kept one again for this install, when something can.
 */
@Composable
internal fun LabBuilds(sessionRunning: Boolean = false, onRebuild: ((LabOverlay.Info) -> Unit)? = null) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val current = remember(version) { LabOverlay.current(context) }
    val pending = remember(version) { LabOverlay.pending(context) }
    val saved = remember(version) { LabOverlay.saved(context) }
    if (current == null && pending == null && saved.isEmpty()) return
    val active = LabOverlay.active
    val paused = active == null && current != null && LabOverlay.skipped == LabOverlay.SKIPPED_APP_IMAGE
    val restartFailed = stringResource(R.string.lab_restart_failed)
    val canRestart = !busy && !sessionRunning

    /** Android may have given the APK an app image again, which a Lab build cannot load over. */
    fun restart() {
        busy = true
        error = null
        Thread({
            val result = runCatching {
                if (LabOverlay.needsRecompile(context)) WirelessAdbFix.shell(context, LabOverlay.recompileCommand(context))
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                busy = false
                if (result.isSuccess) LabOverlay.restart(context)
                else error = restartFailed.format(result.exceptionOrNull()?.message.orEmpty())
            }
        }, "lab-restart").start()
    }

    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(
            stringResource(R.string.lab_builds).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
            color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 10.dp),
        )
        Column(Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) {
            val rows = mutableListOf<@Composable () -> Unit>()
            if (current != null) rows += {
                BuildRow(
                    current, true,
                    when {
                        active != null -> stringResource(R.string.lab_in_use)
                        paused -> stringResource(R.string.lab_paused_row)
                        else -> stringResource(R.string.lab_not_loaded, LabOverlay.skipped.orEmpty())
                    },
                    warn = active == null,
                ) {
                    if (paused) PrimaryButton(stringResource(R.string.lab_restart), compact = true, enabled = canRestart) { restart() }
                    SecondaryButton(stringResource(R.string.lab_turn_off), compact = true, enabled = canRestart) { LabOverlay.turnOff(context) }
                }
            }
            if (pending != null) rows += {
                BuildRow(pending, false, stringResource(R.string.lab_waiting), warn = false) {
                    PrimaryButton(stringResource(R.string.lab_restart), compact = true, enabled = canRestart) { restart() }
                }
            }
            for (build in saved) rows += {
                var armed by remember(build.dir) { mutableStateOf(false) }
                val info = build.info
                BuildRow(
                    info, false,
                    when {
                        armed -> stringResource(R.string.lab_delete_warning)
                        !build.usable -> stringResource(R.string.lab_other_install)
                        build.reason == LabOverlay.Reason.DID_NOT_START -> stringResource(R.string.lab_off_did_not_start)
                        else -> stringResource(R.string.lab_kept)
                    },
                    warn = armed || !build.usable || build.reason == LabOverlay.Reason.DID_NOT_START,
                ) {
                    if (!build.usable && !armed && info != null && onRebuild != null) SecondaryButton(stringResource(R.string.lab_rebuild), compact = true, enabled = !busy) {
                        onRebuild(info)
                    }
                    if (build.usable && !armed) SecondaryButton(stringResource(R.string.lab_use), compact = true, enabled = canRestart) {
                        runCatching { LabOverlay.stage(context, build) }
                            .onSuccess { version++; restart() }
                            .onFailure { error = it.message }
                    }
                    SecondaryButton(stringResource(if (armed) R.string.lab_delete_confirm else R.string.lab_delete), compact = true, enabled = !busy) {
                        if (armed) {
                            LabOverlay.delete(build)
                            version++
                        } else armed = true
                    }
                }
            }
            rows.forEachIndexed { index, row ->
                row()
                if (index < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
            }
        }
        error?.let { Text(it, fontSize = 12.5.sp, color = AttentionAmber, modifier = Modifier.padding(start = 2.dp, top = 8.dp)) }
    }
}

@Composable
private fun BuildRow(info: LabOverlay.Info?, current: Boolean, status: String, warn: Boolean, actions: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp).clip(Shape12)
            .background(if (current) pal.signal.copy(alpha = 0.18f) else colors.surfaceVariant)) {
            Icon(Icons.Outlined.Code, null, tint = if (current) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(info?.label ?: "?", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val built = info?.built?.takeIf { it > 0 }?.let { DateUtils.getRelativeTimeSpanString(it * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() }
            Text(listOfNotNull(status, built).joinToString(" · "), fontSize = 12.5.sp, color = if (warn) AttentionAmber else colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}
