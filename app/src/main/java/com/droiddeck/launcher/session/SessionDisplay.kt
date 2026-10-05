package com.droiddeck.launcher.session

import android.content.Context
import android.os.Build
import android.view.WindowManager

/** The virtual display advertised to the guest, before it is fitted onto the Android panel. */
object SessionDisplay {
    fun panelSize(context: Context): Pair<Int, Int> {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = if (Build.VERSION.SDK_INT >= 30) manager.maximumWindowMetrics.bounds else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealMetrics(metrics)
            android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        return maxOf(bounds.width(), bounds.height()) to minOf(bounds.width(), bounds.height())
    }

    fun resolve(panel: Pair<Int, Int>, cap: Int, shape: String, custom: Pair<Int, Int>? = null): Pair<Int, Int> {
        custom?.let { return it }
        val panelW = maxOf(panel.first, panel.second).toFloat()
        val panelH = minOf(panel.first, panel.second).toFloat().coerceAtLeast(1f)
        // Auto retains wide panels but floors squarer ones at 16:9 for game compatibility.
        // Match screen removes that floor; fixed 16:9 also keeps foldable sessions stable.
        val aspect = when (shape) {
            SessionPrefs.SHAPE_WIDE -> 16f / 9f
            SessionPrefs.SHAPE_EXACT -> panelW / panelH
            else -> maxOf(panelW / panelH, 16f / 9f)
        }
        val height = (if (cap <= 0) panelH else minOf(panelH, cap.toFloat())).toInt()
        return ((height * aspect).toInt() and 1.inv()) to (height and 1.inv())
    }

    /** Mirrors gamescope's even-width 16:9 calculation, including near-16:9 rounding. */
    fun canStretch16x9(size: Pair<Int, Int>): Boolean =
        (size.second * 16 + 4) / 9 / 2 * 2 > size.first
}
