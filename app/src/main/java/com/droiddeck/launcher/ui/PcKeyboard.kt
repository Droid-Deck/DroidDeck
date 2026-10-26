package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.PC_KEY_ROWS
import com.droiddeck.launcher.input.PC_MODIFIERS
import com.droiddeck.launcher.input.PcKey
import androidx.compose.ui.res.stringResource
import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MOD_OFF = 0
private const val MOD_ONCE = 1
private const val MOD_LOCKED = 2

@Composable
fun PcKeyboard(
    sendKey: (Int, Boolean) -> Unit,
    onAndroidKeyboard: () -> Unit,
    onClose: () -> Unit,
) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val mods = remember { mutableStateMapOf<Int, Int>() }
    var capsOn by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }
    val shifted = (mods[42] ?: MOD_OFF) != MOD_OFF || (mods[54] ?: MOD_OFF) != MOD_OFF

    fun releaseOnceModifiers() {
        for ((code, state) in mods.toMap()) if (state == MOD_ONCE) { sendKey(code, false); mods[code] = MOD_OFF }
    }
    fun press(key: PcKey) {
        if (key.code in PC_MODIFIERS) {
            when (mods[key.code] ?: MOD_OFF) {
                MOD_OFF -> { sendKey(key.code, true); mods[key.code] = MOD_ONCE }
                MOD_ONCE -> mods[key.code] = MOD_LOCKED
                else -> { sendKey(key.code, false); mods[key.code] = MOD_OFF }
            }
            return
        }
        sendKey(key.code, true)
    }
    fun release(key: PcKey) {
        if (key.code in PC_MODIFIERS) return
        sendKey(key.code, false)
        if (key.code == 58) capsOn = !capsOn
        releaseOnceModifiers()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { for ((code, state) in mods.toMap()) if (state != MOD_OFF) sendKey(code, false) }
    }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val rowHeight = ((maxHeight * 0.47f - 24.dp - 3.dp * 6 - 10.dp) / 6).coerceIn(24.dp, 44.dp)
        Box(
            Modifier.fillMaxWidth()
                .background(Color(0xE6101418), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .border(1.dp, pal.line2, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume(); waitForUpOrCancellation()?.consume() } }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(24.dp)) {
                    Text(stringResource(R.string.drawer_pc_keyboard), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    HeaderButton(stringResource(R.string.pckb_android), onAndroidKeyboard)
                    Spacer(Modifier.width(8.dp))
                    HeaderButton(stringResource(R.string.pckb_hide), onClose)
                }
                PC_KEY_ROWS.forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth().height(rowHeight)) {
                        row.forEachIndexed { i, key ->
                            val state = mods[key.code] ?: MOD_OFF
                            val lit = state != MOD_OFF || (key.code == 58 && capsOn)
                            val label = when {
                                key.labelRes != 0 -> stringResource(key.labelRes)
                                shifted && key.shifted != null -> key.shifted
                                key.label.length == 1 && key.label[0].isLetter() && (shifted xor capsOn) -> key.label.uppercase()
                                else -> key.label
                            }
                            KeyCap(
                                label, key.weight, lit, locked = state == MOD_LOCKED,
                                modifier = if (r == 0 && i == 0) Modifier.focusRequester(firstKey) else Modifier,
                                onDown = { press(key) }, onUp = { release(key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.KeyCap(
    label: String, weight: Float, lit: Boolean, locked: Boolean,
    modifier: Modifier, onDown: () -> Unit, onUp: () -> Unit,
) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    var down by remember { mutableStateOf(false) }
    val fill by animateColorAsState(
        when {
            down -> pal.signal.copy(alpha = 0.55f)
            lit -> pal.signal.copy(alpha = if (locked) 0.45f else 0.28f)
            else -> Color.White.copy(alpha = 0.07f)
        }, Motion.tw(160), label = "keyFill",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.weight(weight).fillMaxHeight()
            .background(fill, RoundedCornerShape(7.dp))
            .glideBorder(focused, RoundedCornerShape(7.dp), pal.signal, if (lit) pal.signal.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    down = true; onDown()
                    waitForUpOrCancellation()?.consume()
                    down = false; onUp()
                }
            }
            .onPreviewKeyEvent { e ->
                val kc = e.key.nativeKeyCode
                if (kc != KeyEvent.KEYCODE_BUTTON_A && kc != KeyEvent.KEYCODE_DPAD_CENTER && kc != KeyEvent.KEYCODE_ENTER) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> if (!down) { down = true; onDown() }
                    KeyEventType.KeyUp -> if (down) { down = false; onUp() }
                }
                true
            }
            .focusable(interactionSource = src),
    ) {
        Text(
            label, fontSize = if (label.length > 2) 11.sp else 15.sp, fontWeight = FontWeight.SemiBold,
            color = Color.White, textAlign = TextAlign.Center, maxLines = 1,
        )
    }
}

@Composable
private fun HeaderButton(text: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxHeight()
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(7.dp))
            .glideBorder(focused, RoundedCornerShape(7.dp), pal.signal, Color.White.copy(alpha = 0.14f))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 12.dp),
    ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
}
