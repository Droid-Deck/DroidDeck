package com.droiddeck.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The session's pointer. [image]: the program's own cursor (wl_pointer.set_cursor - labwc's resize
 * arrows, a text field's I-beam), in session pixels, drawn [imageScale] times larger with its hotspot
 * ([hotX], [hotY]) on [position]; null = the program has not set one, and the built-in arrow is drawn.
 */
@Composable
fun CursorOverlay(
    position: Offset, visible: Boolean, scale: Float,
    image: ImageBitmap? = null, hotX: Int = 0, hotY: Int = 0, imageScale: Float = 1f,
) {
    if (!visible) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (image != null) {
            val w = (image.width * imageScale).toInt().coerceAtLeast(1)
            val h = (image.height * imageScale).toInt().coerceAtLeast(1)
            drawImage(
                image,
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    (position.x - hotX * imageScale).toInt(), (position.y - hotY * imageScale).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(w, h),
                filterQuality = FilterQuality.Low,
            )
            return@Canvas
        }
        val s = 11f * scale
        val path = Path().apply {
            moveTo(position.x, position.y)
            lineTo(position.x, position.y + s * 1.45f)
            lineTo(position.x + s * 0.36f, position.y + s * 1.12f)
            lineTo(position.x + s * 0.62f, position.y + s * 1.62f)
            lineTo(position.x + s * 0.84f, position.y + s * 1.52f)
            lineTo(position.x + s * 0.58f, position.y + s * 1.02f)
            lineTo(position.x + s * 1.02f, position.y + s * 1.02f)
            close()
        }
        drawPath(path, Color.White)
        drawPath(path, Color(0xFF1B0730), style = Stroke(width = 1.5f * scale))
    }
}
