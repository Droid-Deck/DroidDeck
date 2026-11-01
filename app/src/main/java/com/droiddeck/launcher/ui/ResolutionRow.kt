package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionDisplay

@Composable
fun ResolutionRow(
    host: MenuHost, choice: String, panel: Pair<Int, Int>, onPick: (String) -> Unit,
    chipModifier: Modifier = Modifier,
    portrait: Boolean = false,
) {
    val custom = "custom"
    val size = SessionDisplay.resolveChoice(panel, choice, portrait)
    val screen = SessionDisplay.resolveChoice(panel, SessionDisplay.MATCH_SCREEN, portrait)
    val choices = SessionDisplay.resolutionOptions(panel)
    val customFollow = SessionDisplay.isFollow(choice) && choice !in choices
    val selected = if (SessionDisplay.isFollow(choice)) choice
        else if (size == screen) SessionDisplay.MATCH_SCREEN else choice
    var editCustom by remember { mutableStateOf(false) }
    var editFollow by remember { mutableStateOf(false) }
    val options = choices.map { value ->
        val dimensions = SessionDisplay.resolveChoice(panel, value, portrait)
        value to if (value == SessionDisplay.MATCH_SCREEN) stringResource(R.string.display_match_screen, screen.first, screen.second)
        else if (value == SessionDisplay.FOLLOW_SCREEN) stringResource(R.string.display_follow_screen)
        else if (SessionDisplay.isFollow(value)) stringResource(R.string.display_follow_screen_scaled,
            SessionDisplay.formatScale(SessionDisplay.followScale(value) ?: 1f))
        else if (value == SessionDisplay.DEFAULT_RESOLUTION) stringResource(R.string.mode_res_default, "${dimensions.first}×${dimensions.second}")
        else "${dimensions.first}×${dimensions.second}"
    } + listOf(SessionDisplay.FOLLOW_CUSTOM to stringResource(R.string.display_follow_screen_scaled,
        if (customFollow) SessionDisplay.formatScale(SessionDisplay.followScale(choice) ?: 1f) else "…"
    )) + (custom to if (selected in choices) stringResource(R.string.mode_res_custom)
        else stringResource(R.string.mode_res_custom_value, size.first, size.second))
    ChoiceRow(host, "resolution", stringResource(R.string.display_resolution),
        stringResource(R.string.common_applies_next_session),
        options, if (selected in choices) selected else if (customFollow) SessionDisplay.FOLLOW_CUSTOM else custom, chipModifier = chipModifier,
        onPick = { value ->
            if (value == custom) editCustom = true
            else if (value == SessionDisplay.FOLLOW_CUSTOM) editFollow = true
            else onPick(value) })
    if (editCustom) CustomResolutionDialog(size,
        onSave = { value -> editCustom = false; onPick("${value.first}x${value.second}") },
        onDismiss = { editCustom = false })
    if (editFollow) CustomFollowScaleDialog(SessionDisplay.followScale(choice)?.takeIf { it > 1f },
        onSave = { value -> editFollow = false; onPick(SessionDisplay.followScaled(value)) },
        onDismiss = { editFollow = false })
}
