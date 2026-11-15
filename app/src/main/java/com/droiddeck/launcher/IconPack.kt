package com.droiddeck.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.Xml
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.palette.graphics.Palette
import org.xmlpull.v1.XmlPullParser
import java.time.LocalDate
import kotlin.math.roundToInt

object IconPack {
    const val NONE = ""
    private const val PREFS = "home_launcher"
    private const val KEY = "icon_pack"
    private const val APP_COLORS = "icon_pack_app_colors"
    private const val FILTER = "appfilter"
    private const val DRAWABLES = "drawable"

    private val ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")

    data class Pack(val packageName: String, val label: String)

    internal data class Plate(val back: String?, val mask: String?, val upon: String?, val scale: Float)

    internal data class Filter(val icons: Map<String, String>, val calendars: Map<String, String>, val plate: Plate?) {
        fun names(component: String, day: Int) = listOfNotNull(calendars[component]?.plus(day), icons[component])
    }

    class Loaded internal constructor(
        private val resources: Resources,
        internal val pack: String,
        internal val updatedAt: Long,
        private val filter: Filter,
    ) {
        fun icon(component: ComponentName, densityDpi: Int) =
            filter.names(component.flattenToString(), LocalDate.now().dayOfMonth).firstNotNullOfOrNull { load(resources, pack, it, densityDpi) }

        val hasBack get() = filter.plate?.back != null

        fun plated(icon: Drawable, size: Int, densityDpi: Int, appColor: Boolean): Drawable? {
            val plate = filter.plate ?: return null
            fun image(name: String?) = name?.let { load(resources, pack, it, densityDpi) }?.toBitmap(size, size)
            val base = icon.toBitmap(size, size)
            val color = if (appColor) Palette.from(base).generate().getDominantColor(0).takeIf { it != 0 } else null
            return compose(base, image(plate.back), image(plate.mask), image(plate.upon), plate.scale, color).toDrawable(resources)
        }
    }

    @Volatile private var cached: Loaded? = null

    fun selected(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, NONE).orEmpty()

