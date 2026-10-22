package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.Store


internal val FlathubTeal = Color(0xFF3FB8AF)
internal val FlathubInk = Color(0xFF04211F)

internal const val FLATHUB = "flathub"

internal class SourceColours(val fill: Color, val ink: Color, val dot: Color)

internal fun sourceColours(source: String): SourceColours = when (source) {
    Store.GOG.id -> SourceColours(Color(0xFF3A1A3C), Color(0xFFE6A3EA), Color(0xFFC25BC8))
    Store.EPIC.id -> SourceColours(Color(0xFF2A2A2A), Color(0xFFDDDDDD), Color(0xFFBDBDBD))
    Store.AMAZON.id -> SourceColours(Color(0xFF3A2A0A), Color(0xFFFFC266), Color(0xFFFF9900))
    Store.XBOX.id -> SourceColours(Color(0xFF17381B), Color(0xFFC6EDC2), Color(0xFF70C850))
    Store.ITCH.id -> SourceColours(Color(0xFF3A1A1E), Color(0xFFFFB3B3), Color(0xFFFF5C5C))
    Library.SOURCE_STEAM -> SourceColours(Color(0xFF16293D), Color(0xFF7CC4FF), Color(0xFF1A9FFF))
    FLATHUB -> SourceColours(Color(0xFF0B2E2B), Color(0xFF9FE3DD), FlathubTeal)
    else -> SourceColours(Color(0xFF1A1D22), Color(0xFF9AA3AF), Color(0xFF9AA3AF))
}

@Composable
internal fun sourceLabel(source: String): String = when (source) {
    Library.SOURCE_STEAM -> stringResource(R.string.source_steam)
    Store.GOG.id -> Store.GOG.label
    Store.EPIC.id -> Store.EPIC.label
    Store.AMAZON.id -> Store.AMAZON.label
    Store.XBOX.id -> Store.XBOX.label
    Store.ITCH.id -> Store.ITCH.label
    else -> stringResource(R.string.source_added)
}

@Composable
internal fun SourceChip(source: String, small: Boolean = false, modifier: Modifier = Modifier) {
    val c = sourceColours(source)
    Text(
        sourceLabel(source), fontSize = if (small) 10.sp else 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
        color = c.ink, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(c.fill)
            .padding(horizontal = if (small) 6.dp else 8.dp, vertical = if (small) 1.dp else 2.dp),
    )
}

@Composable
internal fun StateChip(text: String, tone: ChipTone, small: Boolean = false) {
    val pal = LocalPalette.current
    val (fill, ink) = when (tone) {
        ChipTone.OK -> Color(0xFF16321F) to pal.good
        ChipTone.BUSY -> pal.signal.copy(alpha = 0.18f) to pal.signal
        ChipTone.WARN -> Color(0xFF3A2E10) to Color(0xFFFFC24D)
        ChipTone.DEAL -> Color(0xFF1E3A1A) to Color(0xFF9BE27A)
        ChipTone.PLAIN -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text, fontSize = if (small) 9.sp else 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp, color = ink, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(fill).padding(horizontal = if (small) 5.dp else 8.dp, vertical = if (small) 1.dp else 2.dp),
    )
}

internal enum class ChipTone { OK, BUSY, WARN, DEAL, PLAIN }
