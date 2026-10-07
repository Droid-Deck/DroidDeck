package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.droiddeck.launcher.input.DeckControls
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.PadState
import com.droiddeck.launcher.session.SessionPrefs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * The Steam Deck controller's back grips and trackpads, on the second screen.
 *
 * Four tabs: the grips as a 2x2 grid (left grips on the left, upper above lower, as they sit on a
 * Deck); either trackpad on its own with a bar to click it; and both trackpads with the grips in a
 * row beneath. Given the [pad] (the unfolded screen's panel), a fifth: a whole gamepad, each side
 * an analog stick or a trackpad as the player picks. A trackpad clicks from its bar or from a second finger while the first is down.
 * Drawn for OLED: true black, with outlines rather than filled surfaces, and the Steam blue only
 * where something is being touched.
 */
@SuppressLint("ViewConstructor")
class DeckControlsPanel(
    context: Context,
    private val onClose: () -> Unit,
    private val onSteamMenu: () -> Unit,
    private val onQam: () -> Unit,
    private val pad: PadBridge? = null,
) : LinearLayout(context) {
    private enum class Tab(val label: Int) { GAMEPAD(R.string.deck_tab_gamepad), GRIPS(R.string.deck_tab_grips), LEFT(R.string.deck_tab_left), RIGHT(R.string.deck_tab_right), BOTH(R.string.deck_tab_both) }

    private val content = FrameLayout(context)
    private val tabViews = HashMap<Tab, TextView>()
    private val topBar: View by lazy { topBar() }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        select(if (pad != null) selectedWithPad else selected)
    }

    override fun onDetachedFromWindow() {
        DeckControls.releaseAll()
        super.onDetachedFromWindow()
    }

    private fun topBar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(6))
        addView(roundButton("×", context.getString(R.string.deck_close), onClose))
        val tabs = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = outline(CORNER_PILL, SURFACE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            for (tab in Tab.values()) {
                if (tab == Tab.GAMEPAD && pad == null) continue
                val view = TextView(context).apply {
                    text = context.getString(tab.label)
                    textSize = 14f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    isSingleLine = true
                    setPadding(dp(14), 0, dp(14), 0)
                    setOnClickListener { select(tab) }
                }
                tabViews[tab] = view
                addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            }
        }
        val centre = FrameLayout(context).apply {
            addView(tabs, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(44), Gravity.CENTER))
        }
        addView(centre, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(roundButton("STEAM", context.getString(R.string.deck_open_steam_menu), onSteamMenu, wide = true))
        addView(space(dp(8)))
        addView(roundButton("•••", context.getString(R.string.drawer_open_qam), onQam, wide = true))
    }

    private fun select(tab: Tab) {
        if (pad != null) selectedWithPad = tab else selected = tab
        DeckControls.releaseAll()
        for ((each, view) in tabViews) {
            val on = each == tab
            view.background = if (on) filled(CORNER_PILL, ACCENT) else null
            view.setTextColor(if (on) ON_ACCENT else TEXT_DIM)
        }
        content.removeAllViews()
        // The gamepad needs every row of the panel; held upright its Panels button brings the tabs back.
        topBar.visibility = if (tab == Tab.GAMEPAD) View.GONE else View.VISIBLE
        content.addView(
            when (tab) {
                Tab.GRIPS -> gripsGrid()
                Tab.LEFT -> singlePad(right = false)
                Tab.RIGHT -> singlePad(right = true)
                Tab.BOTH -> bothPads()
                Tab.GAMEPAD -> gamepad(pad ?: return)
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }

    private fun gripsGrid(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(dp(12), dp(6), dp(12), dp(12))
        fun column(upper: View, lower: View) = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(upper, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(6) })
            addView(lower, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        }
        addView(column(GripButton(context, "L4", context.getString(R.string.deck_left_upper), DeckControls.L4), GripButton(context, "L5", context.getString(R.string.deck_left_lower), DeckControls.L5)),
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(6) })
        addView(column(GripButton(context, "R4", context.getString(R.string.deck_right_upper), DeckControls.R4), GripButton(context, "R5", context.getString(R.string.deck_right_lower), DeckControls.R5)),
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(6) })
    }

    private fun singlePad(right: Boolean): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(12))
        addView(TrackpadView(context, right), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(12) })
        addView(ClickBar(context, right), LayoutParams(LayoutParams.MATCH_PARENT, dp(76)))
    }

    private fun bothPads(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(12))
        val pads = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(TrackpadView(context, right = false), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(6) })
            addView(TrackpadView(context, right = true), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(6) })
        }
        addView(pads, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(12) })
        // Mirrored, as hands hold them: the upper grips outside, the lower ones in the middle.
        val grips = LinearLayout(context).apply {
            orientation = HORIZONTAL
            val row = listOf("L4" to DeckControls.L4, "L5" to DeckControls.L5, "R5" to DeckControls.R5, "R4" to DeckControls.R4)
            row.forEachIndexed { index, (label, bit) ->
                addView(GripButton(context, label, null, bit), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    if (index > 0) leftMargin = dp(6)
                    if (index < row.size - 1) rightMargin = dp(6)
                })
            }
        }
        addView(grips, LayoutParams(LayoutParams.MATCH_PARENT, dp(84)))
    }

    /**
     * A whole pad, placed for two thumbs rather than in columns (see [GamepadGeometry]): on each side
     * a surface where the thumb rests - most of play is on the sticks or trackpads - and a short
     * slide inward along its arc, the d-pad on the left and the face buttons on the right; the bumpers and triggers above, at the top of the arc; View, Steam, QAM and Menu in the
     * middle, which a thumb only reaches on purpose. Each surface is an analog stick or a Steam
     * trackpad, switched from its corner and remembered (SessionPrefs.gamepadSurface). Its tab bar
     * is hidden; held upright, a Panels button brings the other tabs back, and held flat there is no
     * room for one.
     */
    private fun gamepad(pad: PadBridge): View = GamepadLayout(context).apply {
        fun pill(label: String, write: (PadState, Boolean) -> Unit) = PadButton(context, pad, label, write)
        place(surfaceSlot(pad, right = false), GamepadGeometry.Slot.LEFT_MAIN)
        place(DirectionPad(context, pad), GamepadGeometry.Slot.LEFT_SECOND)
        place(surfaceSlot(pad, right = true), GamepadGeometry.Slot.RIGHT_MAIN)
        place(FaceButtons(context, pad), GamepadGeometry.Slot.RIGHT_SECOND)
        place(pill("LT") { st, d -> st.leftTrigger = if (d) 1f else 0f }, GamepadGeometry.Slot.LEFT_OUTER_SHOULDER)
        place(pill("LB") { st, d -> st.press(PadState.LB, d) }, GamepadGeometry.Slot.LEFT_INNER_SHOULDER)
        place(pill("RB") { st, d -> st.press(PadState.RB, d) }, GamepadGeometry.Slot.RIGHT_INNER_SHOULDER)
        place(pill("RT") { st, d -> st.rightTrigger = if (d) 1f else 0f }, GamepadGeometry.Slot.RIGHT_OUTER_SHOULDER)
        place(pill("VIEW") { st, d -> st.press(PadState.SELECT, d) }, GamepadGeometry.Slot.VIEW)
        place(centreTap("STEAM", context.getString(R.string.deck_open_steam_menu), onSteamMenu), GamepadGeometry.Slot.STEAM)
        place(centreTap("\u2022\u2022\u2022", context.getString(R.string.drawer_open_qam), onQam), GamepadGeometry.Slot.QAM)
        place(pill("MENU") { st, d -> st.press(PadState.START, d) }, GamepadGeometry.Slot.MENU)
        place(centreTap("\u25a6 " + context.getString(R.string.deck_panels), context.getString(R.string.deck_more_controls)) { select(Tab.BOTH) },
            GamepadGeometry.Slot.PANELS)
    }

    private fun centreTap(label: String, description: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        contentDescription = description
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
        setTextColor(TEXT)
        background = outline(CORNER_PILL, SURFACE)
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
    }

    /** One side's stick or trackpad, with a chip in its outer corner that swaps the two. */
    private fun surfaceSlot(pad: PadBridge, right: Boolean): View = FrameLayout(context).apply {
        val slot = this
        fun show() {
            slot.removeAllViews()
            val stick = SessionPrefs.gamepadSurface(context, right) == SessionPrefs.SURFACE_STICK
            // No caption: the switch in its corner says which it is.
            slot.addView(if (stick) StickView(context, pad, right) else TrackpadView(context, right, captioned = false),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val chip = TextView(context).apply {
                text = "\u21c4"
                contentDescription = context.getString(R.string.deck_surface_switch)
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(TEXT_DIM)
                background = outline(CORNER_PILL, SURFACE)
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    SessionPrefs.setGamepadSurface(context, right,
                        if (stick) SessionPrefs.SURFACE_TRACKPAD else SessionPrefs.SURFACE_STICK)
                    show()
                }
            }
            // In the corner away from the thumb, so play does not swap it by accident.
            slot.addView(chip, FrameLayout.LayoutParams(dp(30), dp(30),
                (if (right) Gravity.START else Gravity.END) or Gravity.TOP).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        }
        show()
    }

    private fun roundButton(label: String, description: String, onClick: () -> Unit, wide: Boolean = false) =
        TextView(context).apply {
            text = label
            contentDescription = description
            textSize = if (wide) 13f else 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = if (wide) 0.08f else 0f
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            background = outline(CORNER_PILL, SURFACE)
            setPadding(if (wide) dp(16) else 0, 0, if (wide) dp(16) else 0, 0)
            minWidth = dp(44)
            layoutParams = LayoutParams(if (wide) LayoutParams.WRAP_CONTENT else dp(44), dp(44))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    private fun space(width: Int) = View(context).apply { layoutParams = LayoutParams(width, 1) }

    private fun outline(radius: Float, fill: Int) = GradientDrawable().apply {
        cornerRadius = radius * resources.displayMetrics.density
        setColor(fill)
        setStroke(dp(1), LINE)
    }

    private fun filled(radius: Float, fill: Int) = GradientDrawable().apply {
        cornerRadius = radius * resources.displayMetrics.density
        setColor(fill)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    /** A surface drawn the same way everywhere on the panel; pressed, it lights up in the accent. */
    private abstract class Surface(context: Context) : View(context) {
        protected val density = resources.displayMetrics.density
        protected val rect = RectF()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        protected val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        protected val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = TEXT_DIM
        }

        protected fun drawSurface(canvas: Canvas, lit: Boolean) {
            val inset = density
            rect.set(inset, inset, width - inset, height - inset)
            val radius = CORNER * density
            fill.color = if (lit) ACCENT_WASH else SURFACE
            canvas.drawRoundRect(rect, radius, radius, fill)
            stroke.color = if (lit) ACCENT else LINE
            stroke.strokeWidth = (if (lit) 2f else 1f) * density
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }
    }

    /** A held button: down while a finger is on it. */
    private open class HoldButton(
        context: Context,
        private val label: String,
        private val caption: String?,
        private val onChange: (Boolean) -> Unit,
    ) : Surface(context) {
        private var down = false

        override fun onDraw(canvas: Canvas) {
            drawSurface(canvas, down)
            val cx = width / 2f
            labelPaint.textSize = min(height * 0.3f, 30 * density)
            labelPaint.color = if (down) ACCENT else TEXT
            val labelY = height / 2f + labelPaint.textSize * (if (caption == null) 0.35f else 0.1f)
            canvas.drawText(label, cx, labelY, labelPaint)
            if (caption != null) {
                captionPaint.textSize = 12 * density
                canvas.drawText(caption, cx, labelY + 22 * density, captionPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> set(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> set(false)
            }
            return true
        }

        private fun set(pressed: Boolean) {
            if (down == pressed) return
            down = pressed
            if (pressed) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onChange(pressed)
            invalidate()
        }
    }

    private class GripButton(context: Context, label: String, caption: String?, bit: Int) :
        HoldButton(context, label, caption, { DeckControls.setGrip(bit, it) })

    private class ClickBar(context: Context, right: Boolean) :
        HoldButton(context, context.getString(R.string.deck_click), if (right) context.getString(R.string.deck_right_trackpad) else context.getString(R.string.deck_left_trackpad), { DeckControls.setClick(right, it) })

    /**
     * One trackpad: where the first finger is, as the Deck reports it (-1..1, y up), and a click
     * while a second finger is down.
     */
    private class TrackpadView(context: Context, private val right: Boolean, private val captioned: Boolean = true) : Surface(context) {
        private var touching = false
        private var clicked = false
        private var fingerX = 0f
        private var fingerY = 0f
        private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = GUIDE }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            drawSurface(canvas, clicked)
            val cx = width / 2f
            val cy = height / 2f
            guide.strokeWidth = density
            val reach = min(width, height) / 2f - 24 * density
            canvas.drawCircle(cx, cy, reach, guide)
            canvas.drawCircle(cx, cy, reach / 2f, guide)
            canvas.drawLine(cx - reach, cy, cx + reach, cy, guide)
            canvas.drawLine(cx, cy - reach, cx, cy + reach, guide)
            captionPaint.textSize = 12 * density
            captionPaint.color = if (touching) ACCENT else TEXT_DIM
            if (captioned) canvas.drawText((if (right) context.getString(R.string.deck_right_trackpad) else context.getString(R.string.deck_left_trackpad)).uppercase(), cx, 28 * density, captionPaint)
            if (touching) {
                val radius = 56 * density
                glow.shader = RadialGradient(fingerX, fingerY, radius, ACCENT_GLOW, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawCircle(fingerX, fingerY, radius, glow)
                canvas.drawCircle(fingerX, fingerY, (if (clicked) 16 else 12) * density, dot)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    touch(event.getX(0), event.getY(0))
                    click(event.pointerCount > 1)
                }
                MotionEvent.ACTION_POINTER_DOWN -> click(true)
                MotionEvent.ACTION_POINTER_UP -> {
                    // The finger that lifted may be the first; the pad follows whichever stays.
                    val stays = if (event.actionIndex == 0) 1 else 0
                    touch(event.getX(stays), event.getY(stays))
                    click(event.pointerCount - 1 > 1)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    click(false)
                    touching = false
                    DeckControls.setPad(right, touching = false)
                    invalidate()
                }
            }
            return true
        }

        private fun touch(x: Float, y: Float) {
            fingerX = x.coerceIn(0f, width.toFloat())
            fingerY = y.coerceIn(0f, height.toFloat())
            touching = true
            DeckControls.setPad(right, true, (fingerX - width / 2f) / (width / 2f), -(fingerY - height / 2f) / (height / 2f))
            invalidate()
        }

        private fun click(down: Boolean) {
            if (clicked == down) return
            clicked = down
            if (down) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            DeckControls.setClick(right, down)
            invalidate()
        }
    }

    /** A pad button held while a finger is on it, written into the shared pad as the on-screen pad does. */
    private class PadButton(
        context: Context,
        private val pad: PadBridge,
        label: String,
        private val write: (PadState, Boolean) -> Unit,
    ) : HoldButton(context, label, null, { down -> pad.applyTouch { write(it, down) } }) {
        override fun onDetachedFromWindow() {
            pad.applyTouch { write(it, false) }
            super.onDetachedFromWindow()
        }
    }

    /**
     * An analog stick that centres where the thumb lands, so it is never missed: the knob follows
     * the finger from there out to the ring. Tapping it again and holding (within [CLICK_MS]) is
     * the stick's click - L3 or R3 - for as long as the thumb stays down.
     */
    private class StickView(context: Context, private val pad: PadBridge, private val right: Boolean) : Surface(context) {
        private var touching = false
        private var clicking = false
        private var lastUp = 0L
        private var originX = 0f
        private var originY = 0f
        private var knobX = 0f
        private var knobY = 0f
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun reach() = min(width, height) * 0.30f

        override fun onDraw(canvas: Canvas) {
            drawSurface(canvas, clicking)
            val cx = if (touching) originX else width / 2f
            val cy = if (touching) originY else height / 2f
            ring.strokeWidth = 2 * density
            ring.color = if (touching) ACCENT else LINE
            canvas.drawCircle(cx, cy, reach(), ring)
            knob.color = if (touching) ACCENT else KNOB
            canvas.drawCircle(cx + knobX, cy + knobY, reach() * 0.45f, knob)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val r = reach().coerceAtLeast(1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Centred where the thumb lands, but kept far enough in for the ring to fit.
                    originX = event.x.coerceIn(r, (width - r).coerceAtLeast(r))
                    originY = event.y.coerceIn(r, (height - r).coerceAtLeast(r))
                    touching = true
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (event.eventTime - lastUp < CLICK_MS) setClick(true)
                    follow(event.x, event.y, r)
                }
                MotionEvent.ACTION_MOVE -> follow(event.getX(0), event.getY(0), r)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    lastUp = event.eventTime
                    setClick(false)
                    move(0f, 0f, 1f)
                }
            }
            return true
        }

        private fun follow(x: Float, y: Float, r: Float) {
            var dx = x - originX
            var dy = y - originY
            val length = hypot(dx, dy)
            if (length > r) { dx *= r / length; dy *= r / length }
            move(dx, dy, r)
        }

        private fun move(dx: Float, dy: Float, r: Float) {
            knobX = dx
            knobY = dy
            pad.applyTouch { s ->
                if (right) { s.rightX = dx / r; s.rightY = dy / r } else { s.leftX = dx / r; s.leftY = dy / r }
            }
            invalidate()
        }

        private fun setClick(down: Boolean) {
            if (clicking == down) return
            clicking = down
            if (down) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            pad.applyTouch { s -> s.press(if (right) PadState.R3 else PadState.L3, down) }
        }

        override fun onDetachedFromWindow() {
            setClick(false)
            if (knobX != 0f || knobY != 0f) move(0f, 0f, 1f)
            super.onDetachedFromWindow()
        }
    }

    /** The d-pad: the direction is the finger's angle from the centre, diagonals included. */
    private class DirectionPad(context: Context, private val pad: PadBridge) : View(context) {
        private val density = resources.displayMetrics.density
        private var held = 0 // bits: 1 up, 2 right, 4 down, 8 left
        private val arm = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val size = min(width, height) * 0.86f
            val cx = width / 2f
            val cy = height / 2f
            val half = size / 2f
            val thick = size / 3f / 2f
            fun armRect(bit: Int, l: Float, t: Float, r: Float, b: Float) {
                arm.color = if (held and bit != 0) ACCENT else KNOB
                rect.set(l, t, r, b)
                canvas.drawRoundRect(rect, 8 * density, 8 * density, arm)
            }
            armRect(1, cx - thick, cy - half, cx + thick, cy - thick)
            armRect(4, cx - thick, cy + thick, cx + thick, cy + half)
            armRect(8, cx - half, cy - thick, cx - thick, cy + thick)
            armRect(2, cx + thick, cy - thick, cx + half, cy + thick)
            arm.color = KNOB
            canvas.drawRect(cx - thick, cy - thick, cx + thick, cy + thick, arm)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val bits = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val dx = event.getX(0) - width / 2f
                    val dy = event.getY(0) - height / 2f
                    if (hypot(dx, dy) < min(width, height) * 0.08f) 0 else {
                        // Eight sectors of 45 degrees, starting with right and going clockwise.
                        val sector = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360 + 22.5) % 360 / 45).toInt()
                        intArrayOf(2, 2 or 4, 4, 4 or 8, 8, 8 or 1, 1, 1 or 2)[sector]
                    }
                }
                else -> 0
            }
            set(bits)
            return true
        }

        private fun set(bits: Int) {
            if (bits == held) return
            if (bits and held.inv() != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            held = bits
            pad.applyTouch { s ->
                s.up = bits and 1 != 0; s.right = bits and 2 != 0; s.down = bits and 4 != 0; s.left = bits and 8 != 0
            }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            set(0)
            super.onDetachedFromWindow()
        }
    }

    /** A, B, X and Y in a diamond, as on a Deck; several fingers can hold several at once. */
    private class FaceButtons(context: Context, private val pad: PadBridge) : View(context) {
        private val buttons = listOf(PadState.Y to "Y", PadState.B to "B", PadState.A to "A", PadState.X to "X")
        private var held = emptySet<Int>()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        /** Top, right, bottom, left - the centre of each, and the radius of all. */
        private fun layout(): Pair<List<Pair<Float, Float>>, Float> {
            val cx = width / 2f
            val cy = height / 2f
            val spread = min(width, height) * 0.30f
            val radius = min(width, height) * 0.16f
            return listOf(cx to cy - spread, cx + spread to cy, cx to cy + spread, cx - spread to cy) to radius
        }

        override fun onDraw(canvas: Canvas) {
            val (centres, radius) = layout()
            text.textSize = radius * 0.9f
            buttons.forEachIndexed { i, (button, label) ->
                val (x, y) = centres[i]
                val down = button in held
                fill.color = if (down) ACCENT else KNOB
                canvas.drawCircle(x, y, radius, fill)
                text.color = if (down) ON_ACCENT else TEXT
                canvas.drawText(label, x, y + text.textSize * 0.35f, text)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val (centres, radius) = layout()
            val now = HashSet<Int>()
            val ending = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
            if (!ending) for (p in 0 until event.pointerCount) {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && p == event.actionIndex) continue
                val x = event.getX(p)
                val y = event.getY(p)
                // The nearest button within reach, so a thumb between two still lands on one.
                val nearest = centres.indices.minByOrNull { hypot(centres[it].first - x, centres[it].second - y) } ?: continue
                if (hypot(centres[nearest].first - x, centres[nearest].second - y) <= radius * 1.6f) now.add(buttons[nearest].first)
            }
            set(now)
            return true
        }

        private fun set(now: Set<Int>) {
            if (now == held) return
            if ((now - held).isNotEmpty()) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val before = held
            held = now
            pad.applyTouch { s -> for (b in before + now) s.press(b, b in now) }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            set(emptySet())
            super.onDetachedFromWindow()
        }
    }

    /** Lays its controls out where [GamepadGeometry] puts them for the panel's size. */
    private class GamepadLayout(context: Context) : ViewGroup(context) {
        private val slots = LinkedHashMap<View, GamepadGeometry.Slot>()

        fun place(view: View, slot: GamepadGeometry.Slot) {
            slots[view] = slot
            addView(view)
        }

        private fun boxes(w: Int, h: Int) = GamepadGeometry.layout(w.toFloat(), h.toFloat(), resources.displayMetrics.density)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            val boxes = boxes(w, h)
            for ((view, slot) in slots) {
                val box = boxes[slot]
                view.visibility = if (box == null) View.GONE else View.VISIBLE
                if (box == null) continue
                view.measure(MeasureSpec.makeMeasureSpec(box.width.toInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(box.height.toInt().coerceAtLeast(1), MeasureSpec.EXACTLY))
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val boxes = boxes(r - l, b - t)
            for ((view, slot) in slots) {
                val box = boxes[slot] ?: continue
                val left = box.left.toInt()
                val top = box.top.toInt()
                view.layout(left, top, left + box.width.toInt().coerceAtLeast(1), top + box.height.toInt().coerceAtLeast(1))
            }
        }
    }

    companion object {
        /** A second press on a stick this soon after the last is its click (L3, R3). */
        private const val CLICK_MS = 300L

        /** The tab last shown, kept for the session so reopening the panel lands where it was. */
        private var selected = Tab.BOTH
        /** The same for the unfolded screen's panel, which opens on the gamepad. */
        private var selectedWithPad = Tab.GAMEPAD

        private const val CORNER = 20f
        private const val CORNER_PILL = 22f
        private val ACCENT = Color.rgb(0x1A, 0x9F, 0xFF)
        private val ACCENT_WASH = Color.argb(0x2E, 0x1A, 0x9F, 0xFF)
        private val ACCENT_GLOW = Color.argb(0x66, 0x1A, 0x9F, 0xFF)
        private val ON_ACCENT = Color.rgb(0x03, 0x11, 0x1F)
        private val SURFACE = Color.rgb(0x0A, 0x0B, 0x0D)
        private val LINE = Color.rgb(0x26, 0x29, 0x2E)
        private val GUIDE = Color.rgb(0x16, 0x18, 0x1B)
        private val TEXT = Color.rgb(0xF2, 0xF4, 0xF7)
        private val TEXT_DIM = Color.rgb(0x7A, 0x80, 0x8A)
        private val KNOB = Color.rgb(0x2A, 0x2D, 0x33)
    }
}
