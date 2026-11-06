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
    var result = FullscreenSafeInsets()
    corners.forEach { corner ->
        if (corner.radius <= 0) return@forEach
        val horizontallyVisible = corner.centerX + corner.radius > 0 && corner.centerX - corner.radius < width
        val verticallyVisible = corner.centerY + corner.radius > 0 && corner.centerY - corner.radius < height
        when (corner.position) {
            FullscreenCorner.TOP_LEFT -> result = result.maxWith(FullscreenSafeInsets(
                left = if (horizontallyVisible && verticallyVisible && corner.centerX - corner.radius <= 0) corner.radius else 0,
                top = if (horizontallyVisible && verticallyVisible && corner.centerY - corner.radius <= 0) corner.radius else 0,
            ))
            FullscreenCorner.TOP_RIGHT -> result = result.maxWith(FullscreenSafeInsets(
                top = if (horizontallyVisible && verticallyVisible && corner.centerY - corner.radius <= 0) corner.radius else 0,
                right = if (horizontallyVisible && verticallyVisible && corner.centerX + corner.radius >= width) corner.radius else 0,
            ))
            FullscreenCorner.BOTTOM_RIGHT -> result = result.maxWith(FullscreenSafeInsets(
                right = if (horizontallyVisible && verticallyVisible && corner.centerX + corner.radius >= width) corner.radius else 0,
                bottom = if (horizontallyVisible && verticallyVisible && corner.centerY + corner.radius >= height) corner.radius else 0,
            ))
            FullscreenCorner.BOTTOM_LEFT -> result = result.maxWith(FullscreenSafeInsets(
                left = if (horizontallyVisible && verticallyVisible && corner.centerX - corner.radius <= 0) corner.radius else 0,
                bottom = if (horizontallyVisible && verticallyVisible && corner.centerY + corner.radius >= height) corner.radius else 0,
            ))
        }
    }
    return result
}

@Composable
internal fun rememberFullscreenSafeInsets(enabled: Boolean): FullscreenSafeInsets {
    val view = LocalView.current
    var insets by remember(view, enabled) { mutableStateOf(fullscreenSafeInsets(view, enabled)) }
    DisposableEffect(view, enabled) {
        val controller = FullscreenSafeInsetsController(view, { enabled }) { next ->
            if (next != insets) insets = next
        }
        onDispose(controller::close)
    }
    return insets
}

internal class FullscreenSafeInsetsController(
    private val view: View,
    private val enabled: () -> Boolean,
    private val onChanged: (FullscreenSafeInsets) -> Unit,
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
        val next = fullscreenSafeInsets(view, enabled(), windowInsets)
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
): FullscreenSafeInsets {
    if (!enabled) return FullscreenSafeInsets()
    val insets = windowInsets ?: return FullscreenSafeInsets()
    val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
    val cutoutInsets = FullscreenSafeInsets(
        left = cutout?.safeInsetLeft ?: 0,
        top = cutout?.safeInsetTop ?: 0,
        right = cutout?.safeInsetRight ?: 0,
        bottom = cutout?.safeInsetBottom ?: 0,
    )
    if (Build.VERSION.SDK_INT < 31) return cutoutInsets
    val corners = listOfNotNull(
        insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.toFullscreenCorner(FullscreenCorner.TOP_LEFT),
        insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.toFullscreenCorner(FullscreenCorner.TOP_RIGHT),
        insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.toFullscreenCorner(FullscreenCorner.BOTTOM_RIGHT),
        insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.toFullscreenCorner(FullscreenCorner.BOTTOM_LEFT),
    )
    return cutoutInsets.maxWith(roundedCornerSafeInsets(view.width, view.height, corners))
}

private fun RoundedCorner.toFullscreenCorner(position: FullscreenCorner) = FullscreenRoundedCorner(
    position = position,
    radius = radius,
    centerX = center.x,
    centerY = center.y,
)
