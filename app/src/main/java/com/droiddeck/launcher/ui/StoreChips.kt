package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

// Where a game came from, as a small coloured chip with its mark: Steam, one of the stores, or an added folder.

/** The chip's colours per source: a tinted ground and a readable ink on it, one pair per store. */
internal class SourceColours(val fill: Color, val ink: Color, val dot: Color)

internal fun sourceColours(source: String): SourceColours = when (source) {
    Store.GOG.id -> SourceColours(Color(0xFF3A1A3C), Color(0xFFE6A3EA), Color(0xFFC25BC8))
    Store.EPIC.id -> SourceColours(Color(0xFF2A2A2A), Color(0xFFDDDDDD), Color(0xFFBDBDBD))
    Store.AMAZON.id -> SourceColours(Color(0xFF3A2A0A), Color(0xFFFFC266), Color(0xFFFF9900))
    Library.SOURCE_STEAM -> SourceColours(Color(0xFF16293D), Color(0xFF7CC4FF), Color(0xFF1A9FFF))
    else -> SourceColours(Color(0xFF1A1D22), Color(0xFF9AA3AF), Color(0xFF9AA3AF))
}

@Composable
internal fun sourceLabel(source: String): String = when (source) {
    Library.SOURCE_STEAM -> stringResource(R.string.source_steam)
    Store.GOG.id -> Store.GOG.label
    Store.EPIC.id -> Store.EPIC.label
    Store.AMAZON.id -> Store.AMAZON.label
    else -> stringResource(R.string.source_added)
}

/** The source's mark: the store's glyph (Simple Icons, CC0), Steam's, or a folder for a Custom game. */
@Composable
internal fun sourcePainter(source: String): Painter = when (source) {
    Store.GOG.id -> painterResource(R.drawable.ic_store_gog)
    Store.EPIC.id -> painterResource(R.drawable.ic_store_epic)
    Store.AMAZON.id -> painterResource(R.drawable.ic_store_amazon)
    Library.SOURCE_STEAM -> painterResource(R.drawable.ic_store_steam)
    else -> rememberVectorPainter(Icons.Outlined.CreateNewFolder)
}

/**
 * The source chip: a small rounded square in the source's colours holding its mark, the name as
 * what a screen reader says. [withName] (a hero, a game page) puts the name beside the mark;
 * [small] is a list row's size.
 */
@Composable
internal fun SourceChip(source: String, small: Boolean = false, withName: Boolean = false, modifier: Modifier = Modifier) {
    val c = sourceColours(source)
    val name = sourceLabel(source)
    val side = if (small) 22.dp else 24.dp
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.heightIn(min = side).clip(RoundedCornerShape(6.dp)).background(c.fill)
            .then(if (withName) Modifier.padding(start = 4.dp, end = 8.dp) else Modifier.widthIn(min = side))
            .semantics(mergeDescendants = true) { contentDescription = name },
    ) {
        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
            Icon(sourcePainter(source), null, tint = c.ink, modifier = Modifier.size(if (small) 14.dp else 16.dp))
        }
        if (withName) Text(name, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, color = c.ink, maxLines = 1)
    }
}

/** A status chip in the stores' colours: green for done ("Installed"), blue for busy, amber for a warning. */
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
