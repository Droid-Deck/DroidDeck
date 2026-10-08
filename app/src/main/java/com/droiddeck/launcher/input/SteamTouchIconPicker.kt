package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Picks a touch control's icon, label and colours the way Steam's configurator does: an icon from
 * the game's own `TouchMenuIcons` and Steam's binding icon library, a foreground and a background
 * from Steam's palette. [onPicked] gets the binding with the new fields (the action untouched).
 */
class SteamTouchIconPicker(
    private val context: Context,
    private val host: ViewGroup,
    private val appId: Int,
    private val title: String,
    private val current: SteamTouchBindings.Binding,
    private val onPicked: (SteamTouchBindings.Binding) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2)
    private var icon = current.icon
    private var foreground = current.foreground.ifEmpty { SteamTouchBindings.DEFAULT_FOREGROUND }
    private var background = current.background.ifEmpty { SteamTouchBindings.DEFAULT_BACKGROUND }
    private val density = context.resources.displayMetrics.density

    private fun dp(v: Int) = (v * density).toInt()

    fun show() {
        // Side by side, for a landscape screen: what is picked on the left, the icons on the right.
        val root = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val label = EditText(context).apply {
            hint = "Label (optional)"
            setText(current.label)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        left.addView(label)
        val preview = ImageView(context)
        val names = SteamTouchBindings.iconNames(context, appId)
        val grid = GridView(context).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(56)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(6)
            horizontalSpacing = dp(6)
        }
        val adapter = IconAdapter(names)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            icon = names[position]
            adapter.notifyDataSetChanged()
            updatePreview(preview)
        }
        left.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(preview, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(TextView(context).apply {
                text = if (names.isEmpty()) "Steam's icons were not found" else "${names.size} icons from Steam"
                setPadding(dp(12), 0, 0, 0)
            })
        })
        left.addView(paletteRow("Icon colour", { foreground }) { foreground = it; adapter.notifyDataSetChanged(); updatePreview(preview) })
        left.addView(paletteRow("Button colour", { background }) { background = it; adapter.notifyDataSetChanged(); updatePreview(preview) })
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(grid, LinearLayout.LayoutParams(0, dp(250), 1.2f).apply { marginStart = dp(16) })
        updatePreview(preview)
        showPanel(host, title, root, listOf(
            "No icon" to {
                onPicked(current.copy(label = label.text.toString().replace(",", " ").trim(), icon = "", foreground = "", background = ""))
            },
            "Cancel" to {},
            "Use" to {
                onPicked(current.copy(label = label.text.toString().replace(",", " ").trim(), icon = icon,
                    foreground = if (icon.isEmpty()) "" else foreground, background = if (icon.isEmpty()) "" else background))
            },
        )) { loader.shutdown() }
    }

    private fun paletteRow(name: String, value: () -> String, onPick: (String) -> Unit): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val swatches = mutableListOf<View>()
        fun refresh() = swatches.forEachIndexed { i, v ->
            (v.background as GradientDrawable).setStroke(dp(if (SteamTouchBindings.PALETTE[i].equals(value(), true)) 3 else 1),
                if (SteamTouchBindings.PALETTE[i].equals(value(), true)) Color.rgb(102, 192, 244) else Color.GRAY)
        }
        SteamTouchBindings.PALETTE.forEach { hex ->
            val swatch = View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(hex)) }
                setOnClickListener { onPick(hex); refresh() }
            }
            swatches += swatch
            row.addView(swatch, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(6) })
        }
        refresh()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(TextView(context).apply { text = name })
            addView(HorizontalScrollView(context).apply { addView(row) })
        }
    }

    private fun updatePreview(view: ImageView) {
        if (icon.isEmpty()) { view.setImageDrawable(null); view.background = null; return }
        loader.execute {
            val bitmap = SteamTouchBindings.icon(context, appId, icon, 128)?.let { render(it, dp(56)) }
            main.post { view.setImageBitmap(bitmap) }
        }
    }

    /** The icon as Steam draws it: on a disc of the background colour, filled with the foreground
     *  through its own alpha. */
    private fun render(icon: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = Color.parseColor(background)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        drawSteamIcon(canvas, icon, android.graphics.RectF(size * 0.18f, size * 0.18f, size * 0.82f, size * 0.82f), Color.parseColor(foreground), paint)
        return out
    }

    private inner class IconAdapter(val names: List<String>) : BaseAdapter() {
        private val loading = HashSet<String>()
        private var refreshQueued = false

        /** Loads an icon in the background; the grid redraws from the cache once some have come in. */
        private fun load(name: String) {
            if (!loading.add(name)) return
            loader.execute {
                SteamTouchBindings.icon(context, appId, name, 96)
                main.post {
                    loading.remove(name)
                    if (refreshQueued) return@post
                    refreshQueued = true
                    main.postDelayed({ refreshQueued = false; notifyDataSetChanged() }, 120)
                }
            }
        }

        override fun getCount() = names.size
        override fun getItem(position: Int) = names[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = (convertView as? ImageView) ?: ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val name = names[position]
            view.tag = name
            view.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor(background))
                if (name == icon) setStroke(dp(3), Color.rgb(102, 192, 244))
            }
            view.colorFilter = null
            val cached = SteamTouchBindings.cachedIcon(appId, name, 96)
            if (cached != null) {
                val t = tinted(name, cached)
                if ((view.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== t) view.setImageBitmap(t)
            } else {
                view.setImageDrawable(null)
                load(name)
            }
            return view
        }

        // One thumbnail per icon and colour, reused when the grid rebinds.
        private val thumbs = android.util.LruCache<String, Bitmap>(64)

        private fun tinted(name: String, icon: Bitmap): Bitmap {
            val key = "$name|$foreground"
            thumbs.get(key)?.let { return it }
            val out = Bitmap.createBitmap(icon.width, icon.height, Bitmap.Config.ARGB_8888)
            drawSteamIcon(Canvas(out), icon, android.graphics.RectF(0f, 0f, icon.width.toFloat(), icon.height.toFloat()),
                Color.parseColor(foreground), Paint(Paint.FILTER_BITMAP_FLAG))
            thumbs.put(key, out)
            return out
        }
    }

    companion object {
        /**
         * A panel over the session, in the session's own window: a dialog's window of its own left
         * new bitmaps and layers undrawn in the session window afterwards on the Thor.
         */
        fun showPanel(host: ViewGroup, title: String, content: View, buttons: List<Pair<String, () -> Unit>>, onClose: () -> Unit = {}): () -> Unit {
            val context = host.context
            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val scrim = android.widget.FrameLayout(context).apply {
                setBackgroundColor(Color.argb(140, 0, 0, 0))
                isClickable = true
                isFocusable = true
            }
            // Steam Link's panel: navy, square, a blue rule along the top.
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.LayerDrawable(arrayOf(
                    GradientDrawable().apply { setColor(Color.rgb(49, 106, 196)) },
                    GradientDrawable().apply { setColor(Color.rgb(32, 40, 51)) },
                )).apply { setLayerInset(1, 0, dp(2), 0, 0) }
                setPadding(dp(12), dp(14), dp(12), dp(10))
                isClickable = true
            }
            card.addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(16), 0, dp(16), dp(4))
            })
            card.addView(content)
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), 0) }
            fun close() {
                (scrim.parent as? ViewGroup)?.removeView(scrim)
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(host.windowToken, 0)
                onClose()
            }
            buttons.forEachIndexed { i, (name, action) ->
                row.addView(TextView(context).apply {
                    text = name
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(62, 112, 178), Color.rgb(51, 92, 154)))
                    setPadding(dp(20), dp(10), dp(20), dp(10))
                    setOnClickListener { close(); action() }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
            }
            card.addView(row)
            val width = minOf(dp(640), (context.resources.displayMetrics.widthPixels * 0.9f).toInt())
            scrim.addView(card, android.widget.FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            scrim.setOnClickListener { close() }
            host.addView(scrim, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return { if (scrim.parent != null) close() }
        }

        /** Steam's binding icon look (steamui: the icon multiplied with its foreground colour,
         *  background-blend-mode: multiply, keeping the icon's own alpha). */
        fun drawSteamIcon(canvas: Canvas, icon: Bitmap, into: android.graphics.RectF, foreground: Int, paint: Paint) {
            val tint = Paint(paint).apply { colorFilter = PorterDuffColorFilter(foreground, PorterDuff.Mode.MULTIPLY) }
            canvas.drawBitmap(icon, null, into, tint)
        }
    }
}
