package com.droiddeck.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

internal val CollectionsIcon: ImageVector = ImageVector.Builder("Collections", 24.dp, 24.dp, 24f, 24f).apply {
    for (d in listOf(
        "M2.65,4.6H7.33V22H2.65Z",
        "M8.32,2H13V22H8.32Z",
        "M13.9,5.4H18.5L21.4,22H16.8Z",
    )) addPath(addPathNodes(d), fill = SolidColor(Color.Black))
}.build()
