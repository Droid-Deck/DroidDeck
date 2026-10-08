package com.droiddeck.launcher.input

import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.onSignal
import com.droiddeck.launcher.ui.LocalPalette

class ControllerEditorActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.droiddeck.launcher.core.AppLanguage.applyTo(this, newBase)
    }

    private lateinit var controls: OnScreenControls

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = com.droiddeck.launcher.session.SessionPrefs.orientation(this)
        immersive()
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0B0F14.toInt())
        controls = OnScreenControls(this, null, editing = true)
        root.addView(controls, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val bar = ComposeView(this).apply {
            setContent {
                DroidDeckTheme {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(50))
                            .padding(start = 14.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                    ) {
                        Text(stringResource(R.string.ctrl_editor_drag), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
                        EditorButton(stringResource(R.string.ctrl_reset), false) { controls.resetLayout() }
                        EditorButton(stringResource(R.string.common_cancel), false) { finish() }
                        EditorButton(stringResource(R.string.ctrl_editor_save), true) {
                            controls.saveLayout()
                            finish()
                        }
                    }
                }
            }
        }
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
    }

    @Composable
    private fun EditorButton(label: String, primary: Boolean, onClick: () -> Unit) {
        val pal = LocalPalette.current
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (primary) pal.onSignal else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.clip(RoundedCornerShape(50))
                .background(if (primary) pal.signal else Color.White.copy(alpha = 0.06f))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
