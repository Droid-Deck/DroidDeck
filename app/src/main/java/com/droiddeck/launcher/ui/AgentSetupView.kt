package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.PairingGuideActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentHarness
import com.droiddeck.launcher.agent.AgentHost
import com.droiddeck.launcher.agent.AgentSetup
import com.droiddeck.launcher.agent.AgentStorage
import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import com.droiddeck.launcher.agent.AgentSetup.Status
import com.droiddeck.launcher.agent.AgentSetup.Step
import com.droiddeck.launcher.core.WirelessAdbPairingService

@Composable
internal fun AgentSetupView(modifier: Modifier, onDone: () -> Unit = {}) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    LaunchedEffect(AgentHost.harness) {
        AgentSetup.check(context)
        AgentStorage.measure(context)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var left = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) left = true
            if (event == Lifecycle.Event.ON_RESUME && left) {
                left = false
                AgentSetup.check(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.agent_setup_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
        if (!AgentSetup.runtimeInstalled) {
            Note(stringResource(R.string.agent_setup_runtime))
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (harness in AgentHarness.all) {
                val chosen = harness == AgentHost.harness
                if (chosen) PrimaryButton(harness.name, compact = true) {} else SecondaryButton(harness.name, enabled = !AgentSetup.busy, compact = true) { AgentHost.choose(context, harness) }
            }
        }
        val steps = Step.entries
        val done = steps.count { AgentSetup.status[it] == Status.DONE }
        val busy = steps.any { AgentSetup.status[it] == Status.WORKING }
        var details by rememberSaveable { mutableStateOf(false) }
        val summary = if (AgentSetup.ready) buildList {
            add(stringResource(R.string.agent_summary_signed_in, AgentHost.harness.name))
            if (AgentSetup.status[Step.GITHUB] == Status.DONE) add(stringResource(R.string.agent_step_github))
            if (AgentSetup.status[Step.BUILD_TOOLS] == Status.DONE) add(stringResource(R.string.agent_summary_lab))
            if (AgentSetup.status[Step.PREPARE] == Status.DONE) add(stringResource(R.string.agent_summary_fast))
        }.joinToString(" · ") else steps.firstOrNull { it.required && AgentSetup.status[it] != Status.DONE }?.let { labels(it, AgentHost.harness.name).first }.orEmpty()
        ReadinessCard(
            done, steps.size, summary, details || !AgentSetup.ready || busy, { details = !details },
            title = stringResource(if (AgentSetup.ready) R.string.agent_summary_ready else R.string.agent_summary_not_ready),
            ready = AgentSetup.ready,
        ) {
            steps.forEachIndexed { index, step ->
                val status = AgentSetup.status[step] ?: Status.UNKNOWN
                val (title, hint) = labels(step, AgentHost.harness.name)
                val lab = AgentHost.lab?.takeIf { it.prepare && it.running }
                val detail = if (step == Step.PREPARE && lab != null)
                    stringResource(R.string.agent_prepare_progress, lab.permille / 10, clock(lab.etaSeconds.toLong().coerceAtLeast(0)))
                else AgentSetup.detail[step] ?: hint
                CheckRow(
                    when (status) {
                        Status.DONE -> CheckState.OK
                        Status.WORKING, Status.UNKNOWN -> CheckState.BUSY
                        Status.TODO, Status.FAILED -> CheckState.WARN
                    },
                    title, detail, divider = index < steps.lastIndex,
                ) {
                    val blocked = (step in setOf(Step.SIGN_IN, Step.GITHUB, Step.BUILD_TOOLS) && AgentSetup.status[Step.INSTALL] != Status.DONE) ||
                        (step == Step.PREPARE && AgentSetup.status[Step.BUILD_TOOLS] != Status.DONE)
                    if ((status == Status.TODO || status == Status.FAILED) && !blocked) {
                        val label = stringResource(if (status == Status.FAILED) R.string.agent_setup_retry else action(step))
                        val run = { if (step == Step.CONNECTION) pair(context) else AgentSetup.run(context, step) }
                        if (step.required) PrimaryButton(label, compact = true, onClick = run) else SecondaryButton(label, compact = true, onClick = run)
                    }
                }
            }
        }
        if (AgentSetup.ready || AgentSetup.completed(context)) PrimaryButton(stringResource(R.string.agent_setup_done), main = true, onClick = onDone)
        StorageCard()
    }
}

