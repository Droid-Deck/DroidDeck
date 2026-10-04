package com.droiddeck.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.droiddeck.launcher.R

/**
 * The DroidDeck wordmark from the README banner (artwork/droiddeck-banner-*.svg): the mark
 * standing in as the D, then "roidDeck". Letters and D in the theme's ink, the ball in its blue.
 */
@Composable
internal fun Wordmark(height: Dp, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onBackground
    val ball = LocalPalette.current.signal
    val name = stringResource(R.string.app_name)
    val letters = remember { PathParser().parsePathString(LETTERS).toPath() }
    val leftHalf = remember { PathParser().parsePathString(LEFT_HALF).toPath() }
    val rightHalf = remember { PathParser().parsePathString(RIGHT_HALF).toPath() }
    Canvas(modifier.height(height).width(height * (BOX_W / BOX_H)).semantics { contentDescription = name }) {
        val k = size.height / BOX_H
        scale(k, k, Offset.Zero) {
            translate(-BOX_X, -BOX_Y) {
                drawPath(letters, ink)
                // The mark's placement in the banner: translate(239.135 123.36) scale(0.527445) translate(31.98 0).
                translate(239.135f, 123.36f) {
                    scale(MARK_SCALE, MARK_SCALE, Offset.Zero) {
                        translate(31.98f, 0f) {
                            drawPath(leftHalf, ink)
                            drawPath(rightHalf, ink)
                            drawCircle(ball, 55.98f, Offset(63.98f, 111.86f))
                        }
                    }
                }
            }
        }
    }
}

// The banner's own units: the wordmark spans x 239.135..1040.7 and y 120.02..243.2.
private const val BOX_X = 239.135f
private const val BOX_Y = 120.02f
private const val BOX_W = 1040.7f - 239.135f
private const val BOX_H = 243.2f - 120.02f
private const val MARK_SCALE = 0.527445f

