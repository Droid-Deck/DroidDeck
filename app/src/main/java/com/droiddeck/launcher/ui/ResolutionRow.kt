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

/** The same resolution menu before and during a session; changes take effect on the next start. */
@Composable
fun ResolutionRow(
    host: MenuHost, choice: String, panel: Pair<Int, Int>, onPick: (String) -> Unit,
    chipModifier: Modifier = Modifier, currentSize: Pair<Int, Int>? = null,
) {
    val custom = "custom"
    val size = SessionDisplay.resolveChoice(panel, choice)
    val screen = SessionDisplay.screenSize(panel)
    val choices = SessionDisplay.resolutionOptions(panel)
    val selected = if (size == screen) SessionDisplay.MATCH_SCREEN else choice
    var editCustom by remember { mutableStateOf(false) }
    val options = choices.map { value ->
        val dimensions = SessionDisplay.resolveChoice(panel, value)
        value to if (value == SessionDisplay.MATCH_SCREEN) stringResource(R.string.display_match_screen, screen.first, screen.second)
        else if (value == SessionDisplay.DEFAULT_RESOLUTION) stringResource(R.string.mode_res_default, "${dimensions.first}×${dimensions.second}")
        else "${dimensions.first}×${dimensions.second}"
    } + (custom to if (selected in choices) stringResource(R.string.mode_res_custom)
        else stringResource(R.string.mode_res_custom_value, size.first, size.second))
    ChoiceRow(host, "resolution", stringResource(R.string.display_resolution),
        currentSize?.let { stringResource(R.string.display_current_resolution, it.first, it.second) }
            ?: stringResource(R.string.common_applies_next_session),
        options, if (selected in choices) selected else custom, chipModifier = chipModifier,
        onPick = { value -> if (value == custom) editCustom = true else onPick(value) })
    if (editCustom) CustomResolutionDialog(size,
        onSave = { value -> editCustom = false; onPick("${value.first}x${value.second}") },
        onDismiss = { editCustom = false })
}
