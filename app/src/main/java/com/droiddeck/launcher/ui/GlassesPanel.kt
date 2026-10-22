package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.droiddeck.launcher.glasses.GlassesDisplayModes
import com.droiddeck.launcher.glasses.GlassesDisplayProfile
import com.droiddeck.launcher.glasses.GlassesSettings
import com.droiddeck.launcher.glasses.GlassesState
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.droiddeck.launcher.glasses.SpatialTracking
import com.droiddeck.launcher.glasses.SpatialMeasurements
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
    const val VR = "vr"
    const val HARDWARE = "hardware"
    const val TRACKING = "tracking"
    const val CONTROLLERS = "controllers"
}

@Composable
internal fun VrControllerGroups(host: MenuHost, track: (String) -> Modifier = { Modifier }) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { com.droiddeck.launcher.input.VrControllers.load(context) }
    val c by com.droiddeck.launcher.input.VrControllers.settings.collectAsState()
    val emulate = com.droiddeck.launcher.input.VrControllers.Emulate.entries
    SettingsGroup(stringResource(R.string.vr_controllers_group)) {
        ChoiceRow(host, "vr-emulate", stringResource(R.string.vr_controllers_emulate), stringResource(R.string.vr_controllers_emulate_hint),
            emulate.map { it to stringResource(when (it) {
                com.droiddeck.launcher.input.VrControllers.Emulate.OFF -> R.string.vr_controllers_off
                com.droiddeck.launcher.input.VrControllers.Emulate.INDEX -> R.string.vr_controllers_index
                com.droiddeck.launcher.input.VrControllers.Emulate.TOUCH -> R.string.vr_controllers_touch
                com.droiddeck.launcher.input.VrControllers.Emulate.VIVE -> R.string.vr_controllers_vive
            }) }, c.emulate, chipModifier = track("vr-emulate")) { com.droiddeck.launcher.input.VrControllers.setEmulate(context, it) }
        if (c.emulate != com.droiddeck.launcher.input.VrControllers.Emulate.OFF) ChoiceRow(host, "vr-sticks",
            stringResource(R.string.vr_controllers_sticks), stringResource(R.string.vr_controllers_sticks_hint),
            listOf(false to stringResource(R.string.vr_controllers_sticks_game), true to stringResource(R.string.vr_controllers_sticks_hands)),
            c.sticksMoveHands, chipModifier = track("vr-sticks")) { com.droiddeck.launcher.input.VrControllers.setSticksMoveHands(context, it) }
        SettingsRow(stringResource(R.string.vr_controllers_buttons), stringResource(when (c.emulate) {
            com.droiddeck.launcher.input.VrControllers.Emulate.OFF -> R.string.vr_controllers_buttons_off
            com.droiddeck.launcher.input.VrControllers.Emulate.VIVE -> R.string.vr_controllers_buttons_vive
            else -> R.string.vr_controllers_buttons_stick
        }), hintLines = 6) {}
    }
}

