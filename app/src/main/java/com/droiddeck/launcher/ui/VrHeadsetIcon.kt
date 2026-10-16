package com.droiddeck.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

internal val VrHeadsetIcon: ImageVector = ImageVector.Builder("VrHeadset", 24.dp, 24.dp, 24f, 24f).apply {
    for (d in listOf(
        "M5,8H19A3,3 0 0 1 22,11V15A3,3 0 0 1 19,18H15.6L14.1,15.6A2.4,2.4 0 0 0 9.9,15.6L8.4,18H5A3,3 0 0 1 2,15V11A3,3 0 0 1 5,8Z",
        "M7,8V6.5A1.5,1.5 0 0 1 8.5,5H15.5A1.5,1.5 0 0 1 17,6.5V8",
    )) addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
}.build()
