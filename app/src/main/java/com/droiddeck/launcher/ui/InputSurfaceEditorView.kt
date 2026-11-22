package com.droiddeck.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.InputControl
import com.droiddeck.launcher.input.InputElementKind
import com.droiddeck.launcher.input.InputSurfaceElement
import com.droiddeck.launcher.input.PadKit
import com.droiddeck.launcher.input.InputSurfaceStyle
import com.droiddeck.launcher.input.SurfaceBounds
import com.droiddeck.launcher.input.boundsOf
import com.droiddeck.launcher.input.moveSelection
import com.droiddeck.launcher.input.pressSelection
import com.droiddeck.launcher.input.resizeSelection
import com.droiddeck.launcher.input.scaleSelection
import com.droiddeck.launcher.input.sizePercent
import com.droiddeck.launcher.input.snapSurfaceElement
import com.droiddeck.launcher.input.tapSelection
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class InputSurfaceEditorView(context: Context) : FrameLayout(context) {
    var elements: List<InputSurfaceElement> = emptyList()
        set(value) {
            if (value == field) return
            field = value; preview.updateCustomLayout(value); popPending(); refreshGeometry()
            if (value.none { it.id == focused }) focused = null
            access.invalidateRoot()
        }
    /** Changes whenever Undo or Redo puts back an earlier draft, so the next key nudge starts its own undo step. */
    var restored = 0
        set(value) {
            if (value == field) return
            field = value; nudging = false
            // A drag still running from before the restore would write its old snapshot over the restored draft.
            if (original != null || badge) {
                if (badge) badgePress.animate(badgePress.value, 0f, RELEASE_MS, CubicEase.POP)
                badge = false; pointer = -1; endGesture()
            }
        }
    var style = InputSurfaceStyle()
        set(value) { if (value == field) return; field = value; preview.updateStyle(value, artwork); refreshGeometry() }
    var artwork: PadKit? = null
        set(value) { if (value == field) return; field = value; preview.updateStyle(style, value); refreshGeometry() }
    var snap = true
        set(value) { field = value; if (!value) clearGuides() }
    var selection: List<String> = emptyList()
        set(value) {
            if (value == field) return
            field = value
            if (value.isNotEmpty()) selectionIn.animate(0f, SELECT_MS, SELECT_MS.toLong(), LINEAR) else selectionIn.snap(SELECT_MS)
            nudging = false
            refreshGeometry()
            access.invalidateRoot()
        }
    var onSelect: (List<String>) -> Unit = {}
    var onBeginEdit: () -> Unit = {}
    var onChange: (List<InputSurfaceElement>) -> Unit = {}
    var onDelete: () -> Unit = {}
    /** The percent the readout bubble shows while a corner is dragged. Without it the readout is relative to the size when the drag began. */
    var sizeReadout: (() -> Int?)? = null

    private val preview = InputSurfaceView(context)
    private val density = resources.displayMetrics.density
    private val badgeRadius = 16 * density
    private val badgeReach = 22 * density
    private val handleRadius = 12 * density
    private val handleReach = 22 * density
    /** Every handle and the badge take touches this close to their centre: a 24dp target. */
    private val sureReach = 12 * density

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val dashOval = RectF()
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; pathEffect = DashPathEffect(floatArrayOf(6 * density, 5 * density), 0f)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics); color = ON_SIGNAL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); fontFeatureSettings = "tnum"
    }
    private val ghostPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cross = Path().apply {
        moveTo(7f, 7f); lineTo(17f, 17f)
        moveTo(17f, 7f); lineTo(7f, 17f)
    }
    private val caret = Path()

    private val ring = OutlineShape()
    /** [ring] widened to at least [MIN_SPAN] each way, where the handles and badge sit so they stay apart on small controls. */
    private val spread = OutlineShape()
    private val members = ArrayList<OutlineShape>()
    private var memberCount = 0
    private val others = ArrayList<OutlineShape>()
    private var otherCount = 0
    private val warned = ArrayList<OutlineShape>()
    private var warnCount = 0
    private val probe = OutlineShape()
    private var hasSelection = false
    private var group = false
    private val handleX = FloatArray(3)
    private val handleY = FloatArray(3)
    private val handleCorner = IntArray(3)
    private var badgeCorner = BADGE_CORNERS[0]
    private var badgeX = 0f
    private var badgeY = 0f

    private val selectionIn = FloatAnim(this, SELECT_MS)
    private val badgePress = FloatAnim(this, 0f)
    private val guideIn = FloatAnim(this, 0f)
    private val ghostOut = FloatAnim(this, 1f)
    private val ghosts = ArrayList<Ghost>()
    private val ghostRing = OutlineShape()
    private var ghostGroup = false
    private var pendingPop: Collection<String> = emptyList()

    private var guideX = Float.NaN
    private var guideY = Float.NaN
    private var readout: String? = null
    private var readoutValue = Int.MIN_VALUE
    private var readoutBase: Map<String, InputSurfaceElement> = emptyMap()

    private var badge = false
    private var original: List<InputSurfaceElement>? = null
    private var before: List<String> = emptyList()
    private var hit: String? = null
    private var under: List<String> = emptyList()
    private var keep = false
    private var downX = 0f
    private var downY = 0f
    private var corner = 0
    private var activeHandle = -1
    private var dragging = false
    private var pointer = -1

    private var focused: String? = null
    private val focusRing = OutlineShape()
    private var nudging = false
    private val access = ControlAccess()

    private class Ghost(val bitmap: Bitmap, val x: Float, val y: Float, val alpha: Float)

    init {
        setBackgroundColor(Color.rgb(10, 11, 13))
        preview.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(preview, LayoutParams(-1, -1))
        isFocusable = true
        defaultFocusHighlightEnabled = false
        ViewCompat.setAccessibilityDelegate(this, access)
    }

    /** The control keyboard and controller focus is on, or null. */
    internal val focusedControl get() = focused

    /** Plays the pop-in for newly added controls. Call it once their elements are set; ids not on screen yet pop when they arrive. */
    fun popIn(ids: Collection<String>) {
        if (!motionOn()) return
        val missing = ids.filter { id -> controlView(id)?.let(::pop) == null }
        pendingPop = missing
    }

    /** Plays the delete animation for [ids]. Call it while they are still on screen; removing them right after is fine. */
    fun playDelete(ids: Collection<String>) {
        clearGhosts()
        if (!motionOn() || width == 0 || height == 0) return
        val views = ids.mapNotNull { id -> controlView(id)?.takeIf { it.width > 0 && it.height > 0 } }
        if (views.sumOf { it.width.toLong() * it.height } > 2L * width * height) return
        for (view in views) {
            val bitmap = try {
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                clearGhosts()
                return
            }
            view.draw(Canvas(bitmap))
            var x = 0f; var y = 0f; var at: View = view
            while (at !== this) { x += at.left; y += at.top; at = at.parent as? View ?: break }
            ghosts += Ghost(bitmap, x, y, view.alpha)
        }
        if (hasSelection) { ghostRing.grow(ring, 0f); ghostGroup = group }
        ghostOut.animate(0f, 1f, DELETE_MS, CubicEase.EASE) { clearGhosts() }
    }

    private fun pop(view: View) {
        val layer = view.layerType
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = POP_MS
            interpolator = LINEAR
            addUpdateListener {
                val f = it.animatedFraction
                val s = .6f + .4f * CubicEase.POP.getInterpolation(f)
                view.scaleX = s; view.scaleY = s
                view.alpha = preview.restingAlpha(view) * CubicEase.EASE.getInterpolation((f * 2.5f).coerceAtMost(1f))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) { view.setLayerType(LAYER_TYPE_HARDWARE, null) }
                override fun onAnimationEnd(animation: Animator) { view.setLayerType(layer, null) }
            })
        }.start()
    }

    private fun popPending() {
        if (pendingPop.isEmpty()) return
        val ids = pendingPop
        pendingPop = emptyList()
        for (id in ids) controlView(id)?.let(::pop)
    }

    private fun controlView(id: String): View? {
        fun find(view: View): View? {
            if ((view.tag as? InputSurfaceElement)?.id == id) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return find(preview)
    }

    private fun clearGhosts() {
        for (g in ghosts) g.bitmap.recycle()
        ghosts.clear()
        ghostRing.set(0f, 0f, 0f, 0f, 0f)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        ghostOut.snap(1f)
        clearGhosts()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshGeometry()
    }

    private fun shape(list: ArrayList<OutlineShape>, i: Int) = if (i < list.size) list[i] else OutlineShape().also { list += it }

    private fun refreshGeometry() {
        invalidate()
        hasSelection = false; memberCount = 0; otherCount = 0; warnCount = 0
        if (width == 0 || height == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        for (e in elements) (if (e.id in selection) shape(members, memberCount++) else shape(others, otherCount++)).ofControl(e, w, h, density)
        if (memberCount == 0) return
        hasSelection = true
        group = memberCount > 1
        if (dragging) for (j in 0 until otherCount) {
            val other = others[j]
            for (i in 0 until memberCount) if (other.overlaps(members[i])) { shape(warned, warnCount++).grow(other, RING_OUT * density); break }
        }
        if (group) {
            ring.enclose(members, memberCount, GROUP_PAD * density)
            for (i in 0 until memberCount) members[i].grow(members[i], MEMBER_OUT * density)
        } else ring.grow(members[0], RING_OUT * density)
        val span = MIN_SPAN * density
        spread.set(min(ring.left, ring.centerX - span / 2f), min(ring.top, ring.centerY - span / 2f),
            max(ring.right, ring.centerX + span / 2f), max(ring.bottom, ring.centerY + span / 2f), ring.corner)
        val badgeEdge = badgeRadius + BADGE_RING * density
        if (!dragging) badgeCorner = deleteBadgeCorner(spread, w, h, badgeEdge, others, otherCount, probe)
        badgeX = spread.cornerX(badgeCorner).coerceIn(badgeEdge, max(badgeEdge, w - badgeEdge))
        badgeY = spread.cornerY(badgeCorner).coerceIn(badgeEdge, max(badgeEdge, h - badgeEdge))
        var n = 0
        for (c in CORNERS) if (c != badgeCorner && n < 3) handleCorner[n++] = c
        for (i in 0 until 3) {
            handleX[i] = spread.cornerX(handleCorner[i]).coerceIn(handleRadius, max(handleRadius, w - handleRadius))
            handleY[i] = spread.cornerY(handleCorner[i]).coerceIn(handleRadius, max(handleRadius, h - handleRadius))
        }
    }

    internal fun badgeCenter(): Pair<Float, Float>? = if (hasSelection) badgeX to badgeY else null

    internal fun handleCenter(i: Int): Pair<Float, Float> = handleX[i] to handleY[i]

    /** Index of the resize handle under x,y, counting the corners top-left, bottom-left, bottom-right, top-right minus the badge's; -1 for none. */
    internal fun handleAt(x: Float, y: Float) = if (!hasSelection) -1 else pickHandle(x, y, handleX, handleY, handleReach, ring.centerX, ring.centerY, sureReach)

    /**
     * True when x,y presses the delete badge; a handle wins where the touch is within its 24dp and nearer to it, and the selection
     * wins where the touch is nearer its centre, so a small control's middle never deletes it.
     */
    internal fun hitsBadge(x: Float, y: Float): Boolean {
        if (!hasSelection) return false
        val d = hypot(x - badgeX, y - badgeY)
        return d <= badgeReach && d <= hypot(x - ring.centerX, y - ring.centerY) &&
            (0 until 3).none { i -> hypot(x - handleX[i], y - handleY[i]).let { it <= sureReach && it < d } }
    }

    internal val overlapping get() = warnCount

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawOverlay(canvas)
    }

    internal fun drawOverlay(canvas: Canvas) {
        drawGhosts(canvas)
        drawGuides(canvas)
        drawFocus(canvas)
        if (!hasSelection) return
        for (i in 0 until warnCount) drawRing(canvas, warned[i], WARN, WARN_HALO, 1f)
        val t = selectionIn.value
        val p = CubicEase.EASE.getInterpolation((t / OUTLINE_MS).coerceIn(0f, 1f))
        val s = 1.04f - .04f * p
        canvas.save()
        canvas.scale(s, s, ring.centerX, ring.centerY)
        if (group) drawGroup(canvas, p) else drawHint(canvas, ring, p)
        canvas.restore()
        if (dragging && corner == 0) return
        for (i in 0 until 3) drawHandle(canvas, i, handleIn(t, i))
        drawBadge(canvas, handleIn(t, 3))
        if (dragging) drawReadout(canvas)
    }

    private fun drawFocus(canvas: Canvas) {
        if (!isFocused || width == 0) return
        val e = elements.firstOrNull { it.id == focused } ?: return
        focusRing.ofControl(e, width.toFloat(), height.toFloat(), density)
        drawRing(canvas, focusRing.grow(focusRing, FOCUS_OUT * density), Color.WHITE, FOCUS_HALO, 1f)
    }

    private fun handleIn(t: Float, i: Int) = CubicEase.POP.getInterpolation(((t - HANDLE_DELAY_MS - i * HANDLE_STAGGER_MS) / HANDLE_MS).coerceIn(0f, 1f))

    private fun drawRing(canvas: Canvas, shape: OutlineShape, color: Int, halo: Int, alpha: Float) {
        val d = 3 * density
        stroke.strokeWidth = 4 * density; stroke.color = withAlpha(halo, alpha)
        canvas.drawRoundRect(shape.left - d, shape.top - d, shape.right + d, shape.bottom + d, shape.corner + d, shape.corner + d, stroke)
        stroke.strokeWidth = 2 * density; stroke.color = withAlpha(color, alpha)
        canvas.drawRoundRect(shape.left, shape.top, shape.right, shape.bottom, shape.corner, shape.corner, stroke)
    }

    private fun drawHint(canvas: Canvas, shape: OutlineShape, alpha: Float) {
        stroke.strokeWidth = 1.5f * density; stroke.color = withAlpha(HINT, alpha)
        canvas.drawRoundRect(shape.left, shape.top, shape.right, shape.bottom, shape.corner, shape.corner, stroke)
    }

    private fun drawGroup(canvas: Canvas, alpha: Float) {
        dashed.color = withAlpha(HINT, alpha)
        drawDashedOutline(canvas, ring, DASH_ON * density, DASH_OFF * density, dashed, dashOval)
        stroke.strokeWidth = 1 * density; stroke.color = withAlpha(HINT_MEMBER, alpha)
        for (i in 0 until memberCount) {
            val m = members[i]
            canvas.drawRoundRect(m.left, m.top, m.right, m.bottom, m.corner, m.corner, stroke)
        }
    }

    private fun drawHandle(canvas: Canvas, i: Int, scale: Float) {
        if (scale <= 0f) return
        val x = handleX[i]; val y = handleY[i]
        val active = dragging && i == activeHandle
        val r = handleRadius * scale * if (active) 1.12f else 1f
        if (active) { fill.color = HOT; canvas.drawCircle(x, y, r + 6 * density, fill) }
        fill.color = SHADOW; canvas.drawCircle(x, y + 2 * density, r + .5f * density, fill)
        fill.color = Color.WHITE; canvas.drawCircle(x, y, r, fill)
        stroke.strokeWidth = 2 * density * scale; stroke.color = SIGNAL
        canvas.drawCircle(x, y, r - density * scale, stroke)
    }

    private fun drawBadge(canvas: Canvas, appear: Float) {
        if (appear <= 0f) return
        val press = badgePress.value
        val s = appear * (1f - .1f * press)
        fill.color = withAlpha(ERROR, .2f * press); canvas.drawCircle(badgeX, badgeY, (badgeRadius + 7 * density) * s, fill)
        fill.color = BADGE_RING_COLOR; canvas.drawCircle(badgeX, badgeY, (badgeRadius + BADGE_RING * density) * s, fill)
        fill.color = lerpColor(SURFACE_2, ERROR, press); canvas.drawCircle(badgeX, badgeY, badgeRadius * s, fill)
        stroke.strokeWidth = 1.5f * density * s; stroke.color = lerpColor(ERROR_LINE, ERROR, press)
        canvas.drawCircle(badgeX, badgeY, (badgeRadius - .75f * density) * s, stroke)
        val size = 17 * density * s
        canvas.save()
        canvas.translate(badgeX - size / 2f, badgeY - size / 2f)
        canvas.scale(size / 24f, size / 24f)
        glyph.color = lerpColor(ERROR, ERROR_INK, press)
        canvas.drawPath(cross, glyph)
        canvas.restore()
    }

    private fun drawReadout(canvas: Canvas) {
        val text = readout ?: return
        val margin = 8 * density; val tip = 5 * density; val gap = 10 * density; val h = 22 * density
        val w = label.measureText(text) + 20 * density
        val cx = ring.centerX.coerceIn(margin + w / 2f, max(margin + w / 2f, width - margin - w / 2f))
        val above = ring.top - gap - tip - h >= margin
        val top = if (above) ring.top - gap - tip - h else ring.bottom + gap + tip
        fill.color = SIGNAL
        canvas.drawRoundRect(cx - w / 2f, top, cx + w / 2f, top + h, h / 2f, h / 2f, fill)
        caret.reset()
        val base = if (above) top + h else top
        caret.moveTo(cx - tip, base); caret.lineTo(cx + tip, base); caret.lineTo(cx, if (above) base + tip else base - tip); caret.close()
        canvas.drawPath(caret, fill)
        canvas.drawText(text, cx, top + h / 2f - (label.ascent() + label.descent()) / 2f, label)
    }

    private fun drawGuides(canvas: Canvas) {
        if (guideX.isNaN() && guideY.isNaN()) return
        val a = guideIn.value
        stroke.strokeWidth = 4 * density; stroke.color = withAlpha(GUIDE_GLOW, a)
        guide.color = withAlpha(SIGNAL, a)
        if (!guideX.isNaN()) {
            val x = width * guideX
            canvas.drawLine(x, 0f, x, height.toFloat(), stroke); canvas.drawLine(x, 0f, x, height.toFloat(), guide)
        }
        if (!guideY.isNaN()) {
            val y = height * guideY
            canvas.drawLine(0f, y, width.toFloat(), y, stroke); canvas.drawLine(0f, y, width.toFloat(), y, guide)
        }
    }

    private fun drawGhosts(canvas: Canvas) {
        if (ghosts.isEmpty()) return
        val p = ghostOut.value
        val s = 1f - .6f * p
        for (i in ghosts.indices) {
            val g = ghosts[i]
            canvas.save()
            canvas.scale(s, s, g.x + g.bitmap.width / 2f, g.y + g.bitmap.height / 2f)
            ghostPaint.alpha = (255 * g.alpha * (1f - p)).toInt().coerceIn(0, 255)
            canvas.drawBitmap(g.bitmap, g.x, g.y, ghostPaint)
            canvas.restore()
        }
        if (ghostRing.right > ghostRing.left) {
            canvas.save()
            canvas.scale(s, s, ghostRing.centerX, ghostRing.centerY)
            if (ghostGroup) {
                dashed.color = withAlpha(HINT, 1f - p)
                drawDashedOutline(canvas, ghostRing, DASH_ON * density, DASH_OFF * density, dashed, dashOval)
            } else drawHint(canvas, ghostRing, 1f - p)
            canvas.restore()
        }
    }

    private fun setGuides(x: Float, y: Float) {
        val shown = !guideX.isNaN() || !guideY.isNaN()
        guideX = x; guideY = y
        if (!shown && (!x.isNaN() || !y.isNaN())) guideIn.animate(0f, 1f, GUIDE_MS)
        invalidate()
    }

    private fun clearGuides() { guideX = Float.NaN; guideY = Float.NaN; guideIn.snap(0f) }

    private fun updateReadout(next: List<InputSurfaceElement>) {
        val value = sizeReadout?.invoke() ?: next.sizePercent(selection, readoutBase).roundToInt()
        if (value == readoutValue && readout != null) return
        readoutValue = value
        readout = "$value%"
    }

    override fun onInterceptTouchEvent(event: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || width == 0 || height == 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearGuides()
                pointer = event.getPointerId(0)
                badge = hitsBadge(event.x, event.y)
                if (badge) { badgePress.animate(badgePress.value, 1f, PRESS_MS); return true }
                downX = event.x; downY = event.y; dragging = false; corner = 0; readout = null; nudging = false
                before = selection
                activeHandle = handleAt(downX, downY)
                if (activeHandle >= 0) corner = handleCorner[activeHandle]
                under = if (corner != 0) emptyList() else elements.asReversed().filter {
                    downX / width in it.x..(it.x + it.width) && downY / height in it.y..(it.y + it.height)
                }.map { it.id }
                hit = under.firstOrNull { it in selection } ?: under.firstOrNull()
                hit?.let { focused = it }
                val inside = hit == null && hasSelection && ring.contains(downX, downY)
                if (corner == 0 && !inside) {
                    val pressed = elements.pressSelection(before, hit)
                    if (pressed != selection) { selection = pressed; onSelect(pressed) }
                }
                keep = inside
                original = elements.takeIf { selection.isNotEmpty() }
            }
            MotionEvent.ACTION_MOVE -> {
                if (badge) return true
                val start = original ?: return true
                val index = event.findPointerIndex(pointer)
                if (index < 0) return true
                val px = event.getX(index); val py = event.getY(index)
                if (!dragging && abs(px - downX) + abs(py - downY) > ViewConfiguration.get(context).scaledTouchSlop) {
                    dragging = true; onBeginEdit()
                    readoutBase = start.filter { it.id in selection }.associateBy { it.id }
                    refreshGeometry()
                }
                if (!dragging) return true
                val dx = (px - downX) / width; val dy = (py - downY) / height
                val b = start.boundsOf(selection) ?: return true
                val moved = if (corner == 0) start.moveSelection(selection, dx, dy, safeArea()).boundsOf(selection)!!
                    else SurfaceBounds(if (corner and 1 != 0) b.left + dx else b.left, if (corner and 4 != 0) b.top + dy else b.top,
                        if (corner and 2 != 0) b.right + dx else b.right, if (corner and 8 != 0) b.bottom + dy else b.bottom)
                val others = start.filter { it.id !in selection }
                val box = InputSurfaceElement(InputControl.A, moved.left, moved.top, max(moved.width, .03f), max(moved.height, .03f), id = "selection")
                val aligned = if (snap && (corner == 0 || selection.none { id -> start.any { it.id == id && it.kind == InputElementKind.STICK } }))
                    snapSurfaceElement(box, others, 9 * density / width, 9 * density / height, corner) else null
                val x = aligned?.x ?: Float.NaN; val y = aligned?.y ?: Float.NaN
                if (aligned != null && (!x.isNaN() && x != guideX || !y.isNaN() && y != guideY)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                setGuides(x, y)
                val target = aligned?.element?.let { SurfaceBounds(it.x, it.y, it.x + it.width, it.y + it.height) } ?: moved
                val next = if (corner == 0) start.moveSelection(selection, target.left - b.left, target.top - b.top, safeArea())
                    else start.resizeSelection(selection, corner, target, safeArea())
                onChange(next.filter { it.id in selection })
                if (corner != 0) updateReadout(next)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointer) {
                if (badge) badgePress.animate(badgePress.value, 0f, RELEASE_MS, CubicEase.POP)
                badge = false; pointer = -1; endGesture()
            }
            MotionEvent.ACTION_UP -> {
                if (badge) {
                    badge = false
                    badgePress.animate(badgePress.value, 0f, RELEASE_MS, CubicEase.POP)
                    if (hitsBadge(event.x, event.y)) { playDelete(selection); onDelete() }
                    return true
                }
                if (!dragging && corner == 0 && !keep && pointer >= 0) {
                    val tapped = elements.tapSelection(before, hit, under)
                    if (tapped != selection) { selection = tapped; onSelect(tapped) }
                }
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (badge) badgePress.animate(badgePress.value, 0f, RELEASE_MS, CubicEase.POP)
                badge = false; endGesture()
            }
        }
        return true
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gained, direction, previous)
        access.onFocusChanged(gained, direction, previous)
        if (gained && focused == null) focused = nextControl(elements, null, when (direction) {
            FOCUS_LEFT, FOCUS_BACKWARD -> FOCUS_LEFT
            FOCUS_UP -> FOCUS_UP
            FOCUS_DOWN -> FOCUS_DOWN
            else -> FOCUS_RIGHT
        }, width.toFloat(), height.toFloat())
        invalidate()
    }

    override fun dispatchHoverEvent(event: MotionEvent) = access.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * D-pad or arrows move focus between controls, and A or Enter selects the focused one the way a tap does. With a selection the arrows
     * move it, Shift with an arrow or L1 and R1 resize it, Delete or X deletes it and B or Escape lets it go.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isEnabled || width == 0 || height == 0) return super.onKeyDown(keyCode, event)
        val selected = selection.isNotEmpty()
        val handled = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> arrow(FOCUS_LEFT, event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_RIGHT -> arrow(FOCUS_RIGHT, event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_UP -> arrow(FOCUS_UP, event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_DOWN -> arrow(FOCUS_DOWN, event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                focused?.let { choose(elements.tapSelection(selection, it)) } != null
            KeyEvent.KEYCODE_BUTTON_L1 -> selected.also { if (it) resizeSelected(grow = false) }
            KeyEvent.KEYCODE_BUTTON_R1 -> selected.also { if (it) resizeSelected(grow = true) }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_BUTTON_X -> selected.also { if (it) deleteSelected() }
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> selected.also { if (it) choose(emptyList()) }
            else -> false
        }
        return handled || super.onKeyDown(keyCode, event)
    }

    private fun arrow(direction: Int, resize: Boolean): Boolean {
        if (selection.isEmpty()) {
            focused = nextControl(elements, focused, direction, width.toFloat(), height.toFloat()) ?: return false
            invalidate()
            return true
        }
        val step = NUDGE_DP * density
        if (resize) resizeSelected(grow = direction == FOCUS_RIGHT || direction == FOCUS_UP)
        else nudgeSelected(when (direction) { FOCUS_LEFT -> -step; FOCUS_RIGHT -> step; else -> 0f }, when (direction) { FOCUS_UP -> -step; FOCUS_DOWN -> step; else -> 0f })
        return true
    }

    private fun choose(next: List<String>) {
        if (next == selection) return
        selection = next
        onSelect(next)
    }

    private fun edit(next: List<InputSurfaceElement>) {
        val changed = next.filter { it.id in selection }
        if (changed == elements.filter { it.id in selection }) return
        if (!nudging) { onBeginEdit(); nudging = true }
        onChange(changed)
    }

    private fun nudgeSelected(dx: Float, dy: Float) = edit(elements.moveSelection(selection, dx / width, dy / height, safeArea()))

    private fun resizeSelected(grow: Boolean) = edit(elements.scaleSelection(selection, if (grow) SIZE_STEP else 1f / SIZE_STEP, safeArea()))

    /** The part of the canvas outside the display cutout, where the game keeps every control. */
    private fun safeArea(): SurfaceBounds {
        val c = if (Build.VERSION.SDK_INT >= 28) rootWindowInsets?.displayCutout else null
        return SurfaceBounds((c?.safeInsetLeft ?: 0).toFloat() / width, (c?.safeInsetTop ?: 0).toFloat() / height,
            1f - (c?.safeInsetRight ?: 0).toFloat() / width, 1f - (c?.safeInsetBottom ?: 0).toFloat() / height)
    }

    private fun deleteSelected() {
        playDelete(selection)
        onDelete()
    }

    /** The controls as TalkBack nodes, each named after its control, that select on a double tap and move or delete through actions. */
    private inner class ControlAccess : ExploreByTouchHelper(this) {
        private val bounds = Rect()

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (width == 0 || height == 0) return HOST_ID
            val i = elements.indexOfLast { x / width in it.x..(it.x + it.width) && y / height in it.y..(it.y + it.height) }
            return if (i >= 0) i else HOST_ID
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) { ids.addAll(elements.indices) }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val e = elements.getOrNull(id)
            if (e == null) { node.contentDescription = ""; node.setBoundsInParent(bounds.apply { set(0, 0, 1, 1) }); return }
            node.contentDescription = e.name
            node.className = Button::class.java.name
            node.isSelected = e.id in selection
            node.isClickable = true
            bounds.set((e.x * width).roundToInt(), (e.y * height).roundToInt(), ((e.x + e.width) * width).roundToInt(), ((e.y + e.height) * height).roundToInt())
            node.setBoundsInParent(bounds)
            node.addAction(AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_CLICK, context.getString(R.string.input_editor_select)))
            for ((action, label) in MOVES) node.addAction(AccessibilityActionCompat(action, context.getString(label)))
            node.addAction(AccessibilityActionCompat(R.id.input_editor_delete, context.getString(R.string.input_delete_element)))
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            val e = elements.getOrNull(id) ?: return false
            if (width == 0 || height == 0) return false
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                focused = e.id
                choose(elements.tapSelection(selection, e.id))
                return true
            }
            if (action != R.id.input_editor_delete && MOVES.none { it.first == action }) return false
            if (e.id !in selection) choose(listOf(e.id))
            val step = ACCESS_NUDGE_DP * density
            when (action) {
                R.id.input_editor_delete -> deleteSelected()
                R.id.input_editor_move_left -> nudgeSelected(-step, 0f)
                R.id.input_editor_move_right -> nudgeSelected(step, 0f)
                R.id.input_editor_move_up -> nudgeSelected(0f, -step)
                else -> nudgeSelected(0f, step)
            }
            return true
        }
    }

    private fun endGesture() {
        original = null; dragging = false; activeHandle = -1; readout = null; clearGuides(); refreshGeometry()
    }

    companion object {
        private val LINEAR = android.view.animation.LinearInterpolator()
        private val CORNERS = intArrayOf(1 or 4, 1 or 8, 2 or 8, 2 or 4)
        private const val BADGE_RING = 2f
        private const val RING_OUT = 3f
        private const val MIN_SPAN = 48f
        private const val MEMBER_OUT = 2f
        private const val GROUP_PAD = 10f
        private const val DASH_ON = 6f
        private const val DASH_OFF = 4f
        private const val FOCUS_OUT = 8f
        private const val NUDGE_DP = 8f
        private const val ACCESS_NUDGE_DP = 32f
        private const val SIZE_STEP = 1.1f
        private val MOVES = listOf(R.id.input_editor_move_left to R.string.input_editor_move_left, R.id.input_editor_move_right to R.string.input_editor_move_right,
            R.id.input_editor_move_up to R.string.input_editor_move_up, R.id.input_editor_move_down to R.string.input_editor_move_down)
        private val FOCUS_HALO = Color.argb(64, 255, 255, 255)
        private const val OUTLINE_MS = 120f
        private const val HANDLE_DELAY_MS = 90f
        private const val HANDLE_STAGGER_MS = 30f
        private const val HANDLE_MS = 200f
        private const val SELECT_MS = HANDLE_DELAY_MS + 3 * HANDLE_STAGGER_MS + HANDLE_MS
        private const val PRESS_MS = 70L
        private const val RELEASE_MS = 160L
        private const val GUIDE_MS = 90L
        private const val POP_MS = 280L
        private const val DELETE_MS = 180L
        private val SIGNAL = Color.rgb(0x1A, 0x9F, 0xFF)
        private val HOT = Color.argb(36, 0x1A, 0x9F, 0xFF)
        private val HINT = Color.argb(89, 0xFF, 0xFF, 0xFF)
        private val HINT_MEMBER = Color.argb(51, 0xFF, 0xFF, 0xFF)
        private val GUIDE_GLOW = Color.argb(64, 0x1A, 0x9F, 0xFF)
        private val ON_SIGNAL = Color.rgb(0x03, 0x11, 0x1F)
        private val WARN = Color.rgb(0xFF, 0xB5, 0x47)
        private val WARN_HALO = Color.argb(41, 0xFF, 0xB5, 0x47)
        private val ERROR = Color.rgb(0xFF, 0x6B, 0x6B)
        private val ERROR_LINE = Color.argb(153, 0xFF, 0x6B, 0x6B)
        private val ERROR_INK = Color.rgb(0x1F, 0x06, 0x06)
        private val SURFACE_2 = Color.rgb(0x1A, 0x1D, 0x22)
        private val BADGE_RING_COLOR = Color.argb(217, 0x0A, 0x0B, 0x0D)
        private val SHADOW = Color.argb(77, 0, 0, 0)
    }
}
