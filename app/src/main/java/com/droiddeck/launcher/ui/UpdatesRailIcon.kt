package com.droiddeck.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.update.AppUpdates

internal enum class BuildKind { STABLE, PREVIEW, TEST, LOCAL }

internal object RunningBuild {
    var channel by mutableStateOf(AppUpdates.Channel.STABLE)

    fun kind(me: AppUpdates.Installed = AppUpdates.installed(), channel: AppUpdates.Channel = this.channel): BuildKind = when {
        me.pr > 0 -> BuildKind.TEST
        !me.ciBuild -> BuildKind.LOCAL
        channel == AppUpdates.Channel.NIGHTLY -> BuildKind.PREVIEW
        else -> BuildKind.STABLE
    }
}

private const val PHONE = "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z"
private const val LETTER_P = "M9.6,7.6 H12.6 A2.4,2.4 0 0 1 12.6,12.4 H11.4 V16.4 H9.6 Z M11.4,9.2 V10.8 H12.6 A0.8,0.8 0 0 0 12.6,9.2 Z"
private const val LETTER_T = "M8.6,7.6 H15.4 V9.3 H12.9 V16.4 H11.1 V9.3 H8.6 Z"
private const val LETTER_L = "M9.8,7.6 H11.6 V14.7 H15 V16.4 H9.8 Z"

private fun phoneWith(name: String, letter: String) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(PHONE), fill = SolidColor(Color.Black))
        .addPath(addPathNodes(letter), fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd)
        .build()

private val PreviewIcon by lazy { phoneWith("UpdatesPreview", LETTER_P) }
private val TestIcon by lazy { phoneWith("UpdatesTest", LETTER_T) }
private val LocalIcon by lazy { phoneWith("UpdatesLocal", LETTER_L) }

internal fun updatesIcon(kind: BuildKind): ImageVector = when (kind) {
    BuildKind.STABLE -> Icons.Filled.SystemUpdate
    BuildKind.PREVIEW -> PreviewIcon
    BuildKind.TEST -> TestIcon
    BuildKind.LOCAL -> LocalIcon
}
