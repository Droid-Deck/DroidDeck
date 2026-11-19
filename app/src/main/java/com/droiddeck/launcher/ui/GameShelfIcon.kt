package com.droiddeck.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

internal val GameShelfIcon: ImageVector = ImageVector.Builder("GameShelf", 24.dp, 24.dp, 24f, 24f).apply {
    for (d in listOf(
        "M3,4H7V20H3ZM4.4,5.4V18.6H5.6V5.4Z",
        "M8.5,4H12.5V20H8.5ZM9.9,5.4V18.6H11.1V5.4Z",
        "M14.2,5.1L18.1,4.2L21.4,19.8L17.5,20.7ZM15.9,6.2L18.6,18.9L19.8,18.6L17.1,5.9Z",
        "M2,21H22V22.5H2Z",
    )) addPath(addPathNodes(d), fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd)
}.build()
