package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.BottomDesktop

/** What the bottom screen shows while the desktop is not drawing: the session loading screen's throbber and a line of text. */
@Composable
internal fun BottomDesktopStatus(state: BottomDesktop.State, onStart: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val starting = state == BottomDesktop.State.STARTING
    Box(Modifier.fillMaxSize().background(colors.background).blockTouches(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            LogoThrobber(Modifier.width(if (starting) 96.dp else 76.dp), running = starting)
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(when (state) {
                    BottomDesktop.State.ENDED -> R.string.bottom_desktop_ended
                    BottomDesktop.State.FAILED -> R.string.bottom_desktop_failed
                    BottomDesktop.State.OFF -> R.string.bottom_desktop_off
                    else -> R.string.bottom_desktop_starting
                }),
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
            )
            if (!starting) {
                Spacer(Modifier.height(20.dp))
                PrimaryButton(stringResource(R.string.bottom_desktop_start), main = true, onClick = onStart)
            }
        }
    }
}
