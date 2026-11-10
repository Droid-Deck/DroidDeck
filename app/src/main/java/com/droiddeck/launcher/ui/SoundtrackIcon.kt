package com.droiddeck.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private const val NOTES = "M8,5 L17.5,3 L17.5,6.5 L8,8.5 Z M8,5 H9.5 V18 H8 Z M16,3 H17.5 V16 H16 Z " +
    "M6.7,15.8 A2.8,2.2 0 1,0 6.7,20.2 A2.8,2.2 0 1,0 6.7,15.8 Z M14.7,13.8 A2.8,2.2 0 1,0 14.7,18.2 A2.8,2.2 0 1,0 14.7,13.8 Z"

internal val SoundtrackIcon: ImageVector by lazy {
    ImageVector.Builder("Soundtrack", 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(NOTES), fill = SolidColor(Color.Black))
        .build()
}
