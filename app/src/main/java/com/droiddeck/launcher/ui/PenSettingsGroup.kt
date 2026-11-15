package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs

@Composable
internal fun PenSettingsGroup(
    host: MenuHost, pen: SessionPrefs.Pen, track: (String) -> Modifier = { Modifier },
    onPick: (key: String, value: String) -> Unit,
) {
    SettingsGroup(stringResource(R.string.mode_pen)) {
        ChoiceRow(host, "pen", stringResource(R.string.mode_pen), null,
            listOf(SessionPrefs.PEN_MOUSE to stringResource(R.string.mode_pen_mouse),
                SessionPrefs.PEN_TOUCH to stringResource(R.string.mode_pen_touch)),
            pen.mode, chipModifier = track("pen")) { onPick("pen", it) }
        if (pen.mode != SessionPrefs.PEN_TOUCH) ChoiceRow(host, "pen-button", stringResource(R.string.mode_pen_button), null,
            listOf(SessionPrefs.PEN_BUTTON_RIGHT to stringResource(R.string.gesture_right_click),
                SessionPrefs.PEN_BUTTON_MIDDLE to stringResource(R.string.gesture_middle_click),
                SessionPrefs.PEN_BUTTON_OFF to stringResource(R.string.widgets_off)),
            pen.button, chipModifier = track("pen-button")) { onPick("penButton", it) }
    }
}
