package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

class ScreenKeys(context: Context, private val sendKey: (Int, Boolean) -> Unit) : View(context) {
    private class Entry(val codes: List<Int>) {
        var glyph = ""
        var text = ""
        var image = ""
        var bitmap: Bitmap? = null
        var bitmapFor = ""
        var x = 0.5f
        var y = 0.5f
        var size = DEFAULT_SIZE
        var opacity = 1f
        var shape = SCREEN_KEY_PILL
        var toggle = false
        var labels = true
        var armed = -1
        var cx = 0f
        var cy = 0f
        var halfW = 0f
        var halfH = 0f
    }

    private val entries = mutableListOf<Entry>()
    private val down = HashMap<Int, Entry>()
    private val latched = mutableListOf<Entry>()
    private val dpadDown = HashMap<Int, Int>()
    private val stickEntries = HashMap<Int, Entry>()
    private val stickCodes = HashMap<Int, List<Int>>()
    private val held = HashMap<Int, Int>()
    private var forwardedPointerBits = 0
    private var addX = -1f
    private var addY = -1f
    private var pressX = 0f
    private var pressY = 0f
    private var guideX = -1f
    private var guideY = -1f
    private val addPress = Runnable { addX = pressX; addY = pressY; onAdd?.invoke() }
    private var editing = false
    private var selected: Entry? = null
    private var dragX = 0f
    private var dragY = 0f
    private var dragging = false
    private var moved = false
    private var toolbarDrag = false
    private var safe = Rect()
    private var tint = ControllerPrefs.STEAM_BLUE

    init {
        ControllerPrefs.screenKeys(context).forEach { key -> entries += entryOf(key) }
    }

    var followSteam = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var steamStyle: () -> Pair<Int, Float>? = { null }

    var onAdd: (() -> Unit)? = null
    var onSelect: ((List<Int>) -> Unit)? = null
    var onEditingChanged: ((Boolean) -> Unit)? = null
    var onTouchMiss: ((MotionEvent) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val bodyPathRect = RectF()
    private val bodyPath = android.graphics.Path()
    private val clipPath = android.graphics.Path()
    private val imageRect = RectF()
    private val bitmapRect = RectF()

    private val capsuleRadius = dp(24f)
    private val capsuleGap = dp(62f)
    private val toolbar = PointF()
    private val addCenter = PointF()
    private val doneCenter = PointF()

    fun startEditing() {
        if (editing) return
        editing = true
        releaseAll()
        selected = null
        placeToolbar()
        invalidate()
        onEditingChanged?.invoke(true)
    }

    fun stopEditing() {
        if (!editing) return
        releaseAll()
        editing = false
        selected = null
        save()
        invalidate()
        onEditingChanged?.invoke(false)
    }

    fun add(codes: List<Int>) {
        if (codes.isEmpty()) return
        entries.firstOrNull { it.codes == codes }?.let {
            selected = it
            invalidate()
            return
        }
        val entry = Entry(codes)
        if (addX >= 0f && width > 0) {
            entry.x = (addX / width).coerceIn(0.05f, 0.95f)
            entry.y = (addY / height).coerceIn(0.05f, 0.95f)
        } else {
            entry.x = (0.30f + 0.08f * (entries.size % 5)).coerceAtMost(0.70f)
            entry.y = 0.62f
        }
        addX = -1f
        addY = -1f
        entries += entry
        if (width > 0) measure(entry)
        selected = entry
        save()
        invalidate()
    }

    fun remove(codes: List<Int>) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        entries.remove(entry)
        if (selected === entry) selected = null
        save()
        invalidate()
    }

    fun setCodes(from: List<Int>, to: List<Int>): ScreenKey? {
        if (to.isEmpty()) return null
        val index = entries.indexOfFirst { it.codes == from }
        if (index < 0) return null
        val old = entries[index]
        if (to != from && entries.any { it !== old && it.codes == to }) return null
        val fresh = Entry(to).also {
            it.glyph = old.glyph
            it.text = old.text
            it.image = old.image
            it.bitmap = old.bitmap
            it.bitmapFor = old.bitmapFor
            it.x = old.x
            it.y = old.y
            it.size = old.size
            it.opacity = old.opacity
            it.shape = old.shape
            it.toggle = old.toggle
            it.labels = old.labels
        }
        entries[index] = fresh
        measure(fresh)
        if (selected === old) selected = fresh
        save()
        invalidate()
        return stateOf(fresh.codes)
    }

