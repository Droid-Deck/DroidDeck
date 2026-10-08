package com.droiddeck.launcher.input

import android.app.AlertDialog
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
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val label = EditText(context).apply {
            hint = "Label (optional)"
            setText(current.label)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        root.addView(label)
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
        root.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(preview, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(TextView(context).apply {
                text = if (names.isEmpty()) "Steam's icons were not found" else "${names.size} icons from Steam"
                setPadding(dp(12), 0, 0, 0)
            })
        })
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        root.addView(paletteRow("Icon colour", { foreground }) { foreground = it; adapter.notifyDataSetChanged(); updatePreview(preview) })
        root.addView(paletteRow("Button colour", { background }) { background = it; adapter.notifyDataSetChanged(); updatePreview(preview) })
        updatePreview(preview)
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("Use") { _, _ ->
                onPicked(current.copy(label = label.text.toString().replace(",", " ").trim(), icon = icon,
                    foreground = if (icon.isEmpty()) "" else foreground, background = if (icon.isEmpty()) "" else background))
            }
            .setNeutralButton("No icon") { _, _ ->
                onPicked(current.copy(label = label.text.toString().replace(",", " ").trim(), icon = "", foreground = "", background = ""))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { loader.shutdown() }
            .show()
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
            if (cached != null) view.setImageBitmap(tinted(cached)) else {
                view.setImageDrawable(null)
                loader.execute {
                    val bitmap = SteamTouchBindings.icon(context, appId, name, 96)
                    main.post { if (view.tag == name && bitmap != null) view.setImageBitmap(tinted(bitmap)) }
                }
            }
            return view
        }

        private fun tinted(icon: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(icon.width, icon.height, Bitmap.Config.ARGB_8888)
            drawSteamIcon(Canvas(out), icon, android.graphics.RectF(0f, 0f, icon.width.toFloat(), icon.height.toFloat()),
                Color.parseColor(foreground), Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }
    }

    companion object {
        /** Steam's binding icon look (steamui: the icon multiplied with its foreground colour,
         *  background-blend-mode: multiply, keeping the icon's own alpha). */
        fun drawSteamIcon(canvas: Canvas, icon: Bitmap, into: android.graphics.RectF, foreground: Int, paint: Paint) {
            val tint = Paint(paint).apply { colorFilter = PorterDuffColorFilter(foreground, PorterDuff.Mode.MULTIPLY) }
            canvas.drawBitmap(icon, null, into, tint)
        }
    }
}
