package com.droiddeck.launcher.ui

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import kotlin.math.ceil
import kotlin.math.sqrt

internal data class FullscreenSafeInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    fun maxWith(other: FullscreenSafeInsets) = FullscreenSafeInsets(
        left = maxOf(left, other.left),
        top = maxOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )
}

internal enum class FullscreenCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

internal data class FullscreenRoundedCorner(
    val position: FullscreenCorner,
    val radius: Int,
    val centerX: Int,
    val centerY: Int,
)

internal fun roundedCornerSafeInsets(
    width: Int,
    height: Int,
    corners: Iterable<FullscreenRoundedCorner>,
): FullscreenSafeInsets {
    if (width <= 0 || height <= 0) return FullscreenSafeInsets()
    val landscape = width > height
    var result = FullscreenSafeInsets()
    corners.forEach { corner ->
        if (corner.radius <= 0) return@forEach
        val horizontallyVisible = corner.centerX + corner.radius > 0 && corner.centerX - corner.radius < width
        val verticallyVisible = corner.centerY + corner.radius > 0 && corner.centerY - corner.radius < height
        if (!horizontallyVisible || !verticallyVisible) return@forEach
        val cornerQuadrantIntersectsWindow = when (corner.position) {
            FullscreenCorner.TOP_LEFT -> corner.centerX > 0 && corner.centerY > 0
            FullscreenCorner.TOP_RIGHT -> corner.centerX < width && corner.centerY > 0
            FullscreenCorner.BOTTOM_RIGHT -> corner.centerX < width && corner.centerY < height
            FullscreenCorner.BOTTOM_LEFT -> corner.centerX > 0 && corner.centerY < height
        }
        if (!cornerQuadrantIntersectsWindow) return@forEach
        if (landscape) {
            val verticalDistance = when (corner.position) {
                FullscreenCorner.TOP_LEFT, FullscreenCorner.TOP_RIGHT -> corner.centerY
                FullscreenCorner.BOTTOM_RIGHT, FullscreenCorner.BOTTOM_LEFT -> height - corner.centerY
            }.coerceIn(0, corner.radius)
            val horizontalReach = sqrt(
                (corner.radius.toDouble() * corner.radius - verticalDistance.toDouble() * verticalDistance),
            )
            result = when (corner.position) {
                FullscreenCorner.TOP_LEFT, FullscreenCorner.BOTTOM_LEFT -> result.maxWith(FullscreenSafeInsets(
                    left = if (corner.centerX - corner.radius <= 0)
                        ceil((corner.centerX - horizontalReach).coerceAtLeast(0.0)).toInt() else 0,
                ))
                FullscreenCorner.TOP_RIGHT, FullscreenCorner.BOTTOM_RIGHT -> result.maxWith(FullscreenSafeInsets(
                    right = if (corner.centerX + corner.radius >= width)
                        ceil((width - (corner.centerX + horizontalReach)).coerceAtLeast(0.0)).toInt() else 0,
                ))
            }
            return@forEach
        }
        // A rectangular viewport only has to clear the 45-degree point of the rounded arc.
        // Reserving the full radius on both axes turns a local corner into a full-edge band.
        val diagonalOffset = (corner.radius / sqrt(2.0)).toInt()
        when (corner.position) {
            FullscreenCorner.TOP_LEFT -> result = result.maxWith(FullscreenSafeInsets(
                left = if (corner.centerX - corner.radius <= 0)
                    (corner.centerX - diagonalOffset).coerceIn(0, width) else 0,
                top = if (corner.centerY - corner.radius <= 0)
                    (corner.centerY - diagonalOffset).coerceIn(0, height) else 0,
            ))
            FullscreenCorner.TOP_RIGHT -> result = result.maxWith(FullscreenSafeInsets(
                top = if (corner.centerY - corner.radius <= 0)
                    (corner.centerY - diagonalOffset).coerceIn(0, height) else 0,
                right = if (corner.centerX + corner.radius >= width)
                    (width - corner.centerX - diagonalOffset).coerceIn(0, width) else 0,
            ))
            FullscreenCorner.BOTTOM_RIGHT -> result = result.maxWith(FullscreenSafeInsets(
                right = if (corner.centerX + corner.radius >= width)
                    (width - corner.centerX - diagonalOffset).coerceIn(0, width) else 0,
                bottom = if (corner.centerY + corner.radius >= height)
                    (height - corner.centerY - diagonalOffset).coerceIn(0, height) else 0,
            ))
            FullscreenCorner.BOTTOM_LEFT -> result = result.maxWith(FullscreenSafeInsets(
                left = if (corner.centerX - corner.radius <= 0)
                    (corner.centerX - diagonalOffset).coerceIn(0, width) else 0,
                bottom = if (corner.centerY + corner.radius >= height)
                    (height - corner.centerY - diagonalOffset).coerceIn(0, height) else 0,
            ))
        }
    }
    return result
}

