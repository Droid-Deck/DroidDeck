package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * What each touch control is bound to, and the icon it shows - Steam's, as its configurator keeps
 * them.
 *
 * A Steam Input binding is one string: `<action>, <label>, <icon>, <#foreground #background>`, e.g.
 * `xinput_button A, Jump, ghost_045_move_0400.png, #232323 #E4E4E4`. The icon is a file name looked
 * up as Steam does (steamui.so): the game's own `TouchMenuIcons` folder, then the binding icon
 * library `tenfoot/resource/images/library/controller/binding_icons`. Steam draws it filled with
 * the foreground colour through its own alpha (mask-image) on a button of the background colour.
 * Steam Link is sent these per control by the client it streams from; here they are read from the
 * game's touch config, and editing one writes the binding string back, as the configurator does.
 */
object SteamTouchBindings {

    /** Steam's binding icon colours (the configurator's palette, steamui sp.js), and its defaults. */
    val PALETTE = listOf(
        "#FFFFFF", "#E4E4E4", "#AAAAAA", "#787878", "#434343", "#222222", "#0000AD", "#0045AD", "#0074AD",
        "#00ADAD", "#33AD69", "#00AD3D", "#00AD00", "#48B119", "#74AD00", "#96AD00", "#ADA200", "#AD5D00",
        "#AD3A00", "#AD0000", "#AD0051", "#AD007F", "#AD00AD", "#6800AD", "#4800AD",
    )
    const val DEFAULT_FOREGROUND = "#232323"
    const val DEFAULT_BACKGROUND = "#E4E4E4"

    /** A parsed binding string. Empty strings for absent fields. */
    data class Binding(val action: String, val label: String, val icon: String, val foreground: String, val background: String) {
        fun compose(): String =
            if (icon.isEmpty() && foreground.isEmpty() && background.isEmpty()) "$action, $label, "
            else "$action, $label, $icon, ${listOf(foreground, background).filter { it.isNotEmpty() }.joinToString(" ")}"

        val hasIcon get() = icon.isNotEmpty()
    }

    fun parse(binding: String): Binding {
        val parts = binding.split(',').map { it.trim() }
        val colors = parts.getOrNull(3).orEmpty().split(' ').filter { it.startsWith("#") }
        return Binding(parts.getOrNull(0).orEmpty(), parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty(),
            colors.getOrNull(0).orEmpty(), colors.getOrNull(1).orEmpty())
    }

    /** Where a control's binding lives: its input in the group the preset puts on its source. */
    data class Ref(val groupId: String, val input: String)

    /** The source and input names a control reads (D-pad: its four directions). */
    private fun inputsOf(type: Int): List<Pair<String, String>> = when (type) {
        SteamTouchConfig.A -> listOf("button_diamond" to "button_a")
        SteamTouchConfig.B -> listOf("button_diamond" to "button_b")
        SteamTouchConfig.X -> listOf("button_diamond" to "button_x")
        SteamTouchConfig.Y -> listOf("button_diamond" to "button_y")
        SteamTouchConfig.SELECT -> listOf("switch" to "button_menu")
        SteamTouchConfig.START -> listOf("switch" to "button_escape")
        SteamTouchConfig.BUMPER_LEFT -> listOf("switch" to "left_bumper")
        SteamTouchConfig.BUMPER_RIGHT -> listOf("switch" to "right_bumper")
        SteamTouchConfig.TRIGGER_LEFT -> listOf("left_trigger" to "click")
        SteamTouchConfig.TRIGGER_RIGHT -> listOf("right_trigger" to "click")
        SteamTouchConfig.JOYSTICK_LEFT_BUTTON -> listOf("joystick" to "click")
        SteamTouchConfig.JOYSTICK_RIGHT_BUTTON -> listOf("right_joystick" to "click")
        SteamTouchConfig.MACRO_1_FINGER -> listOf("switch" to "button_macro_1finger")
        SteamTouchConfig.MACRO_2_FINGER -> listOf("switch" to "button_macro_2finger")
        SteamTouchConfig.DPAD -> DPAD_INPUTS.map { "dpad" to it }
        in SteamTouchConfig.MACRO_0..SteamTouchConfig.MACRO_0 + 7 -> listOf("switch" to "button_macro${type - SteamTouchConfig.MACRO_0}")
        else -> emptyList()
    }

    /** D-pad directions, in the order [refsFor] returns them: up, down, left, right. */
    val DPAD_INPUTS = listOf("dpad_north", "dpad_south", "dpad_west", "dpad_east")

    /**
     * Where control [type]'s bindings are for preset [preset] (the action set less one) and its
     * active [layers]: a layer's group wins over the base's for the same source.
     */
    fun refsFor(mappings: KeyValues.Node, type: Int, preset: Int, layers: List<Int> = emptyList()): List<Ref?> {
        val presets = mappings.children("preset").associateBy { it.string("id")?.toIntOrNull() ?: -1 }
        val stack = layers.reversed().mapNotNull { presets[it - 1] } + listOfNotNull(presets[preset] ?: presets.values.firstOrNull())
        return inputsOf(type).map { (source, input) ->
            stack.firstNotNullOfOrNull { p -> groupFor(p, source)?.let { Ref(it, input) } }
        }
    }

