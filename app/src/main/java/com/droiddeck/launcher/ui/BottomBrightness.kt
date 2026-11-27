package com.droiddeck.launcher.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs

/** The bottom screen's own brightness, in percent. It never goes below [MIN] so the slider cannot leave the screen dark. */
internal object BottomBrightness {
    const val MIN = 5
    const val STEP = 5

    /** The window brightness to use while the screen is on, or null to follow the system. */
    fun level(context: Context): Float? = SessionPrefs.bottomBrightness(context)?.let { clamp(it) / 100f }

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, 100)

    /** Where the slider starts: the saved level, else the system brightness as a percentage. */
    fun initial(context: Context): Int = clamp(SessionPrefs.bottomBrightness(context) ?: runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
    }.getOrDefault(60))

    /** Auto-dim never brightens a screen the user set darker. */
    fun dimmed(context: Context, dim: Float): Float = level(context)?.let { minOf(it, dim) } ?: dim
}

/** A dropdown under the bottom bar: a brightness slider and the screen-off button. A tap outside closes it. */
@Composable
internal fun BrightnessDrop(percent: Int, onChange: (Int) -> Unit, onScreenOff: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(top = BAR_HEIGHT, start = 12.dp, end = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().testTag("bottom-brightness").clip(shape).background(colors.surface).border(1.dp, pal.line, shape)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.LightMode, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
            ValueSlider(
                percent, BottomBrightness.MIN..100, BottomBrightness.STEP, true, stringResource(R.string.bottom_brightness), { "$it%" },
                modifier = Modifier.weight(1f), track = 32.dp, onChange = onChange,
            )
            BarButton(stringResource(R.string.bottom_screen_off), Modifier.testTag("bottom-brightness-off"), vector = Icons.Filled.DarkMode,
                onClick = onScreenOff)
        }
    }
}