    fun setText(codes: List<Int>, text: String) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        entry.text = text
        measure(entry)
        save()
        invalidate()
    }

    fun setImage(codes: List<Int>, image: String) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        val previous = entry.image
        entry.image = image
        entry.bitmap = null
        entry.bitmapFor = ""
        if (previous.isNotEmpty() && previous != image) File(File(context.filesDir, IMAGE_DIR), previous).delete()
        measure(entry)
        save()
        invalidate()
    }

    fun resize(codes: List<Int>, size: Int) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        entry.size = size.coerceIn(MIN_SIZE, MAX_SIZE)
        measure(entry)
        save()
        invalidate()
    }

    fun setOpacity(codes: List<Int>, opacity: Float) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        entry.opacity = opacity.coerceIn(0.2f, 1f)
        save()
        invalidate()
    }

    fun setShape(codes: List<Int>, shape: Int) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        entry.shape = shape.coerceIn(SCREEN_KEY_PILL, SCREEN_KEY_STICK)
        measure(entry)
        save()
        invalidate()
    }

    fun setToggle(codes: List<Int>, toggle: Boolean) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        entry.toggle = toggle
        if (!toggle && latched.remove(entry)) release(entry)
        save()
        invalidate()
    }

    fun setLabels(codes: List<Int>, labels: Boolean) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        entry.labels = labels
        save()
        invalidate()
    }

    fun setDirection(codes: List<Int>, index: Int, code: Int): ScreenKey? {
        val entry = entries.firstOrNull { it.codes == codes } ?: entries.firstOrNull { it.codes == trimmed(codes) } ?: return null
        val next = entry.codes.toMutableList()
        while (next.size <= index) next += 0
        next[index] = code
        val nextCodes = trimmed(next)
        if (entries.any { it !== entry && it.codes == nextCodes }) return null
        val updated = Entry(nextCodes).also {
            it.glyph = entry.glyph
            it.text = entry.text
            it.image = entry.image
            it.bitmap = entry.bitmap
            it.bitmapFor = entry.bitmapFor
            it.x = entry.x
            it.y = entry.y
            it.size = entry.size
            it.opacity = entry.opacity
            it.shape = entry.shape
            it.toggle = entry.toggle
            it.labels = entry.labels
        }
        entries[entries.indexOf(entry)] = updated
        measure(updated)
        if (selected === entry) selected = updated
        save()
        invalidate()
        return stateOf(updated.codes)
    }

    private fun trimmed(codes: List<Int>): List<Int> = codes.dropLastWhile { it == 0 }.ifEmpty { listOf(0) }

    fun stateOf(codes: List<Int>): ScreenKey? = entries.firstOrNull { it.codes == codes }?.let {
        ScreenKey(it.codes, it.x, it.y, it.size, it.glyph, it.text, it.image, it.opacity, it.shape, it.toggle, it.labels)
    }

    fun deselect() {
        if (selected == null) return
        selected = null
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshAppearance()
    }

    fun refreshAppearance() {
        tint = ControllerPrefs.read(context).tint
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        entries.forEach { measure(it) }
        placeToolbar()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
        val fresh = Rect(
            cutout?.safeInsetLeft ?: 0, cutout?.safeInsetTop ?: 0,
            cutout?.safeInsetRight ?: 0, cutout?.safeInsetBottom ?: 0,
        )
        if (fresh != safe) {
            safe = fresh
            placeToolbar()
            invalidate()
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) releaseAll()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility != VISIBLE) releaseAll()
    }

    fun releaseAll() {
        dpadDown.values.forEach { releaseKey(it) }
        dpadDown.clear()
        entries.forEach { it.armed = -1 }
        stickCodes.values.flatten().forEach { if (it != 0) releaseKey(it) }
        stickCodes.clear()
        stickEntries.clear()
        down.values.forEach { release(it) }
        down.clear()
        latched.toList().forEach { release(it) }
        latched.clear()
        invalidate()
    }

    private fun snap(value: Float) = if (width > 0) round(value / (width / 100f)) * (width / 100f) else value

    private fun placeToolbar() {
        if (width == 0 || height == 0) return
        if (toolbar.x == 0f && toolbar.y == 0f) {
            toolbar.set(width - safe.right - capsuleGap - capsuleRadius, height - safe.bottom - capsuleRadius - dp(20f))
        }
        addCenter.set(toolbar.x - capsuleGap / 2f, toolbar.y)
        doneCenter.set(toolbar.x + capsuleGap / 2f, toolbar.y)
    }

    private fun entryOf(key: ScreenKey): Entry = Entry(key.codes).also {
        it.glyph = key.glyph
        it.text = key.text
        it.image = key.image
        it.x = key.x
        it.y = key.y
        it.size = key.size
        it.opacity = key.opacity
        it.shape = key.shape
        it.toggle = key.toggle
        it.labels = key.labels
    }

    private fun label(entry: Entry) = when {
        entry.text.isNotEmpty() -> entry.text
        entry.glyph.isNotEmpty() -> entry.glyph
        else -> screenKeyLabel(context, entry.codes)
    }

    private fun bitmapFor(entry: Entry): Bitmap? {
        if (entry.image.isEmpty()) return null
        if (entry.bitmapFor == entry.image) return entry.bitmap
        val file = File(File(context.filesDir, IMAGE_DIR), entry.image)
        val bitmap = if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
        entry.bitmap = bitmap
        entry.bitmapFor = entry.image
        return bitmap
    }

    private fun measure(entry: Entry) {
        val h = dp(entry.size.toFloat())
        entry.halfH = h / 2f
        entry.halfW = when {
            entry.shape == SCREEN_KEY_CIRCLE -> entry.halfH
            entry.shape == SCREEN_KEY_TRIGGER -> entry.halfH * 1.7f
            entry.shape == SCREEN_KEY_DPAD || entry.shape == SCREEN_KEY_STICK -> entry.halfH * 1.15f
            entry.image.isNotEmpty() -> entry.halfH * 1.15f
            hasIcon(entry) -> entry.halfH * 1.15f
            else -> {
                val chars = label(entry).length
                entry.halfH * (0.55f * chars + 1.05f).coerceIn(1.7f, 4.6f)
            }
        }
        entry.cx = entry.x * width
        entry.cy = entry.y * height
    }

    private fun hasIcon(entry: Entry): Boolean =
        entry.text.isEmpty() && entry.glyph.isEmpty() && entry.codes.size == 1 && ScreenKeyIcons.has(entry.codes[0])

    private fun unit() = minOf(width / 1280f, height / 720f)

    private fun accent(): Pair<Int, Float> {
        val style = if (followSteam) steamStyle() else null
        return (style?.first ?: tint) to (style?.second ?: 1f)
    }

    private fun tinted(color: Int, alpha: Float): Int {
        val scale = accent().second
        return Color.argb(
            (alpha * scale * 255f).toInt().coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color),
        )
    }

    private fun triggerPath(entry: Entry, path: android.graphics.Path, inset: Float = 0f) {
        val lean = (entry.halfH - inset) * 0.6f
        val left = entry.cx - entry.halfW + inset
        val right = entry.cx + entry.halfW - inset
        val top = entry.cy - entry.halfH + inset
        val bottom = entry.cy + entry.halfH - inset
        path.reset()
        path.moveTo(left + lean, top)
        path.lineTo(right, top)
        path.lineTo(right - lean, bottom)
        path.lineTo(left, bottom)
        path.close()
    }

    private fun body(canvas: Canvas, entry: Entry, radius: Float, paint: Paint, inset: Float = 0f) {
        if (entry.shape != SCREEN_KEY_TRIGGER) {
            canvas.drawRoundRect(rect, radius, radius, paint)
            return
        }
        triggerPath(entry, bodyPath, inset)
        canvas.drawPath(bodyPath, paint)
    }

    private fun drawDpad(canvas: Canvas, entry: Entry) {
        val color = accent().first
        val alpha = entry.opacity
        val r = minOf(entry.halfW, entry.halfH)
        val t = r * 0.42f
        val hairline = maxOf(dp(1.2f), entry.halfH * 0.032f)
        val engaged = entry.armed >= 0
        crossPath(entry, bodyPath, r, t)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = tinted(color, if (entry.armed >= 0) 0.26f * alpha else 0.10f * alpha)
        canvas.drawPath(bodyPath, fillPaint)
        if (entry.armed >= 0) {
            armRect(entry, bodyPathRect, entry.armed, r, t)
            fillPaint.color = tinted(color, 0.55f * alpha)
            canvas.drawRoundRect(bodyPathRect, t * 0.6f, t * 0.6f, fillPaint)
        }
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeWidth = if (engaged) hairline * 1.6f else hairline
        strokePaint.color = tinted(color, (if (editing) 0.90f else 0.80f) * alpha)
        canvas.drawPath(bodyPath, strokePaint)
        textPaint.setShadowLayer(0f, 0f, 0f, 0)
        fillPaint.color = tinted(color, if (editing) 0.90f else 0.80f)
        DPAD_DIRS.forEachIndexed { index, dir ->
            val on = entry.armed == index
            val code = entry.codes.getOrNull(index) ?: 0
            val name = if (code == 0) "" else screenKeyLabel(context, listOf(code))
            if (entry.labels && name.isNotEmpty()) {
                textPaint.color = if (on) Color.WHITE else tinted(color, alpha)
                textPaint.textSize = dp(48f)
                val fit = 48f * (t * 1.9f) / textPaint.measureText(name).coerceAtLeast(1f)
                textPaint.textSize = minOf(fit, t * 1.7f).coerceIn(dp(8f), dp(60f))
                val tx = entry.cx + dir.first * (r - t * 0.6f)
                val ty = entry.cy + dir.second * (r - t * 0.6f)
                canvas.drawText(name, tx, ty - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
            } else {
                fillPaint.color = if (on) Color.WHITE else tinted(color, if (editing) 0.90f else 0.80f)
                arrow(canvas, fillPaint, entry.cx, entry.cy, r - t * 0.55f, dir.first, dir.second, t * 0.8f)
            }
        }
        if (editing && selected === entry) {
            ringPaint.strokeWidth = hairline * 0.8f
            ringPaint.color = tinted(color, 0.9f)
            ringPaint.style = Paint.Style.STROKE
            canvas.drawCircle(entry.cx, entry.cy, r + hairline * 3f, ringPaint)
        }
    }

    private fun crossPath(entry: Entry, path: android.graphics.Path, r: Float, t: Float) {
        val cx = entry.cx
        val cy = entry.cy
        path.reset()
        path.moveTo(cx - t, cy - r)
        path.lineTo(cx + t, cy - r)
        path.lineTo(cx + t, cy - t)
        path.lineTo(cx + r, cy - t)
        path.lineTo(cx + r, cy + t)
        path.lineTo(cx + t, cy + t)
        path.lineTo(cx + t, cy + r)
        path.lineTo(cx - t, cy + r)
        path.lineTo(cx - t, cy + t)
        path.lineTo(cx - r, cy + t)
        path.lineTo(cx - r, cy - t)
        path.lineTo(cx - t, cy - t)
        path.close()
    }

    private fun armRect(entry: Entry, rect: RectF, index: Int, r: Float, t: Float) {
        when (index) {
            0 -> rect.set(entry.cx - t, entry.cy - r, entry.cx + t, entry.cy - t)
            1 -> rect.set(entry.cx + t, entry.cy - t, entry.cx + r, entry.cy + t)
            2 -> rect.set(entry.cx - t, entry.cy + t, entry.cx + t, entry.cy + r)
            else -> rect.set(entry.cx - r, entry.cy - t, entry.cx - t, entry.cy + t)
        }
    }

    private fun arrow(canvas: Canvas, paint: Paint, cx: Float, cy: Float, dist: Float, dx: Float, dy: Float, size: Float) {
        val tipX = cx + dx * dist
        val tipY = cy + dy * dist
        val baseX = cx + dx * (dist - size)
        val baseY = cy + dy * (dist - size)
        val nx = -dy * size * 0.62f
        val ny = dx * size * 0.62f
        bodyPath.reset()
        bodyPath.moveTo(tipX, tipY)
        bodyPath.lineTo(baseX + nx, baseY + ny)
        bodyPath.lineTo(baseX - nx, baseY - ny)
        bodyPath.close()
        canvas.drawPath(bodyPath, paint)
    }

    private fun armOf(entry: Entry, x: Float, y: Float): Int {
        val dx = x - entry.cx
        val dy = y - entry.cy
        return if (abs(dx) > abs(dy)) (if (dx > 0f) 1 else 3) else (if (dy > 0f) 2 else 0)
    }

    private fun drawStick(canvas: Canvas, entry: Entry) {
        val color = accent().first
        val alpha = entry.opacity
        val engaged = entry.armed >= 0
        val radius = minOf(entry.halfW, entry.halfH)
        val hairline = maxOf(dp(1.2f), entry.halfH * 0.032f)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = tinted(color, 0.14f * alpha)
        canvas.drawCircle(entry.cx, entry.cy, radius - hairline, fillPaint)
        strokePaint.strokeWidth = if (engaged) hairline * 1.6f else hairline
        strokePaint.color = tinted(color, (if (editing) 0.90f else 0.80f) * alpha)
        canvas.drawCircle(entry.cx, entry.cy, radius - hairline / 2f, strokePaint)
        val dir = if (engaged) STICK_DIRS[entry.armed.coerceIn(0, 7)] else 0f to 0f
        val knob = radius * 0.36f
        fillPaint.color = tinted(color, if (engaged) 0.55f * alpha else 0.24f * alpha)
        canvas.drawCircle(entry.cx + dir.first * radius * 0.40f, entry.cy + dir.second * radius * 0.40f, knob, fillPaint)
        strokePaint.strokeWidth = hairline
        strokePaint.color = tinted(color, (if (editing) 0.90f else 0.80f) * alpha)
        canvas.drawCircle(entry.cx + dir.first * radius * 0.40f, entry.cy + dir.second * radius * 0.40f, knob, strokePaint)
        textPaint.setShadowLayer(0f, 0f, 0f, 0)
        STICK_DIRS.forEachIndexed { index, unit ->
            val on = entry.armed == index
            val code = entry.codes.getOrNull(index) ?: 0
            val name = if (code == 0) "" else screenKeyLabel(context, listOf(code))
            if (entry.labels && name.isNotEmpty()) {
                textPaint.color = if (on) Color.WHITE else tinted(color, 0.85f * alpha)
                textPaint.textSize = dp(48f)
                val fit = 48f * (radius * 0.34f) / textPaint.measureText(name).coerceAtLeast(1f)
                textPaint.textSize = minOf(fit, radius * 0.22f).coerceAtLeast(dp(6f))
                val tx = entry.cx + unit.first * radius * 0.66f
                val ty = entry.cy + unit.second * radius * 0.66f
                canvas.drawText(name, tx, ty - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
            } else {
                fillPaint.color = if (on) Color.WHITE else tinted(color, if (editing) 0.88f else 0.72f)
                arrow(canvas, fillPaint, entry.cx, entry.cy, radius * 0.88f, unit.first, unit.second, radius * 0.16f)
            }
        }
        if (editing && selected === entry) {
            ringPaint.strokeWidth = hairline * 0.8f
            ringPaint.color = tinted(color, 0.9f)
            ringPaint.style = Paint.Style.STROKE
            canvas.drawCircle(entry.cx, entry.cy, radius + hairline * 3f, ringPaint)
        }
    }

    private fun directionCodes(entry: Entry, index: Int): List<Int> {
        if (index < 0) return emptyList()
        val code = entry.codes.getOrNull(index) ?: 0
        if (code != 0) return listOf(code)
        if (entry.shape != SCREEN_KEY_STICK || index % 2 == 0) return emptyList()
        return listOfNotNull(
            entry.codes.getOrNull((index + 7) % 8)?.takeIf { it != 0 },
            entry.codes.getOrNull((index + 1) % 8)?.takeIf { it != 0 },
        )
    }

    private fun stickSector(entry: Entry, x: Float, y: Float): Int {
        val dx = x - entry.cx
        val dy = y - entry.cy
        if (hypot(dx, dy) < minOf(entry.halfW, entry.halfH) * 0.24f) return -1
        val degrees = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
        val index = (round(degrees / 45f).toInt() + 2) % 8
        return (index + 8) % 8
    }

    private fun radiusOf(entry: Entry): Float = when (entry.shape) {
        SCREEN_KEY_ROUND -> entry.halfH * 0.30f
        SCREEN_KEY_RECT -> entry.halfH * 0.12f
        SCREEN_KEY_TRIGGER -> entry.halfH * 0.45f
        else -> entry.halfH
    }

    private fun entryAt(x: Float, y: Float): Entry? = entries.lastOrNull { entry ->
        x >= entry.cx - entry.halfW && x <= entry.cx + entry.halfW && y >= entry.cy - entry.halfH && y <= entry.cy + entry.halfH
    }

    private fun inCircle(center: PointF, x: Float, y: Float, radius: Float) = hypot(x - center.x, y - center.y) <= radius

    private fun inToolbar(x: Float, y: Float): Boolean =
        editing && x >= toolbar.x - capsuleGap / 2f - capsuleRadius && x <= toolbar.x + capsuleGap / 2f + capsuleRadius &&
            y >= toolbar.y - capsuleRadius && y <= toolbar.y + capsuleRadius

    override fun onDraw(canvas: Canvas) {
        if (editing) drawGrid(canvas)
        entries.forEach { draw(canvas, it) }
        if (editing) {
            drawGuides(canvas)
            drawToolbar(canvas)
        }
    }

    private fun drawGuides(canvas: Canvas) {
        if (guideX < 0f && guideY < 0f) return
        gridPaint.strokeWidth = dp(1f)
        gridPaint.color = tinted(accent().first, 0.9f)
        if (guideX >= 0f) canvas.drawLine(guideX, 0f, guideX, height.toFloat(), gridPaint)
        if (guideY >= 0f) canvas.drawLine(0f, guideY, width.toFloat(), guideY, gridPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val step = width / 100f
        gridPaint.strokeWidth = dp(1f)
        gridPaint.color = Color.argb(38, 255, 255, 255)
        var x = step
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        var y = step
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += step
        }
        gridPaint.color = Color.argb(96, 255, 255, 255)
        gridPaint.strokeWidth = dp(1f)
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), gridPaint)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, gridPaint)
    }

    private fun draw(canvas: Canvas, entry: Entry) {
        if (entry.shape == SCREEN_KEY_DPAD) {
            drawDpad(canvas, entry)
            return
        }
        if (entry.shape == SCREEN_KEY_STICK) {
            drawStick(canvas, entry)
            return
        }
        val style = if (followSteam) steamStyle() else null
        val engaged = down.values.any { it === entry } || latched.contains(entry)
        val bitmap = bitmapFor(entry)
        val icon = bitmap == null && hasIcon(entry)
        val frameless = entry.text.isBlank() && (bitmap != null || icon)
        val alpha = entry.opacity
        val h2 = entry.halfH * 2f
        val radius = radiusOf(entry)
        val selectedNow = editing && selected === entry
        val hairline = maxOf(dp(1.2f), h2 * 0.032f)
        rect.set(entry.cx - entry.halfW, entry.cy - entry.halfH, entry.cx + entry.halfW, entry.cy + entry.halfH)
        val inkColor: Int
        if (style != null) {
            val inkAlpha = (255f * style.second * alpha).toInt().coerceIn(30, 255)
            inkColor = Color.argb(inkAlpha, Color.red(style.first), Color.green(style.first), Color.blue(style.first))
            fillPaint.style = Paint.Style.FILL
            if (engaged) {
                fillPaint.color = Color.argb((inkAlpha * 0.85f).toInt(), 230, 233, 236)
                body(canvas, entry, radius, fillPaint)
            }
            if (frameless) {
                if (engaged) {
                    fillPaint.color = Color.argb((inkAlpha * 0.45f).toInt(), 230, 233, 236)
                    body(canvas, entry, radius, fillPaint)
                }
            } else {
                strokePaint.color = if (selectedNow) Color.rgb(26, 159, 255) else inkColor
                strokePaint.strokeWidth = if (engaged) hairline * 1.6f else hairline
                body(canvas, entry, radius, strokePaint)
            }
        } else {
            fillPaint.style = Paint.Style.FILL
            if (!frameless) {
                fillPaint.color = tinted(BODY, 0.60f * alpha)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
            }
            if (engaged) {
                fillPaint.color = tinted(accent().first, if (frameless) 0.32f * alpha else 0.42f * alpha)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
            }
            if (!frameless) {
                strokePaint.strokeWidth = if (engaged) hairline * 1.6f else hairline
                strokePaint.color = tinted(
                    accent().first,
                    (if (engaged) 0.96f else if (editing) 0.80f else 0.68f) * alpha,
                )
                body(canvas, entry, radius, strokePaint)
            }
            inkColor = if (engaged) Color.WHITE else tinted(accent().first, alpha)
        }
        if (editing && selected === entry) {
            ringPaint.strokeWidth = hairline * 0.8f
            ringPaint.color = tinted(accent().first, 0.9f)
            val gap = hairline * 2.4f
            rect.set(
                entry.cx - entry.halfW - gap, entry.cy - entry.halfH - gap,
                entry.cx + entry.halfW + gap, entry.cy + entry.halfH + gap,
            )
            if (entry.shape == SCREEN_KEY_TRIGGER) {
                body(canvas, entry, radius + gap, ringPaint, gap)
            } else {
                canvas.drawRoundRect(rect, radius + gap, radius + gap, ringPaint)
            }
            rect.set(entry.cx - entry.halfW, entry.cy - entry.halfH, entry.cx + entry.halfW, entry.cy + entry.halfH)
        }

        if (bitmap != null) {
            imageRect.set(rect)
            canvas.save()
            clipPath.reset()
            when (entry.shape) {
                SCREEN_KEY_CIRCLE -> clipPath.addCircle(
                    entry.cx, entry.cy, minOf(entry.halfW, entry.halfH), android.graphics.Path.Direction.CW,
                )
                SCREEN_KEY_TRIGGER -> triggerPath(entry, clipPath, 0f)
                else -> clipPath.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            val scale = maxOf(imageRect.width() / bitmap.width, imageRect.height() / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            bitmapRect.set(
                imageRect.centerX() - w / 2f, imageRect.centerY() - h / 2f,
                imageRect.centerX() + w / 2f, imageRect.centerY() + h / 2f,
            )
            canvas.drawBitmap(bitmap, null, bitmapRect, fillPaint)
            canvas.restore()
        }

        val name = label(entry)
        textPaint.color = inkColor
        if (bitmap != null && name.isEmpty()) return
        var iconDrawn = false
        if (icon) {
            val box = minOf(entry.halfW, entry.halfH) * 1.35f
            iconDrawn = ScreenKeyIcons.draw(canvas, entry.codes[0], entry.cx, entry.cy, box, textPaint)
            textPaint.color = inkColor
        }
        if (iconDrawn || name.isEmpty()) return
        val overImage = bitmap != null
        if (overImage) textPaint.color = Color.WHITE
        textPaint.setShadowLayer(if (overImage) dp(3f) else 0f, 0f, 0f, Color.argb(200, 0, 0, 0))
        textPaint.textSize = dp(48f)
        val available = rect.width() - hairline * 4f
        val fit = 48f * available / textPaint.measureText(name).coerceAtLeast(1f)
        textPaint.textSize = minOf(fit, entry.halfH * 1.15f).coerceIn(dp(8f), dp(120f))
        canvas.drawText(name, entry.cx, entry.cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        textPaint.clearShadowLayer()
    }

    private fun drawToolbar(canvas: Canvas) {
        val (color, _) = accent()
        val capsule = RectF(
            toolbar.x - capsuleGap / 2f - capsuleRadius, toolbar.y - capsuleRadius,
            toolbar.x + capsuleGap / 2f + capsuleRadius, toolbar.y + capsuleRadius,
        )
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = tinted(color, 0.30f)
        canvas.drawRoundRect(capsule, capsuleRadius, capsuleRadius, fillPaint)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = tinted(color, 0.85f)
        canvas.drawRoundRect(capsule, capsuleRadius, capsuleRadius, strokePaint)

        textPaint.color = Color.WHITE
        textPaint.setShadowLayer(0f, 0f, 0f, 0)
        textPaint.textSize = capsuleRadius * 1.15f
        val plus = "+"
        canvas.drawText(plus, addCenter.x, addCenter.y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        val path = android.graphics.Path()
        val check = capsuleRadius * 0.42f
        path.moveTo(doneCenter.x - check, doneCenter.y)
        path.lineTo(doneCenter.x - check * 0.25f, doneCenter.y + check * 0.7f)
        path.lineTo(doneCenter.x + check, doneCenter.y - check * 0.7f)
        ringPaint.strokeWidth = dp(3f)
        ringPaint.strokeCap = Paint.Cap.ROUND
        ringPaint.color = Color.WHITE
        canvas.drawPath(path, ringPaint)
        ringPaint.strokeCap = Paint.Cap.BUTT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = if (editing) editTouch(event) else playTouch(event)

    private fun playTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val entry = entryAt(event.getX(index), event.getY(index))
                if (entry == null) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) return false
                    forwardedPointerBits = forwardedPointerBits or (1 shl event.getPointerId(index))
                    forwardPointers(event, forwardedPointerBits)
                    return true
                }
                if (forwardedPointerBits != 0) forwardPointers(event, forwardedPointerBits)
                if (entry.shape == SCREEN_KEY_DPAD) {
                    val arm = armOf(entry, event.getX(index), event.getY(index))
                    val code = entry.codes.getOrNull(arm) ?: 0
                    if (code == 0) return true
                    val pid = event.getPointerId(index)
                    dpadDown.remove(pid)?.let { releaseKey(it) }
                    dpadDown[pid] = code
                    entry.armed = arm
                    pressKey(code)
                    invalidate()
                    return true
                }
                if (entry.shape == SCREEN_KEY_STICK) {
                    val sector = stickSector(entry, event.getX(index), event.getY(index))
                    val pid = event.getPointerId(index)
                    stickCodes.remove(pid)?.forEach { if (it != 0) releaseKey(it) }
                    val codes = directionCodes(entry, sector)
                    stickEntries[pid] = entry
                    stickCodes[pid] = codes
                    entry.armed = if (codes.isEmpty()) -1 else sector
                    codes.forEach { pressKey(it) }
                    invalidate()
                    return true
                }
                if (entry.toggle) {
                    if (latched.remove(entry)) release(entry) else {
                        latched += entry
                        entry.codes.forEach { pressKey(it) }
                    }
                    invalidate()
                    return true
                }
                if (down.values.any { it === entry }) return true
                down[event.getPointerId(index)] = entry
                entry.codes.forEach { pressKey(it) }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (forwardedPointerBits != 0) forwardPointers(event, forwardedPointerBits)
                if (stickEntries.isEmpty()) return true
                repeat(event.pointerCount) { index ->
                    val pid = event.getPointerId(index)
                    val entry = stickEntries[pid] ?: return@repeat
                    val sector = stickSector(entry, event.getX(index), event.getY(index))
                    val codes = directionCodes(entry, sector)
                    val previous = stickCodes[pid].orEmpty()
                    if (codes != previous) {
                        previous.filterNot { it in codes }.forEach { releaseKey(it) }
                        codes.filterNot { it in previous }.forEach { pressKey(it) }
                        stickCodes[pid] = codes
                        entry.armed = if (codes.isEmpty()) -1 else sector
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(event.actionIndex)
                if (forwardedPointerBits and (1 shl pid) != 0) {
                    forwardPointers(event, forwardedPointerBits)
                    forwardedPointerBits = forwardedPointerBits and (1 shl pid).inv()
                    if (stickCodes.containsKey(pid) || dpadDown.containsKey(pid)) return true
                    down.remove(pid)?.let(::release)
                    return true
                }
                if (forwardedPointerBits != 0) forwardPointers(event, forwardedPointerBits)
                if (stickCodes.containsKey(pid)) {
                    stickCodes.remove(pid)?.forEach { if (it != 0) releaseKey(it) }
                    stickEntries.remove(pid)?.armed = -1
                    invalidate()
                    return true
                }
                val armedCode = dpadDown.remove(pid)
                if (armedCode != null) {
                    releaseKey(armedCode)
                    entries.firstOrNull { it.armed >= 0 && it.codes.contains(armedCode) }?.armed = -1
                    invalidate()
                    return true
                }
                val entry = down.remove(pid)
                if (entry != null) release(entry)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (forwardedPointerBits != 0) forwardPointers(event, forwardedPointerBits)
                forwardedPointerBits = 0
                stickCodes.values.flatten().forEach { if (it != 0) releaseKey(it) }
                stickCodes.clear()
                stickEntries.clear()
                entries.forEach { it.armed = -1 }
                dpadDown.values.forEach { releaseKey(it) }
                dpadDown.clear()
                entries.forEach { it.armed = -1 }
                down.values.forEach { release(it) }
                down.clear()
                return true
            }
        }
        return true
    }

    private fun forwardPointers(event: MotionEvent, pointerIdBits: Int) {
        val split = event.pointerSubset(pointerIdBits) ?: return
        try {
            onTouchMiss?.invoke(split)
        } finally {
            split.recycle()
        }
    }

    private fun editTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                if (inCircle(addCenter, x, y, capsuleRadius)) {
                    onAdd?.invoke()
                    return true
                }
                if (inCircle(doneCenter, x, y, capsuleRadius)) {
                    stopEditing()
                    return true
                }
                if (inToolbar(x, y)) {
                    toolbarDrag = true
                    dragX = toolbar.x - x
                    dragY = toolbar.y - y
                    return true
                }
                val entry = entryAt(x, y)
                if (entry == null) {
                    pressX = x
                    pressY = y
                    postDelayed(addPress, 520)
                    selected = null
                    invalidate()
                    return true
                }
                selected = entry
                dragging = true
                moved = false
                dragX = entry.cx - x
                dragY = entry.cy - y
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (toolbarDrag) {
                    val maxX = width - safe.right - capsuleGap / 2f - capsuleRadius
                    val maxY = height - safe.bottom - capsuleRadius
                    toolbar.x = (event.x + dragX).coerceIn(safe.left + capsuleGap / 2f + capsuleRadius, maxOf(safe.left + capsuleGap / 2f + capsuleRadius, maxX))
                    toolbar.y = (event.y + dragY).coerceIn(safe.top + capsuleRadius, maxOf(safe.top + capsuleRadius, maxY))
                    addCenter.set(toolbar.x - capsuleGap / 2f, toolbar.y)
                    doneCenter.set(toolbar.x + capsuleGap / 2f, toolbar.y)
                    invalidate()
                    return true
                }
                val entry = selected
                if (entry == null) {
                    if (hypot(event.x - pressX, event.y - pressY) > dp(10f)) removeCallbacks(addPress)
                    return true
                }
                if (!dragging) return true
                if (hypot(event.x - (entry.cx - dragX), event.y - (entry.cy - dragY)) > dp(10f)) removeCallbacks(addPress)
                var nextX = snap((event.x + dragX).coerceIn(entry.halfW + safe.left, width - entry.halfW - safe.right))
                var nextY = snap((event.y + dragY).coerceIn(entry.halfH + safe.top, height - entry.halfH - safe.bottom))
                guideX = -1f
                guideY = -1f
                entries.forEach { other ->
                    if (other === entry) return@forEach
                    if (abs(nextX - other.cx) < dp(8f)) { nextX = other.cx; guideX = other.cx }
                    if (abs(nextY - other.cy) < dp(8f)) { nextY = other.cy; guideY = other.cy }
                }
                if (abs(nextX - width / 2f) < dp(8f)) { nextX = width / 2f; guideX = nextX }
                if (abs(nextY - height / 2f) < dp(8f)) { nextY = height / 2f; guideY = nextY }
                if (hypot(nextX - entry.cx, nextY - entry.cy) > dp(4f)) moved = true
                entry.cx = nextX
                entry.cy = nextY
                entry.x = entry.cx / width
                entry.y = entry.cy / height
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(addPress)
                guideX = -1f
                guideY = -1f
                if (toolbarDrag) {
                    toolbarDrag = false
                    return true
                }
                val entry = selected
                if (entry != null && dragging) {
                    dragging = false
                    if (moved) {
                        save()
                        invalidate()
                    } else {
                        onSelect?.invoke(entry.codes)
                    }
                }
                return true
            }
        }
        return true
    }

    private fun pressKey(code: Int) {
        val owners = held[code] ?: 0
        held[code] = owners + 1
        if (owners == 0) sendKey(code, true)
    }

    private fun releaseKey(code: Int) {
        val owners = held[code] ?: return
        if (owners > 1) {
            held[code] = owners - 1
            return
        }
        held.remove(code)
        sendKey(code, false)
    }

    private fun release(entry: Entry) {
        entry.codes.asReversed().forEach { releaseKey(it) }
        invalidate()
    }

    fun export(): String = ControllerPrefs.encodeScreenKeys(
        entries.map { ScreenKey(it.codes, it.x, it.y, it.size, it.glyph, it.text, it.image, it.opacity, it.shape, it.toggle, it.labels) },
    )

    fun load(raw: String) {
        releaseAll()
        entries.clear()
        ControllerPrefs.decodeScreenKeys(raw).forEach { entries += entryOf(it) }
        entries.forEach { measure(it) }
        selected = null
        save()
        invalidate()
    }

    private fun save() {
        ControllerPrefs.setScreenKeys(
            context,
            entries.map { ScreenKey(it.codes, it.x, it.y, it.size, it.glyph, it.text, it.image, it.opacity, it.shape, it.toggle, it.labels) },
        )
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    companion object {
        private val DPAD_DIRS = listOf(
            0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f,
        )
        private val STICK_DIRS = listOf(
            0f to -1f, 0.7071f to -0.7071f, 1f to 0f, 0.7071f to 0.7071f,
            0f to 1f, -0.7071f to 0.7071f, -1f to 0f, -0.7071f to -0.7071f,
        )
        const val DEFAULT_SIZE = 44
        const val MIN_SIZE = 24
        const val MAX_SIZE = 140
        const val IMAGE_DIR = "screenkeys"
        private const val BODY = 0xFF14181F.toInt()
    }
}
