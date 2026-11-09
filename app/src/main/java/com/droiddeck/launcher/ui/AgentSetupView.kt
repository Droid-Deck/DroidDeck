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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.PairingGuideActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentHarness
import com.droiddeck.launcher.agent.AgentHost
import com.droiddeck.launcher.agent.AgentSetup
import com.droiddeck.launcher.agent.AgentSetup.Status
import com.droiddeck.launcher.agent.AgentSetup.Step
import com.droiddeck.launcher.core.WirelessAdbPairingService

@Composable
internal fun AgentSetupView(modifier: Modifier, onDone: () -> Unit = {}) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    LaunchedEffect(AgentHost.harness) { AgentSetup.check(context) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.agent_setup_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
        if (!AgentSetup.runtimeInstalled) {
            Note(stringResource(R.string.agent_setup_runtime))
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (harness in AgentHarness.all) {
                val chosen = harness == AgentHost.harness
                if (chosen) PrimaryButton(harness.name, compact = true) {} else SecondaryButton(harness.name, compact = true) { AgentHost.choose(context, harness) }
            }
        }
        Column(Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) {
            val steps = Step.entries
            steps.forEachIndexed { index, step ->
                val status = AgentSetup.status[step] ?: Status.UNKNOWN
                val (title, hint) = labels(step, AgentHost.harness.name)
                val detail = AgentSetup.detail[step] ?: hint
                CheckRow(
                    when (status) {
                        Status.DONE -> CheckState.OK
                        Status.WORKING, Status.UNKNOWN -> CheckState.BUSY
                        Status.TODO, Status.FAILED -> CheckState.WARN
                    },
                    title, detail, divider = index < steps.lastIndex,
                ) {
                    val blocked = step == Step.SIGN_IN && AgentSetup.status[Step.INSTALL] != Status.DONE
                    if ((status == Status.TODO || status == Status.FAILED) && !blocked) {
                        val label = stringResource(if (status == Status.FAILED) R.string.agent_setup_retry else action(step))
                        val run = { if (step == Step.CONNECTION) pair(context) else AgentSetup.run(context, step) }
                        if (step.required) PrimaryButton(label, compact = true, onClick = run) else SecondaryButton(label, compact = true, onClick = run)
                    }
                }
            }
        }
        if (AgentSetup.ready) PrimaryButton(stringResource(R.string.agent_setup_done), main = true, onClick = onDone)
    }
}

@Composable
private fun labels(step: Step, harness: String): Pair<String, String> = when (step) {
    Step.CONNECTION -> stringResource(R.string.agent_step_connection) to stringResource(R.string.agent_step_connection_hint)
    Step.INSTALL -> stringResource(R.string.agent_step_install, harness) to stringResource(R.string.agent_step_install_hint)
    Step.SIGN_IN -> stringResource(R.string.agent_step_sign_in, harness) to stringResource(R.string.agent_step_sign_in_hint)
    Step.GITHUB -> stringResource(R.string.agent_step_github) to stringResource(R.string.agent_step_github_hint)
    Step.BUILD_TOOLS -> stringResource(R.string.agent_step_build) to stringResource(R.string.agent_step_build_hint)
}

private fun action(step: Step): Int = when (step) {
    Step.CONNECTION -> R.string.agent_action_pair
    Step.INSTALL -> R.string.agent_action_install
    Step.SIGN_IN, Step.GITHUB -> R.string.agent_action_sign_in
    Step.BUILD_TOOLS -> R.string.agent_action_install
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
