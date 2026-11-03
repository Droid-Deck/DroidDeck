package com.droiddeck.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
internal fun Wordmark(height: Dp, modifier: Modifier = Modifier, ink: Color = MaterialTheme.colorScheme.onBackground, ball: Color = LocalPalette.current.signal) {
    val name = stringResource(R.string.app_name)
    val letters = remember { PathParser().parsePathString(WordmarkArt.LETTERS).toPath() }
    val leftHalf = remember { PathParser().parsePathString(WordmarkArt.LEFT_HALF).toPath() }
    val rightHalf = remember { PathParser().parsePathString(WordmarkArt.RIGHT_HALF).toPath() }
    Canvas(modifier.height(height).width(height * (WordmarkArt.BOX_W / WordmarkArt.BOX_H)).semantics { contentDescription = name }) {
        val k = size.height / WordmarkArt.BOX_H
        scale(k, k, Offset.Zero) {
            translate(-WordmarkArt.BOX_X, -WordmarkArt.BOX_Y) {
                drawPath(letters, ink)
                translate(239.135f, 123.36f) {
                    scale(WordmarkArt.MARK_SCALE, WordmarkArt.MARK_SCALE, Offset.Zero) {
                        translate(31.98f, 0f) {
                            drawPath(leftHalf, ink)
                            drawPath(rightHalf, ink)
                            drawCircle(ball, 55.98f, Offset(63.98f, 111.86f))
                        }
                    }
                }
            }
        }
    }
}
