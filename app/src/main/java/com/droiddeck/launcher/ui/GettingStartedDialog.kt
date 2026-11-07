package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

@Composable
internal fun GettingStartedDialog(
    runtimeReady: Boolean,
    onInstallRuntime: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    val steps = listOf(
        R.string.guide_step_device_title to R.string.guide_step_device_body,
        R.string.guide_step_runtime_title to R.string.guide_step_runtime_body,
        R.string.guide_step_limit_title to R.string.guide_step_limit_body,
        R.string.guide_step_steam_title to R.string.guide_step_steam_body,
        R.string.guide_step_help_title to R.string.guide_step_help_body,
    )
    AppDialog(shown, close, "gettingStarted", wide = false, maxWidth = 520.dp) {
        DialogTitleRow(stringResource(R.string.guide_title), onClose = close)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            steps.forEachIndexed { i, (title, body) -> GuideStep(i + 1, stringResource(title), stringResource(body)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (!runtimeReady) PrimaryButton(stringResource(R.string.guide_install_runtime), compact = true) {
                onInstallRuntime()
                close()
            }
            SecondaryButton(stringResource(R.string.guide_done), compact = true) { close() }
        }
    }
}

@Composable
private fun GuideStep(number: Int, title: String, body: String) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(26.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text(number.toString(), color = colors.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = colors.onBackground.copy(alpha = 0.75f), fontSize = 13.sp)
        }
    }
}