@Composable
internal fun glassesTabs(g: GlassesState): List<Pair<String, String>> = buildList {
    add(GlassesTab.DISPLAY to stringResource(R.string.glasses_display))
    if (g.canControl || g.hasDisplay) add(GlassesTab.VR to stringResource(R.string.glasses_vr))
    if (g.canControl) {
        add(GlassesTab.TRACKING to stringResource(R.string.glasses_tracking))
        if (g.hardwareControls) add(GlassesTab.HARDWARE to stringResource(R.string.glasses_hardware))
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
    val vr = tab == null || tab == GlassesTab.VR
    val picture = GlassesRendering.picture(s, g.sbs, steamVrRunning)
    val usesGlasses = GlassesRendering.usesGlasses(g, outputActive, steamVrRunning)
    val spatialTracking = g.spatialConnected && s.positionalTracking
    val spatialVr = spatialTracking && usesGlasses && steamVrRunning
    val flatPicture = picture == GlassesRendering.Picture.FLAT || picture == GlassesRendering.Picture.DUPLICATED
    val virtualScreen = usesGlasses && picture == GlassesRendering.Picture.VIRTUAL_SCREEN
    val directView = usesGlasses && picture == GlassesRendering.Picture.STEREO
    val menuScreen = !inSession && g.usable && s.gameOnGlasses && s.screen3dof
    if (g.canControl && onRecenter != null && s.headTracking && (virtualScreen || menuScreen || (directView && steamVrRunning))) {
        SettingsGroup(stringResource(R.string.glasses_quick_actions), compact = true) {
            ActionRow(stringResource(R.string.glasses_recenter),
                if (virtualScreen || menuScreen) stringResource(R.string.glasses_recenter_screen_hint) else null,
                stringResource(R.string.cardboard_recenter_button), onRecenter)
        }
    }
    if (display) SettingsGroup(g.model) {
        if (g.experimental) SettingsRow(stringResource(R.string.glasses_control), stringResource(
            if (g.supportsDisplayModeControl) R.string.glasses_xreal_experimental else R.string.glasses_pro2_experimental)) {}
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
    if (display && g.canControl && g.supportsDisplayModeControl && (inSession || g.sbs || g.sbsSupport == GlassesState.SBS_FAILS)) {
        SettingsGroup(stringResource(R.string.glasses_display_mode)) {
            if (g.sbsSupport == GlassesState.SBS_FAILS && !g.sbs) {
                ActionRow(stringResource(R.string.glasses_3d), stringResource(R.string.glasses_3d_failed),
                    stringResource(R.string.stores_dl_retry), { Glasses.set3d(true) })
            } else ToggleRow(host, "glasses-3d", stringResource(R.string.glasses_3d), stringResource(R.string.glasses_3d_hint),
                g.sbs, chipModifier = track("glasses-3d")) { Glasses.set3d(it) }
        }
    }
    if (display && g.canControl && g.supportsDisplayModeControl) {
        val profiles = if (inSession) listOf(GlassesDisplayModes.profile(g.sbs, false))
            else listOf(GlassesDisplayProfile.TWO_D, GlassesDisplayProfile.THREE_D)
        for (profile in profiles) SettingsGroup(stringResource(if (profile == GlassesDisplayProfile.TWO_D)
            R.string.glasses_output_2d else R.string.glasses_output_3d)) {
            GlassesResolutionRow(host, g, s, profile, track)
            if ((profile == GlassesDisplayProfile.THREE_D) == g.sbs) GlassesRefreshRow(host, g, s, track)
        }
    }
    if (display && (g.canControl || g.hasDisplay)) SettingsGroup(stringResource(R.string.glasses_picture_group)) {
        ChoiceRow(host, "glasses-picture", stringResource(R.string.glasses_picture), stringResource(R.string.glasses_picture_hint),
            listOf(
                GlassesSettings.PICTURE_AUTO to stringResource(R.string.glasses_picture_auto),
                GlassesSettings.PICTURE_SCREEN to stringResource(R.string.glasses_picture_screen),
                GlassesSettings.PICTURE_SBS to stringResource(R.string.glasses_picture_sbs),
            ), s.picture3d, chipModifier = track("glasses-picture")) { Glasses.setPicture3d(it) }
        if (g.canControl && g.supportsScreen3dof) ToggleRow(host, "glasses-3dof",
            stringResource(if (g.spatialConnected && s.positionalTracking) R.string.glasses_6dof else R.string.glasses_3dof),
            stringResource(R.string.glasses_3dof_hint), s.screen3dof, chipModifier = track("glasses-3dof")) { Glasses.set3dof(it) }
        when {
            !inSession -> SettingsRow(stringResource(R.string.glasses_picture_screen), stringResource(R.string.glasses_screen_app_wide)) {}
            !usesGlasses -> SettingsRow(stringResource(R.string.glasses_picture_screen), stringResource(R.string.glasses_screen_no_output)) {}
            flatPicture -> ActionRow(
                stringResource(R.string.glasses_picture_screen), stringResource(R.string.glasses_screen_flat_hint),
                stringResource(R.string.glasses_screen_use), { Glasses.setPicture3d(GlassesSettings.PICTURE_SCREEN) })
            directView -> SettingsRow(stringResource(R.string.glasses_picture_screen),
                stringResource(if (steamVrRunning) R.string.glasses_screen_direct_hint else R.string.glasses_screen_sbs_hint)) {}
        }
        if (virtualScreen || (!inSession && s.picture3d != GlassesSettings.PICTURE_SBS)) {
            SliderRow(stringResource(R.string.glasses_screen_size), null, s.screenWidthDm, 5..60, step = 1,
                format = { SpatialMeasurements.distance(it / 10f) }, modifier = track("glasses-width")) { Glasses.setScreenWidthDm(it) }
            SliderRow(stringResource(R.string.glasses_screen_distance), null, s.screenDistanceDm, 5..100, step = 1,
                format = { SpatialMeasurements.distance(it / 10f) }, modifier = track("glasses-distance")) { Glasses.setScreenDistanceDm(it) }
        }
    }
    if (vr && (g.canControl || g.hasDisplay)) SettingsGroup(stringResource(R.string.cardboard_steamvr)) {
        steamVr()
        if (g.supportsDisplayModeControl) ToggleRow(host, "glasses-auto3d", stringResource(R.string.glasses_auto3d),
            stringResource(R.string.glasses_auto3d_hint), s.auto3d, chipModifier = track("glasses-auto3d")) { Glasses.setAuto3d(it) }
        if (g.canControl && g.supportsDisplayModeControl) {
            GlassesResolutionRow(host, g, s, GlassesDisplayProfile.VR, track)
            if (steamVrRunning && tab != null) GlassesRefreshRow(host, g, s, track)
        }
        SliderRow(stringResource(R.string.glasses_vr_fov), stringResource(R.string.glasses_vr_fov_hint), s.vrFovDeg,
            GlassesSettings.VR_FOV_MIN..GlassesSettings.VR_FOV_MAX, step = 1,
            format = { "$it°" }, modifier = track("glasses-vr-fov")) { Glasses.setVrFovDeg(it) }
    }
    if ((tab == null || tab == GlassesTab.TRACKING) && g.canControl) {
        SettingsGroup(stringResource(R.string.glasses_head_tracking)) {
            val trackingHint = when {
                inSession && !usesGlasses -> R.string.glasses_screen_no_output
                spatialVr -> R.string.glasses_head_tracking_spatial_vr_hint
                spatialTracking -> R.string.glasses_head_tracking_spatial_hint
                inSession && flatPicture -> R.string.glasses_tracking_flat_hint
                inSession && directView && !steamVrRunning -> R.string.glasses_tracking_sbs_hint
                else -> R.string.glasses_head_tracking_hint
            }
            ToggleRow(host, "glasses-tracking", stringResource(R.string.glasses_head_tracking), stringResource(trackingHint),
                s.headTracking || spatialVr, enabled = !spatialVr,
                chipModifier = track("glasses-tracking")) { Glasses.setHeadTracking(it) }
        }
        val calibration by Glasses.calibration.collectAsState()
        if (calibration.supported) SettingsGroup(stringResource(R.string.glasses_calibration)) {
            GlassesCalibrationControls(calibration,
                onStart = Glasses::startCalibration, onCancel = Glasses::cancelCalibration,
                onReset = Glasses::resetCalibration, onDone = { onRecenter?.invoke() })
        }
    }
    if ((tab == null || tab == GlassesTab.HARDWARE) && g.canControl && g.hardwareControls) SettingsGroup(stringResource(R.string.glasses_hardware)) {
        if (g.brightnessMax > 0) SliderRow(stringResource(R.string.glasses_brightness), null, g.brightness.coerceAtLeast(0), 0..g.brightnessMax, step = 1,
            modifier = track("glasses-brightness")) { Glasses.setBrightness(it) }
        if (g.volumeMax > 0) SliderRow(stringResource(R.string.glasses_volume), null, g.volume.coerceAtLeast(0), 0..g.volumeMax, step = 1,
            modifier = track("glasses-volume")) { Glasses.setVolume(it) }
        if (g.hasSunblock) ToggleRow(host, "glasses-sunblock", stringResource(R.string.glasses_sunblock), stringResource(R.string.glasses_sunblock_hint),
            g.sunblock, chipModifier = track("glasses-sunblock")) { Glasses.setSunblock(it) }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val hands by HandTracking.settings.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { HandTracking.load(context) }
    if ((tab == null || tab == GlassesTab.TRACKING) && g.canControl) {
        if (g.spatialConnected) {
            SettingsGroup(stringResource(R.string.glasses_experimental)) {
                val spatial by SpatialTracking.status.collectAsState()
                ToggleRow(host, "glasses-position", stringResource(R.string.glasses_position), stringResource(R.string.glasses_position_hint),
                    s.positionalTracking, chipModifier = track("glasses-position")) { Glasses.setPositionalTracking(it) }
                if (s.positionalTracking) {
                    SettingsRow(stringResource(R.string.glasses_tracking_status), spatial.message) {}
                    SliderRow(stringResource(R.string.glasses_eye_height), null, s.eyeHeightCm, 50..250, step = 1,
                        format = SpatialMeasurements::height, modifier = track("glasses-eye-height")) { Glasses.setEyeHeight(it) }
                    ActionRow(stringResource(R.string.glasses_floor), null, stringResource(R.string.glasses_floor_set), Glasses::setSpatialFloor)
                    HandCalibrationStatus(spatial)
                    ActionRow(stringResource(R.string.glasses_spatial_setup), stringResource(R.string.glasses_spatial_setup_hint),
                        stringResource(R.string.glasses_spatial_open), { context.startActivity(Intent(context, SpatialTrackingActivity::class.java)) })
                    ToggleRow(host, "glasses-hands", stringResource(R.string.glasses_hands), stringResource(R.string.glasses_hands_hint),
                        s.handControllers, chipModifier = track("glasses-hands")) { Glasses.setHandControllers(it) }
                    if (s.handControllers) ToggleRow(host, "glasses-steady-hand-aim",
                        stringResource(R.string.glasses_steady_hand_aim), stringResource(R.string.glasses_steady_hand_aim_hint),
                        s.steadyHandAim, chipModifier = track("glasses-steady-hand-aim")) { Glasses.setSteadyHandAim(it) }
                }
                ToggleRow(host, "glasses-joycons", stringResource(R.string.glasses_joycons),
                    stringResource(if (s.positionalTracking) R.string.glasses_joycons_hint else R.string.glasses_joycons_requires_position),
                    s.joyConTracking, enabled = s.positionalTracking, chipModifier = track("glasses-joycons")) { Glasses.setJoyConTracking(it) }
            }
        }
        if (hands.usable) SettingsGroup(stringResource(R.string.phone_camera_tracking_group)) {
            HandCameraRows(host, track, inSession)
        }
    }

}

@Composable
private fun GlassesResolutionRow(host: MenuHost, g: GlassesState, s: GlassesSettings,
    profile: GlassesDisplayProfile, track: (String) -> Modifier) {
    val label = when (profile) {
        GlassesDisplayProfile.TWO_D -> R.string.glasses_resolution_2d
        GlassesDisplayProfile.THREE_D -> R.string.glasses_resolution_3d
        GlassesDisplayProfile.VR -> R.string.glasses_resolution_vr
    }
    val choices = listOf("" to stringResource(if (profile == GlassesDisplayProfile.VR)
        R.string.glasses_resolution_inherit else R.string.glasses_resolution_auto)) +
        g.resolutions.map { it.key to "${it.width} × ${it.height}" }
    val saved = GlassesDisplayModes.choice(s, profile)
    val key = "glasses-resolution-${profile.name.lowercase()}"
    ChoiceRow(host, key, stringResource(label), stringResource(R.string.glasses_resolution_hint), choices,
        saved.takeIf { value -> choices.any { it.first == value } } ?: "",
        enabled = !g.switching3d, chipModifier = track(key)) { Glasses.setResolution(profile, it) }
}

@Composable
private fun GlassesRefreshRow(host: MenuHost, g: GlassesState, s: GlassesSettings, track: (String) -> Modifier) {
    val rates = GlassesRendering.refreshRates(s, g).map { it to "$it Hz" }
    val hint = if (g.supports30Hz && !s.gameOnGlasses) stringResource(R.string.glasses_refresh_30_hint) else null
    val label = stringResource(if (g.sbs) R.string.glasses_3d_refresh else R.string.glasses_refresh)
    if (rates.size > 1) {
        val key = if (g.sbs) "glasses-3d-hz" else "glasses-hz"
        ChoiceRow(host, key, label, hint, rates, GlassesRendering.outputHz(s, g),
            enabled = !g.switching3d, chipModifier = track(key)) {
            if (g.sbs) Glasses.setSbsHz(it) else Glasses.setRefreshHz(it)
        }
    } else SettingsRow(label, hint ?: "${rates.single().first} Hz") {}
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
internal fun GlassesRecenterButton(tint: Int, opacity: Int, onRecenterJoyCons: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(top = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassesRecenterAction(stringResource(R.string.glasses_recenter_button), "glasses-recenter", tint, opacity, onClick)
            if (onRecenterJoyCons != null) GlassesRecenterAction(
                stringResource(R.string.glasses_recenter_joycons_button), "glasses-recenter-joycons", tint, opacity, onRecenterJoyCons)
        }
    }
}

@Composable
private fun GlassesRecenterAction(label: String, tag: String, tint: Int, opacity: Int, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (fill, line) = padButtonColors(tint, opacity, pressed)
    val shape = RoundedCornerShape(50)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .background(fill)
            .border(1.5.dp, line, shape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp)
            .testTag(tag),
    ) {
        Text(label, color = line, fontSize = 14.sp)
    }
}

