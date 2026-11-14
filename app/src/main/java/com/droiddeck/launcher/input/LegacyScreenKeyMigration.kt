package com.droiddeck.launcher.input

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import org.json.JSONObject

internal object LegacyScreenKeyMigration {
    const val BACKUP = "legacy-screen-keys-backup"
    const val MERGED = "legacy-screen-keys-merged"
    private const val SHOWN = "migrated.screenkeys.0"

    fun snapshot(context: Context): String? {
        val values = ControllerPrefs.prefs(context).all.filterKeys { it == "screenKeys" || it == "screenKeysShown" || it.startsWith("screenLayout.") }
        return values.takeIf { it.isNotEmpty() }?.let { JSONObject(it).toString() }
    }

    fun clean(context: Context) {
        val prefs = ControllerPrefs.prefs(context)
        prefs.edit().apply { prefs.all.keys.filter { it == "screenKeys" || it == "screenKeysShown" || it.startsWith("screenLayout.") }.forEach(::remove) }.apply()
    }

    @Suppress("DEPRECATION")
    fun migrate(context: Context, library: InputLibrary, raw: String): InputLibrary {
        val old = JSONObject(raw)
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics().also { if (display != null) display.getRealMetrics(it) else it.setTo(context.resources.displayMetrics) }
        val settings = ControllerPrefs.read(context)
        val style = InputSurfaceStyle(settings.tint, settings.opacity)
        val sets = buildList {
            old.optString("screenKeys").takeIf { it.isNotBlank() }?.let { add("Screen keys (imported)" to it) }
            old.keys().asSequence().filter { it.startsWith("screenLayout.") }.sorted().forEach { key ->
                add((key.removePrefix("screenLayout.").ifBlank { "Screen keys" } + " (imported)").take(80) to old.getString(key))
            }
        }
        var result = library
        for ((index, set) in sets.withIndex()) {
            val id = "migrated.screenkeys.$index"
            if (result.profiles.any { it.id == id }) continue
            val converted = convert(context, set.second, metrics.widthPixels, metrics.heightPixels, metrics.density)
            if (converted.isEmpty()) continue
            val layout = SavedInputLayout("$id.layout", set.first, converted.map { it.first.copy(alwaysShown = true) }, style = style, screen = InputLayoutScreen.MAIN,
                aspect = (metrics.widthPixels.coerceAtLeast(1).toFloat() / metrics.heightPixels.coerceAtLeast(1)).coerceIn(.05f, 20f))
            val bindings = SavedInputBindings("$id.bindings", set.first, false, layout.id, elements = converted.associate { it.first.id to it.second })
            val base = library.profile(library.steam)
            val profile = SavedInputProfile(id, set.first, base.base, base.parts + (InputRole.PRIMARY to InputPart(bindings.id, layout.id)))
            result = result.withLayout(layout).withPreset(bindings).withProfile(profile)
        }
        return result.validate()
    }

    fun merge(library: InputLibrary, raw: String): InputLibrary {
        val old = JSONObject(raw)
        if (!old.optBoolean("screenKeysShown", true) || old.optString("screenKeys").isBlank()) return library
        val keys = library.layouts.firstOrNull { it.id == "$SHOWN.layout" } ?: return library
        val actions = library.bindings.firstOrNull { it.id == "$SHOWN.bindings" }?.elements.orEmpty()
        var result = library
        for (part in listOf(false, true).map { library.activePart(it, InputRole.PRIMARY) }.distinct()) {
            val layout = result.layouts.firstOrNull { it.id == part.layout } ?: continue
            val preset = result.preset(part.bindings)
            if (!layout.editable || preset.layoutId != layout.id || layout.elements.any { e -> keys.elements.any { it.id == e.id } } ||
                layout.elements.size + keys.elements.size > 64) continue
            val added = keys.elements.mapNotNull { e -> actions[e.id]?.let { e.id to it } }.toMap()
            result = result.withLayout(layout.copy(elements = layout.elements + keys.elements))
                .withPreset(preset.copy(elements = added + preset.elements))
        }
        return result.validate()
    }

    fun pin(library: InputLibrary): InputLibrary {
        val keys = library.layouts.firstOrNull { it.id == "$SHOWN.layout" }?.elements.orEmpty()
        return library.copy(layouts = library.layouts.map { layout ->
            val imported = layout.id.startsWith("migrated.screenkeys.")
            layout.copy(elements = layout.elements.map { e ->
                val key = e.id.startsWith("imported:") && (imported || keys.any { it.id == e.id && it.kind == e.kind && it.name == e.name })
                if (key) e.copy(alwaysShown = true) else e
            })
        })
    }