    fun select(context: Context, pack: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, pack).apply()
    }

    fun appColors(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(APP_COLORS, false)

    fun setAppColors(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(APP_COLORS, on).apply()
    }

    fun installed(context: Context): List<Pack> {
        val pm = context.packageManager
        return ACTIONS.flatMap { pm.queryIntentActivities(Intent(it), 0) }
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { app -> resources(pm, app.packageName)?.let { withXml(it, app.packageName, FILTER) { true } } == true }
            .map { app -> Pack(app.packageName, app.loadLabel(pm).toString().trim().ifEmpty { app.packageName }) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun active(context: Context): Loaded? {
        val pack = selected(context)
        if (pack == NONE) return null
        val pm = context.packageManager
        val updatedAt = runCatching { pm.getPackageInfo(pack, 0).lastUpdateTime }.getOrNull() ?: return null
        cached?.takeIf { it.pack == pack && it.updatedAt == updatedAt }?.let { return it }
        val resources = resources(pm, pack) ?: return null
        val filter = withXml(resources, pack, FILTER) { parse(it) } ?: return null
        return Loaded(resources, pack, updatedAt, filter).also { cached = it }
    }

    fun drawable(context: Context, pack: String, name: String, densityDpi: Int): Drawable? {
        val resources = resources(context.packageManager, pack) ?: return null
        return load(resources, pack, name, densityDpi)
    }

    fun names(context: Context, pack: String): List<String> {
        val resources = resources(context.packageManager, pack) ?: return emptyList()
        return withXml(resources, pack, DRAWABLES) { parseNames(it) }?.takeIf { it.isNotEmpty() }
            ?: withXml(resources, pack, FILTER) { parse(it).icons.values.distinct().sorted() }.orEmpty()
    }

    private fun resources(pm: PackageManager, pack: String): Resources? =
        runCatching { pm.getResourcesForApplication(pack) }.getOrNull()

    private fun load(resources: Resources, pack: String, name: String, densityDpi: Int): Drawable? {
        val id = resources.getIdentifier(name, "drawable", pack).takeIf { it != 0 }
            ?: resources.getIdentifier(name, "mipmap", pack).takeIf { it != 0 }
            ?: return null
        return runCatching { ResourcesCompat.getDrawableForDensity(resources, id, densityDpi, null) }.getOrNull()
    }

    internal fun parseNames(xml: XmlPullParser): List<String> {
        val names = LinkedHashSet<String>()
        forEachTag(xml) { if (xml.name == "item") xml.getAttributeValue(null, "drawable")?.trim()?.takeIf { it.isNotEmpty() }?.let { names.add(it) } }
        return names.toList()
    }

    internal fun parse(xml: XmlPullParser): Filter {
        val icons = HashMap<String, String>()
        val calendars = HashMap<String, String>()
        var back: String? = null
        var mask: String? = null
        var upon: String? = null
        var scale = 1f
        fun image() = xml.getAttributeValue(null, "img1")?.trim()?.takeIf { it.isNotEmpty() }
        forEachTag(xml) {
            when (xml.name) {
                "item" -> {
                    val key = componentKey(xml.getAttributeValue(null, "component"))
                    val drawable = xml.getAttributeValue(null, "drawable")?.trim()
                    if (key != null && !drawable.isNullOrEmpty()) icons.putIfAbsent(key, drawable)
                }
                "calendar" -> {
                    val key = componentKey(xml.getAttributeValue(null, "component"))
                    val prefix = xml.getAttributeValue(null, "prefix")?.trim()
                    if (key != null && !prefix.isNullOrEmpty()) calendars.putIfAbsent(key, prefix)
                }
                "iconback" -> back = back ?: image()
                "iconmask" -> mask = mask ?: image()
                "iconupon" -> upon = upon ?: image()
                "scale" -> scale = xml.getAttributeValue(null, "factor")?.toFloatOrNull() ?: scale
            }
        }
        return Filter(icons, calendars, if (back == null && mask == null && upon == null) null else Plate(back, mask, upon, scale))
    }

    internal fun compose(icon: Bitmap, back: Bitmap?, mask: Bitmap?, upon: Bitmap?, scale: Float, backColor: Int? = null): Bitmap {
        val size = icon.width
        val inset = (size * (1 - scale) / 2).roundToInt()
        val result = createBitmap(size, size)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val full = Rect(0, 0, size, size)
        canvas.drawBitmap(icon, null, Rect(inset, inset, size - inset, size - inset), paint)
        mask?.let {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            canvas.drawBitmap(it, null, full, paint)
        }
        back?.let {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER)
            paint.colorFilter = backColor?.let { color -> PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }
            canvas.drawBitmap(it, null, full, paint)
        }
        paint.xfermode = null
        paint.colorFilter = null
        upon?.let { canvas.drawBitmap(it, null, full, paint) }
        return result
    }

    private inline fun forEachTag(xml: XmlPullParser, block: () -> Unit) {
        var event = xml.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) block()
            event = xml.next()
        }
    }

    internal fun componentKey(component: String?): String? =
        component?.trim()?.removePrefix("ComponentInfo{")?.removeSuffix("}")?.let(ComponentName::unflattenFromString)?.flattenToString()

    private fun <T> withXml(resources: Resources, pack: String, file: String, block: (XmlPullParser) -> T): T? = runCatching {
        val xml = resources.getIdentifier(file, "xml", pack)
        val raw = resources.getIdentifier(file, "raw", pack)
        if (xml != 0) resources.getXml(xml).use(block)
        else (if (raw != 0) resources.openRawResource(raw) else resources.assets.open("$file.xml"))
            .use { stream -> block(Xml.newPullParser().also { it.setInput(stream, null) }) }
    }.getOrNull()
}
