package com.droiddeck.launcher.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.droiddeck.launcher.R
import com.droiddeck.launcher.handtracking.Advice
import com.droiddeck.launcher.handtracking.BodyPose
import com.droiddeck.launcher.handtracking.HandCamera
import com.droiddeck.launcher.handtracking.HandTracking
import com.droiddeck.launcher.handtracking.Landmark
import com.droiddeck.launcher.handtracking.TrackingCameras
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun HandCameraHost(check: Boolean?, onAskPermission: () -> Unit, onPlay: () -> Unit, onClose: () -> Unit) {
    if (!HandTracking.supported) return
    val context = LocalContext.current
    val (glasses, tracking) = rememberGlasses()
    val settings by HandTracking.settings.collectAsState()
    LaunchedEffect(Unit) { HandTracking.load(context) }
    var prompted by rememberSaveable { mutableStateOf(false) }
    if (!prompted && !glasses.usesSpatialControllers(tracking) &&
        HandTracking.shouldAsk(settings, glasses.present && glasses.canControl)) {
        HandCameraPrompt(settings.sides,
            onAllow = { side ->
                prompted = true
                HandTracking.allow(context, side)
                if (!HandTracking.permitted(context)) onAskPermission()
            },
            onNotNow = {
                prompted = true
                HandTracking.decline(context)
            })
    }
    val requests by HandTracking.checkRequests.collectAsState()
    var handled by remember { mutableStateOf(requests) }
    val fromSettings = requests != handled
    if (check != null || fromSettings) {
        PlacementCheck(settings, play = check == true && !fromSettings,
            onPlay = { handled = requests; onPlay() },
            onClose = { handled = requests; onClose() })
    }
}

@Composable
internal fun HandCameraPrompt(sides: Set<String>, onAllow: (String?) -> Unit, onNotNow: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val choose = sides.size > 1
    var side by remember { mutableStateOf(TrackingCameras.REAR) }
    AlertDialog(
        onDismissRequest = onNotNow,
        modifier = Modifier.testTag("hand-camera-prompt"),
        title = { Text(stringResource(R.string.hand_camera_prompt_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.hand_camera_prompt_body), fontSize = 14.sp)
                if (choose) listOf(
                    Triple(TrackingCameras.REAR, R.string.hand_camera_rear, R.string.hand_camera_rear_hint),
                    Triple(TrackingCameras.FRONT, R.string.hand_camera_front, R.string.hand_camera_front_hint),
                ).forEach { (id, label, hint) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .selectable(side == id, role = Role.RadioButton) { side = id }
                            .testTag("hand-camera-$id")
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(side == id, onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = pal.signal, unselectedColor = colors.onSurfaceVariant))
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(stringResource(label), fontSize = 15.sp, color = colors.onBackground)
                            Text(stringResource(hint), fontSize = 13.sp, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAllow(if (choose) side else null) }, modifier = Modifier.testTag("hand-camera-allow")) {
                Text(stringResource(R.string.hand_camera_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow, modifier = Modifier.testTag("hand-camera-not-now")) { Text(stringResource(R.string.hand_camera_not_now)) }
        },
    )
}

@Composable
internal fun HandCameraRows(host: MenuHost, track: (String) -> Modifier, inSession: Boolean) {
    if (!HandTracking.supported) return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        HandTracking.load(context)
        val watch = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) HandTracking.refreshPermission(context) }
        lifecycle.addObserver(watch)
        onDispose { lifecycle.removeObserver(watch) }
    }
    val s by HandTracking.settings.collectAsState()
    if (!s.usable) return
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        HandTracking.refreshPermission(context)
        denied = !granted
    }
    ToggleRow(host, "hand-camera", stringResource(R.string.phone_camera_tracking_setting), stringResource(R.string.hand_camera_setting_hint),
        s.enabled, chipModifier = track("hand-camera")) { on ->
        HandTracking.setEnabled(context, on)
        if (on && !s.permitted) ask.launch(Manifest.permission.CAMERA)
    }
    if (!s.enabled) return
    if (s.sides.size > 1) ChoiceRow(host, "hand-camera-side", stringResource(R.string.hand_camera_choice), null,
        listOf(TrackingCameras.REAR to stringResource(R.string.hand_camera_rear), TrackingCameras.FRONT to stringResource(R.string.hand_camera_front)),
        s.side ?: TrackingCameras.REAR, chipModifier = track("hand-camera-side")) { HandTracking.setSide(context, it) }
    when {
        !s.permitted && denied -> ActionRow(stringResource(R.string.hand_camera_access), stringResource(R.string.hand_camera_access_denied),
            stringResource(R.string.hand_camera_open_settings), {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        !s.permitted -> ActionRow(stringResource(R.string.hand_camera_access), stringResource(R.string.hand_camera_access_hint),
            stringResource(R.string.hand_camera_allow_button), { ask.launch(Manifest.permission.CAMERA) })
        !inSession -> ActionRow(stringResource(R.string.hand_camera_placement), stringResource(R.string.hand_camera_placement_hint),
            stringResource(R.string.hand_camera_check), { HandTracking.requestCheck() })
    }
}

@Composable
private fun PlacementCheck(settings: HandTracking.Settings, play: Boolean, onPlay: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val side = settings.side
    val camera = remember(side) { HandCamera(context, null, preview = true) }
    DisposableEffect(camera) {
        camera.start(side)
        onDispose { camera.stop() }
    }
    val view by camera.view.collectAsState()
    val failed by camera.failed.collectAsState()
    val front = view?.front ?: (TrackingCameras.pick(HandTracking.cameras(context), side)?.front == true)
    val advice = when {
        failed -> null
        else -> view?.advice ?: Advice.STARTING
    }
    val adviceText = stringResource(advice?.let(::adviceLabel) ?: R.string.hand_camera_failed)
    val start by rememberUpdatedState(onPlay)
    if (play) LaunchedEffect(advice == Advice.GOOD) {
        if (advice == Advice.GOOD) {
            delay(GOOD_HOLD_MS)
            start()
        }
    }
    if (!front) SpokenAdvice(advice, adviceText)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PlacementCheckContent(view, front, advice, adviceText, play, switchable = settings.sides.size > 1,
            onSwitch = { HandTracking.setSide(context, if (front) TrackingCameras.REAR else TrackingCameras.FRONT) },
            onPlay = onPlay, onClose = onClose)
    }
}