@Composable
private fun StorageCard() {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val items = AgentStorage.Item.entries.filter { (AgentStorage.sizes[it] ?: 0L) > 0L }
    if (items.isEmpty()) return
    val total = items.sumOf { AgentStorage.sizes[it] ?: 0L }
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.agent_storage_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                Text(Formatter.formatShortFileSize(context, total), fontSize = 13.sp, color = pal.onSurfaceVariant)
            }
            SecondaryButton(stringResource(if (open) R.string.common_hide else R.string.setup_details), compact = true) { open = !open }
        }
        if (open) items.forEachIndexed { index, item ->
            if (index == 0) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
            var armed by remember(item) { mutableStateOf(false) }
            val busy = AgentStorage.removing[item] == true
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(storageTitle(item)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                    Text(
                        if (armed) stringResource(storageWarning(item)) else Formatter.formatShortFileSize(context, AgentStorage.sizes[item] ?: 0L),
                        fontSize = 13.sp, color = if (armed) AttentionAmber else pal.onSurfaceVariant,
                    )
                }
                SecondaryButton(
                    stringResource(if (armed) R.string.agent_storage_confirm else R.string.agent_storage_remove),
                    enabled = !busy && AgentHost.state != AgentHost.State.WORKING, compact = true,
                ) {
                    if (armed) {
                        armed = false
                        AgentStorage.remove(context, item)
                    } else armed = true
                }
            }
            if (index < items.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        }
    }
}

private fun storageTitle(item: AgentStorage.Item) = when (item) {
    AgentStorage.Item.CLAUDE_CODE -> R.string.agent_storage_claude
    AgentStorage.Item.CODEX -> R.string.agent_storage_codex
    AgentStorage.Item.BUILD_TOOLS -> R.string.agent_step_build
    AgentStorage.Item.SOURCE -> R.string.agent_storage_source
    AgentStorage.Item.CACHES -> R.string.agent_storage_caches
}

private fun storageWarning(item: AgentStorage.Item) = when (item) {
    AgentStorage.Item.SOURCE -> R.string.agent_storage_source_warning
    AgentStorage.Item.CACHES -> R.string.agent_storage_caches_warning
    else -> R.string.agent_storage_reinstall_warning
}

@Composable
private fun labels(step: Step, harness: String): Pair<String, String> = when (step) {
    Step.CONNECTION -> stringResource(R.string.agent_step_connection) to stringResource(R.string.agent_step_connection_hint)
    Step.INSTALL -> stringResource(R.string.agent_step_install, harness) to stringResource(R.string.agent_step_install_hint)
    Step.SIGN_IN -> stringResource(R.string.agent_step_sign_in, harness) to stringResource(R.string.agent_step_sign_in_hint)
    Step.GITHUB -> stringResource(R.string.agent_step_github) to stringResource(R.string.agent_step_github_hint)
    Step.BUILD_TOOLS -> stringResource(R.string.agent_step_build) to stringResource(R.string.agent_step_build_hint)
    Step.PREPARE -> stringResource(R.string.agent_step_prepare) to stringResource(R.string.agent_step_prepare_hint)
}

private fun action(step: Step): Int = when (step) {
    Step.CONNECTION -> R.string.agent_action_pair
    Step.INSTALL -> R.string.agent_action_install
    Step.SIGN_IN, Step.GITHUB -> R.string.agent_action_sign_in
    Step.BUILD_TOOLS -> R.string.agent_action_install
    Step.PREPARE -> R.string.agent_action_prepare
}

/** The pairing DroidDeck already uses for the phantom process fix; the agent reuses its key. */
private fun pair(context: android.content.Context) {
    if (!WirelessAdbPairingService.notificationsEnabled(context)) {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    WirelessAdbPairingService.start(context)
    PairingGuideActivity.start(context, null, false)
}
