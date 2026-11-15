package com.droiddeck.launcher.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.QuestXrActivity

/** A faint bottom-right tag on every screen of a test or local build; stable and preview builds draw none. */
object BuildWatermark {
    private const val TITLE_LIMIT = 80

    fun lines(
        pr: Int = BuildConfig.PR_NUMBER,
        ci: Boolean = BuildConfig.CI_BUILD,
        commit: String = BuildConfig.SOURCE_COMMIT,
        title: String = BuildConfig.PR_TITLE,
        branch: String = BuildConfig.BUILD_BRANCH,
    ): List<String>? {
        if (ci && pr <= 0) return null
        val hash = commit.take(7).ifEmpty { "unknown" }
        val head = if (pr > 0) "PR-$pr | $hash" else "LOCAL | $hash"
        val detail = (if (pr > 0) title else branch).trim()
        return if (detail.isEmpty()) listOf(head) else listOf(head, truncate(detail))
    }

    private fun truncate(text: String) =
        if (text.length <= TITLE_LIMIT) text else text.take(TITLE_LIMIT - 1).trimEnd() + "…"

    fun install(app: Application) {
        val lines = lines() ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is QuestXrActivity) return
                val decor = activity.window.decorView as? ViewGroup ?: return
                if (decor.findViewWithTag<View>(TAG) != null) return
                val margin = (8 * activity.resources.displayMetrics.density).toInt()
                decor.addView(
                    MarkView(activity, lines).apply { tag = TAG },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.END,
                    ).apply { setMargins(margin, margin, margin, margin) },
                )
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private const val TAG = "build-watermark"

    private class MarkView(context: Context, private val lines: List<String>) : View(context) {
        private var shown = lines
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            typeface = Typeface.MONOSPACE
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 9f, context.resources.displayMetrics,
            )
            setShadowLayer(1.5f * context.resources.displayMetrics.density, 0f, 0f, 0xFF000000.toInt())
            textAlign = Paint.Align.RIGHT
        }
        private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66303030 }
        private val lineHeight get() = paint.fontSpacing

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val pad = paddingForShadow() * 2
            val limit = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) Float.MAX_VALUE
                else (MeasureSpec.getSize(widthSpec) - pad).coerceAtLeast(0).toFloat()
            shown = lines.map { TextUtils.ellipsize(it, paint, limit, TextUtils.TruncateAt.END).toString() }
            val width = shown.maxOf { paint.measureText(it) }.toInt() + pad
            setMeasuredDimension(width, (lineHeight * shown.size).toInt() + pad)
        }

        override fun onDraw(canvas: Canvas) {
            val pad = paddingForShadow()
            val radius = 3f * resources.displayMetrics.density
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, backdrop)
            shown.forEachIndexed { i, line ->
                canvas.drawText(line, width - pad.toFloat(), pad - paint.ascent() + lineHeight * i, paint)
            }
        }

        override fun onTouchEvent(event: android.view.MotionEvent) = false

        private fun paddingForShadow() = (3 * resources.displayMetrics.density).toInt()
    }
}
