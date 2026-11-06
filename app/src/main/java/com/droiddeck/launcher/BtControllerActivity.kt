package com.droiddeck.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.AppLanguage
import com.droiddeck.launcher.input.bt.BtGamepad
import com.droiddeck.launcher.input.bt.BtPadProfile
import com.droiddeck.launcher.input.bt.BtPadState
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.LocalPalette
import com.droiddeck.launcher.ui.PrimaryButton
import com.droiddeck.launcher.ui.SecondaryButton
import kotlin.math.hypot
import kotlin.math.roundToInt

class BtControllerActivity : ComponentActivity() {
    private val physical = BtPadState()
    private val touch = BtPadState()
    private val merged = BtPadState()
    private var keyButtons = 0
    private var hatButtons = 0
    private val main = Handler(Looper.getMainLooper())
    private val exitHold = Runnable { finish() }
    private var exitArmed = false
    private var profile by mutableStateOf(BtPadProfile.GENERIC)
    private var showTouch by mutableStateOf(true)
    private var permissionDenied by mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = prefs(this)
        profile = BtPadProfile.byId(prefs.getString(KEY_PROFILE, null))
        showTouch = prefs.getBoolean(KEY_TOUCH, true)
        val wanted = if (Build.VERSION.SDK_INT >= 31) NEARBY + LOCATION else LOCATION
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startPad() else requestPermissions(missing.toTypedArray(), REQUEST_NEARBY)
        setContent { DroidDeckTheme { Screen() } }
    }

    private fun openAppInfo() {
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
        }
    }

    override fun onResume() {
        super.onResume()
        if (permissionDenied && Build.VERSION.SDK_INT >= 31 && NEARBY.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            permissionDenied = false
            startPad()
        }
    }

    private fun startPad() {
        BtGamepad.start(this, profile)
        BtGamepad.openPairingWindow()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NEARBY) return
        val nearbyDenied = Build.VERSION.SDK_INT >= 31 && NEARBY.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        permissionDenied = nearbyDenied
        if (!nearbyDenied) startPad()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) return
        physical.set(BtPadState())
        keyButtons = 0
        hatButtons = 0
        push()
    }

    override fun onDestroy() {
        main.removeCallbacks(exitHold)
        if (isFinishing) BtGamepad.stop()
        super.onDestroy()
    }

    private fun pickProfile(p: BtPadProfile) {
        if (p == profile) return
        profile = p
        prefs(this).edit().putString(KEY_PROFILE, p.id).apply()
        BtGamepad.switchProfile(p)
    }

    private fun push() {
        physical.buttons = keyButtons or hatButtons
        merged.merge(physical, touch)
        BtGamepad.update(merged)
        val combo = BtPadState.SELECT or BtPadState.START
        val held = physical.buttons and combo == combo
        if (held && !exitArmed) main.postDelayed(exitHold, EXIT_HOLD_MS)
        else if (!held && exitArmed) main.removeCallbacks(exitHold)
        exitArmed = held
    }

    private fun isPad(event: KeyEvent): Boolean {
        val s = event.source
        return s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            KeyEvent.isGamepadButton(event.keyCode)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val bit = KEYS[event.keyCode]
        if (bit == null || event.keyCode in DPAD_KEYS && !isPad(event) && event.source and InputDevice.SOURCE_DPAD != InputDevice.SOURCE_DPAD) {
            return super.dispatchKeyEvent(event)
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> keyButtons = keyButtons or bit
            KeyEvent.ACTION_UP -> keyButtons = keyButtons and bit.inv()
        }
        push()
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        val device = event.device
        fun axis(code: Int): Float {
            val range = device?.getMotionRange(code, event.source) ?: return 0f
            val v = event.getAxisValue(code)
            return if (Math.abs(v) > maxOf(range.flat, 0.04f)) v else 0f
        }
        val hasZ = device?.getMotionRange(MotionEvent.AXIS_Z, event.source) != null
        physical.lx = axis(MotionEvent.AXIS_X); physical.ly = axis(MotionEvent.AXIS_Y)
        physical.rx = axis(if (hasZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX)
        physical.ry = axis(if (hasZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY)
        physical.lt = maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE))
        physical.rt = maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS))
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        hatButtons = (if (hy < -0.5f) BtPadState.UP else 0) or (if (hy > 0.5f) BtPadState.DOWN else 0) or
            (if (hx < -0.5f) BtPadState.LEFT else 0) or (if (hx > 0.5f) BtPadState.RIGHT else 0)
        push()
        return true
    }

    private fun touchButton(bit: Int, down: Boolean) {
        touch.buttons = if (down) touch.buttons or bit else touch.buttons and bit.inv()
        if (bit == BtPadState.LT) touch.lt = if (down) 1f else 0f
        if (bit == BtPadState.RT) touch.rt = if (down) 1f else 0f
        push()
    }

    private fun touchStick(left: Boolean, x: Float, y: Float) {
        if (left) { touch.lx = x; touch.ly = y } else { touch.rx = x; touch.ry = y }
        push()
    }

    @Composable
    private fun Screen() {
        val status by BtGamepad.status.collectAsState()
        val colors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Panel(status)
            if (showTouch) {
                TouchPad(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp), status)
            } else {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Instructions(status, Modifier.widthIn(max = 480.dp))
                    Text(stringResource(R.string.btpad_physical_hint), fontSize = 15.sp, color = colors.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    private fun Panel(status: BtGamepad.Status) {
        val colors = MaterialTheme.colorScheme
        val pal = LocalPalette.current
        val connected = status.phase == BtGamepad.Phase.CONNECTED
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 600.dp
            val types: @Composable (Modifier) -> Unit = { m ->
                Row(m.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BtPadProfile.entries.forEach { p -> Chip(profileLabel(p), p == profile) { pickProfile(p) } }
                    Chip(stringResource(R.string.btpad_touch), showTouch) {
                        showTouch = !showTouch
                        prefs(this@BtControllerActivity).edit().putBoolean(KEY_TOUCH, showTouch).apply()
                    }
                }
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(status.lightbar?.let { Color(it) } ?: if (connected) pal.signal else colors.onSurfaceVariant.copy(alpha = 0.4f)))
                    Column(if (narrow) Modifier.weight(1f) else Modifier.widthIn(max = 220.dp)) {
                        Text(shortStatus(status), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(if (connected) R.string.btpad_exit_hint else R.string.btpad_switch_hint), fontSize = 11.sp,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!narrow) types(Modifier.weight(1f))
                    if (permissionDenied) PrimaryButton(stringResource(R.string.btpad_permission_open), compact = true) { openAppInfo() }
                    else if (connected) SecondaryButton(stringResource(R.string.btpad_disconnect), compact = true) { BtGamepad.disconnect() }
                    else PrimaryButton(stringResource(R.string.btpad_pair), compact = true, enabled = status.phase == BtGamepad.Phase.READY) { BtGamepad.openPairingWindow() }
                    SecondaryButton(stringResource(R.string.btpad_close), compact = true) { finish() }
                }
                if (narrow) types(Modifier.padding(top = 6.dp))
            }
        }
    }

    @Composable
    private fun shortStatus(status: BtGamepad.Status): String =
        if (status.phase == BtGamepad.Phase.READY && !permissionDenied) stringResource(R.string.btpad_status_waiting) else statusText(status)

    @Composable
    private fun Instructions(status: BtGamepad.Status, modifier: Modifier, size: androidx.compose.ui.unit.TextUnit = 14.sp) {
        if (status.phase == BtGamepad.Phase.CONNECTED) return
        Text(statusText(status), fontSize = size, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = modifier)
    }

    @Composable
    private fun statusText(status: BtGamepad.Status): String = if (permissionDenied) stringResource(R.string.btpad_status_permission) else when (status.phase) {
        BtGamepad.Phase.OFF, BtGamepad.Phase.STARTING -> stringResource(R.string.btpad_status_starting)
        BtGamepad.Phase.BLUETOOTH_OFF -> stringResource(R.string.btpad_status_bt_off)
        BtGamepad.Phase.UNSUPPORTED -> stringResource(R.string.btpad_status_unsupported)
        BtGamepad.Phase.READY -> stringResource(R.string.btpad_status_ready, status.name ?: "")
        BtGamepad.Phase.CONNECTING -> stringResource(R.string.btpad_status_connecting, status.host ?: "")
        BtGamepad.Phase.CONNECTED -> stringResource(R.string.btpad_status_connected, status.host ?: "")
    }

    @Composable
    private fun profileLabel(p: BtPadProfile) = stringResource(when (p) {
        BtPadProfile.GENERIC -> R.string.btpad_profile_generic
        BtPadProfile.XBOX -> R.string.btpad_profile_xbox
        BtPadProfile.DUALSHOCK4 -> R.string.btpad_profile_ds4
        BtPadProfile.DUALSENSE -> R.string.btpad_profile_dualsense
        BtPadProfile.STEAM -> R.string.btpad_profile_steam
    })

    @Composable
    private fun Chip(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        val pal = LocalPalette.current
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (on) colors.background else if (enabled) colors.onBackground else colors.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).background(if (on) pal.signal else colors.surface)
                .border(1.dp, if (on) pal.signal else pal.line2, CircleShape)
                .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp))
    }

    @Composable
    private fun TouchPad(modifier: Modifier, status: BtGamepad.Status) {
        val lightbar = status.lightbar
        val sony = profile == BtPadProfile.DUALSHOCK4 || profile == BtPadProfile.DUALSENSE
        val steam = profile == BtPadProfile.STEAM
        BoxWithConstraints(modifier) {
            val w = maxWidth
            val h = maxHeight
            val stick = minOf(h * (if (steam) 0.38f else 0.46f), w * 0.2f)
            val face = stick * 0.4f
            val cluster = face * 3f
            val shoulderW = minOf(w * 0.09f, stick * 0.75f)
            val shoulderH = minOf(h * 0.13f, 44.dp)
            val small = shoulderH * 0.85f
            @Composable
            fun at(fx: Float, fy: Float, bw: Dp, bh: Dp, content: @Composable () -> Unit) {
                val x = (w * fx - bw / 2).coerceIn(0.dp, w - bw)
                val y = (h * fy - bh / 2).coerceIn(0.dp, h - bh)
                Box(Modifier.offset(x, y).size(bw, bh), contentAlignment = Alignment.Center) { content() }
            }
            val shoulderY = shoulderH / h / 2f
            at(0.0f, shoulderY, shoulderW, shoulderH) { PadButton(if (sony) "L2" else "LT", BtPadState.LT, shoulderW, shoulderH) }
            val second = (shoulderW * 1.5f + 8.dp) / w
            at(second, shoulderY, shoulderW, shoulderH) { PadButton(if (sony) "L1" else "LB", BtPadState.LB, shoulderW, shoulderH) }
            at(1f - second, shoulderY, shoulderW, shoulderH) { PadButton(if (sony) "R1" else "RB", BtPadState.RB, shoulderW, shoulderH) }
            at(1.0f, shoulderY, shoulderW, shoulderH) { PadButton(if (sony) "R2" else "RT", BtPadState.RT, shoulderW, shoulderH) }
            val menuW = shoulderW * 0.95f
            val menu = listOfNotNull(
                Triple(if (sony) "Share" else "View", BtPadState.SELECT, null),
                Triple(if (sony) "PS" else if (steam) "Steam" else "Home", BtPadState.GUIDE, lightbar),
                if (steam) Triple("···", BtPadState.SHARE, null) else null,
                Triple(if (sony) "Options" else "Menu", BtPadState.START, null),
            )
            menu.forEachIndexed { i, (label, bit, tint) ->
                val fx = 0.5f + (i - (menu.size - 1) / 2f) * ((menuW + 8.dp) / w)
                at(fx, shoulderY, menuW, small) { PadButton(label, bit, menuW, small, tint = tint?.let { Color(it) }) }
            }
            val steamPad = minOf(h * 0.4f, w * 0.2f)
            val infoW = if (steam) w * 0.4f - steamPad - 12.dp else w * 0.3f
            val infoH = if (steam) h * 0.3f else h * 0.22f
            at(0.5f, if (steam) 0.72f else 0.3f, infoW, infoH) { Instructions(status, Modifier.fillMaxWidth(), if (steam) 12.sp else 14.sp) }
            if (steam) {
                val rearY = (shoulderH * 1.6f) / h
                at(0.0f, rearY, shoulderW, small) { PadButton("L5", BtPadState.L5, shoulderW, small) }
                at(second, rearY, shoulderW, small) { PadButton("L4", BtPadState.L4, shoulderW, small) }
                at(1f - second, rearY, shoulderW, small) { PadButton("R4", BtPadState.R4, shoulderW, small) }
                at(1.0f, rearY, shoulderW, small) { PadButton("R5", BtPadState.R5, shoulderW, small) }
                val pad = steamPad
                at(0.11f, 0.5f, stick, stick) { Stick(stick, left = true) }
                at(0.89f, 0.5f, stick, stick) { Stick(stick, left = false) }
                at(0.3f, 0.34f, cluster, cluster) { Cross(face) }
                at(0.7f, 0.34f, cluster, cluster) { Diamond(face, false) }
                at(0.3f, 1f, pad, pad) { Trackpad(pad, left = true) }
                at(0.7f, 1f, pad, pad) { Trackpad(pad, left = false) }
                at(0.44f, 0.94f, menuW, small) { PadButton("L3", BtPadState.L3, menuW, small) }
                at(0.56f, 0.94f, menuW, small) { PadButton("R3", BtPadState.R3, menuW, small) }
            } else {
                at(0.12f, 0.45f, stick, stick) { Stick(stick, left = true) }
                at(0.88f, 0.45f, cluster, cluster) { Diamond(face, sony) }
                at(0.32f, 0.78f, cluster, cluster) { Cross(face) }
                at(0.68f, 0.78f, stick, stick) { Stick(stick, left = false) }
                at(0.42f, 0.5f, menuW, small) { PadButton("L3", BtPadState.L3, menuW, small) }
                at(0.58f, 0.5f, menuW, small) { PadButton("R3", BtPadState.R3, menuW, small) }
                if (sony) at(0.5f, 0.68f, menuW * 1.6f, small * 1.4f) { PadButton("Touchpad", BtPadState.TOUCHPAD, menuW * 1.6f, small * 1.4f) }
            }
        }
    }

    @Composable
    private fun Trackpad(size: Dp, left: Boolean) {
        val colors = MaterialTheme.colorScheme
        val pal = LocalPalette.current
        var dot by remember { mutableStateOf<Offset?>(null) }
        val px = with(LocalDensity.current) { size.toPx() }
        Box(
            Modifier.size(size).clip(RoundedCornerShape(18.dp)).background(colors.surface).border(1.dp, pal.line2, RoundedCornerShape(18.dp))
                .pointerInput(left) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val started = first.uptimeMillis
                        fun norm(p: Offset) = Offset((p.x / this.size.width * 2f - 1f).coerceIn(-1f, 1f), (p.y / this.size.height * 2f - 1f).coerceIn(-1f, 1f))
                        var moved = 0f
                        var last = norm(first.position)
                        dot = last
                        touchPad(left, last, true)
                        var upTime = started
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                                if (!change.pressed) { upTime = change.uptimeMillis; break }
                                change.consume()
                                val p = norm(change.position)
                                moved += (p - last).getDistance()
                                last = p
                                dot = p
                                touchPad(left, p, true)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            dot = null
                            touchPad(left, last, false)
                            throw e
                        }
                        dot = null
                        if (upTime - started < TAP_MS && moved < 0.15f) clickPad(left, last)
                        else touchPad(left, last, false)
                    }
                },
        ) {
            dot?.let { d ->
                val r = px * 0.08f
                Box(Modifier.offset { IntOffset(((d.x + 1f) / 2f * px - r).roundToInt(), ((d.y + 1f) / 2f * px - r).roundToInt()) }
                    .size(size * 0.16f).clip(CircleShape).background(pal.signal))
            }
        }
    }

    private fun touchPad(left: Boolean, p: Offset, down: Boolean) {
        val bit = if (left) BtPadState.LPAD_TOUCH else BtPadState.RPAD_TOUCH
        touch.buttons = if (down) touch.buttons or bit else touch.buttons and bit.inv()
        if (left) { touch.lpx = p.x; touch.lpy = p.y } else { touch.rpx = p.x; touch.rpy = p.y }
        push()
    }

    private fun clickPad(left: Boolean, p: Offset) {
        val click = if (left) BtPadState.LPAD_CLICK else BtPadState.RPAD_CLICK
        touch.buttons = touch.buttons or click
        touchPad(left, p, true)
        main.postDelayed({
            touch.buttons = touch.buttons and click.inv()
            touchPad(left, p, false)
        }, CLICK_MS)
    }

    @Composable
    private fun Cross(size: Dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PadButton("▲", BtPadState.UP, size, size)
            Row {
                PadButton("◀", BtPadState.LEFT, size, size)
                Spacer(Modifier.size(size))
                PadButton("▶", BtPadState.RIGHT, size, size)
            }
            PadButton("▼", BtPadState.DOWN, size, size)
        }
    }

    @Composable
    private fun Diamond(size: Dp, sony: Boolean) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PadButton(if (sony) "△" else "Y", BtPadState.Y, size, size, round = true)
            Row {
                PadButton(if (sony) "□" else "X", BtPadState.X, size, size, round = true)
                Spacer(Modifier.size(size))
                PadButton(if (sony) "○" else "B", BtPadState.B, size, size, round = true)
            }
            PadButton(if (sony) "✕" else "A", BtPadState.A, size, size, round = true)
        }
    }

    @Composable
    private fun PadButton(label: String, bit: Int, width: Dp, height: Dp, round: Boolean = false, tint: Color? = null) {
        val colors = MaterialTheme.colorScheme
        val pal = LocalPalette.current
        var down by remember { mutableStateOf(false) }
        val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(width, height).clip(shape)
                .background(if (down) pal.signal else tint?.copy(alpha = 0.35f) ?: colors.surface)
                .border(1.dp, pal.line2, shape)
                .pointerInput(bit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        down = true; touchButton(bit, true)
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            down = false; touchButton(bit, false)
                        }
                    }
                },
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (down) colors.background else colors.onBackground)
        }
    }

    @Composable
    private fun Stick(size: Dp, left: Boolean) {
        val colors = MaterialTheme.colorScheme
        val pal = LocalPalette.current
        var knob by remember { mutableStateOf(Offset.Zero) }
        val px = with(LocalDensity.current) { size.toPx() }
        val knobSize = size * 0.42f
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).clip(CircleShape).background(colors.surface).border(1.dp, pal.line2, CircleShape)
                .pointerInput(left) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val radius = this.size.width / 2f
                        val center = Offset(radius, this.size.height / 2f)
                        fun move(p: Offset) {
                            var d = (p - center) / (radius * 0.8f)
                            val len = hypot(d.x, d.y)
                            if (len > 1f) d /= len
                            knob = d
                            touchStick(left, d.x, d.y)
                        }
                        move(first.position)
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                move(change.position)
                            }
                        } finally {
                            knob = Offset.Zero
                            touchStick(left, 0f, 0f)
                        }
                    }
                },
        ) {
            val travel = px * 0.29f
            Box(Modifier.offset { IntOffset((knob.x * travel).roundToInt(), (knob.y * travel).roundToInt()) }
                .size(knobSize).clip(CircleShape).background(if (knob != Offset.Zero) pal.signal else colors.onSurfaceVariant.copy(alpha = 0.5f)))
        }
    }

    companion object {
        private const val EXIT_HOLD_MS = 1_500L
        private const val TAP_MS = 200L
        private const val CLICK_MS = 90L
        private const val REQUEST_NEARBY = 41
        private val NEARBY = listOf("android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN")
        private val LOCATION = listOf("android.permission.ACCESS_FINE_LOCATION")
        private const val KEY_PROFILE = "btpad_profile"
        private const val KEY_TOUCH = "btpad_touch"

        private fun prefs(c: Context) = c.getSharedPreferences("trinkets", Context.MODE_PRIVATE)

        private val DPAD_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

        private val KEYS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to BtPadState.A,
            KeyEvent.KEYCODE_BUTTON_B to BtPadState.B,
            KeyEvent.KEYCODE_BUTTON_X to BtPadState.X,
            KeyEvent.KEYCODE_BUTTON_Y to BtPadState.Y,
            KeyEvent.KEYCODE_BUTTON_L1 to BtPadState.LB,
            KeyEvent.KEYCODE_BUTTON_R1 to BtPadState.RB,
            KeyEvent.KEYCODE_BUTTON_L2 to BtPadState.LT,
            KeyEvent.KEYCODE_BUTTON_R2 to BtPadState.RT,
            KeyEvent.KEYCODE_BUTTON_SELECT to BtPadState.SELECT,
            KeyEvent.KEYCODE_BUTTON_START to BtPadState.START,
            KeyEvent.KEYCODE_BUTTON_MODE to BtPadState.GUIDE,
            KeyEvent.KEYCODE_BUTTON_THUMBL to BtPadState.L3,
            KeyEvent.KEYCODE_BUTTON_THUMBR to BtPadState.R3,
            KeyEvent.KEYCODE_BUTTON_C to BtPadState.L4,
            KeyEvent.KEYCODE_BUTTON_Z to BtPadState.R4,
            KeyEvent.KEYCODE_BUTTON_1 to BtPadState.L4,
            KeyEvent.KEYCODE_BUTTON_2 to BtPadState.R4,
            KeyEvent.KEYCODE_BUTTON_3 to BtPadState.L5,
            KeyEvent.KEYCODE_BUTTON_4 to BtPadState.R5,
            KeyEvent.KEYCODE_DPAD_UP to BtPadState.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to BtPadState.DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to BtPadState.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to BtPadState.RIGHT,
        )
    }
}
