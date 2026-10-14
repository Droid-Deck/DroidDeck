package com.droiddeck.launcher.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.outlinedMenuCard(): Modifier {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    return this
        .clip(shape)
        .background(cs.surfaceContainer)
        .border(1.dp, cs.outline, shape)
}

@Composable
internal fun MenuItemDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
}
