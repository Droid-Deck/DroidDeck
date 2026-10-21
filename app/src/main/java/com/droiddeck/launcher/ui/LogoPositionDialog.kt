package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.LogoPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Steam's library hero is 3840 x 1240, so the preview keeps that shape. */
private const val HERO_RATIO = 3840f / 1240f

/** The gap Steam leaves between the pinned logo and the edge of the hero, as a share of the hero's width. */
private const val EDGE = 0.03f

@Composable
private fun LogoPosition.Pin.label(): String = stringResource(
    when (this) {
        LogoPosition.Pin.UPPER_LEFT -> R.string.logo_pos_upper_left
        LogoPosition.Pin.UPPER_CENTER -> R.string.logo_pos_upper_center
        LogoPosition.Pin.CENTER -> R.string.logo_pos_center
        LogoPosition.Pin.BOTTOM_LEFT -> R.string.logo_pos_bottom_left
        LogoPosition.Pin.BOTTOM_CENTER -> R.string.logo_pos_bottom_center
    },
)

private fun LogoPosition.Pin.alignment(): Alignment = when (this) {
    LogoPosition.Pin.UPPER_LEFT -> Alignment.TopStart
    LogoPosition.Pin.UPPER_CENTER -> Alignment.TopCenter
    LogoPosition.Pin.CENTER -> Alignment.Center
    LogoPosition.Pin.BOTTOM_LEFT -> Alignment.BottomStart
    LogoPosition.Pin.BOTTOM_CENTER -> Alignment.BottomCenter
}

@Composable
internal fun logoPositionSummary(value: LogoPosition.Value?): String =
    if (value == null) stringResource(R.string.logo_pos_default) else "${value.pin.label()} · ${value.widthPct}%"

/**
 * Where a game's logo sits on its background in Steam's library: the logo box is [Value.widthPct] x [Value.heightPct] of the
 * background, pinned to one of five places, and the logo is drawn as large as fits in it.
 */
@Composable
private fun LogoPreview(hero: File?, logo: File?, value: LogoPosition.Value) {
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(HERO_RATIO).clip(shape).background(Color(0xFF0E1218)).border(1.dp, LocalPalette.current.line, shape)) {
        if (hero != null) AsyncImage(model = hero, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (logo != null) {
            val edge = maxWidth * EDGE
            Box(
                Modifier.align(value.pin.alignment()).padding(edge)
                    .size((maxWidth - edge * 2) * (value.widthPct / 100f), (maxHeight - edge * 2) * (value.heightPct / 100f)),
            ) {
                AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, alignment = value.pin.alignment(), modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun PinChip(label: String, selected: Boolean, focus: FocusRequester?, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.then(if (focus != null) Modifier.focusRequester(focus) else Modifier).clip(shape)
            .background(if (selected) pal.signal.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.03f))
            .border(if (selected) 2.dp else 1.dp, if (selected) pal.signal else pal.line, shape)
            .glideBorder(hot, shape, pal.signal)
            .hoverable(src)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, role = Role.RadioButton, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = colors.onBackground, maxLines = 1)
    }
}

/** The "Adjust logo" screen: the game's background with its logo on it, where to pin the logo and how large to draw it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LogoPositionDialog(
    target: ArtTarget, hero: File?, logo: File?, start: LogoPosition.Value?,
    onBack: () -> Unit, onCloseAll: () -> Unit, onChanged: () -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val shown = rememberShown(onBack)
    val back = { shown.targetState = false }
    var value by remember { mutableStateOf(start ?: LogoPosition.DEFAULT) }
    val startPin = remember { value.pin }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon { first } }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val save: (LogoPosition.Value?) -> Unit = { next ->
        if (!busy) {
            busy = true
            scope.launch {
                val saved = withContext(Dispatchers.IO) { runCatching { target.setPosition(app, next) }.getOrDefault(false) }
                busy = false
                failed = !saved
                if (saved) {
                    onChanged()
                    back()
                }
            }
        }
    }
    AppDialog(shown, back, "logoPosition", wide = false, maxWidth = 460.dp) {
        DialogTitleRow(stringResource(R.string.art_adjust_logo), onClose = onCloseAll, leading = {
            SmallIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), colors.onBackground, onClick = back)
        })
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        ) {
            LogoPreview(hero, logo, value)
            Text(
                stringResource(if (logo == null) R.string.logo_pos_no_logo else R.string.logo_pos_hint),
                fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp),
            )
            Text(stringResource(R.string.logo_pos_position), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (pin in LogoPosition.Pin.values()) PinChip(pin.label(), pin == value.pin, if (pin == startPin) first else null) { value = value.copy(pin = pin) }
            }
            ValueSlider(
                value.widthPct, LogoPosition.MIN_PCT..LogoPosition.MAX_PCT, step = 1, enabled = true, label = stringResource(R.string.logo_pos_size),
                format = { "$it%" },
            ) { value = LogoPosition.sized(value.pin, it) }
            if (failed) Text(stringResource(R.string.logo_pos_failed), fontSize = 12.sp, color = colors.error, modifier = Modifier.padding(horizontal = 2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                SecondaryButton(stringResource(R.string.logo_pos_reset), enabled = !busy, compact = true) { save(null) }
                PrimaryButton(stringResource(R.string.logo_pos_save), enabled = !busy, compact = true) { save(value) }
            }
        }
    }
}