@Composable
internal fun PlacementCheckContent(
    view: HandCamera.View?,
    front: Boolean,
    advice: Advice?,
    adviceText: String,
    play: Boolean,
    switchable: Boolean,
    onSwitch: () -> Unit,
    onPlay: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize().background(pal.background).padding(24.dp).testTag("hand-camera-check"),
    ) {
        Box(Modifier.weight(1.3f).fillMaxHeight(), contentAlignment = Alignment.Center) { CameraPreview(view) }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.hand_camera_check_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
            Text(stringResource(if (front) R.string.hand_camera_how_front else R.string.hand_camera_how_rear), fontSize = 15.sp, color = colors.onSurfaceVariant)
            Text(adviceText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                color = if (advice == Advice.GOOD) pal.good else colors.onBackground, modifier = Modifier.testTag("hand-camera-advice"))
            Spacer(Modifier.heightIn(min = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (switchable) SecondaryButton(stringResource(R.string.hand_camera_switch), compact = true, onClick = onSwitch)
                if (play) {
                    SecondaryButton(stringResource(R.string.hand_camera_skip), compact = true, modifier = Modifier.testTag("hand-camera-skip"), onClick = onPlay)
                    SecondaryButton(stringResource(R.string.common_cancel), compact = true, onClick = onClose)
                } else {
                    SecondaryButton(stringResource(R.string.hand_camera_done), compact = true, modifier = Modifier.testTag("hand-camera-done"), onClick = onClose)
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(view: HandCamera.View?) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    if (view == null) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(shape).background(pal.surface), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = pal.signal, modifier = Modifier.size(36.dp))
        }
        return
    }
    val image = remember(view) { view.image.asImageBitmap() }
    Box(Modifier.aspectRatio(view.image.width.toFloat() / view.image.height).clip(shape)) {
        Image(image, null, contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().graphicsLayer { if (view.front) scaleX = -1f })
        Canvas(Modifier.fillMaxSize()) { drawSkeleton(view.landmarks, view.front, pal.signal, pal.good) }
    }
}

