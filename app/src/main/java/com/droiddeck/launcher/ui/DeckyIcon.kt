package com.droiddeck.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

internal val DeckyIcon: ImageVector = ImageVector.Builder("Decky", 24.dp, 24.dp, 24f, 24f).apply {
    for (d in listOf(
        "M8,2h2v6H8Z",
        "M14,2h2v6h-2Z",
        "M5,8h14v3a7,7 0 0 1 -5,6.7V19h-4v-1.3A7,7 0 0 1 5,11Z",
        "M11,19h2v3h-2Z",
    )) addPath(addPathNodes(d), fill = SolidColor(Color.Black))
}.build()
