package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * One line of text in a slot of fixed width - a rail label, a key cap - set as large as
 * [maxSize] when it fits and smaller, down to [minSize], when it does not. Labels are as long as
 * their language makes them ("Components" is "コンポーネント", "Componentes", "구성 요소"), so a
 * fixed size cuts some of them off; below [minSize] the end is ellipsized rather than clipped.
 */
@Composable
fun FitText(
    text: String,
    maxSize: TextUnit,
    minSize: TextUnit = 9.sp,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val measurer = rememberTextMeasurer()
        val style = LocalTextStyle.current
        val width = constraints.maxWidth
        val size = remember(text, width, maxSize, minSize, fontWeight, style) {
            if (width == Constraints.Infinity) return@remember maxSize
            var s = maxSize.value
            while (s > minSize.value) {
                val measured = measurer.measure(
                    text, style.copy(fontSize = s.sp, fontWeight = fontWeight ?: style.fontWeight),
                    maxLines = 1, softWrap = false,
                )
                if (measured.size.width <= width) break
                s -= 0.5f
            }
            s.coerceAtLeast(minSize.value).sp
        }
        Text(
            text, fontSize = size, fontWeight = fontWeight, color = color, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}
