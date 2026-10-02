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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.droiddeck.launcher.input.DeckControls
import kotlin.math.min

/**
 * The Steam Deck controller's back grips and trackpads, on the second screen.
 *
 * Four tabs: the grips as a 2x2 grid (left grips on the left, upper above lower, as they sit on a
 * Deck); either trackpad on its own with a bar to click it; and both trackpads with the grips in a
 * row beneath. A trackpad clicks from its bar or from a second finger while the first is down.
 * Drawn for OLED: true black, with outlines rather than filled surfaces, and the Steam blue only
 * where something is being touched.
 */
@SuppressLint("ViewConstructor")
class DeckControlsPanel(
    context: Context,
    private val onClose: () -> Unit,
    private val onSteamMenu: () -> Unit,
    private val onQam: () -> Unit,
) : LinearLayout(context) {
    private enum class Tab(val label: Int) { GRIPS(R.string.deck_tab_grips), LEFT(R.string.deck_tab_left), RIGHT(R.string.deck_tab_right), BOTH(R.string.deck_tab_both) }

    private val content = FrameLayout(context)
    private val tabViews = HashMap<Tab, TextView>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        addView(topBar(), LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        select(selected)
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
        selected = tab
        DeckControls.releaseAll()
        for ((each, view) in tabViews) {
            val on = each == tab
            view.background = if (on) filled(CORNER_PILL, ACCENT) else null
            view.setTextColor(if (on) ON_ACCENT else TEXT_DIM)
        }
        content.removeAllViews()
        content.addView(
            when (tab) {
                Tab.GRIPS -> gripsGrid()
                Tab.LEFT -> singlePad(right = false)
                Tab.RIGHT -> singlePad(right = true)
                Tab.BOTH -> bothPads()
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
    private class TrackpadView(context: Context, private val right: Boolean) : Surface(context) {
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
            canvas.drawText((if (right) context.getString(R.string.deck_right_trackpad) else context.getString(R.string.deck_left_trackpad)).uppercase(), cx, 28 * density, captionPaint)
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

    companion object {
        /** The tab last shown, kept for the session so reopening the panel lands where it was. */
        private var selected = Tab.BOTH

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
    }
}
