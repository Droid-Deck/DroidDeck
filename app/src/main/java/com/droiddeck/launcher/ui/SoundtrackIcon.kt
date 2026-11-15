package com.droiddeck.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private const val NOTES = "M8.4,4.5 L20,2 V6 L8.4,8.5 Z M8.4,4.5 H10.6 V17.5 H8.4 Z M17.9,2 H20 V15.5 H17.9 Z M7.2,14.7 A3.4,2.8 0 1,0 7.2,20.3 A3.4,2.8 0 1,0 7.2,14.7 Z M16.6,12.7 A3.4,2.8 0 1,0 16.6,18.3 A3.4,2.8 0 1,0 16.6,12.7 Z"

internal val SoundtrackIcon: ImageVector by lazy {
    ImageVector.Builder("Soundtrack", 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(NOTES), fill = SolidColor(Color.Black))
        .build()
}