    internal fun convert(context: Context, raw: String, width: Int, height: Int, density: Float): List<Pair<InputSurfaceElement, InputAction>> =
        convert(context, parse(raw), width, height, density)

    internal fun parse(raw: String): List<LegacyScreenKey> = raw.split(';').filter { it.isNotBlank() }.map { entry ->
        val parts = entry.split(':')
        require(parts.size >= 2) { "Invalid legacy screen key" }
        val codes = parts[0].split('+').map { it.toInt() }
        val position = parts[1].split(',').map { it.toFloat() }
        require(position.size == 3)
        fun text(at: Int) = parts.getOrNull(at).orEmpty().replace("%2C", ",").replace("%3A", ":").replace("%3B", ";").replace("%25", "%")
        LegacyScreenKey(codes, position[0], position[1], position[2], text(2), text(3), text(4),
            parts.getOrNull(5)?.toFloatOrNull() ?: 1f, parts.getOrNull(6)?.toIntOrNull() ?: 0, parts.getOrNull(7) == "1", parts.getOrNull(8) != "0")
    }

    internal fun convert(context: Context, keys: List<LegacyScreenKey>, width: Int, height: Int, density: Float): List<Pair<InputSurfaceElement, InputAction>> =
        keys.mapIndexed { index, key ->
            val codes = key.codes
            require(codes.all { it in 0..255 })
            require(listOf(key.x, key.y, key.size).all { it.isFinite() })
            val shape = key.shape
            val kind = when (shape) { 5 -> InputElementKind.DPAD; 6 -> InputElementKind.STICK; 4 -> InputElementKind.TRIGGER; else -> InputElementKind.BUTTON }
            val label = key.text.ifEmpty { key.glyph }.ifEmpty { codes.filter { it != 0 }.joinToString("+") { pcKeyLabel(context, it) } }.ifBlank { "Button" }.take(80)
            val image = key.image
            val icon = codes.singleOrNull()?.takeIf { InputButtonIcons.has(it) && key.text.isEmpty() && key.glyph.isEmpty() } ?: 0
            val heightPx = key.size.coerceIn(24f, 140f) * density
            val ratio = when { shape == 2 -> 1f; shape == 4 -> 1.7f; shape == 5 || shape == 6 || image.isNotEmpty() || icon != 0 -> 1.15f; else -> (.55f * label.length + 1.05f).coerceIn(1.7f, 4.6f) }
            val w = (heightPx * ratio / width.coerceAtLeast(1)).coerceIn(.03f, 1f)
            val h = (heightPx / height.coerceAtLeast(1)).coerceIn(.03f, 1f)
            val element = InputSurfaceElement(kind.control, (key.x - w / 2).coerceIn(0f, 1f - w), (key.y - h / 2).coerceIn(0f, 1f - h), w, h,
                id = "imported:$index", kind = kind, name = label, floating = false,
                toggle = key.toggle && kind != InputElementKind.DPAD && kind != InputElementKind.STICK,
                icon = icon, image = image, opacity = key.opacity.coerceIn(.2f, 1f),
                buttonShape = when (shape) { 0 -> InputButtonShape.PILL; 2 -> InputButtonShape.CIRCLE; 3 -> InputButtonShape.RECTANGLE; else -> InputButtonShape.ROUNDED },
                showLabel = key.labels).bounded()
            val action = when (kind) {
                InputElementKind.DPAD -> InputAction.Directions(codes.getOrElse(0) { 0 }, codes.getOrElse(1) { 0 }, codes.getOrElse(2) { 0 }, codes.getOrElse(3) { 0 })
                InputElementKind.STICK -> InputAction.Directions(codes.getOrElse(0) { 0 }, codes.getOrElse(2) { 0 }, codes.getOrElse(4) { 0 }, codes.getOrElse(6) { 0 }, listOf(1, 3, 5, 7).map { codes.getOrElse(it) { 0 } })
                else -> codes.filter { it != 0 }.distinct().let { if (it.isEmpty()) InputAction.Disabled else InputAction.Keys(it) }
            }
            require(action.validFor(element))
            element to action
        }
}

internal data class LegacyScreenKey(
    val codes: List<Int>, val x: Float, val y: Float, val size: Float,
    val glyph: String = "", val text: String = "", val image: String = "",
    val opacity: Float = 1f, val shape: Int = 0, val toggle: Boolean = false, val labels: Boolean = true,
)
