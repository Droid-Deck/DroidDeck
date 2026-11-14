package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs

@Composable
internal fun PenSettingsGroup(
    host: MenuHost, pen: SessionPrefs.Pen, auto: String, desktop: Boolean, note: String? = null,
    track: (String) -> Modifier = { Modifier }, onPick: (key: String, value: String) -> Unit,
) {
    SettingsGroup(stringResource(R.string.mode_pen)) {
        ChoiceRow(host, "pen", stringResource(R.string.mode_pen), null,
            listOf(SessionPrefs.PEN_AUTO to auto, SessionPrefs.PEN_MOUSE to stringResource(R.string.mode_pen_mouse),
                SessionPrefs.PEN_TOUCH to stringResource(R.string.mode_pen_touch)),
            pen.mode, note = note, chipModifier = track("pen")) { onPick("pen", it) }
        if (pen.mode != SessionPrefs.PEN_TOUCH) ChoiceRow(host, "pen-button", stringResource(R.string.mode_pen_button), null,
            listOf(SessionPrefs.PEN_BUTTON_RIGHT to stringResource(R.string.gesture_right_click),
                SessionPrefs.PEN_BUTTON_MIDDLE to stringResource(R.string.gesture_middle_click),
                SessionPrefs.PEN_BUTTON_OFF to stringResource(R.string.widgets_off)),
            pen.button, chipModifier = track("pen-button")) { onPick("penButton", it) }
        if (desktop && pen.mode == SessionPrefs.PEN_AUTO) ChoiceRow(host, "pen-pressure", stringResource(R.string.mode_pen_pressure), null,
            listOf(SessionPrefs.PEN_PRESSURE_SOFT to stringResource(R.string.mode_pen_pressure_soft),
                SessionPrefs.PEN_PRESSURE_NORMAL to stringResource(R.string.mode_pen_pressure_normal),
                SessionPrefs.PEN_PRESSURE_FIRM to stringResource(R.string.mode_pen_pressure_firm)),
            pen.pressure, chipModifier = track("pen-pressure")) { onPick("penPressure", it) }
    }
}
