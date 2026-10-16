package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.droiddeck.launcher.R
import com.droiddeck.launcher.glasses.Glasses
import com.droiddeck.launcher.glasses.GlassesRendering
import com.droiddeck.launcher.glasses.GlassesSettings
import com.droiddeck.launcher.glasses.GlassesState
import com.droiddeck.launcher.handtracking.HandTracking
import java.util.Locale

internal val GlassesIcon: ImageVector by lazy {
    ImageVector.Builder("Glasses", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(2.5f, 10f); lineTo(21.5f, 10f)
            moveTo(3.2f, 10f); lineTo(3.8f, 15.2f); quadTo(4f, 17f, 6f, 17f); lineTo(8.6f, 17f); quadTo(10.2f, 17f, 10.6f, 15.4f); lineTo(11.2f, 12.6f)
            quadTo(12f, 11.6f, 12.8f, 12.6f); lineTo(13.4f, 15.4f); quadTo(13.8f, 17f, 15.4f, 17f); lineTo(18f, 17f); quadTo(20f, 17f, 20.2f, 15.2f); lineTo(20.8f, 10f)
            moveTo(2.5f, 10f); lineTo(1.8f, 7.6f)
            moveTo(21.5f, 10f); lineTo(22.2f, 7.6f)
        }
    }.build()
}

@Composable
internal fun rememberGlasses(): Pair<GlassesState, GlassesSettings> {
    val g by Glasses.state.collectAsState()
    val s by Glasses.settings.collectAsState()
    return g to s
}

internal object GlassesTab {
    const val DISPLAY = "display"
    const val HARDWARE = "hardware"
    const val TRACKING = "tracking"
}

@Composable
internal fun glassesTabs(g: GlassesState): List<Pair<String, String>> = buildList {
    add(GlassesTab.DISPLAY to stringResource(R.string.glasses_display))
    if (g.canControl) {
        if (g.hardwareControls) add(GlassesTab.HARDWARE to stringResource(R.string.glasses_hardware))
        add(GlassesTab.TRACKING to stringResource(R.string.glasses_tracking))
    }
}