private const val LEFT_HALF = "M-31.98 0H56.055V40.489A71.81 71.81 0 0 0 56.055 183.231V223.72H-31.98Z"
private const val RIGHT_HALF = "M71.885 0.28A111.86 111.86 0 0 1 71.885 223.44V183.234A71.81 71.81 0 0 0 71.885 40.486Z"
private const val LETTERS =
    "M361.45 241.36V160.13H387.02V241.36ZM387.02 196.73 376.32 188.38Q379.5 174.17 387.02 166.31Q394.54 158.46 407.91 158.46Q413.76 158.46 418.19 160.21Q422.62 161.97 425.96 165.65L410.75 184.87Q409.08 183.03 406.57 182.03Q404.07 181.02 400.89 181.02Q394.54 181.02 390.78 184.95Q387.02 188.88 387.02 196.73" +
    "ZM467.91 243.2Q455.38 243.2 445.27 237.6Q435.15 232 429.3 222.31Q423.45 212.61 423.45 200.58Q423.45 188.54 429.3 179.02Q435.15 169.49 445.18 163.89Q455.21 158.29 467.91 158.29Q480.62 158.29 490.64 163.81Q500.67 169.32 506.52 178.93Q512.37 188.54 512.37 200.58Q512.37 212.61 506.52 222.31Q500.67 232 490.64 237.6Q480.62 243.2 467.91 243.2" +
    "ZM467.91 219.97Q473.43 219.97 477.61 217.54Q481.79 215.12 484.04 210.69Q486.3 206.26 486.3 200.58Q486.3 194.9 483.96 190.63Q481.62 186.37 477.52 183.95Q473.43 181.52 467.91 181.52Q462.56 181.52 458.39 183.95Q454.21 186.37 451.87 190.72Q449.53 195.06 449.53 200.75Q449.53 206.26 451.87 210.69Q454.21 215.12 458.39 217.54Q462.56 219.97 467.91 219.97" +
    "ZM523.4 241.36V160.13H548.98V241.36ZM536.27 148.93Q530.26 148.93 526.33 144.84Q522.4 140.74 522.4 134.89Q522.4 128.88 526.33 124.86Q530.26 120.85 536.27 120.85Q542.29 120.85 546.13 124.86Q549.98 128.88 549.98 134.89Q549.98 140.74 546.13 144.84Q542.29 148.93 536.27 148.93ZM599.95 243.03Q588.42 243.03 579.48 237.52Q570.54 232 565.44 222.47Q560.34 212.95 560.34 200.75Q560.34 188.54 565.44 179.02Q570.54 169.49 579.48 163.97Q588.42 158.46 599.95 158.46Q608.31 158.46 615.08 161.63Q621.85 164.81 626.28 170.41Q630.71 176.01 631.21 183.2V217.46Q630.71 224.65 626.36 230.41Q622.02 236.18 615.16 239.61Q608.31 243.03 599.95 243.03" +
    "ZM604.47 219.97Q609.98 219.97 613.99 217.54Q618 215.12 620.34 210.77Q622.68 206.43 622.68 200.75Q622.68 195.06 620.43 190.8Q618.17 186.54 614.08 184.03Q609.98 181.52 604.63 181.52Q599.28 181.52 595.19 184.03Q591.09 186.54 588.67 190.88Q586.25 195.23 586.25 200.75Q586.25 206.26 588.59 210.61Q590.93 214.95 595.11 217.46Q599.28 219.97 604.47 219.97" +
    "ZM646.75 241.36H621.68V219.46L625.53 199.74L621.18 180.02V120.02H646.75ZM683.19 241.36V218.29H712.44Q723.13 218.29 731.16 214.03Q739.18 209.77 743.53 201.58Q747.87 193.39 747.87 182.19Q747.87 170.99 743.44 162.97Q739.01 154.95 731.07 150.6Q723.13 146.26 712.44 146.26H682.35V123.36H712.77Q726.14 123.36 737.42 127.62Q748.71 131.88 757.15 139.82Q765.59 147.76 770.18 158.54Q774.78 169.32 774.78 182.36Q774.78 195.23 770.18 206.09Q765.59 216.96 757.23 224.81Q748.87 232.67 737.59 237.01Q726.31 241.36 713.11 241.36" +
    "ZM665.47 241.36V123.36H691.71V241.36ZM827.6 243.2Q814.39 243.2 804.11 237.77Q793.83 232.33 787.98 222.64Q782.13 212.95 782.13 200.75Q782.13 188.54 787.9 178.93Q793.67 169.32 803.53 163.81Q813.39 158.29 825.76 158.29Q837.79 158.29 846.98 163.47Q856.18 168.65 861.44 177.85Q866.71 187.04 866.71 198.91Q866.71 201.08 866.46 203.5Q866.21 205.93 865.54 209.1L795.51 209.27V191.72L854.67 191.55L843.64 198.91Q843.47 191.89 841.47 187.29Q839.46 182.69 835.54 180.27Q831.61 177.85 825.92 177.85Q819.91 177.85 815.48 180.61Q811.05 183.36 808.63 188.38Q806.2 193.39 806.2 200.58Q806.2 207.77 808.79 212.86Q811.38 217.96 816.15 220.72Q820.91 223.48 827.43 223.48Q833.45 223.48 838.29 221.39Q843.14 219.3 846.82 215.12L860.86 229.16Q854.84 236.18 846.32 239.69Q837.79 243.2 827.6 243.2" +
    "ZM916.68 243.2Q904.15 243.2 893.95 237.68Q883.75 232.17 877.9 222.47Q872.05 212.78 872.05 200.75Q872.05 188.54 877.99 178.93Q883.92 169.32 894.12 163.81Q904.31 158.29 917.02 158.29Q926.54 158.29 934.48 161.55Q942.42 164.81 948.6 171.33L932.22 187.71Q929.38 184.53 925.62 183.03Q921.86 181.52 917.02 181.52Q911.5 181.52 907.24 183.95Q902.98 186.37 900.55 190.63Q898.13 194.9 898.13 200.58Q898.13 206.26 900.55 210.61Q902.98 214.95 907.32 217.46Q911.67 219.97 917.02 219.97Q922.03 219.97 925.96 218.21Q929.88 216.46 932.73 213.28L948.94 229.66Q942.59 236.35 934.56 239.77Q926.54 243.2 916.68 243.2" +
    "ZM1010.61 241.36 982.2 199.24 1010.45 160.13H1039.03L1004.93 204.09L1005.77 193.56L1040.7 241.36ZM958.63 241.36V120.02H984.21V241.36Z"
