package com.droiddeck.launcher.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The last game frame, captured as the session left the screen, and where it sat in the overlay. */
class PausedFrame(val image: ImageBitmap, val bounds: android.graphics.Rect)

private const val CARD_SCALE = 0.82f
private const val FRAME_WAIT_MS = 600L
private val PopEasing = CubicBezierEasing(0.3f, 1.5f, 0.4f, 1f)

private fun tone(saturation: Float, brightness: Float): ColorMatrix =
    ColorMatrix().apply {
        setToSaturation(saturation)
        timesAssign(ColorMatrix().apply { setToScale(brightness, brightness, brightness, 1f) })
    }

@Composable
fun SessionPausedOverlay(
    suspended: Boolean,
    engaged: Boolean,
    failures: Int,
    frame: PausedFrame?,
    animateIn: Boolean,
    title: String,
    onResume: () -> Unit,
    onFrameNeeded: () -> Unit,
    onHidden: () -> Unit,
) {
    val currentFrame by rememberUpdatedState(frame)
    var shown by remember { mutableStateOf(suspended) }
    val lift = remember { Animatable(if (suspended) 1f else 0f) }
    val orb = remember { Animatable(if (suspended) 1f else 0f) }
    val press = remember { Animatable(1f) }
    val ring = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }

    LaunchedEffect(suspended, engaged) {
        when {
            suspended && !engaged -> {
                if (!shown && animateIn) {
                    if (currentFrame == null) {
                        onFrameNeeded()
                        withTimeoutOrNull(FRAME_WAIT_MS) { snapshotFlow { currentFrame }.first { it != null } }
                    }
                    shown = true
                    launch { press.animateTo(1f, Motion.sp(0.5f, Spring.StiffnessMedium)) }
                    launch { ring.animateTo(0f, Motion.tw(200)) }
                    launch { lift.animateTo(1f, Motion.tw(520)) }
                    orb.animateTo(1f, Motion.tw(380, delay = if (orb.value < 0.5f) 260 else 0, easing = PopEasing))
                    return@LaunchedEffect
                }
                if (!shown) {
                    if (currentFrame == null) {
                        onFrameNeeded()
                        withTimeoutOrNull(FRAME_WAIT_MS) { snapshotFlow { currentFrame }.first { it != null } }
                    }
                    // Pausing mostly happens while the screen is off or the app is away, so it appears already settled.
                    press.snapTo(1f)
                    ring.snapTo(0f)
                    lift.snapTo(1f)
                    orb.snapTo(1f)
                    shown = true
                    return@LaunchedEffect
                }
                launch { press.animateTo(1f, Motion.sp(0.5f, Spring.StiffnessMedium)) }
                launch { ring.animateTo(0f, Motion.tw(200)) }
                orb.animateTo(1f, Motion.tw(200))
            }
            suspended -> {
                if (!shown) return@LaunchedEffect
                launch { orb.animateTo(1f, Motion.tw(200)) }
                launch { press.animateTo(0.92f, Motion.tw(140)) }
                ring.animateTo(0.25f, Motion.tw(140))
                ring.animateTo(0.8f, Motion.tw(650, easing = CubicBezierEasing(0.1f, 0.6f, 0.3f, 1f)))
                ring.animateTo(0.92f, Motion.tw(2000, easing = LinearOutSlowInEasing))
            }
            shown -> {
                ring.animateTo(1f, Motion.tw(110))
                coroutineScope {
                    launch { orb.animateTo(0f, Motion.tw(220, easing = FastOutLinearInEasing)) }
                    lift.animateTo(0f, Motion.sp(0.62f, 380f))
                }
                shown = false
                ring.snapTo(0f)
                press.snapTo(1f)
                onHidden()
            }
        }
    }

    val seenFailures = remember { mutableIntStateOf(failures) }
    LaunchedEffect(failures) {
        if (failures == seenFailures.intValue) return@LaunchedEffect
        seenFailures.intValue = failures
        if (!shown || Motion.scale == 0f) return@LaunchedEffect
        shake.animateTo(0f, keyframes {
            durationMillis = Motion.ms(360)
            10f at Motion.ms(60)
            -8f at Motion.ms(140)
            5f at Motion.ms(220)
            -2f at Motion.ms(300)
        })
    }

    if (!shown) return

    val presence = lift.value.coerceIn(0f, 1f)
    val idle = suspended && !engaged
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    val resumeLabel = stringResource(R.string.resume_session)
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = title }
            .focusRequester(focus)
            .controllerConfirm(enabled = idle, onClick = onResume)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = resumeLabel,
            ) { if (idle) onResume() },
    ) {
        if (frame != null) {
            PausedCard(frame, lift.value, presence)
        } else {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = presence }.background(Color.Black.copy(alpha = 0.55f)))
        }
        ResumeOrb(orb.value, press.value, ring.value, shake.value, idle, focused)
    }
}