@Composable
internal fun GlassesGroups(
    host: MenuHost,
    g: GlassesState,
    s: GlassesSettings,
    track: (String) -> Modifier = { Modifier },
    onRecenter: (() -> Unit)? = null,
    inSession: Boolean = true,
    outputActive: Boolean = inSession,
    steamVrRunning: Boolean = false,
    tab: String? = null,
    steamVr: @Composable () -> Unit = {},
) {
    val display = tab == null || tab == GlassesTab.DISPLAY
    val picture = GlassesRendering.picture(s, g.sbs, steamVrRunning)
    val usesGlasses = GlassesRendering.usesGlasses(g, outputActive, steamVrRunning)
    val virtualScreen = usesGlasses && picture == GlassesRendering.Picture.VIRTUAL_SCREEN
    val directView = usesGlasses && picture == GlassesRendering.Picture.STEREO
    if (display) SettingsGroup(g.model) {
        if (g.experimental) SettingsRow(stringResource(R.string.glasses_control), stringResource(R.string.glasses_xreal_experimental)) {}
        val status = if (g.hasDisplay) stringResource(R.string.glasses_status, g.width, g.height, kotlin.math.round(g.hz).toInt(),
            stringResource(if (g.sbs) R.string.glasses_mode_3d else R.string.glasses_mode_2d)) else null
        when {
            g.noSignal -> SettingsRow(stringResource(R.string.glasses_output), stringResource(R.string.glasses_no_signal)) {}
            !g.controllable -> {
                if (status != null) SettingsRow(stringResource(R.string.glasses_output), status) {}
                SettingsRow(stringResource(R.string.glasses_control), g.controlError ?: stringResource(R.string.glasses_no_control)) {}
            }
            g.permission == Glasses.Permission.ASKING ->
                SettingsRow(stringResource(R.string.glasses_control), stringResource(R.string.glasses_asking)) {}
            !g.connected -> ActionRow(stringResource(R.string.glasses_control),
                g.controlError ?: stringResource(if (g.permission == Glasses.Permission.DENIED) R.string.glasses_denied else R.string.glasses_allow_hint),
                stringResource(R.string.glasses_allow), { Glasses.requestControl() })
            status != null -> SettingsRow(stringResource(R.string.glasses_output), status) {}
        }
        ToggleRow(host, "glasses-game", stringResource(R.string.glasses_game_on), stringResource(R.string.glasses_game_on_hint),
            s.gameOnGlasses, chipModifier = track("glasses-game")) { Glasses.setGameOnGlasses(it) }
    }
    if (display && (g.canControl || g.hasDisplay)) SettingsGroup(stringResource(R.string.glasses_display)) {
        if (g.canControl) {
            if (!g.sbs) ChoiceRow(host, "glasses-hz", stringResource(R.string.glasses_refresh), null,
                listOf(60, 90, 120).map { it to "$it Hz" }, s.refreshHz, chipModifier = track("glasses-hz")) { Glasses.setRefreshHz(it) }
            if (g.sbsSupport == GlassesState.SBS_FAILS && !g.sbs) {
                ActionRow(stringResource(R.string.glasses_3d), stringResource(R.string.glasses_3d_failed),
                    stringResource(R.string.stores_dl_retry), { Glasses.set3d(true) })
            } else {
                if (inSession || g.sbs) ToggleRow(host, "glasses-3d", stringResource(R.string.glasses_3d), stringResource(R.string.glasses_3d_hint),
                    g.sbs, chipModifier = track("glasses-3d")) { Glasses.set3d(it) }
                if (g.sbs) {
                    if (g.sbs90) ChoiceRow(host, "glasses-3d-hz", stringResource(R.string.glasses_3d_refresh), null,
                        listOf(60, 90).map { it to "$it Hz" }, s.sbsHz, chipModifier = track("glasses-3d-hz")) { Glasses.setSbsHz(it) }
                    else SettingsRow(stringResource(R.string.glasses_3d_refresh), "60 Hz") {}
                }
            }
        }
        ToggleRow(host, "glasses-auto3d", stringResource(R.string.glasses_auto3d), stringResource(R.string.glasses_auto3d_hint),
            s.auto3d, chipModifier = track("glasses-auto3d")) { Glasses.setAuto3d(it) }
        ChoiceRow(host, "glasses-picture", stringResource(R.string.glasses_picture), stringResource(R.string.glasses_picture_hint),
            listOf(
                GlassesSettings.PICTURE_AUTO to stringResource(R.string.glasses_picture_auto),
                GlassesSettings.PICTURE_SCREEN to stringResource(R.string.glasses_picture_screen),
                GlassesSettings.PICTURE_SBS to stringResource(R.string.glasses_picture_sbs),
            ), s.picture3d, chipModifier = track("glasses-picture"), hintLines = 2) { Glasses.setPicture3d(it) }
        when {
            !inSession -> SettingsRow(stringResource(R.string.glasses_picture_screen), stringResource(R.string.glasses_screen_next_session)) {}
            !usesGlasses -> SettingsRow(stringResource(R.string.glasses_picture_screen), stringResource(R.string.glasses_screen_no_output)) {}
            picture == GlassesRendering.Picture.FLAT -> ActionRow(
                stringResource(R.string.glasses_picture_screen), stringResource(R.string.glasses_screen_flat_hint),
                stringResource(R.string.glasses_screen_use), { Glasses.setPicture3d(GlassesSettings.PICTURE_SCREEN) })
            directView -> SettingsRow(stringResource(R.string.glasses_picture_screen),
                stringResource(if (steamVrRunning) R.string.glasses_screen_direct_hint else R.string.glasses_screen_sbs_hint)) {}
        }
        if (virtualScreen || (!inSession && s.picture3d != GlassesSettings.PICTURE_SBS)) {
            SliderRow(stringResource(R.string.glasses_screen_size), null, s.screenWidthDm, 5..60, step = 1,
                format = { String.format(Locale.US, "%.1f m", it / 10f) }, modifier = track("glasses-width")) { Glasses.setScreenWidthDm(it) }
            SliderRow(stringResource(R.string.glasses_screen_distance), null, s.screenDistanceDm, 5..100, step = 1,
                format = { String.format(Locale.US, "%.1f m", it / 10f) }, modifier = track("glasses-distance")) { Glasses.setScreenDistanceDm(it) }
        }
        steamVr()
        if ((directView && steamVrRunning) || !inSession) SliderRow(stringResource(R.string.glasses_vr_fov), stringResource(R.string.glasses_vr_fov_hint), s.vrFovDeg,
            GlassesSettings.VR_FOV_MIN..GlassesSettings.VR_FOV_MAX, step = 1,
            format = { "$it°" }, modifier = track("glasses-vr-fov")) { Glasses.setVrFovDeg(it) }
    }
    if ((tab == null || tab == GlassesTab.HARDWARE) && g.canControl && g.hardwareControls) SettingsGroup(stringResource(R.string.glasses_hardware)) {
        if (g.brightnessMax > 0) SliderRow(stringResource(R.string.glasses_brightness), null, g.brightness.coerceAtLeast(0), 0..g.brightnessMax, step = 1,
            modifier = track("glasses-brightness")) { Glasses.setBrightness(it) }
        if (g.volumeMax > 0) SliderRow(stringResource(R.string.glasses_volume), null, g.volume.coerceAtLeast(0), 0..g.volumeMax, step = 1,
            modifier = track("glasses-volume")) { Glasses.setVolume(it) }
        if (g.hasSunblock) ToggleRow(host, "glasses-sunblock", stringResource(R.string.glasses_sunblock), stringResource(R.string.glasses_sunblock_hint),
            g.sunblock, chipModifier = track("glasses-sunblock")) { Glasses.setSunblock(it) }
    }
    if ((tab == null || tab == GlassesTab.TRACKING) && g.canControl) SettingsGroup(stringResource(R.string.glasses_tracking)) {
        val trackingHint = when {
            inSession && !usesGlasses -> R.string.glasses_screen_no_output
            inSession && picture == GlassesRendering.Picture.FLAT -> R.string.glasses_tracking_flat_hint
            inSession && directView && !steamVrRunning -> R.string.glasses_tracking_sbs_hint
            else -> R.string.glasses_head_tracking_hint
        }
        ToggleRow(host, "glasses-tracking", stringResource(R.string.glasses_head_tracking), stringResource(trackingHint),
            s.headTracking, chipModifier = track("glasses-tracking")) { Glasses.setHeadTracking(it) }
        if (onRecenter != null && s.headTracking && (virtualScreen || (directView && steamVrRunning))) ActionRow(stringResource(R.string.glasses_recenter), null,
            stringResource(R.string.cardboard_recenter_button), onRecenter)
        val calibration by Glasses.calibration.collectAsState()
        if (calibration.supported) GlassesCalibrationControls(calibration,
            onStart = Glasses::startCalibration, onCancel = Glasses::cancelCalibration,
            onReset = Glasses::resetCalibration, onDone = { onRecenter?.invoke() })
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val hands by HandTracking.settings.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { HandTracking.load(context) }
    if ((tab == null || tab == GlassesTab.TRACKING) && g.canControl && hands.usable) SettingsGroup(stringResource(R.string.hand_camera_group)) {
        HandCameraRows(host, track, inSession)
    }
}

private fun padButtonColors(tint: Int, opacity: Int, pressed: Boolean): Pair<Color, Color> {
    val base = Color(tint)
    val alpha = opacity.coerceIn(0, 100) / 100f
    fun light(a: Int, f: Float) = Color(base.red + (1 - base.red) * f, base.green + (1 - base.green) * f, base.blue + (1 - base.blue) * f, a * alpha / 255f)
    return Color(base.red * 0.12f, base.green * 0.12f, base.blue * 0.12f, 190 / 255f) to (if (pressed) light(230, 0.7f) else light(150, 0.25f))
}

@Composable
internal fun GlassesCornerButton(slot: Int, tint: Int, opacity: Int, onClick: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        val x = if (slot < 0) maxWidth / 2 + 56.dp else 34.dp + 56.dp * slot
        val bottom = if (slot < 0) 44.dp else 34.dp
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val (fill, line) = padButtonColors(tint, opacity, pressed)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset { with(density) { IntOffset((x - 18.dp).roundToPx(), (maxHeight - bottom - 18.dp).roundToPx()) } }
                .size(36.dp)
                .clip(CircleShape)
                .background(fill)
                .border(1.5.dp, line, CircleShape)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
                .testTag("glasses-button"),
        ) {
            Icon(GlassesIcon, stringResource(R.string.glasses_open), tint = line, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun GlassesRecenterButton(tint: Int, opacity: Int, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (fill, line) = padButtonColors(tint, opacity, pressed)
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(top = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .height(36.dp)
                .clip(shape)
                .background(fill)
                .border(1.5.dp, line, shape)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp)
                .testTag("glasses-recenter"),
        ) {
            Text(stringResource(R.string.glasses_recenter_button), color = line, fontSize = 14.sp)
        }
    }
}

@Composable
internal fun GlassesPlayingHint(model: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(GlassesIcon, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.glasses_playing, model), color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp)
        }
    }
}
