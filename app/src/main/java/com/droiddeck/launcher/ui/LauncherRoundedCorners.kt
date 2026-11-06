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

internal data class LauncherSafeInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    fun maxWith(other: LauncherSafeInsets) = LauncherSafeInsets(
        left = maxOf(left, other.left),
        top = maxOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )
}

internal enum class LauncherCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

internal data class LauncherRoundedCorner(
    val position: LauncherCorner,
    val radius: Int,
    val centerX: Int,
    val centerY: Int,
)

internal fun roundedCornerSafeInsets(
    width: Int,
    height: Int,
    corners: Iterable<LauncherRoundedCorner>,
): LauncherSafeInsets {
    if (width <= 0 || height <= 0) return LauncherSafeInsets()
    var result = LauncherSafeInsets()
    corners.forEach { corner ->
        if (corner.radius <= 0) return@forEach
        val horizontallyVisible = corner.centerX + corner.radius > 0 && corner.centerX - corner.radius < width
        val verticallyVisible = corner.centerY + corner.radius > 0 && corner.centerY - corner.radius < height
        when (corner.position) {
            LauncherCorner.TOP_LEFT -> result = result.maxWith(LauncherSafeInsets(
                left = if (horizontallyVisible && verticallyVisible && corner.centerX - corner.radius <= 0) corner.radius else 0,
                top = if (horizontallyVisible && verticallyVisible && corner.centerY - corner.radius <= 0) corner.radius else 0,
            ))
            LauncherCorner.TOP_RIGHT -> result = result.maxWith(LauncherSafeInsets(
                top = if (horizontallyVisible && verticallyVisible && corner.centerY - corner.radius <= 0) corner.radius else 0,
                right = if (horizontallyVisible && verticallyVisible && corner.centerX + corner.radius >= width) corner.radius else 0,
            ))
            LauncherCorner.BOTTOM_RIGHT -> result = result.maxWith(LauncherSafeInsets(
                right = if (horizontallyVisible && verticallyVisible && corner.centerX + corner.radius >= width) corner.radius else 0,
                bottom = if (horizontallyVisible && verticallyVisible && corner.centerY + corner.radius >= height) corner.radius else 0,
            ))
            LauncherCorner.BOTTOM_LEFT -> result = result.maxWith(LauncherSafeInsets(
                left = if (horizontallyVisible && verticallyVisible && corner.centerX - corner.radius <= 0) corner.radius else 0,
                bottom = if (horizontallyVisible && verticallyVisible && corner.centerY + corner.radius >= height) corner.radius else 0,
            ))
        }
    }
    return result
}

@Composable
internal fun rememberLauncherFullscreenSafeInsets(enabled: Boolean): LauncherSafeInsets {
    val view = LocalView.current
    var insets by remember(view, enabled) { mutableStateOf(launcherFullscreenSafeInsets(view, enabled)) }
    DisposableEffect(view, enabled) {
        fun update() {
            val next = launcherFullscreenSafeInsets(view, enabled)
            if (next != insets) insets = next
        }
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        val observer = view.viewTreeObserver
        val globalLayoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { update() }
        view.addOnLayoutChangeListener(layoutListener)
        if (observer.isAlive) observer.addOnGlobalLayoutListener(globalLayoutListener)
        update()
        onDispose {
            view.removeOnLayoutChangeListener(layoutListener)
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(globalLayoutListener)
        }
    }
    return insets
}

private fun launcherFullscreenSafeInsets(view: View, enabled: Boolean): LauncherSafeInsets {
    if (!enabled) return LauncherSafeInsets()
    val insets = view.rootWindowInsets ?: return LauncherSafeInsets()
    val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
    val cutoutInsets = LauncherSafeInsets(
        left = cutout?.safeInsetLeft ?: 0,
        top = cutout?.safeInsetTop ?: 0,
        right = cutout?.safeInsetRight ?: 0,
        bottom = cutout?.safeInsetBottom ?: 0,
    )
    if (Build.VERSION.SDK_INT < 31) return cutoutInsets
    val corners = listOfNotNull(
        insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.toLauncherCorner(LauncherCorner.TOP_LEFT),
        insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.toLauncherCorner(LauncherCorner.TOP_RIGHT),
        insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.toLauncherCorner(LauncherCorner.BOTTOM_RIGHT),
        insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.toLauncherCorner(LauncherCorner.BOTTOM_LEFT),
    )
    return cutoutInsets.maxWith(roundedCornerSafeInsets(view.width, view.height, corners))
}

private fun RoundedCorner.toLauncherCorner(position: LauncherCorner) = LauncherRoundedCorner(
    position = position,
    radius = radius,
    centerX = center.x,
    centerY = center.y,
)
