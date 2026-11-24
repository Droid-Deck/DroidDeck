package com.droiddeck.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.hud.WordmarkArt

@Composable
internal fun Wordmark(height: Dp, modifier: Modifier = Modifier, ink: Color = MaterialTheme.colorScheme.onBackground, ball: Color = LocalPalette.current.signal, progress: Float? = null) {
    val name = stringResource(R.string.app_name)
    val letters = remember { PathParser().parsePathString(WordmarkArt.LETTERS).toPath() }
    val leftHalf = remember { PathParser().parsePathString(WordmarkArt.LEFT_HALF).toPath() }
    val rightHalf = remember { PathParser().parsePathString(WordmarkArt.RIGHT_HALF).toPath() }
    val shown = remember { Animatable(progress ?: 1f) }
    LaunchedEffect(progress) { if (progress != null) shown.animateTo(progress.coerceIn(0f, 1f), tween(300, easing = LinearEasing)) }
    Canvas(modifier.height(height).width(height * (WordmarkArt.BOX_W / WordmarkArt.BOX_H)).semantics { contentDescription = name }) {
        val k = size.height / WordmarkArt.BOX_H
        fun mark(inkColor: Color, ballColor: Color) {
            scale(k, k, Offset.Zero) {
                translate(-WordmarkArt.BOX_X, -WordmarkArt.BOX_Y) {
                    drawPath(letters, inkColor)
                    translate(239.135f, 123.36f) {
                        scale(WordmarkArt.MARK_SCALE, WordmarkArt.MARK_SCALE, Offset.Zero) {
                            translate(31.98f, 0f) {
                                drawPath(leftHalf, inkColor)
                                drawPath(rightHalf, inkColor)
                                drawCircle(ballColor, 55.98f, Offset(63.98f, 111.86f))
                            }
                        }
                    }
                }
            }
        }
        if (progress == null) {
            mark(ink, ball)
        } else {
            mark(ink.copy(alpha = 0.28f), ball.copy(alpha = 0.28f))
            clipRect(right = size.width * shown.value) { mark(ink, ball) }
        }
    }
}