@Composable
private fun BoxScope.PausedCard(frame: PausedFrame, lift: Float, presence: Float) {
    val density = LocalDensity.current
    val bounds = frame.bounds
    val ambient = remember { ColorFilter.colorMatrix(tone(0.1f, 0.3f)) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = presence }.background(Color.Black)) {
        Image(
            frame.image, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = ambient,
            modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(32.dp) else Modifier),
        )
    }
    Image(
        frame.image, contentDescription = null, contentScale = ContentScale.FillBounds,
        colorFilter = ColorFilter.colorMatrix(tone(1f - 0.92f * presence, 1f - 0.45f * presence)),
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset { IntOffset(bounds.left, bounds.top) }
            .size(with(density) { bounds.width().toDp() }, with(density) { bounds.height().toDp() })
            .graphicsLayer {
                val scale = 1f - (1f - CARD_SCALE) * lift
                scaleX = scale
                scaleY = scale
                shape = RoundedCornerShape((20f * presence).dp)
                clip = true
                shadowElevation = 24.dp.toPx() * presence
            },
    )
}

@Composable
private fun ResumeOrb(presence: Float, press: Float, ring: Float, shake: Float, idle: Boolean, focused: Boolean) {
    val glintAlpha by animateFloatAsState(if (idle) 1f else 0f, Motion.tw(160), label = "glintAlpha")
    val glint: State<Float>? = if (Motion.scale > 0f) {
        rememberInfiniteTransition(label = "glint").animateFloat(
            0f, 360f, infiniteRepeatable(tween(Motion.ms(3600), easing = LinearEasing)), label = "glintAngle",
        )
    } else null
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(96.dp)
            .graphicsLayer {
                val scale = press * (0.4f + 0.6f * presence)
                scaleX = scale
                scaleY = scale
                alpha = presence.coerceIn(0f, 1f)
                translationX = shake.dp.toPx()
            }
            .drawBehind {
                val halo = size.minDimension * 0.8f
                drawCircle(Brush.radialGradient(0.6f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent, radius = halo), radius = halo)
            }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .glideBorder(focused, CircleShape, Color.White, hotFillAlpha = 0f)
            .drawWithContent {
                drawContent()
                val stroke = 2.5.dp.toPx()
                val radius = size.minDimension / 2f * 0.92f
                if (glint != null && glintAlpha > 0f) {
                    rotate(glint.value) {
                        drawCircle(
                            Brush.sweepGradient(
                                0f to Color.Transparent,
                                0.083f to Color.White.copy(alpha = 0.4f * glintAlpha),
                                0.166f to Color.Transparent,
                                1f to Color.Transparent,
                            ),
                            radius = radius, style = Stroke(stroke),
                        )
                    }
                }
                if (ring > 0f) {
                    drawArc(
                        Color.White, startAngle = -90f, sweepAngle = 360f * ring.coerceAtMost(1f), useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2f, radius * 2f),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            },
    ) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
    }
}