/** Keep the launcher full width in portrait while retaining its existing landscape insets. */
internal fun launcherRoundedCornerSafeInsets(
    width: Int,
    height: Int,
    corners: Iterable<FullscreenRoundedCorner>,
): FullscreenSafeInsets {
    if (width <= 0 || height <= 0) return FullscreenSafeInsets()
    if (width >= height) return roundedCornerSafeInsets(width, height, corners)

    // Rotate portrait geometry clockwise so the existing landscape calculation can
    // find each exposed arc at the window's left/right edges, then rotate it back.
    val rotated = corners.map { corner ->
        FullscreenRoundedCorner(
            position = when (corner.position) {
                FullscreenCorner.TOP_LEFT -> FullscreenCorner.TOP_RIGHT
                FullscreenCorner.TOP_RIGHT -> FullscreenCorner.BOTTOM_RIGHT
                FullscreenCorner.BOTTOM_RIGHT -> FullscreenCorner.BOTTOM_LEFT
                FullscreenCorner.BOTTOM_LEFT -> FullscreenCorner.TOP_LEFT
            },
            radius = corner.radius,
            centerX = height - corner.centerY,
            centerY = corner.centerX,
        )
    }
    val insets = roundedCornerSafeInsets(height, width, rotated)
    return FullscreenSafeInsets(
        top = insets.right.coerceIn(0, height),
        bottom = insets.left.coerceIn(0, height),
    )
}

@Composable
internal fun rememberFullscreenSafeInsets(
    enabled: Boolean,
    preferFullWidthInPortrait: Boolean = false,
): FullscreenSafeInsets {
    val view = LocalView.current
    var insets by remember(view, enabled, preferFullWidthInPortrait) {
        mutableStateOf(fullscreenSafeInsets(view, enabled, preferFullWidthInPortrait = preferFullWidthInPortrait))
    }
    DisposableEffect(view, enabled, preferFullWidthInPortrait) {
        val controller = FullscreenSafeInsetsController(
            view, { enabled }, { next -> if (next != insets) insets = next }, preferFullWidthInPortrait,
        )
        onDispose(controller::close)
    }
    return insets
}

internal class FullscreenSafeInsetsController(
    private val view: View,
    private val enabled: () -> Boolean,
    private val onChanged: (FullscreenSafeInsets) -> Unit,
    private val preferFullWidthInPortrait: Boolean = false,
) : AutoCloseable {
    private var current: FullscreenSafeInsets? = null
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
    private val observer = view.viewTreeObserver
    private val globalLayoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { refresh() }

    init {
        view.addOnLayoutChangeListener(layoutListener)
        if (observer.isAlive) observer.addOnGlobalLayoutListener(globalLayoutListener)
        view.requestApplyInsets()
        refresh()
    }

    fun refresh() = update(view.rootWindowInsets)

    private fun update(windowInsets: android.view.WindowInsets?) {
        val next = fullscreenSafeInsets(view, enabled(), windowInsets, preferFullWidthInPortrait)
        if (current == next) return
        current = next
        onChanged(next)
    }

    override fun close() {
        view.removeOnLayoutChangeListener(layoutListener)
        if (observer.isAlive) observer.removeOnGlobalLayoutListener(globalLayoutListener)
    }
}

internal fun safeContentSize(
    width: Int,
    height: Int,
    insets: FullscreenSafeInsets,
): Pair<Int, Int> = (width - insets.left - insets.right).coerceAtLeast(0) to
    (height - insets.top - insets.bottom).coerceAtLeast(0)

internal fun fullscreenSafeInsetsEnabled(inPictureInPicture: Boolean, opticalViewport: Boolean): Boolean =
    !inPictureInPicture && !opticalViewport

internal fun surfaceLocalPoint(
    windowX: Float,
    windowY: Float,
    surfaceLeft: Int,
    surfaceTop: Int,
    surfaceWidth: Int,
    surfaceHeight: Int,
): Pair<Float, Float>? {
    val localX = windowX - surfaceLeft
    val localY = windowY - surfaceTop
    return if (localX >= 0f && localY >= 0f && localX < surfaceWidth && localY < surfaceHeight) {
        localX to localY
    } else null
}

private fun fullscreenSafeInsets(
    view: View,
    enabled: Boolean,
    windowInsets: android.view.WindowInsets? = view.rootWindowInsets,
    preferFullWidthInPortrait: Boolean = false,
): FullscreenSafeInsets {
    if (!enabled) return FullscreenSafeInsets()
    val insets = windowInsets ?: return FullscreenSafeInsets()
    if (Build.VERSION.SDK_INT < 31) return FullscreenSafeInsets()
    val location = IntArray(2)
    view.getLocationInWindow(location)
    val corners = listOfNotNull(
        insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
            ?.toFullscreenCorner(FullscreenCorner.TOP_LEFT, location[0], location[1]),
        insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
            ?.toFullscreenCorner(FullscreenCorner.TOP_RIGHT, location[0], location[1]),
        insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
            ?.toFullscreenCorner(FullscreenCorner.BOTTOM_RIGHT, location[0], location[1]),
        insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
            ?.toFullscreenCorner(FullscreenCorner.BOTTOM_LEFT, location[0], location[1]),
    )
    return if (preferFullWidthInPortrait)
        launcherRoundedCornerSafeInsets(view.width, view.height, corners)
    else roundedCornerSafeInsets(view.width, view.height, corners)
}

private fun RoundedCorner.toFullscreenCorner(
    position: FullscreenCorner,
    windowOffsetX: Int,
    windowOffsetY: Int,
) = FullscreenRoundedCorner(
    position = position,
    radius = radius,
    centerX = center.x - windowOffsetX,
    centerY = center.y - windowOffsetY,
)