private fun DrawScope.drawSkeleton(marks: List<Landmark>?, mirrored: Boolean, line: Color, hand: Color) {
    if (marks == null || marks.size <= BodyPose.RIGHT_HIP) return
    fun at(i: Int) = Offset((if (mirrored) 1f - marks[i].x else marks[i].x) * size.width, marks[i].y * size.height)
    fun seen(i: Int) = marks[i].visibility >= BodyPose.MIN_VISIBILITY
    val width = size.minDimension / 120f
    for ((a, b) in BONES) if (seen(a) && seen(b)) drawLine(line, at(a), at(b), strokeWidth = width, cap = StrokeCap.Round)
    for (i in JOINTS) if (seen(i)) drawCircle(line, width * 1.2f, at(i))
    for (wrist in listOf(BodyPose.LEFT_WRIST, BodyPose.RIGHT_WRIST)) {
        if (!seen(wrist)) continue
        val grip = (at(wrist) + at(wrist + 2) + at(wrist + 4)) / 3f
        drawCircle(hand.copy(alpha = 0.35f), width * 5f, grip)
        drawCircle(hand, width * 2.2f, grip)
    }
}

@Composable
private fun SpokenAdvice(advice: Advice?, text: String) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    val speech = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.getDefault()
                ready = true
            }
        }
        engine
    }
    DisposableEffect(speech) { onDispose { speech.shutdown() } }
    LaunchedEffect(advice, ready) {
        if (!ready || advice == null || advice == Advice.STARTING) return@LaunchedEffect
        delay(SPEAK_AFTER_MS)
        while (true) {
            speech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hand-camera")
            if (advice == Advice.GOOD) break
            delay(SPEAK_AGAIN_MS)
        }
    }
}

private fun adviceLabel(advice: Advice): Int = when (advice) {
    Advice.STARTING -> R.string.hand_camera_starting
    Advice.STAND_UP -> R.string.hand_camera_stand_up
    Advice.UPRIGHT -> R.string.hand_camera_upright
    Advice.NOT_SEEN -> R.string.hand_camera_not_seen
    Advice.MOVE_BACK -> R.string.hand_camera_move_back
    Advice.MOVE_CLOSER -> R.string.hand_camera_move_closer
    Advice.SHOW_HANDS -> R.string.hand_camera_show_hands
    Advice.GOOD -> R.string.hand_camera_good
}

private val BONES = listOf(
    BodyPose.LEFT_SHOULDER to BodyPose.RIGHT_SHOULDER,
    BodyPose.LEFT_SHOULDER to BodyPose.LEFT_ELBOW, BodyPose.LEFT_ELBOW to BodyPose.LEFT_WRIST,
    BodyPose.RIGHT_SHOULDER to BodyPose.RIGHT_ELBOW, BodyPose.RIGHT_ELBOW to BodyPose.RIGHT_WRIST,
    BodyPose.LEFT_SHOULDER to BodyPose.LEFT_HIP, BodyPose.RIGHT_SHOULDER to BodyPose.RIGHT_HIP, BodyPose.LEFT_HIP to BodyPose.RIGHT_HIP,
    BodyPose.NOSE to BodyPose.LEFT_EYE, BodyPose.NOSE to BodyPose.RIGHT_EYE,
    BodyPose.LEFT_EYE to BodyPose.LEFT_EAR, BodyPose.RIGHT_EYE to BodyPose.RIGHT_EAR,
)
private val JOINTS = listOf(BodyPose.NOSE, BodyPose.LEFT_SHOULDER, BodyPose.RIGHT_SHOULDER, BodyPose.LEFT_ELBOW, BodyPose.RIGHT_ELBOW,
    BodyPose.LEFT_HIP, BodyPose.RIGHT_HIP)
private const val GOOD_HOLD_MS = 1200L
private const val SPEAK_AFTER_MS = 1200L
private const val SPEAK_AGAIN_MS = 6000L
