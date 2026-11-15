package com.droiddeck.launcher.wallpaper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.AppUiPrefs
import com.droiddeck.launcher.ui.DroidDeckTheme

class WallpaperSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DroidDeckTheme(appScale = AppUiPrefs.scale(this)) {
                val ink = MaterialTheme.colorScheme.onBackground
                var fps by remember { mutableIntStateOf(WallPrefs.fps(this)) }
                var speed by remember { mutableFloatStateOf(WallPrefs.speed(this)) }
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Bottom) {
                Column(
                    Modifier.background(MaterialTheme.colorScheme.background.copy(alpha = 0.88f), RoundedCornerShape(20.dp))
                        .verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(getString(R.string.steam_wall_label), style = MaterialTheme.typography.titleLarge, color = ink)
                    Text(getString(R.string.wall_settings_fps), style = MaterialTheme.typography.titleMedium, color = ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WallPrefs.FPS_CHOICES.forEach { choice ->
                            FilterChip(
                                selected = fps == choice,
                                onClick = { fps = choice; WallPrefs.setFps(this@WallpaperSettingsActivity, choice) },
                                label = { Text(if (choice == WallPrefs.MATCH_SCREEN) getString(R.string.wall_settings_match) else "$choice") },
                            )
                        }
                    }
                    Text(getString(R.string.wall_settings_speed), style = MaterialTheme.typography.titleMedium, color = ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WallPrefs.SPEED_CHOICES.forEach { choice ->
                            FilterChip(
                                selected = speed == choice,
                                onClick = { speed = choice; WallPrefs.setSpeed(this@WallpaperSettingsActivity, choice) },
                                label = { Text(if (choice == 1f) "1x" else "${choice.toString().removeSuffix(".0")}x") },
                            )
                        }
                    }
                }
                }
            }
        }
    }
}
