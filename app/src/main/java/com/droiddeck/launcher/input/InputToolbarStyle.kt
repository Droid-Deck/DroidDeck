package com.droiddeck.launcher.input

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt

internal object InputToolbarStyle {
    const val HEIGHT = 64f
    const val BUTTON = 44f
    const val PADDING = 12f
    const val GAP = 8f
    const val TOP = 10f
    const val ICON = 20f
    const val TEXT = 13f
    const val TEXT_PADDING = 14f
    const val LETTER_SPACING = 1f

    val textStyle = Typography().bodyLarge.copy(fontSize = TEXT.sp, fontWeight = FontWeight.Bold,
        letterSpacing = LETTER_SPACING.sp, textDirection = TextDirection.Content)

    fun label(context: Context, text: String) = ParagraphIntrinsics(text, textStyle,
        density = Density(context.resources.displayMetrics.density, context.resources.configuration.fontScale),
        fontFamilyResolver = createFontFamilyResolver(context))

    fun labelWidth(context: Context, label: String): Int =
        ceil(label(context, label).maxIntrinsicWidth).toInt() + 2 * (TEXT_PADDING * context.resources.displayMetrics.density).roundToInt()
}
