package com.droiddeck.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private fun chevron(name: String, d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 2.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    .build()

internal val ChevronDownIcon: ImageVector = chevron("ChevronDown", "M6,9L12,15L18,9")
internal val ChevronUpIcon: ImageVector = chevron("ChevronUp", "M6,15L12,9L18,15")