    private fun groupFor(preset: KeyValues.Node, source: String): String? =
        preset.child("group_source_bindings")?.entries?.firstOrNull { (_, v) ->
            val parts = (v as? String)?.split(' ') ?: return@firstOrNull false
            parts.getOrNull(0) == source && parts.getOrNull(1) == "active" && !parts.contains("modeshift")
        }?.first

    /** The binding at [ref]: its first activator's first binding (Full_Press when there is one). */
    fun bindingAt(mappings: KeyValues.Node, ref: Ref): Binding? {
        val group = mappings.children("group").firstOrNull { it.string("id") == ref.groupId } ?: return null
        val activators = group.child("inputs")?.child(ref.input)?.child("activators") ?: return null
        val activator = activators.child("Full_Press") ?: activators.entries.firstNotNullOfOrNull { it.second as? KeyValues.Node }
        return activator?.child("bindings")?.string("binding")?.let(::parse)
    }

    /** A short glyph for a binding with no icon or label: what it presses. */
    fun glyph(binding: Binding?): String? {
        val words = binding?.action?.split(' ') ?: return null
        val arg = words.getOrNull(1)?.uppercase() ?: return null
        return when (words[0]) {
            "xinput_button" -> XINPUT_GLYPHS[arg] ?: arg
            "key_press" -> KEY_GLYPHS[arg] ?: arg.take(4)
            "mouse_button" -> when (arg) { "LEFT" -> "LMB"; "RIGHT" -> "RMB"; "MIDDLE" -> "MMB"; else -> "M" + arg.take(2) }
            "mouse_wheel" -> if (arg.contains("UP")) "▲" else "▼"
            else -> null
        }
    }

    private val XINPUT_GLYPHS = mapOf(
        "SHOULDER_LEFT" to "LB", "SHOULDER_RIGHT" to "RB", "TRIGGER_LEFT" to "LT", "TRIGGER_RIGHT" to "RT",
        "JOYSTICK_LEFT" to "L3", "JOYSTICK_RIGHT" to "R3", "START" to "☰", "SELECT" to "⧉", "BACK" to "⧉",
        "DPAD_UP" to "▲", "DPAD_DOWN" to "▼", "DPAD_LEFT" to "◀", "DPAD_RIGHT" to "▶",
    )
    private val KEY_GLYPHS = mapOf(
        "RETURN" to "⏎", "SPACE" to "␣", "ESCAPE" to "Esc", "TAB" to "Tab", "BACKSPACE" to "⌫",
        "LEFT_SHIFT" to "⇧", "RIGHT_SHIFT" to "⇧", "LEFT_CONTROL" to "Ctrl", "RIGHT_CONTROL" to "Ctrl",
        "LEFT_ALT" to "Alt", "RIGHT_ALT" to "Alt", "UP_ARROW" to "↑", "DOWN_ARROW" to "↓",
        "LEFT_ARROW" to "←", "RIGHT_ARROW" to "→",
    )

    // ---- Writing a binding back ----

    /**
     * Rewrites every binding of [ref]'s input in config [text] (VDF) with [change], keeping the rest
     * of the file byte for byte. Returns the new text, or null when the input is not there.
     */
    fun rewrite(text: String, ref: Ref, change: (Binding) -> Binding): String? {
        val tokens = Vdf.tokens(text)
        val group = Vdf.blocks(tokens, 0, tokens.size, "group").firstOrNull { (s, e) ->
            Vdf.value(tokens, s, e, "id") == ref.groupId
        } ?: return null
        val inputs = Vdf.blocks(tokens, group.first, group.second, "inputs").firstOrNull() ?: return null
        val input = Vdf.blocks(tokens, inputs.first, inputs.second, ref.input).firstOrNull() ?: return null
        val targets = (input.first until input.second).filter { i ->
            tokens[i].text.equals("binding", true) && tokens[i].quoted && i + 1 < input.second && tokens[i + 1].quoted
        }.map { tokens[it + 1] }
        if (targets.isEmpty()) return null
        val out = StringBuilder(text)
        for (t in targets.sortedByDescending { it.start }) out.replace(t.start, t.end, change(parse(t.text)).compose())
        return out.toString()
    }

    /** A VDF tokenizer that keeps where each token sits, for edits that leave the rest untouched. */
    private object Vdf {
        class Token(val text: String, val quoted: Boolean, val start: Int, val end: Int)

