package com.droiddeck.launcher.input

import com.droiddeck.launcher.ui.focusGlide
import com.droiddeck.launcher.ui.controllerBack
import com.droiddeck.launcher.ui.controllerConfirm
import com.droiddeck.launcher.ui.HeldKeys
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
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
    private var dualSense: DualSenseManager? = null
    private var heldPadKeys = IntArray(0)

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
                    var snap by remember { mutableStateOf(ControllerPrefs.read(context).snap) }
                    val firstFocus = remember { FocusRequester() }
                    var hasFocused by remember { mutableStateOf(false) }
                    val inputModeManager = LocalInputModeManager.current
                    LaunchedEffect(HeldKeys.focusWakeup) {
                        if (HeldKeys.focusWakeup > 0 && !hasFocused) {
                            if (inputModeManager.inputMode != InputMode.Keyboard) {
                                inputModeManager.requestInputMode(InputMode.Keyboard)
                            }
                            runCatching { firstFocus.requestFocus() }
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .onFocusChanged { hasFocused = it.hasFocus }
                            .controllerBack { finish() }
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(20.dp))
                            .padding(start = 5.dp, end = 5.dp, top = 8.dp, bottom = 5.dp),
                    ) {
                        Text(stringResource(R.string.ctrl_editor_drag), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.focusGroup()) {
                            EditorButton(stringResource(R.string.ctrl_editor_snap), false, selected = snap, modifier = Modifier.focusRequester(firstFocus)) {
                                snap = !snap
                                ControllerPrefs.setSnap(context, snap)
                                controls.setSnap(snap)
                            }
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
        }
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (dualSense == null && !com.droiddeck.launcher.session.SessionState.running) {
            dualSense = DualSenseManager(this).apply {
                onStateUpdated = { state ->
                    val next = LauncherPadKeys.held(state).toIntArray()
                    if (!next.contentEquals(heldPadKeys)) {
                        runOnUiThread { dispatchPadKeys(next) }
                    }
                }
                start()
            }
        }
    }

    override fun onPause() {
        releasePadKeys()
        dualSense?.stop()
        dualSense = null
        super.onPause()
    }

    private fun dispatchPadKeys(next: IntArray) {
        val now = android.os.SystemClock.uptimeMillis()
        for (code in heldPadKeys) {
            if (code !in next) dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        }
        for (code in next) {
            if (code !in heldPadKeys) dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        }
        heldPadKeys = next
    }

    private fun releasePadKeys() {
        val held = heldPadKeys
        if (held.isEmpty()) return
        heldPadKeys = IntArray(0)
        val now = android.os.SystemClock.uptimeMillis()
        for (code in held) dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) HeldKeys.confirm = event.action == KeyEvent.ACTION_DOWN
        if (event.action == KeyEvent.ACTION_DOWN) HeldKeys.focusWakeup++
        return super.dispatchKeyEvent(event)
    }

    @Composable
    private fun EditorButton(label: String, primary: Boolean, selected: Boolean? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val pal = LocalPalette.current
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = when {
                primary -> pal.onSignal
                selected == true -> pal.signal
                else -> MaterialTheme.colorScheme.onBackground
            },
            modifier = modifier.clip(RoundedCornerShape(50))
                .background(
                    when {
                        primary -> pal.signal
                        selected == true -> pal.signal.copy(alpha = 0.18f)
                        else -> Color.White.copy(alpha = 0.06f)
                    },
                )
                .focusGlide(RoundedCornerShape(50))
                .then(
                    if (selected == null) Modifier.clickable(onClick = onClick)
                    else Modifier.toggleable(value = selected, role = Role.Switch, onValueChange = { onClick() }),
                )
                .controllerConfirm(onClick = onClick)
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
