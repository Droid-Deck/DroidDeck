package com.droiddeck.launcher.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.InputSurfaceView
import com.droiddeck.launcher.ui.PadArt

class OnScreenControls(context: Context, pad: PadBridge?, val dock: OnScreenLayout.Dock? = null) : FrameLayout(context) {
    /** Why the controls are hidden while the corner button stays. */
    enum class Hidden { BY_TAP, BY_CONTROLLER }

    private val surface = InputSurfaceView(context, pad, inputRole = InputRole.PRIMARY, haptics = dock != null)
    private var layout: SavedInputLayout? = null
    private val safe = Rect()
    private var cutouts = emptyList<Rect>()
    private var viewWidth = 0
    private var viewHeight = 0
    private val corner = CornerButton(context)
    var hidden: Hidden? = null
        private set
    var shortcutsOnly = false
        private set
    /** Holding the corner button opens DroidDeck's controller menu. */
    var onCornerHold: (() -> Unit)? = null
    /** The player tapped the corner button. */
    var onCornerTap: ((Hidden?) -> Unit)? = null
    val inputSurface: InputSurfaceView get() = surface
    var onTouchMiss: ((MotionEvent) -> Unit)?
        get() = surface.onTouchMiss
        set(value) { surface.onTouchMiss = value }

    init {
        addView(surface, LayoutParams(-1, -1))
        val size = (CORNER_HIT_DP * resources.displayMetrics.density).toInt()
        addView(corner, LayoutParams(size, size, Gravity.START or Gravity.BOTTOM))
    }

    fun updateLayout(value: SavedInputLayout?) {
        val kit = PadKit.current(context)
        if (layout == value && surface.kit == kit) return
        releaseAll()
        layout = value
        surface.kit = kit
        render()
    }

    fun setPresentation(value: Hidden?, shortcuts: Boolean = false) {
        if (hidden == value && shortcutsOnly == shortcuts) return
        releaseAll()
        hidden = value
        shortcutsOnly = shortcuts
        render()
    }

    private fun render() {
        val placed = OnScreenLayout.elements(context, layout, viewWidth, viewHeight, safe, dock, cutouts,
            rearButtons = SessionPrefs.oscRearButtons(context), trackpads = SessionPrefs.oscTrackpads(context))
        val visible = when {
            hidden != null -> placed.filter { it.alwaysShown }
            shortcutsOnly -> OnScreenLayout.steamShortcuts(viewWidth, viewHeight, safe, resources.displayMetrics.density) +
                placed.filter { it.alwaysShown }
            else -> placed
        }
        surface.updateCustomLayout(visible)
        surface.updateStyle(layout?.style ?: InputSurfaceStyle())
        corner.visibility = if (layout == null || dock != null) GONE else VISIBLE
        corner.alpha = (layout?.style?.opacity ?: 100) / 100f
        val density = resources.displayMetrics.density
        val margin = (CORNER_MARGIN_DP * density).toInt()
        val params = corner.layoutParams as LayoutParams
        val leftInset = if (dock == null && viewWidth >= viewHeight && layout?.let(OnScreenLayout::generated) == true)
            KitLayout.cornerInset(safe, cutouts, viewHeight, density) else safe.left
        if (params.leftMargin != leftInset + margin || params.bottomMargin != safe.bottom + margin) {
            params.leftMargin = leftInset + margin
            params.bottomMargin = safe.bottom + margin
            corner.layoutParams = params
        }
        corner.invalidate()
    }

    fun takesTouchAt(x: Float, y: Float) = surface.takesTouchAt(x, y) ||
        corner.visibility == VISIBLE && x >= corner.left && x < corner.right && y >= corner.top && y < corner.bottom
    fun cornerTakes(x: Float, y: Float) = corner.visibility == VISIBLE && x >= corner.left && x < corner.right && y >= corner.top && y < corner.bottom
    fun releaseAll() = surface.releaseInputs()
    fun reload() { releaseAll(); surface.kit = PadKit.current(context); render() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (w != viewWidth || h != viewHeight) {
            viewWidth = w
            viewHeight = h
            render()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDetachedFromWindow() { releaseAll(); super.onDetachedFromWindow() }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
        val fresh = Rect(cutout?.safeInsetLeft ?: 0, if (dock == null) cutout?.safeInsetTop ?: 0 else 0, cutout?.safeInsetRight ?: 0, cutout?.safeInsetBottom ?: 0)
        val bounds = cutout?.boundingRects.orEmpty()
        if (fresh != safe || bounds != cutouts) {
            safe.set(fresh)
            cutouts = bounds
            render()
        }
        return super.onApplyWindowInsets(insets)
    }

    private inner class CornerButton(context: Context) : View(context) {
        private val art = PadArt(context)
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
        private var armed = false
        private var held = false
        private val hold = Runnable { if (armed) { held = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val dimmed = hidden != null && !armed
            if (dimmed) canvas.saveLayerAlpha(0f, 0f, w, h, 170)
            art.system(canvas, w, h, PadArt.Shape.ROUND, PadArt.Icon.MARK, armed)
            if (dimmed) canvas.restore()
            if (hidden == Hidden.BY_CONTROLLER) {
                val d = art.density
                val r = RectF(w - 15 * d, 3 * d, w - 2 * d, 12 * d)
                badge.style = Paint.Style.FILL; badge.color = Color.rgb(0x16, 0x19, 0x1E)
                canvas.drawRoundRect(r, 2.5f * d, 2.5f * d, badge)
                badge.style = Paint.Style.STROKE; badge.strokeWidth = 1.3f * d; badge.color = Color.rgb(0xFF, 0xB3, 0x47)
                canvas.drawRoundRect(r, 2.5f * d, 2.5f * d, badge)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { armed = true; held = false; postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong()); invalidate() }
                MotionEvent.ACTION_CANCEL -> { armed = false; removeCallbacks(hold); invalidate() }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(hold)
                    if (armed && held) post { onCornerHold?.invoke() }
                    else if (armed && event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        val next = if (hidden == null) Hidden.BY_TAP else null
                        setPresentation(next, shortcutsOnly)
                        onCornerTap?.invoke(next)
                    }
                    armed = false
                    invalidate()
                }
            }
            return true
        }
    }

    private companion object {
        const val CORNER_HIT_DP = 48f
        const val CORNER_MARGIN_DP = 4f
    }
}