        fun tokens(text: String): List<Token> {
            val out = mutableListOf<Token>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && i + 1 < text.length && text[i + 1] == '/' -> while (i < text.length && text[i] != '\n') i++
                    c == '{' || c == '}' -> { out += Token(c.toString(), false, i, i + 1); i++ }
                    c == '"' -> {
                        val start = i + 1
                        i++
                        while (i < text.length && text[i] != '"') { if (text[i] == '\\') i++; i++ }
                        out += Token(text.substring(start, minOf(i, text.length)), true, start, minOf(i, text.length))
                        i++
                    }
                    else -> {
                        val start = i
                        while (i < text.length && !text[i].isWhitespace() && text[i] != '{' && text[i] != '}' && text[i] != '"') i++
                        out += Token(text.substring(start, i), false, start, i)
                    }
                }
            }
            return out
        }

        /** The bodies (token index ranges inside the braces) of direct children named [key] of the
         *  block spanning [from, to). */
        fun blocks(tokens: List<Token>, from: Int, to: Int, key: String): List<Pair<Int, Int>> {
            val found = mutableListOf<Pair<Int, Int>>()
            var i = from
            var depth = 0
            while (i < to) {
                val t = tokens[i]
                if (t.text == "{" && !t.quoted) depth++
                else if (t.text == "}" && !t.quoted) depth--
                else if (depth == 0 && t.quoted && t.text.equals(key, true) && i + 1 < to && tokens[i + 1].text == "{" && !tokens[i + 1].quoted) {
                    val open = i + 1
                    var d = 0
                    var j = open
                    while (j < to) {
                        if (tokens[j].text == "{" && !tokens[j].quoted) d++
                        if (tokens[j].text == "}" && !tokens[j].quoted) { d--; if (d == 0) break }
                        j++
                    }
                    found += (open + 1) to j
                    i = j + 1
                    continue
                }
                i++
            }
            // The whole file is one top-level block ("controller_mappings"): look inside it too.
            if (found.isEmpty() && from == 0) {
                val top = tokens.indexOfFirst { it.text == "{" && !it.quoted }
                if (top > 0) {
                    var d = 0
                    var j = top
                    while (j < to) {
                        if (tokens[j].text == "{" && !tokens[j].quoted) d++
                        if (tokens[j].text == "}" && !tokens[j].quoted) { d--; if (d == 0) break }
                        j++
                    }
                    return blocks(tokens, top + 1, j, key)
                }
            }
            return found
        }

        /** A direct key-value of the block spanning [from, to). */
        fun value(tokens: List<Token>, from: Int, to: Int, key: String): String? {
            var depth = 0
            var i = from
            while (i < to) {
                val t = tokens[i]
                if (t.text == "{" && !t.quoted) depth++
                else if (t.text == "}" && !t.quoted) depth--
                else if (depth == 0 && t.quoted && t.text.equals(key, true) && i + 1 < to && tokens[i + 1].quoted) return tokens[i + 1].text
                i++
            }
            return null
        }
    }

    // ---- Icons ----

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<String>()

    fun libraryDir(context: Context) = File(SteamTouchConfig.steamRoot(context), "tenfoot/resource/images/library/controller/binding_icons")

    /** The game's own icons folder (`TouchMenuIcons` in its install directory), when it has one. */
    fun gameIconsDir(context: Context, appId: Int): File? = try {
        val manifest = File(SteamTouchConfig.steamRoot(context), "steamapps/appmanifest_$appId.acf")
        val installDir = KeyValues.parse(manifest.readText()).let { it.child("AppState") ?: it }.string("installdir")
        installDir?.let { File(SteamTouchConfig.steamRoot(context), "steamapps/common/$it/TouchMenuIcons") }?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    fun iconFile(context: Context, appId: Int, name: String): File? {
        if (name.isEmpty() || name.contains('/')) return null
        gameIconsDir(context, appId)?.let { File(it, name) }?.takeIf { it.isFile }?.let { return it }
        File(libraryDir(context), name).takeIf { it.isFile }?.let { return it }
        return File(SteamTouchConfig.steamRoot(context), "steamui/images/controller/$name").takeIf { it.isFile }
    }

    /** An icon, decoded at about [size] px. Blocking (reads a file); cached. */
    fun icon(context: Context, appId: Int, name: String, size: Int = 128): Bitmap? {
        val key = "$appId/$name/$size"
        cache.get(key)?.let { return it }
        if (key in missing) return null
        val file = iconFile(context, appId, name)
        val bitmap = file?.let {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(it.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size) sample *= 2
            BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (bitmap == null) missing += key else cache.put(key, bitmap)
        return bitmap
    }

    fun cachedIcon(appId: Int, name: String, size: Int = 128): Bitmap? = cache.get("$appId/$name/$size")

    /** Icon names for the picker: the game's own first, then Steam's library by category. */
    fun iconNames(context: Context, appId: Int): List<String> {
        val own = gameIconsDir(context, appId)?.list()?.filter { it.endsWith(".png", true) }?.sorted().orEmpty()
        val library = libraryDir(context).list()?.filter { it.endsWith(".png", true) && it != "special_blank.png" }?.sorted().orEmpty()
        return own + library.filter { it !in own }
    }
}
