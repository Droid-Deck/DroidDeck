package com.droiddeck.launcher.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import com.droiddeck.launcher.input.SteamTouchConfig.Element
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Steam's touch controls, drawn by the app.
 *
 * The layout is Steam's: the game's touch config for the action set the client says is active
 * (SteamTouchDevice.State, SteamTouchConfig), placed where the config's layout puts each control or
 * where Steam Link would by default, and only the controls the config binds. Touches become the
 * touch controller's input report; the client does the rest - bindings, action sets, the virtual
 * pad the game reads. Each control shows its binding's icon, label and colours, as Steam keeps them
 * (SteamTouchBindings). [startEditing] moves, resizes and hides controls, picks their icons and the
 * layout's colour, and saves all of it into the game's touch config, as Steam Link and Steam's
 * configurator do.
 */
@SuppressLint("ViewConstructor")
class SteamTouchControls(
    context: Context,
    val device: SteamTouchDevice,
    private val onMenu: () -> Unit,
    private val onKeyboard: () -> Unit,
) : View(context) {

    /** The game's input and mouse modes changed (from its config or the menu): the session routes
     *  touches outside the controls by them. */
    var onOptions: (SteamTouchConfig.Options) -> Unit = {}

    /** The Paste control: types the clipboard into the session. */
    var onPaste: () -> Unit = {}

    // Steam Link's own settings, for every game (CVirtualControllerGlobalConfig).
    private val prefs = context.getSharedPreferences("steam_touch", Context.MODE_PRIVATE)
    private var feedback = prefs.getBoolean("feedback", false)
    private var autoFade = prefs.getBoolean("autoFade", true)
    private var gyroOn = prefs.getBoolean("gyro", true).also { device.motionEnabled = it }
    // Shake the device to hide the controls and again to bring them back (Steam Link's shake_fade).
    private var shakeToHide = prefs.getBoolean("shake", false)
    private var shakenAway = false
    private val sensors by lazy { context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager }
    private var lastJolt = 0L
    private var jolts = 0
    private var lastShake = 0L
    private val shakeListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            val (ax, ay, az) = event.values
            val g = kotlin.math.sqrt(ax * ax + ay * ay + az * az) / android.hardware.SensorManager.GRAVITY_EARTH
            if (g < SHAKE_G) return
            val now = android.os.SystemClock.uptimeMillis()
            jolts = if (now - lastJolt < 400) jolts + 1 else 1
            lastJolt = now
            // Three jolts in quick succession, at most once a second.
            if (jolts >= 3 && now - lastShake > 1000) {
                lastShake = now
                jolts = 0
                handler.post { toggleShaken() }
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
    }

    private fun updateShake() {
        sensors.unregisterListener(shakeListener)
        if (shakeToHide && isAttachedToWindow) sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(shakeListener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        }
        if (!shakeToHide && shakenAway) toggleShaken()
    }

    private fun toggleShaken() {
        if (editing || menuOpen) return
        shakenAway = !shakenAway
        releaseAll()
        if (feedback) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }
    private var lastTouchMs = android.os.SystemClock.uptimeMillis()
    private var faded = false
    private var options = SteamTouchConfig.Options()
    private var pendingOptions: SteamTouchConfig.Options? = null

    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()

    private var config = SteamTouchConfig.Config(null, null, null, emptyMap())
    private var configApp = -1
    private var appId = 0
    private var actionSet = 0
    private var layers: List<Int> = emptyList()
    private var actionSeq = -1
    private var opened = 0
    private var elements: List<Element> = emptyList()
    private var tint = Color.WHITE
    private var alphaScale = 0.45f

    // What each control is bound to (its icon, label, colours), per element type; the D-pad has four.
    private class Visual(val ref: SteamTouchBindings.Ref?, val binding: SteamTouchBindings.Binding?)
    private var visuals: Map<Int, List<Visual>> = emptyMap()

    // Touch state.
    private class Finger(val element: Element?, var x: Float, var y: Float, val toolbar: Int = -1)
    private val fingers = HashMap<Int, Finger>()

    // Editing.
    private var editing = false
    private var editElements: MutableList<Element> = mutableListOf()
    private var hidden: MutableList<Element> = mutableListOf()
    private var selected = -1
    private var dragOffset = 0f to 0f
    private var pinchStart = 0f
    private var pinchScale = 1f
    private var saving = false
    // Icon and colour edits not saved yet.
    private val pendingBindings = HashMap<SteamTouchBindings.Ref, SteamTouchBindings.Binding>()
    private var pendingColor: FloatArray? = null
    private var selectedArm = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    private val poll = object : Runnable {
        override fun run() {
            refreshState()
            val fade = autoFade && !editing && fingers.isEmpty() &&
                android.os.SystemClock.uptimeMillis() - lastTouchMs > FADE_AFTER_MS
            if (fade != faded) { faded = fade; invalidate() }
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(poll)
        updateShake()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(poll)
        sensors.unregisterListener(shakeListener)
        releaseAll()
        super.onDetachedFromWindow()
    }

    /** Follows what the client tells the device: a new app loads its config, a new action set or
     *  layer redraws. */
    private fun refreshState() {
        val state = device.state()
        val app = if (state.appId == 0) BIG_PICTURE else state.appId
        // The client reads the config afresh when the controller (re)connects: so do we.
        val reconnected = state.opened > 0 && opened == 0
        opened = state.opened
        if (app != configApp || reconnected) {
            configApp = app
            reload(app)
        }
        if (state.actionSeq != actionSeq) {
            actionSeq = state.actionSeq
            actionSet = state.actionSet
            layers = state.layers
            appId = app
            rebuild()
        }
    }

    private fun reload(app: Int) {
        loader.execute {
            val loaded = SteamTouchConfig.load(context, app)
            handler.post {
                if (app != configApp) return@post
                config = loaded
                appId = app
                rebuild()
            }
        }
    }

    /** Re-reads the config for the current app (after a save). */
    fun reloadConfig() {
        configApp = -1
        refreshState()
    }

    private fun rebuild() {
        elements = SteamTouchConfig.elementsFor(config, actionSet, layers)
        val nextOptions = pendingOptions ?: config.layouts?.options ?: SteamTouchConfig.Options()
        if (nextOptions != options) { options = nextOptions; onOptions(options) }
        applyColor(pendingColor ?: SteamTouchConfig.layoutColor(config, actionSet))
        rebuildVisuals()
        if (!editing) {
            Log.i(TAG, "steam touch: app $appId, action set $actionSet, layers $layers: ${elements.size} controls")
            releaseAll()
        }
        invalidate()
    }

    private fun applyColor(color: FloatArray?) {
        tint = if (color != null) Color.rgb((color[0] * 255).toInt(), (color[1] * 255).toInt(), (color[2] * 255).toInt()) else Color.WHITE
        // Steam Link's opacity: its default layout colour (alpha about 0.4) draws solid white
        // outlines; lower values fade them.
        alphaScale = ((color?.get(3) ?: 0.45f) * 2.2f).coerceIn(0.15f, 1f)
    }

    /** Each control's bindings for the action set and layers in use, with edits not saved yet. */
    private fun rebuildVisuals() {
        val mappings = config.mappings
        val preset = SteamTouchConfig.layoutIdOf(actionSet) - 1
        val types = (elements + editElements).map { it.type }.toSet()
        visuals = if (mappings == null) emptyMap() else types.associateWith { type ->
            SteamTouchBindings.refsFor(mappings, type, preset, layers).map { ref ->
                Visual(ref, ref?.let { pendingBindings[it] ?: SteamTouchBindings.bindingAt(mappings, it) })
            }
        }
        val icons = visuals.values.flatten().mapNotNull { it.binding?.icon?.takeIf(String::isNotEmpty) }.toSet()
        if (icons.isEmpty()) return
        val app = appId
        loader.execute {
            icons.forEach { SteamTouchBindings.icon(context, app, it, ICON_PX) }
            handler.post { invalidate() }
        }
    }

    // ---- Geometry ----

    private fun unit() = min(width / 1280f, height / 720f)

    private fun radius(e: Element): Float = baseRadius(e.type) * e.xScale * unit()
    private fun radiusY(e: Element): Float = baseRadius(e.type) * e.yScale * unit()

    private fun baseRadius(type: Int) = when (type) {
        // Steam Link's sizes on its 1280x720 reference.
        SteamTouchConfig.DPAD -> 112f
        SteamTouchConfig.JOYSTICK_LEFT, SteamTouchConfig.JOYSTICK_RIGHT -> 90f
        SteamTouchConfig.TRACKPAD_LEFT, SteamTouchConfig.TRACKPAD_RIGHT, SteamTouchConfig.TRACKPAD_CENTER -> 100f
        SteamTouchConfig.A, SteamTouchConfig.B, SteamTouchConfig.X, SteamTouchConfig.Y -> 38f
        SteamTouchConfig.STEAM -> 48f
        SteamTouchConfig.SELECT, SteamTouchConfig.START -> 34f
        SteamTouchConfig.TRIGGER_LEFT, SteamTouchConfig.TRIGGER_RIGHT, SteamTouchConfig.BUMPER_LEFT, SteamTouchConfig.BUMPER_RIGHT,
        SteamTouchConfig.THUMB, SteamTouchConfig.KEYBOARD -> 37f
        else -> 30f
    }

    private fun cx(e: Element) = e.x * width
    private fun cy(e: Element) = e.y * height

    /** How far (x, y) is from [e]'s centre, in its own radii (it may be an oval). */
    private fun reach(e: Element, x: Float, y: Float) = hypot((x - cx(e)) / radius(e), (y - cy(e)) / radiusY(e))

    private fun hit(list: List<Element>, x: Float, y: Float): Element? =
        list.filter { reach(it, x, y) <= 1.15f }.minByOrNull { reach(it, x, y) }

    /** What is drawn and touchable: in mouse mode only the menu button, as on Steam Link. */
    private fun live(): List<Element> =
        if (shakenAway) emptyList() else if (options.inputMode == SteamTouchConfig.INPUT_MOUSE) elements.filter { it.type == SteamTouchConfig.THUMB } else elements

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        val shown = if (editing) editElements else live()
        shown.forEach { e ->
            val x = cx(e)
            val y = cy(e)
            canvas.save()
            // An oval control: drawn as its circle, stretched to its height.
            if (e.yScale != e.xScale && e.xScale > 0f) canvas.scale(1f, e.yScale / e.xScale, x, y)
            draw(canvas, e, editing && editElements.indexOf(e) == selected)
            canvas.restore()
        }
        if (editing) {
            drawEditChrome(canvas)
            if (trayOpen) drawTray(canvas)
        } else if (menuOpen) {
            hots.clear()
            drawMenu(canvas)
        }
    }

    private fun pressedTypes(): Set<Int> = fingers.values.mapNotNull { it.element?.type }.toSet()

    // ---- Steam Link's look (measured from Steam Link 1.3 on the Thor) ----
    // Outlines only: 3 px white strokes on a 1280x720 reference, no fill; a pressed control fills
    // white with its content dark. Face letters in Steam's colours; Select and Start are ◀ ▶ pills
    // beside a solid white Steam disc; bumpers, triggers, the menu and keyboard are rounded squares.

    private fun lineWidth() = 3f * unit()

    private fun draw(canvas: Canvas, e: Element, isSelected: Boolean) {
        val x = cx(e)
        val y = cy(e)
        val r = radius(e)
        val pressed = !editing && e.type in pressedTypes()
        val alpha = (255 * alphaScale * if (faded) FADED else 1f).toInt().coerceIn(30, 255)
        val ink = Color.argb(alpha, Color.red(tint), Color.green(tint), Color.blue(tint))
        stroke.color = if (isSelected) Color.rgb(26, 159, 255) else ink
        stroke.strokeWidth = if (isSelected) lineWidth() * 2f else lineWidth()
        fill.color = Color.argb((alpha * 0.85f).toInt(), 230, 233, 236)
        text.color = ink
        text.typeface = android.graphics.Typeface.DEFAULT_BOLD
        when (e.type) {
            SteamTouchConfig.DPAD -> drawDpad(canvas, e, x, y, r)
            SteamTouchConfig.JOYSTICK_LEFT, SteamTouchConfig.JOYSTICK_RIGHT -> {
                canvas.drawCircle(x, y, r, stroke)
                val f = fingers.values.firstOrNull { it.element?.type == e.type }
                if (f != null && !editing) {
                    val d = hypot(f.x - x, f.y - y)
                    val k = if (d > r) r / d else 1f
                    val kx = x + (f.x - x) * k
                    val ky = y + (f.y - y) * k
                    canvas.drawCircle(kx, ky, r * 0.42f, fill)
                }
            }
            SteamTouchConfig.TRACKPAD_LEFT, SteamTouchConfig.TRACKPAD_RIGHT, SteamTouchConfig.TRACKPAD_CENTER -> {
                val rect = RectF(x - r * 1.3f, y - r * 0.85f, x + r * 1.3f, y + r * 0.85f)
                if (pressed) canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, fill)
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, stroke)
                if (editing) {
                    text.textSize = r * 0.3f
                    text.color = Color.argb(alpha / 2, 255, 255, 255)
                    canvas.drawText("Trackpad", x, y - (text.descent() + text.ascent()) / 2, text)
                }
            }
            SteamTouchConfig.STEAM -> {
                // The guide button: DroidDeck's split D and orb, in a ring.
                if (pressed) canvas.drawCircle(x, y, r, fill)
                canvas.drawCircle(x, y, r, stroke)
                drawMark(canvas, x, y, r * 0.5f, if (pressed) Color.argb(alpha, 23, 26, 33) else ink, alpha)
            }
            SteamTouchConfig.SELECT, SteamTouchConfig.START -> {
                val binding = visuals[e.type]?.firstOrNull()?.binding
                if (binding?.hasIcon == true) {
                    drawFace(canvas, e.type, binding, x, y, r, alpha, pressed); return
                }
                val rect = RectF(x - r * 1.08f, y - r * 0.55f, x + r * 1.08f, y + r * 0.55f)
                if (pressed) canvas.drawRoundRect(rect, r * 0.5f, r * 0.5f, fill)
                canvas.drawRoundRect(rect, r * 0.5f, r * 0.5f, stroke)
                val t = r * 0.26f
                val dir = if (e.type == SteamTouchConfig.START) 1f else -1f
                val tri = Path().apply {
                    moveTo(x + dir * t, y); lineTo(x - dir * t * 0.75f, y - t); lineTo(x - dir * t * 0.75f, y + t); close()
                }
                canvas.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (pressed) Color.argb(alpha, 23, 26, 33) else ink })
            }
            else -> drawFace(canvas, e.type, visuals[e.type]?.firstOrNull()?.binding, x, y, r, alpha, pressed)
        }
    }

    /** Controls drawn as rounded squares on Steam Link; the face buttons and the rest are rings. */
    private fun isSquare(type: Int) = type in setOf(SteamTouchConfig.BUMPER_LEFT, SteamTouchConfig.BUMPER_RIGHT,
        SteamTouchConfig.TRIGGER_LEFT, SteamTouchConfig.TRIGGER_RIGHT, SteamTouchConfig.THUMB, SteamTouchConfig.KEYBOARD,
        SteamTouchConfig.PASTE, SteamTouchConfig.MACRO_1_FINGER, SteamTouchConfig.MACRO_2_FINGER) ||
        type in SteamTouchConfig.MACRO_0..SteamTouchConfig.MACRO_0 + 7

    private val markD by lazy { androidx.core.graphics.PathParser.createPathFromPathData(MARK_D) }

    /** DroidDeck's mark (artwork/droiddeck-mark.svg): the split D in [ink] around the blue orb,
     *  centred at (x, y), [r] its half height. */
    private fun drawMark(canvas: Canvas, x: Float, y: Float, r: Float, ink: Int = onSurface, alpha: Int = 255) {
        val k = r / 111.86f
        canvas.save()
        canvas.translate(x - 71.98f * k, y - 111.86f * k)
        canvas.scale(k, k)
        canvas.drawPath(markD, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
        canvas.drawCircle(63.98f, 111.86f, 55.98f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; this.alpha = alpha })
        canvas.restore()
    }

    /** The Steam logo's mark, drawn: a wheel and a piston rod. */
    private fun drawSteamMark(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        p.strokeWidth = r * 0.16f
        canvas.drawCircle(x + r * 0.28f, y - r * 0.18f, r * 0.27f, p)
        canvas.drawCircle(x + r * 0.28f, y - r * 0.18f, r * 0.1f, Paint(p).apply { style = Paint.Style.FILL })
        canvas.drawCircle(x - r * 0.3f, y + r * 0.3f, r * 0.17f, p)
        p.strokeWidth = r * 0.2f
        canvas.drawLine(x + r * 0.1f, y - r * 0.02f, x - r * 0.2f, y + r * 0.22f, p)
        canvas.drawLine(x - r * 0.45f, y + r * 0.28f, x - r * 0.95f, y + r * 0.1f, p)
    }

    /**
     * A button as Steam Link draws it: a ring (or rounded square) outline with its name - face
     * letters in Steam's colours. With a binding icon, the icon on a disc of its background colour,
     * as Steam draws binding icons; with a binding label, the label.
     */
    private fun drawFace(canvas: Canvas, type: Int, binding: SteamTouchBindings.Binding?, x: Float, y: Float, r: Float,
                         alpha: Int, pressed: Boolean) {
        val square = isSquare(type)
        val rect = RectF(x - r, y - r, x + r, y + r)
        val corner = r * 0.3f
        fun shape(paint: Paint) = if (square) canvas.drawRoundRect(rect, corner, corner, paint) else canvas.drawCircle(x, y, r, paint)
        val icon = binding?.icon?.takeIf { it.isNotEmpty() }?.let(::iconOrLoad)
        if (icon != null) {
            val s = r * 0.62f
            val custom = binding.background.isNotEmpty() && !binding.background.equals(SteamTouchBindings.DEFAULT_BACKGROUND, true)
            if (custom) {
                // Colours chosen for this icon: drawn as Steam draws binding icons.
                shape(Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = parseColor(binding.background, SteamTouchBindings.DEFAULT_BACKGROUND)
                    this.alpha = if (pressed) alpha else (alpha * 0.85f).toInt()
                })
                canvas.drawBitmap(tinted(binding.icon, icon, binding.foreground), null, RectF(x - s, y - s, x + s, y + s),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha })
            } else {
                // Steam's default colours: Steam Link's own look, the icon in the outline's ink.
                if (pressed) shape(fill)
                val ink = if (pressed) Color.argb(alpha, 23, 26, 33) else stroke.color
                canvas.drawBitmap(inked(binding.icon, icon), null, RectF(x - s, y - s, x + s, y + s),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = android.graphics.PorterDuffColorFilter(ink, android.graphics.PorterDuff.Mode.SRC_IN)
                    })
            }
            shape(stroke)
            return
        }
        if (pressed) shape(fill)
        shape(stroke)
        if (type == SteamTouchConfig.THUMB) { drawDots(canvas, x, y, r, if (pressed) Color.argb(alpha, 23, 26, 33) else text.color); return }
        if (type == SteamTouchConfig.KEYBOARD) { drawKeyboardGlyph(canvas, x, y, r, if (pressed) Color.argb(alpha, 23, 26, 33) else text.color); return }
        // Steam Link names a control by itself, whatever its binding is labelled ("B", not "Back").
        val own = label(type)
        val shown = own
        text.textSize = when {
            shown.length <= 1 -> r * 0.9f
            shown.length <= 2 -> r * 0.66f
            shown.length <= 5 -> r * 0.4f
            else -> r * 0.3f
        }
        text.color = when {
            pressed -> Color.argb(alpha, 23, 26, 33)
            shown == own -> LABEL_COLORS[type]?.let { Color.argb(alpha, Color.red(it), Color.green(it), Color.blue(it)) } ?: text.color
            else -> text.color
        }
        val line = if (shown.length > 10) shown.take(9) + "…" else shown
        canvas.drawText(line, x, y - (text.descent() + text.ascent()) / 2, text)
    }

    private fun drawDots(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        for (i in -1..1) canvas.drawCircle(x + i * r * 0.3f, y + r * 0.22f, r * 0.08f, p)
    }

    private fun drawKeyboardGlyph(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = lineWidth() * 0.8f }
        val body = RectF(x - r * 0.6f, y - r * 0.34f, x + r * 0.6f, y + r * 0.34f)
        canvas.drawRoundRect(body, r * 0.08f, r * 0.08f, p)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        for (row in 0..1) for (i in 0..5) canvas.drawCircle(body.left + r * 0.15f + i * r * 0.18f, body.top + r * 0.18f + row * r * 0.17f, r * 0.035f, dot)
        canvas.drawLine(x - r * 0.3f, body.bottom - r * 0.14f, x + r * 0.3f, body.bottom - r * 0.14f, p)
    }

    private val iconsLoading = HashSet<String>()

    // Icons as a mask (their alpha), tinted when drawn.
    private val inkedIcons = android.util.LruCache<String, android.graphics.Bitmap>(48)

    private fun inked(name: String, icon: android.graphics.Bitmap): android.graphics.Bitmap {
        val key = "$name|${icon.width}"
        inkedIcons.get(key)?.let { return it }
        // Steam's icons are drawn in greys on clear; their dark parts are the glyph's detail, so the
        // mask keeps what is opaque and not near white.
        val out = icon.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val px = IntArray(out.width * out.height)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        for (i in px.indices) {
            val c = px[i]
            val a = Color.alpha(c)
            val light = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
            px[i] = Color.argb(if (light > 200) a else a * light / 200, 255, 255, 255)
        }
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
        inkedIcons.put(key, out)
        return out
    }

    // Icons already in their foreground colour. Drawn plain: a colour filter on the icon bitmap
    // itself stopped drawing on the hardware canvas once a dialog had been over the view.
    private val tintedIcons = android.util.LruCache<String, android.graphics.Bitmap>(48)

    private fun tinted(name: String, icon: android.graphics.Bitmap, foreground: String): android.graphics.Bitmap {
        val key = "$name|$foreground|${icon.width}"
        tintedIcons.get(key)?.let { return it }
        val out = android.graphics.Bitmap.createBitmap(icon.width, icon.height, android.graphics.Bitmap.Config.ARGB_8888)
        SteamTouchIconPicker.drawSteamIcon(Canvas(out), icon, RectF(0f, 0f, icon.width.toFloat(), icon.height.toFloat()),
            parseColor(foreground, SteamTouchBindings.DEFAULT_FOREGROUND), Paint(Paint.FILTER_BITMAP_FLAG))
        tintedIcons.put(key, out)
        return out
    }

    /** An icon from the cache, or null while it is (re)loaded; the view redraws when it comes in. */
    private fun iconOrLoad(name: String): android.graphics.Bitmap? {
        SteamTouchBindings.cachedIcon(appId, name, ICON_PX)?.let { return it }
        if (iconsLoading.add(name)) {
            val app = appId
            loader.execute {
                SteamTouchBindings.icon(context, app, name, ICON_PX)
                handler.post { iconsLoading.remove(name); invalidate() }
            }
        }
        return null
    }

    private fun parseColor(hex: String, fallback: String) =
        try { Color.parseColor(hex.ifEmpty { fallback }) } catch (_: IllegalArgumentException) { Color.parseColor(fallback) }

    /** Steam Link's d-pad: four shield-shaped arms pointing at the centre, outlined. */
    private fun drawDpad(canvas: Canvas, e: Element, x: Float, y: Float, r: Float) {
        val dirs = if (editing) 0 else dpadBits(e)
        val armVisuals = visuals[SteamTouchConfig.DPAD].orEmpty()
        val isSelected = editing && selected >= 0 && editElements.indexOf(e) == selected
        val half = r * 0.36f      // half the arm's width
        val outer = r              // the arm's far end
        val inner = r * 0.1f       // the point, near the centre
        val shoulder = r * 0.42f   // where the sides turn into the point
        val corner = r * 0.12f
        // Arms in report order: up, down, left, right, as (bit, rotation).
        val arms = listOf(DPAD_UP to 0f, DPAD_DOWN to 180f, DPAD_LEFT to 270f, DPAD_RIGHT to 90f)
        val oldStroke = stroke.color
        for ((armIndex, armEntry) in arms.withIndex()) {
            val (bit, angle) = armEntry
            val path = Path().apply {
                moveTo(x, y - inner)
                lineTo(x - half, y - shoulder)
                lineTo(x - half, y - outer + corner)
                quadTo(x - half, y - outer, x - half + corner, y - outer)
                lineTo(x + half - corner, y - outer)
                quadTo(x + half, y - outer, x + half, y - outer + corner)
                lineTo(x + half, y - shoulder)
                close()
            }
            canvas.save()
            canvas.rotate(angle, x, y)
            val down = dirs and bit != 0L
            val binding = armVisuals.getOrNull(armIndex)?.binding
            val icon = binding?.icon?.takeIf { it.isNotEmpty() }?.let(::iconOrLoad)
            if (icon != null) {
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = parseColor(binding.background, SteamTouchBindings.DEFAULT_BACKGROUND)
                    alpha = (255 * alphaScale).toInt().coerceIn(60, 255)
                })
            } else if (down) canvas.drawPath(path, fill)
            stroke.color = if (isSelected && armIndex == selectedArm) Color.rgb(26, 159, 255) else oldStroke
            canvas.drawPath(path, stroke)
            canvas.restore()
            if (icon != null) {
                // Upright, in the middle of the arm.
                val c = (outer + shoulder) / 2f
                val (ax, ay) = when (armIndex) { 0 -> x to y - c; 1 -> x to y + c; 2 -> x - c to y; else -> x + c to y }
                val s = half * 0.8f
                canvas.drawBitmap(tinted(binding.icon, icon, binding.foreground), null, RectF(ax - s, ay - s, ax + s, ay + s),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
        }
        stroke.color = oldStroke
    }

    private fun label(type: Int) = when (type) {
        SteamTouchConfig.A -> "A"
        SteamTouchConfig.B -> "B"
        SteamTouchConfig.X -> "X"
        SteamTouchConfig.Y -> "Y"
        SteamTouchConfig.STEAM -> "STEAM"
        SteamTouchConfig.THUMB -> "…"
        SteamTouchConfig.KEYBOARD -> "⌨"
        SteamTouchConfig.PASTE -> "Paste"
        SteamTouchConfig.SELECT -> "⧉"
        SteamTouchConfig.START -> "☰"
        SteamTouchConfig.BUMPER_LEFT -> "LB"
        SteamTouchConfig.BUMPER_RIGHT -> "RB"
        SteamTouchConfig.TRIGGER_LEFT -> "LT"
        SteamTouchConfig.TRIGGER_RIGHT -> "RT"
        SteamTouchConfig.JOYSTICK_LEFT_BUTTON -> "LS"
        SteamTouchConfig.JOYSTICK_RIGHT_BUTTON -> "RS"
        SteamTouchConfig.MACRO_1_FINGER -> "1F"
        SteamTouchConfig.MACRO_2_FINGER -> "2F"
        in SteamTouchConfig.MACRO_0..SteamTouchConfig.MACRO_0 + 7 -> "M${type - SteamTouchConfig.MACRO_0 + 1}"
        else -> "?"
    }

    // ---- The report ----

    private fun dpadBits(e: Element): Long {
        var bits = 0L
        fingers.values.filter { it.element?.type == SteamTouchConfig.DPAD }.forEach { f ->
            val dx = f.x - cx(e)
            val dy = f.y - cy(e)
            if (hypot(dx, dy) < radius(e) * 0.22f) return@forEach
            val angle = Math.toDegrees(atan2(-dy, dx).toDouble()).let { if (it < 0) it + 360 else it }
            // Eight sectors: each direction covers 67.5°, diagonals press two.
            if (angle < 67.5 || angle > 292.5) bits = bits or DPAD_RIGHT
            if (angle in 22.5..157.5) bits = bits or DPAD_UP
            if (angle in 112.5..247.5) bits = bits or DPAD_LEFT
            if (angle in 202.5..337.5) bits = bits or DPAD_DOWN
        }
        return bits
    }

    private fun publish() {
        var buttons = 0L
        val sticks = ShortArray(4)
        val pads = ShortArray(6)
        for (f in fingers.values) {
            val e = f.element ?: continue
            val r = radius(e)
            when (e.type) {
                SteamTouchConfig.DPAD -> buttons = buttons or dpadBits(e)
                SteamTouchConfig.JOYSTICK_LEFT, SteamTouchConfig.JOYSTICK_RIGHT -> {
                    val right = e.type == SteamTouchConfig.JOYSTICK_RIGHT
                    var dx = (f.x - cx(e)) / r
                    var dy = (f.y - cy(e)) / radiusY(e)
                    val d = hypot(dx, dy)
                    if (d > 1f) { dx /= d; dy /= d }
                    sticks[if (right) 2 else 0] = (dx * 32767).toInt().coerceIn(-32767, 32767).toShort()
                    sticks[if (right) 3 else 1] = (-dy * 32767).toInt().coerceIn(-32767, 32767).toShort()
                    buttons = buttons or if (right) STICK_RIGHT_TOUCHED else STICK_LEFT_TOUCHED
                }
                SteamTouchConfig.TRACKPAD_CENTER, SteamTouchConfig.TRACKPAD_LEFT, SteamTouchConfig.TRACKPAD_RIGHT -> {
                    val index = e.type - SteamTouchConfig.TRACKPAD_CENTER // centre, left, right
                    val nx = ((f.x - (cx(e) - r)) / (2 * r)).coerceIn(0f, 1f)
                    val ny = ((f.y - (cy(e) - radiusY(e))) / (2 * radiusY(e))).coerceIn(0f, 1f)
                    pads[2 * index] = (((nx * 65535).toInt().coerceIn(0, 65535)) xor 0x8000).toShort()
                    pads[2 * index + 1] = (((ny * 65535).toInt().coerceIn(0, 65535)) xor 0x7fff).toShort()
                    buttons = buttons or TRACKPAD_TOUCHED[index]
                }
                else -> BUTTON_BITS[e.type]?.let { buttons = buttons or it }
            }
        }
        device.setControls(buttons, sticks, pads)
        invalidate()
    }

    fun releaseAll() {
        fingers.clear()
        device.setControls(0L, ShortArray(4), ShortArray(6))
        invalidate()
    }

    // ---- Touch ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editing) return onEditTouch(event)
        if (menuOpen) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val x = event.x; val y = event.y
                val (frame, _) = panelFrame()
                val hot = hots.lastOrNull { it.rect.contains(x, y) }
                when {
                    hot?.onDown != null -> hot.onDown.invoke(x, y)
                    hot != null -> hot.onTap()
                    !frame.contains(x, y) -> menuOpen = false
                }
                invalidate()
            } else if (event.actionMasked == MotionEvent.ACTION_MOVE && volumeSlider.contains(volumeSlider.centerX(), event.y) &&
                abs(event.x - volumeSlider.centerX()) < volumeSlider.width() * 2) {
                setVolumeAt(event.y)
            }
            return true
        }
        val index = event.actionIndex
        lastTouchMs = android.os.SystemClock.uptimeMillis()
        if (faded) { faded = false; invalidate() }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(index)
                val y = event.getY(index)
                val e = hit(live(), x, y) ?: return event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
                fingers[event.getPointerId(index)] = Finger(e, x, y)
                if (feedback) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                requestUnbufferedDispatch(event)
                publish()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val f = fingers[event.getPointerId(i)] ?: continue
                    f.x = event.getX(i)
                    f.y = event.getY(i)
                    // A finger on a button can slide onto another, as on Steam Link.
                    val e = f.element ?: continue
                    if (BUTTON_BITS.containsKey(e.type)) {
                        val over = hit(live(), f.x, f.y)
                        if (over != null && over !== e && BUTTON_BITS.containsKey(over.type)) {
                            fingers[event.getPointerId(i)] = Finger(over, f.x, f.y)
                            if (feedback) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                    }
                }
                publish()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = event.getPointerId(index)
                val f = fingers.remove(id)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) fingers.clear()
                val e = f?.element
                if (event.actionMasked != MotionEvent.ACTION_CANCEL && e != null && reach(e, event.getX(index), event.getY(index)) <= 1.3f) {
                    when (e.type) {
                        SteamTouchConfig.THUMB -> openMenu()
                        SteamTouchConfig.KEYBOARD -> onKeyboard()
                        SteamTouchConfig.PASTE -> onPaste()
                    }
                }
                publish()
                return true
            }
        }
        return true
    }

    // ---- Editing ----

    val isEditing get() = editing

    /** Moves, resizes and hides controls, action set by action set; Save writes them to the
     *  game's touch config. */
    fun startEditing() {
        releaseAll()
        editing = true
        editSets.clear()
        selected = -1
        pendingBindings.clear()
        pendingColor = null
        loadEditSet(SteamTouchConfig.layoutIdOf(actionSet))
        trayOpen = true
        invalidate()
    }

    // The action set being edited (its layout id), and the edits of every set visited.
    private var editSet = 1
    private class EditState(val elements: MutableList<Element>, val hidden: MutableList<Element>)
    private val editSets = HashMap<Int, EditState>()

    private fun loadEditSet(id: Int) {
        editSet = id
        val state = editSets.getOrPut(id) {
            // The device's action set numbering is the layout id.
            val available = config.availableFor(id, emptyList())
            val shown = SteamTouchConfig.elementsFor(config, id, emptyList())
            val els = available.mapNotNull { type ->
                shown.firstOrNull { it.type == type } ?: if (config.layouts?.forActionSet(id)?.elements?.any { it.type == type && !it.visible } == true) null
                else SteamTouchConfig.defaultElement(type, available)
            }.toMutableList()
            shown.filter { it.type in SteamTouchConfig.OPTIONAL }.forEach { els += it }
            val hid = available.filter { type -> els.none { it.type == type } }
                .mapNotNull { SteamTouchConfig.defaultElement(it, available)?.copy(visible = false) }.toMutableList()
            EditState(els.sortedBy { it.type }.toMutableList(), hid)
        }
        editElements = state.elements
        hidden = state.hidden
        selected = -1
        rebuildVisualsFor(id)
        invalidate()
    }

    private fun editSetIndex(): Int = SteamTouchConfig.actionSets(config).indexOfFirst { it.first == editSet }

    private fun stepEditSet(by: Int) {
        val sets = SteamTouchConfig.actionSets(config)
        if (sets.size < 2) return
        val i = (editSetIndex().coerceAtLeast(0) + by + sets.size) % sets.size
        loadEditSet(sets[i].first)
    }

    private fun rebuildVisualsFor(layoutId: Int) {
        val saved = actionSet
        actionSet = layoutId
        rebuildVisuals()
        actionSet = saved
    }

    private fun stopEditing() {
        editing = false
        trayOpen = false
        trayDrag = null
        selected = -1
        editSets.clear()
        pendingBindings.clear()
        pendingColor = null
        rebuild()
    }

    private var pinchSpan = 0f to 0f
    private var pinchScales = 1f to 1f

    private fun onEditTouch(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val x = event.getX(index)
        val y = event.getY(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hots.lastOrNull { it.rect.contains(x, y) }?.let { hot ->
                    if (hot.onDown != null) hot.onDown.invoke(x, y) else hot.onTap()
                    invalidate()
                    return true
                }
                if (trayOpen) return true
                val e = hit(editElements, x, y)
                selected = if (e != null) editElements.indexOf(e) else -1
                if (e != null) dragOffset = (x - cx(e)) to (y - cy(e))
                // The D-pad's icons are per direction: a tap picks the one under the finger.
                if (e?.type == SteamTouchConfig.DPAD) {
                    val dx = x - cx(e)
                    val dy = y - cy(e)
                    selectedArm = if (abs(dx) > abs(dy)) (if (dx < 0) 2 else 3) else (if (dy < 0) 0 else 1)
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (selected >= 0 && event.pointerCount == 2 && trayDrag == null) {
                pinchSpan = abs(event.getX(0) - event.getX(1)) to abs(event.getY(0) - event.getY(1))
                pinchStart = hypot(pinchSpan.first, pinchSpan.second)
                pinchScales = editElements[selected].xScale to editElements[selected].yScale
            }
            MotionEvent.ACTION_MOVE -> if (selected >= 0 && !trayOpen) {
                val e = editElements[selected]
                if (event.pointerCount >= 2 && pinchStart > 0f) {
                    // A pinch along one axis stretches that axis; a diagonal one scales both.
                    val sx = abs(event.getX(0) - event.getX(1))
                    val sy = abs(event.getY(0) - event.getY(1))
                    val min = 40f * unit()
                    val fx = if (pinchSpan.first > min) sx / pinchSpan.first else hypot(sx, sy) / pinchStart
                    val fy = if (pinchSpan.second > min) sy / pinchSpan.second else hypot(sx, sy) / pinchStart
                    editElements[selected] = e.copy(xScale = (pinchScales.first * fx).coerceIn(0.4f, 3f),
                        yScale = (pinchScales.second * fy).coerceIn(0.4f, 3f))
                } else if (event.pointerCount == 1) {
                    val nx = ((event.getX(0) - dragOffset.first) / width).coerceIn(0f, 1f)
                    val ny = ((event.getY(0) - dragOffset.second) / height).coerceIn(0f, 1f)
                    editElements[selected] = e.copy(x = nx, y = ny)
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> pinchStart = 0f
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pinchStart = 0f
                // Dropped back on the menu button: back into the tray, as on Steam Link.
                val e = editElements.getOrNull(selected)
                if (e != null && editMenuButton().contains(cx(e), cy(e)) && e.type != SteamTouchConfig.THUMB) {
                    hidden += editElements.removeAt(selected).copy(visible = false)
                    selected = -1
                }
                trayDrag = null
                invalidate()
            }
        }
        return true
    }

    private fun reshape(which: Int) {
        val e = editElements.getOrNull(selected) ?: return
        val f = if (which % 2 == 0) 0.88f else 1.14f
        editElements[selected] = if (which < 2) e.copy(xScale = (e.xScale * f).coerceIn(0.4f, 3f))
        else e.copy(yScale = (e.yScale * f).coerceIn(0.4f, 3f))
        invalidate()
    }

    private fun resetEditSet() {
        val available = config.availableFor(editSet, emptyList())
        editElements.clear()
        editElements += available.mapNotNull { SteamTouchConfig.defaultElement(it, available) }.sortedBy { it.type }
        hidden.clear()
        selected = -1
        invalidate()
    }

    private fun controlName(type: Int) = when (type) {
        SteamTouchConfig.DPAD -> "D-pad"
        SteamTouchConfig.JOYSTICK_LEFT -> "Left stick"
        SteamTouchConfig.JOYSTICK_RIGHT -> "Right stick"
        SteamTouchConfig.TRACKPAD_LEFT -> "Left trackpad"
        SteamTouchConfig.TRACKPAD_RIGHT -> "Right trackpad"
        SteamTouchConfig.TRACKPAD_CENTER -> "Trackpad"
        SteamTouchConfig.STEAM -> "Steam"
        SteamTouchConfig.THUMB -> "Menu"
        SteamTouchConfig.KEYBOARD -> "Keyboard"
        else -> label(type)
    }

    /** Reset, copy and paste a layout, the layout's colour. */
    private fun moreMenu() {
        val host = parent as? android.view.ViewGroup ?: return
        val density = resources.displayMetrics.density
        val list = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0) }
        var close: () -> Unit = {}
        val copied = prefs.getString("copiedLayout", null)
        fun item(name: String, enabled: Boolean = true, action: () -> Unit) = list.addView(android.widget.Button(context).apply {
            text = name
            isAllCaps = false
            isEnabled = enabled
            setOnClickListener { close(); action(); invalidate() }
        })
        item("Colour and opacity…") { pickColor() }
        item("Copy this layout") {
            prefs.edit().putString("copiedLayout", (editElements + hidden).joinToString(";") {
                "${it.type},${it.visible},${it.x},${it.y},${it.xScale},${it.yScale}"
            }).apply()
            Toast.makeText(context, "Layout copied; paste it into any game's touch layout", Toast.LENGTH_SHORT).show()
        }
        item("Paste copied layout", copied != null) { pasteLayout(copied ?: "") }
        item("Reset to Steam's default") { resetEditSet() }
        close = SteamTouchIconPicker.showPanel(host, "Layout", list, listOf("Close" to {}))
    }

    /** A copied layout onto this action set: each control the set has takes the copied place. */
    private fun pasteLayout(copied: String) {
        val placed = copied.split(';').mapNotNull { row ->
            val f = row.split(',')
            if (f.size < 6) null else Element(f[0].toInt(), f[1].toBoolean(), f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), f[5].toFloat())
        }.associateBy { it.type }
        val all = (editElements + hidden).map { e -> placed[e.type]?.let { p -> e.copy(visible = p.visible, x = p.x, y = p.y, xScale = p.xScale, yScale = p.yScale) } ?: e }
        editElements.clear()
        editElements += all.filter { it.visible }
        hidden.clear()
        hidden += all.filter { !it.visible }
        selected = -1
    }

    // ---- Steam Link's menu ----

    /** The menu button: Steam Link's touch menu. */
    private fun openMenu() {
        releaseAll()
        menuOpen = true
        invalidate()
    }

    // ---- Steam Link's panels: the touch menu and the layout tray ----
    //
    // Drawn here, on this view, to Steam Link's measurements (a 624x400 panel in its reference
    // pixels, navy with a blue top rule, blue tiles), so that controls can be dragged out of the tray
    // onto the screen as on Steam Link.

    private var menuOpen = false
    private var trayOpen = false

    /** A hit area on a panel and what it does. */
    private class Hot(val rect: RectF, val onTap: () -> Unit, val onDown: ((Float, Float) -> Unit)? = null)
    private val hots = mutableListOf<Hot>()
    private var trayDrag: Element? = null

    // DroidDeck's own palette (ui/Theme.kt, the default theme).
    private val surface = Color.rgb(18, 20, 23)
    private val surfaceVariant = Color.rgb(26, 29, 34)
    private val line = Color.rgb(38, 42, 49)
    private val line2 = Color.rgb(52, 58, 67)
    private val onSurface = Color.rgb(242, 244, 247)
    private val muted = Color.rgb(154, 163, 175)
    private val accent = Color.rgb(26, 159, 255)
    private val onAccent = Color.rgb(3, 17, 31)

    /** The panel's rectangle and its scale (screen pixels per reference pixel). */
    private fun panelFrame(): Pair<RectF, Float> {
        val s = min(width * 0.8f / 624f, height * 0.78f / 400f)
        val w = 624f * s
        val h = 400f * s
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return RectF(left, top, left + w, top + h) to s
    }

    private fun Canvas.panel(frame: RectF, s: Float) {
        drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.argb(150, 0, 0, 0) })
        val radius = 18f * s
        drawRoundRect(frame, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface })
        drawRoundRect(frame, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line; style = Paint.Style.STROKE; strokeWidth = 1.5f * s })
    }

    private fun ref(frame: RectF, s: Float, l: Float, t: Float, r: Float, b: Float) =
        RectF(frame.left + l * s, frame.top + t * s, frame.left + r * s, frame.top + b * s)

    private fun text(canvas: Canvas, str: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE, bold: Boolean = true,
                      align: Paint.Align = Paint.Align.CENTER) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; textAlign = align
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        canvas.drawText(str, x, y - (p.descent() + p.ascent()) / 2, p)
    }

    /** A button in DroidDeck's style: the accent filled for the main action, a quiet outlined
     *  surface otherwise. */
    private fun blueButton(canvas: Canvas, r: RectF, text: String, s: Float, enabled: Boolean = true, primary: Boolean = false, onTap: () -> Unit) {
        val radius = 12f * s
        if (primary && enabled) {
            canvas.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
        } else {
            canvas.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surfaceVariant })
            canvas.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line2; style = Paint.Style.STROKE; strokeWidth = 1.2f * s })
        }
        val ink = when { !enabled -> Color.argb(110, 242, 244, 247); primary -> onAccent; else -> onSurface }
        text(canvas, text, r.centerX(), r.centerY(), 14f * s, ink)
        if (enabled) hots += Hot(r, onTap)
    }

    /** A toggle: quiet when off, accent-tinted with an accent glyph when on. */
    private fun iconButton(canvas: Canvas, r: RectF, active: Boolean, onTap: () -> Unit, glyph: (Canvas, RectF, Int) -> Unit) {
        val radius = 10f * (r.height() / 40f)
        canvas.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active) Color.argb(46, 26, 159, 255) else surfaceVariant
        })
        canvas.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active) Color.argb(160, 26, 159, 255) else line; style = Paint.Style.STROKE; strokeWidth = 1.2f
        })
        glyph(canvas, r, if (active) accent else muted)
        hots += Hot(r, onTap)
    }

    private fun closeButton(canvas: Canvas, frame: RectF, s: Float, onTap: () -> Unit) {
        val r = ref(frame, s, 542f, 18f, 579f, 55f)
        canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surfaceVariant })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; strokeWidth = 2f * s; strokeCap = Paint.Cap.ROUND }
        val i = r.width() * 0.33f
        canvas.drawLine(r.left + i, r.top + i, r.right - i, r.bottom - i, p)
        canvas.drawLine(r.right - i, r.top + i, r.left + i, r.bottom - i, p)
        hots += Hot(r, onTap)
    }

    private fun dotsButton(canvas: Canvas, frame: RectF, s: Float, onTap: () -> Unit) {
        val r = ref(frame, s, 45f, 18f, 82f, 55f)
        canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surfaceVariant })
        drawDots(canvas, r.centerX(), r.centerY() - r.height() * 0.22f, r.width() * 0.55f, onSurface)
        hots += Hot(r, onTap)
    }

    private val gameNames = HashMap<Int, String>()

    private fun gameName(): String = when (appId) {
        BIG_PICTURE -> "Steam"
        else -> gameNames[appId] ?: run {
            val app = appId
            gameNames[app] = "app $app"
            loader.execute {
                val name = SteamTouchConfig.gameName(context, app) ?: return@execute
                handler.post { gameNames[app] = name; invalidate() }
            }
            gameNames[app]!!
        }
    }

    // -- The touch menu (Steam Link: CVirtualController's choose-mode screen) --

    private fun drawMenu(canvas: Canvas) {
        val (f, s) = panelFrame()
        canvas.panel(f, s)
        // Header: power (DroidDeck's own menu: stop, settings), the Steam mark, what is running, close.
        iconButton(canvas, ref(f, s, 494f, 18f, 531f, 55f), false, { menuOpen = false; onMenu() }) { c, r, col -> drawPower(c, r, col) }
        drawMark(canvas, f.left + 104f * s, f.top + 37f * s, 13f * s)
        text(canvas, "Touch controls", f.left + 124f * s, f.top + 30f * s, 14f * s, onSurface, align = Paint.Align.LEFT)
        text(canvas, gameName(), f.left + 124f * s, f.top + 46f * s, 11f * s, muted, bold = false, align = Paint.Align.LEFT)
        closeButton(canvas, f, s) { menuOpen = false }
        val controller = options.inputMode != SteamTouchConfig.INPUT_MOUSE
        val mouse = options.inputMode != SteamTouchConfig.INPUT_CONTROLLER
        fun setModes(c: Boolean, m: Boolean) = setOptions(options.copy(inputMode = when {
            c && m -> SteamTouchConfig.INPUT_BOTH
            m -> SteamTouchConfig.INPUT_MOUSE
            else -> SteamTouchConfig.INPUT_CONTROLLER
        }))
        tile(canvas, ref(f, s, 45f, 81f, 249f, 271f), s, "Touch Controller", controller, { setModes(!controller, mouse || controller) }) { c, r, col ->
            drawPhonePad(c, r, col)
        }
        tile(canvas, ref(f, s, 271f, 81f, 475f, 271f), s, "Mouse", mouse, { setModes(controller || mouse, !mouse) }) { c, r, col ->
            drawMouse(c, r, col, crossed = false)
        }
        // Under the controller: gyroscope, fade, vibrate on touch.
        iconButton(canvas, ref(f, s, 45f, 278f, 91f, 318f), gyroOn, {
            gyroOn = !gyroOn; device.motionEnabled = gyroOn; prefs.edit().putBoolean("gyro", gyroOn).apply()
        }) { c, r, col -> drawGyro(c, r, col) }
        iconButton(canvas, ref(f, s, 98f, 278f, 144f, 318f), autoFade, {
            autoFade = !autoFade; prefs.edit().putBoolean("autoFade", autoFade).apply()
        }) { c, r, col -> drawFadeGlyph(c, r, col) }
        iconButton(canvas, ref(f, s, 151f, 278f, 197f, 318f), shakeToHide, {
            shakeToHide = !shakeToHide; prefs.edit().putBoolean("shake", shakeToHide).apply(); updateShake()
        }) { c, r, col -> drawShake(c, r, col) }
        iconButton(canvas, ref(f, s, 204f, 278f, 249f, 318f), feedback, {
            feedback = !feedback; prefs.edit().putBoolean("feedback", feedback).apply()
        }) { c, r, col -> drawHaptic(c, r, col) }
        // Under the mouse: direct touch, trackpad, trackpad speed.
        val relative = options.mouseMode == SteamTouchConfig.MOUSE_RELATIVE
        iconButton(canvas, ref(f, s, 271f, 278f, 333f, 318f), mouse && !relative, {
            setOptions(options.copy(mouseMode = SteamTouchConfig.MOUSE_ABSOLUTE))
        }) { c, r, col -> drawTapGlyph(c, r, col) }
        iconButton(canvas, ref(f, s, 341f, 278f, 405f, 318f), mouse && relative, {
            setOptions(options.copy(mouseMode = SteamTouchConfig.MOUSE_RELATIVE))
        }) { c, r, col -> drawTrackpadGlyph(c, r, col) }
        val speeds = listOf(0.5f, 1f, 1.75f)
        val speed = speeds.indexOfFirst { abs(it - options.trackpadSensitivity) < 0.2f }.coerceAtLeast(1)
        iconButton(canvas, ref(f, s, 412f, 278f, 475f, 318f), mouse && relative, {
            setOptions(options.copy(trackpadSensitivity = speeds[(speed + 1) % speeds.size]))
        }) { c, r, col -> text(c, listOf("Slow", "Normal", "Fast")[speed], r.centerX(), r.centerY(), r.height() * 0.32f, col) }
        drawVolume(canvas, f, s)
        blueButton(canvas, ref(f, s, 45f, 329f, 249f, 372f), "Edit layout", s, primary = true) { menuOpen = false; startEditing() }
        blueButton(canvas, ref(f, s, 271f, 329f, 475f, 372f), "DroidDeck menu", s) { menuOpen = false; onMenu() }
    }

    private val audio by lazy { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    private var volumeSlider = RectF()

    /** Steam Link's audio column: the speaker (mute), its volume slider - the device's media
     *  volume here - and the microphone, which DroidDeck does not stream, shown off. */
    private fun drawVolume(canvas: Canvas, f: RectF, s: Float) {
        val stream = android.media.AudioManager.STREAM_MUSIC
        val maxVol = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val vol = audio.getStreamVolume(stream)
        iconButton(canvas, ref(f, s, 487f, 81f, 529f, 123f), vol > 0, {
            audio.adjustStreamVolume(stream, if (vol > 0) android.media.AudioManager.ADJUST_MUTE else android.media.AudioManager.ADJUST_UNMUTE, 0)
        }) { c, r, col -> drawSpeaker(c, r, col) }
        iconButton(canvas, ref(f, s, 537f, 81f, 579f, 123f), false, {}) { c, r, col -> drawMicOff(c, r, col) }
        val track = ref(f, s, 489f, 133f, 527f, 372f)
        volumeSlider = track
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line2; style = Paint.Style.STROKE; strokeWidth = 1.5f * s }
        canvas.drawRoundRect(track, track.width() / 2, track.width() / 2, stroke)
        val inner = RectF(track.left + 5f * s, track.top + 5f * s, track.right - 5f * s, track.bottom - 5f * s)
        val level = vol.toFloat() / maxVol
        val knobY = inner.bottom - (inner.height() - inner.width()) * level - inner.width() / 2
        canvas.drawRoundRect(RectF(inner.left, knobY, inner.right, inner.bottom), inner.width() / 2, inner.width() / 2,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
        canvas.drawCircle(inner.centerX(), knobY, inner.width() / 2 * 1.05f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onSurface })
        // The mic's slider, dimmed.
        val micTrack = ref(f, s, 539f, 133f, 577f, 372f)
        canvas.drawRoundRect(micTrack, micTrack.width() / 2, micTrack.width() / 2, Paint(stroke).apply { color = Color.argb(60, 255, 255, 255) })
        hots += Hot(track, {}) { _, y -> setVolumeAt(y) }
    }

    private fun setVolumeAt(y: Float) {
        val stream = android.media.AudioManager.STREAM_MUSIC
        val maxVol = audio.getStreamMaxVolume(stream)
        val t = volumeSlider
        val level = ((t.bottom - y) / t.height()).coerceIn(0f, 1f)
        audio.setStreamVolume(stream, Math.round(level * maxVol), 0)
        invalidate()
    }

    private fun drawSpeaker(c: Canvas, r: RectF, col: Int) {
        val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
        val x = r.centerX() - r.width() * 0.12f; val y = r.centerY(); val u = r.height() * 0.1f
        c.drawPath(Path().apply {
            moveTo(x - u * 2, y - u); lineTo(x - u * 0.6f, y - u); lineTo(x + u, y - u * 2.4f); lineTo(x + u, y + u * 2.4f)
            lineTo(x - u * 0.6f, y + u); lineTo(x - u * 2, y + u); close()
        }, fillP)
        val p = glyphPaint(col, u * 0.6f)
        for (k in 1..2) c.drawArc(RectF(x + u - k * u * 1.6f, y - k * u * 1.6f, x + u + k * u * 1.6f, y + k * u * 1.6f), -45f, 90f, false, p)
    }

    private fun drawMicOff(c: Canvas, r: RectF, col: Int) {
        val p = glyphPaint(col, r.height() * 0.06f)
        val u = r.height() * 0.1f; val x = r.centerX(); val y = r.centerY()
        c.drawRoundRect(RectF(x - u, y - u * 2.6f, x + u, y + u * 0.6f), u, u, p)
        c.drawArc(RectF(x - u * 1.9f, y - u * 1.6f, x + u * 1.9f, y + u * 1.6f), 0f, 180f, false, p)
        c.drawLine(x, y + u * 1.6f, x, y + u * 2.4f, p)
        c.drawLine(x - u * 2.4f, y - u * 2.4f, x + u * 2.4f, y + u * 2.4f, glyphPaint(Color.rgb(200, 40, 40), r.height() * 0.07f))
    }

    /** A mode card: tap to turn it on or off; the accent marks it on. */
    private fun tile(canvas: Canvas, r: RectF, s: Float, title: String, on: Boolean, toggle: () -> Unit, art: (Canvas, RectF, Int) -> Unit) {
        val radius = 14f * s
        canvas.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surfaceVariant })
        canvas.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (on) accent else line; style = Paint.Style.STROKE; strokeWidth = (if (on) 2f else 1.2f) * s
        })
        text(canvas, title, r.left + 16f * s, r.top + 22f * s, 14f * s, onSurface, align = Paint.Align.LEFT)
        // A switch, top right.
        val sw = RectF(r.right - 50f * s, r.top + 12f * s, r.right - 14f * s, r.top + 32f * s)
        canvas.drawRoundRect(sw, sw.height() / 2, sw.height() / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (on) accent else line2 })
        canvas.drawCircle(if (on) sw.right - sw.height() / 2 else sw.left + sw.height() / 2, sw.centerY(), sw.height() * 0.38f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (on) onAccent else muted })
        val artRect = RectF(r.centerX() - 58f * s, r.top + 62f * s, r.centerX() + 58f * s, r.top + 140f * s)
        art(canvas, artRect, if (on) onSurface else muted)
        text(canvas, if (on) "On" else "Off", r.left + 16f * s, r.bottom - 18f * s, 12f * s, if (on) accent else muted, bold = false, align = Paint.Align.LEFT)
        hots += Hot(r, toggle)
    }

    // -- The layout tray (Steam Link: CVirtualController's tray) --

    private fun drawTray(canvas: Canvas) {
        val (f, s) = panelFrame()
        canvas.panel(f, s)
        dotsButton(canvas, f, s) { moreMenu() }
        closeButton(canvas, f, s) { save() }
        val sets = SteamTouchConfig.actionSets(config)
        val setName = sets.firstOrNull { it.first == editSet }?.second
        val title = if (sets.size > 1 && setName != null) "${gameName()} · $setName" else "${gameName()} ${config.title ?: "Layout"}"
        text(canvas, title, f.centerX(), f.top + 30f * s, 14f * s, onSurface)
        text(canvas, "Drag controls onto the screen", f.centerX(), f.top + 47f * s, 11f * s, muted, bold = false)
        if (sets.size > 1) {
            arrowPill(canvas, ref(f, s, 120f, 26f, 150f, 44f), -1) { stepEditSet(-1) }
            arrowPill(canvas, ref(f, s, 474f, 26f, 504f, 44f), 1) { stepEditSet(1) }
        }
        val available = config.availableFor(editSet, emptyList()) + SteamTouchConfig.OPTIONAL
        fun item(type: Int, cx: Float, cy: Float, r: Float) {
            val e = Element(type, true, (f.left + cx * s) / width, (f.top + cy * s) / height, r * s / (baseRadius(type) * unit()), r * s / (baseRadius(type) * unit()))
            val bound = type in available
            val saved = alphaScale
            alphaScale = if (bound) 1f else 0.3f
            canvas.save()
            draw(canvas, e, false)
            canvas.restore()
            alphaScale = saved
            if (bound) {
                val box = RectF(f.left + (cx - r) * s, f.top + (cy - r) * s, f.left + (cx + r) * s, f.top + (cy + r) * s)
                hots += Hot(box, {}) { x, y -> beginTrayDrag(type, x, y) }
            }
        }
        item(SteamTouchConfig.BUMPER_LEFT, 98f, 108f, 14f)
        item(SteamTouchConfig.TRIGGER_LEFT, 140f, 98f, 14f)
        item(SteamTouchConfig.SELECT, 228f, 108f, 13f)
        item(SteamTouchConfig.STEAM, 274f, 108f, 19f)
        item(SteamTouchConfig.START, 321f, 108f, 13f)
        item(SteamTouchConfig.TRIGGER_RIGHT, 408f, 98f, 14f)
        item(SteamTouchConfig.BUMPER_RIGHT, 449f, 108f, 14f)
        item(SteamTouchConfig.DPAD, 139f, 180f, 46f)
        item(SteamTouchConfig.TRACKPAD_CENTER, 274f, 179f, 59f)
        item(SteamTouchConfig.Y, 409f, 148f, 15f)
        item(SteamTouchConfig.X, 378f, 179f, 15f)
        item(SteamTouchConfig.B, 440f, 179f, 15f)
        item(SteamTouchConfig.A, 409f, 210f, 15f)
        item(SteamTouchConfig.JOYSTICK_LEFT, 170f, 272f, 37f)
        item(SteamTouchConfig.JOYSTICK_LEFT_BUTTON, 115f, 293f, 14f)
        item(SteamTouchConfig.KEYBOARD, 257f, 286f, 14f)
        item(SteamTouchConfig.PASTE, 292f, 286f, 14f)
        item(SteamTouchConfig.JOYSTICK_RIGHT, 377f, 272f, 37f)
        item(SteamTouchConfig.JOYSTICK_RIGHT_BUTTON, 434f, 293f, 14f)
        item(SteamTouchConfig.MACRO_1_FINGER, 519f, 184f, 14f)
        item(SteamTouchConfig.MACRO_2_FINGER, 562f, 184f, 14f)
        for (i in 0 until 4) {
            item(SteamTouchConfig.MACRO_0 + i, 519f, 228f + 43f * i, 14f)
            item(SteamTouchConfig.MACRO_0 + 4 + i, 562f, 228f + 43f * i, 14f)
        }
        // The colour wheel: the layout's colour and opacity.
        val wheel = ref(f, s, 511f, 91f, 573f, 153f)
        canvas.drawCircle(wheel.centerX(), wheel.centerY(), wheel.width() / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.SweepGradient(wheel.centerX(), wheel.centerY(),
                intArrayOf(Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED), null)
        })
        canvas.drawCircle(wheel.centerX(), wheel.centerY(), wheel.width() / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(wheel.centerX(), wheel.centerY(), wheel.width() / 2f, Color.WHITE, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
        })
        hots += Hot(wheel, { pickColor() })
        blueButton(canvas, ref(f, s, 45f, 329f, 232f, 372f), "Arrange", s, primary = true) { trayOpen = false }
        blueButton(canvas, ref(f, s, 254f, 329f, 441f, 372f), "Discard", s) { stopEditing() }
    }

    private fun arrowPill(canvas: Canvas, r: RectF, dir: Int, onTap: () -> Unit) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = r.height() * 0.1f }
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        val t = r.height() * 0.22f
        val x = r.centerX(); val y = r.centerY()
        canvas.drawPath(Path().apply { moveTo(x + dir * t, y); lineTo(x - dir * t * 0.75f, y - t); lineTo(x - dir * t * 0.75f, y + t); close() },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        hots += Hot(RectF(r.left - r.height() * 0.4f, r.top - r.height() * 0.6f, r.right + r.height() * 0.4f, r.bottom + r.height() * 0.6f), onTap)
    }

    /** A control taken out of the tray: on screen under the finger, following it until it lifts. */
    private fun beginTrayDrag(type: Int, x: Float, y: Float) {
        val available = config.availableFor(editSet, emptyList())
        val existing = editElements.indexOfFirst { it.type == type }
        val base = if (existing >= 0) editElements.removeAt(existing) else
            (hidden.firstOrNull { it.type == type } ?: SteamTouchConfig.defaultElement(type, available) ?: Element(type, true, 0.5f, 0.5f))
        hidden.removeAll { it.type == type }
        val placed = base.copy(visible = true, x = x / width, y = y / height)
        editElements += placed
        selected = editElements.size - 1
        dragOffset = 0f to 0f
        trayDrag = placed
        trayOpen = false
        invalidate()
    }

    // -- While editing: Steam Link's menu button, and a small bar for the selected control --

    private fun editMenuButton(): RectF {
        val r = 37f * unit()
        val x = 75f / 1280f * width
        val y = 75f / 720f * height
        return RectF(x - r, y - r, x + r, y + r)
    }

    private val contextLabels = listOf("Icon", "W−", "W+", "H−", "H+", "Hide")

    private fun contextRects(): List<RectF> {
        val e = editElements.getOrNull(selected) ?: return emptyList()
        val u = max(unit(), 0.6f)
        val w = 58f * u
        val h = 36f * u
        val gap = 6f * u
        val total = contextLabels.size * w + (contextLabels.size - 1) * gap
        val left = (cx(e) - total / 2).coerceIn(4f, width - total - 4f)
        val above = cy(e) - radiusY(e) - h - 14f * u
        val top = if (above > 4f) above else cy(e) + radiusY(e) + 14f * u
        return contextLabels.indices.map { i -> RectF(left + i * (w + gap), top, left + i * (w + gap) + w, top + h) }
    }

    private fun drawEditChrome(canvas: Canvas) {
        hots.clear()
        val m = editMenuButton()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = lineWidth() }
        canvas.drawRoundRect(m, m.width() * 0.15f, m.width() * 0.15f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 18, 20, 23) })
        canvas.drawRoundRect(m, m.width() * 0.15f, m.width() * 0.15f, p)
        drawDots(canvas, m.centerX(), m.centerY() - m.height() * 0.1f, m.width() * 0.5f, Color.WHITE)
        hots += Hot(m, { trayOpen = true })
        if (saving) text(canvas, "Saving…", width / 2f, height * 0.08f, 22f * max(unit(), 0.6f))
        contextRects().forEachIndexed { i, r ->
            canvas.drawRoundRect(r, r.height() * 0.3f, r.height() * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface })
            canvas.drawRoundRect(r, r.height() * 0.3f, r.height() * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line2; style = Paint.Style.STROKE; strokeWidth = 1.2f })
            text(canvas, contextLabels[i], r.centerX(), r.centerY(), r.height() * 0.4f, onSurface, bold = false)
            hots += Hot(r, { contextTool(i) })
        }
    }

    private fun contextTool(i: Int) {
        if (selected < 0) return
        when (i) {
            0 -> pickIcon(editElements[selected])
            in 1..4 -> reshape(i - 1)
            5 -> { hidden += editElements.removeAt(selected).copy(visible = false); selected = -1 }
        }
        invalidate()
    }

    // -- Glyphs for the menu's buttons --

    private fun glyphPaint(col: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col; style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private fun drawPower(c: Canvas, r: RectF, col: Int) {
        val p = glyphPaint(col, r.width() * 0.08f)
        val rad = r.width() * 0.26f
        c.drawArc(RectF(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad), -60f, 300f, false, p)
        c.drawLine(r.centerX(), r.centerY() - rad * 1.2f, r.centerX(), r.centerY() - rad * 0.2f, p)
    }

    private fun drawPhonePad(c: Canvas, r: RectF, col: Int) {
        val p = glyphPaint(col, r.height() * 0.07f)
        c.drawRoundRect(r, r.height() * 0.15f, r.height() * 0.15f, p)
        val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
        val cx = r.left + r.width() * 0.3f; val cy = r.centerY() + r.height() * 0.05f; val a = r.height() * 0.13f
        c.drawRect(cx - a * 1.5f, cy - a / 2, cx + a * 1.5f, cy + a / 2, fillP)
        c.drawRect(cx - a / 2, cy - a * 1.5f, cx + a / 2, cy + a * 1.5f, fillP)
        val bx = r.left + r.width() * 0.68f
        for ((dx, dy) in listOf(0f to -1f, -1f to 0f, 1f to 0f, 0f to 1f)) c.drawCircle(bx + dx * a * 1.2f, cy + dy * a * 1.2f, a * 0.55f, fillP)
        c.drawCircle(r.left + r.width() * 0.06f, r.centerY(), a * 0.25f, fillP)
        c.drawLine(r.right - r.width() * 0.06f, r.centerY() - a, r.right - r.width() * 0.06f, r.centerY() + a, p)
    }

    private fun drawMouse(c: Canvas, r: RectF, col: Int, crossed: Boolean) {
        val h = r.height() * 0.9f; val w = h * 0.6f
        val body = RectF(r.centerX() - w / 2, r.centerY() - h / 2 + h * 0.12f, r.centerX() + w / 2, r.centerY() + h / 2)
        c.drawRoundRect(body, w / 2, w / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
        val line = glyphPaint(surfaceVariant, w * 0.05f)
        c.drawLine(body.centerX(), body.top, body.centerX(), body.top + h * 0.3f, line)
        c.drawLine(body.left, body.top + h * 0.3f, body.right, body.top + h * 0.3f, line)
        c.drawLine(body.centerX(), body.top, body.centerX(), r.top, glyphPaint(col, w * 0.05f))
        if (crossed) c.drawLine(r.centerX() - h * 0.45f, r.top + h * 0.2f, r.centerX() + h * 0.45f, r.bottom - h * 0.05f, glyphPaint(Color.rgb(200, 40, 40), w * 0.12f))
    }

    private fun drawGyro(c: Canvas, r: RectF, col: Int) {
        val p = glyphPaint(col, r.height() * 0.06f)
        val rad = r.height() * 0.3f
        c.drawCircle(r.centerX(), r.centerY(), rad, p)
        c.drawOval(RectF(r.centerX() - rad * 1.4f, r.centerY() - rad * 0.45f, r.centerX() + rad * 1.4f, r.centerY() + rad * 0.45f), p)
        c.drawCircle(r.centerX(), r.centerY(), rad * 0.18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
    }

    private fun drawFadeGlyph(c: Canvas, r: RectF, col: Int) {
        val p = glyphPaint(col, r.height() * 0.06f)
        val w = r.height() * 0.85f; val h = r.height() * 0.5f
        c.drawRect(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2, p)
        val q = glyphPaint(col, r.height() * 0.04f).apply { pathEffect = android.graphics.DashPathEffect(floatArrayOf(r.height() * 0.06f, r.height() * 0.05f), 0f) }
        c.drawRect(r.centerX() - w * 0.32f, r.centerY() - h * 0.25f, r.centerX() + w * 0.32f, r.centerY() + h * 0.25f, q)
    }

    private fun drawShake(c: Canvas, r: RectF, col: Int) {
        val p = glyphPaint(col, r.height() * 0.06f)
        val w = r.height() * 0.3f; val h = r.height() * 0.5f
        c.save(); c.rotate(-15f, r.centerX(), r.centerY())
        c.drawRoundRect(RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2), w * 0.2f, w * 0.2f, p)
        c.restore()
        for (side in listOf(-1f, 1f)) c.drawArc(RectF(r.centerX() - h * 0.75f, r.centerY() - h * 0.55f, r.centerX() + h * 0.75f, r.centerY() + h * 0.55f),
            if (side < 0) 150f else -30f, 60f, false, p)
    }

    private fun drawHaptic(c: Canvas, r: RectF, col: Int) {
        val p = glyphPaint(col, r.height() * 0.06f)
        val w = r.height() * 0.28f; val h = r.height() * 0.5f
        c.drawRoundRect(RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2), w * 0.2f, w * 0.2f, p)
        for (side in listOf(-1f, 1f)) for (k in 1..2) {
            val x = r.centerX() + side * (w / 2 + k * r.height() * 0.09f)
            c.drawLine(x, r.centerY() - h * 0.25f * k / 2, x, r.centerY() + h * 0.25f * k / 2, p)
        }
    }

    private fun drawTapGlyph(c: Canvas, r: RectF, col: Int) {
        val p = glyphPaint(col, r.height() * 0.06f)
        val rad = r.height() * 0.12f
        c.drawCircle(r.centerX(), r.centerY() - rad, rad, p)
        c.drawCircle(r.centerX(), r.centerY() - rad, rad * 2f, glyphPaint(col, r.height() * 0.03f))
        c.drawLine(r.centerX(), r.centerY(), r.centerX(), r.centerY() + r.height() * 0.3f, p)
    }

    private fun drawTrackpadGlyph(c: Canvas, r: RectF, col: Int) {
        val p = glyphPaint(col, r.height() * 0.06f)
        val w = r.height() * 0.8f; val h = r.height() * 0.5f
        c.drawRoundRect(RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2), h * 0.15f, h * 0.15f, p)
        val ax = r.centerX() + w * 0.1f; val ay = r.centerY()
        c.drawPath(Path().apply { moveTo(ax, ay - h * 0.3f); lineTo(ax, ay + h * 0.25f); lineTo(ax + h * 0.12f, ay + h * 0.12f); lineTo(ax + h * 0.3f, ay + h * 0.12f); close() },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
    }

    /** New per-game input options: applied now, and saved into the game's touch layout as Steam
     *  Link saves them, so they follow the game and the account. */
    private fun setOptions(next: SteamTouchConfig.Options) {
        pendingOptions = next
        options = next
        onOptions(next)
        releaseAll()
        invalidate()
        val app = appId
        val cfg = config
        loader.execute {
            val file = try { SteamTouchConfig.saveLayouts(context, app, cfg, emptyMap(), options = next) } catch (e: Exception) { null }
            handler.post {
                if (file == null) Log.w(TAG, "steam touch: input options for app $app not saved")
                else { pendingOptions = null; reloadConfig() }
            }
        }
    }

    /** The selected control's icon, label and colours (a D-pad direction: the one tapped). */
    private fun pickIcon(e: Element) {
        val list = visuals[e.type].orEmpty()
        val visual = list.getOrNull(if (e.type == SteamTouchConfig.DPAD) selectedArm else 0)
        val ref = visual?.ref
        val binding = visual?.binding
        if (ref == null || binding == null) {
            Toast.makeText(context, "This control has no binding in Steam to put an icon on", Toast.LENGTH_SHORT).show()
            return
        }
        val name = if (e.type == SteamTouchConfig.DPAD) "D-pad " + listOf("up", "down", "left", "right")[selectedArm] else label(e.type)
        val host = parent as? android.view.ViewGroup ?: return
        SteamTouchIconPicker(context, host, appId, "Icon for $name", binding) { picked ->
            pendingBindings[ref] = picked
            rebuildVisuals()
            invalidate()
        }.show()
    }

    /** The layout's colour and opacity, as Steam Link's colour picker sets them. */
    private fun pickColor() {
        val current = pendingColor ?: SteamTouchConfig.layoutColor(config, actionSet) ?: floatArrayOf(1f, 1f, 1f, 0.45f)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        var chosen = current.copyOf()
        val root = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val swatchRow = android.widget.LinearLayout(context)
        SteamTouchBindings.PALETTE.forEach { hex ->
            val c = Color.parseColor(hex)
            swatchRow.addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c); setStroke(dp(1), Color.GRAY) }
                setOnClickListener {
                    chosen = floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, chosen[3])
                    pendingColor = chosen
                    applyColor(chosen)
                    invalidate()
                }
            }, android.widget.LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(6) })
        }
        root.addView(android.widget.TextView(context).apply { text = "Colour" })
        root.addView(android.widget.HorizontalScrollView(context).apply { addView(swatchRow) })
        root.addView(android.widget.TextView(context).apply { text = "Opacity"; setPadding(0, dp(12), 0, 0) })
        val opacityRow = android.widget.LinearLayout(context)
        listOf(20, 35, 50, 65, 80, 100).forEach { percent ->
            opacityRow.addView(android.widget.Button(context).apply {
                text = "$percent%"
                setOnClickListener {
                    chosen = floatArrayOf(chosen[0], chosen[1], chosen[2], percent / 100f)
                    pendingColor = chosen
                    applyColor(chosen)
                    invalidate()
                }
            })
        }
        root.addView(android.widget.HorizontalScrollView(context).apply { addView(opacityRow) })
        val host = parent as? android.view.ViewGroup ?: return
        SteamTouchIconPicker.showPanel(host, "Touch layout colour", root, listOf("Done" to {}))
    }

    private fun save() {
        trayOpen = false
        saving = true
        invalidate()
        val app = appId
        val cfg = config
        val sets = editSets.mapValues { (_, st) ->
            st.elements.map { it.copy(visible = true) } + st.hidden.filter { h -> st.elements.none { it.type == h.type } }
        }
        val color = pendingColor
        val colors = if (color != null) sets.keys.associateWith { color } else emptyMap()
        // A picked icon replaces the label, icon and colours of every binding of that input; what
        // the binding does stays as it is.
        val bindings = pendingBindings.mapValues { (_, picked) ->
            { old: SteamTouchBindings.Binding ->
                old.copy(label = picked.label, icon = picked.icon, foreground = picked.foreground, background = picked.background)
            }
        }
        loader.execute {
            val file = try {
                SteamTouchConfig.saveLayouts(context, app, cfg, sets, colors, bindings)
            } catch (e: Exception) {
                Log.w(TAG, "steam touch: saving failed: $e")
                null
            }
            handler.post {
                saving = false
                if (file == null) {
                    Toast.makeText(context, "Could not save the touch layout", Toast.LENGTH_SHORT).show()
                    invalidate()
                    return@post
                }
                Toast.makeText(context, "Touch layout saved to Steam", Toast.LENGTH_SHORT).show()
                onSaved()
                stopEditing()
                reloadConfig()
            }
        }
    }

    /** Called after a save: the client reads the config again when the controller reconnects. */
    var onSaved: () -> Unit = {}

    companion object {
        private const val TAG = "SteamTouch"
        private const val POLL_MS = 150L
        const val BIG_PICTURE = 769
        private const val ICON_PX = 128
        // artwork/droiddeck-mark.svg: the D's two halves, the orb's ring cut out (Wordmark.kt).
        private const val MARK_D = "M-31.98 0H56.055V40.489A71.81 71.81 0 0 0 56.055 183.231V223.72H-31.98Z" +
            "M71.885 0.28A111.86 111.86 0 0 1 71.885 223.44V183.234A71.81 71.81 0 0 0 71.885 40.486Z"
        private const val FADE_AFTER_MS = 5000L
        private const val FADED = 0.3f
        private const val SHAKE_G = 2.3f

        private const val DPAD_UP = 1L shl 8
        private const val DPAD_RIGHT = 1L shl 9
        private const val DPAD_LEFT = 1L shl 10
        private const val DPAD_DOWN = 1L shl 11
        private const val STICK_LEFT_TOUCHED = 1L shl 46
        private const val STICK_RIGHT_TOUCHED = 1L shl 47
        private val TRACKPAD_TOUCHED = longArrayOf(1L shl 27, 1L shl 19, 1L shl 20)

        /** Steam Link's button bits (CVirtualController's table, by element type). */
        private val BUTTON_BITS: Map<Int, Long> = buildMap {
            put(SteamTouchConfig.STEAM, 1L shl 13)
            put(SteamTouchConfig.JOYSTICK_LEFT_BUTTON, 1L shl 22)
            put(SteamTouchConfig.JOYSTICK_RIGHT_BUTTON, 1L shl 26)
            put(SteamTouchConfig.A, 1L shl 7)
            put(SteamTouchConfig.B, 1L shl 5)
            put(SteamTouchConfig.X, 1L shl 6)
            put(SteamTouchConfig.Y, 1L shl 4)
            put(SteamTouchConfig.SELECT, 1L shl 12)
            put(SteamTouchConfig.START, 1L shl 14)
            put(SteamTouchConfig.TRIGGER_LEFT, 1L shl 1)
            put(SteamTouchConfig.TRIGGER_RIGHT, 1L shl 0)
            put(SteamTouchConfig.BUMPER_LEFT, 1L shl 3)
            put(SteamTouchConfig.BUMPER_RIGHT, 1L shl 2)
            for (i in 0 until 8) put(SteamTouchConfig.MACRO_0 + i, 1L shl (32 + i))
            put(SteamTouchConfig.MACRO_1_FINGER, 1L shl 48)
            put(SteamTouchConfig.MACRO_2_FINGER, 1L shl 49)
            // Not bits: the menu and the keyboard are the app's own.
            put(SteamTouchConfig.THUMB, 0L)
            put(SteamTouchConfig.KEYBOARD, 0L)
            put(SteamTouchConfig.PASTE, 0L)
        }

        private val LABEL_COLORS = mapOf(
            SteamTouchConfig.A to Color.rgb(72, 133, 48),
            SteamTouchConfig.B to Color.rgb(211, 68, 37),
            SteamTouchConfig.X to Color.rgb(23, 60, 184),
            SteamTouchConfig.Y to Color.rgb(235, 200, 70),
        )
    }
}
