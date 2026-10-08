package com.droiddeck.launcher.input

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Steam's touch configs, read and written where Steam keeps them.
 *
 * A touch config is a Steam Input config (`controller_mobile_touch`) like any other - bindings per
 * action set, synced to the account, shared as community configs - with one extra key,
 * `touch_layout`: where each on-screen control sits, per action set, as a hex-encoded
 * `CVirtualControllerLayouts` protobuf (steammessages_virtualcontroller.proto). Steam Link is
 * handed both by the client it streams from; here the client tells the device only the app and
 * action set it is on (SteamTouchDevice), so the config is found on disk: the file the client's
 * console log says it loaded for the touch controller, else the app's entry in
 * `configset_controller_mobile_touch.vdf`. With no layout in it, controls go where Steam Link puts
 * them by default. Saving a layout writes `touch_layout` into the app's autosave config, as the
 * client does when Steam Link saves one; Steam Cloud picks that file up like any autosave.
 * (docs/development/steam-touch-controller.md.)
 */
object SteamTouchConfig {
    private const val TAG = "SteamTouch"

    // EControllerElementType.
    const val THUMB = 0
    const val STEAM = 1
    const val JOYSTICK_LEFT = 2
    const val JOYSTICK_LEFT_BUTTON = 3
    const val JOYSTICK_RIGHT = 4
    const val JOYSTICK_RIGHT_BUTTON = 5
    const val DPAD = 6
    const val A = 7
    const val B = 8
    const val X = 9
    const val Y = 10
    const val SELECT = 11
    const val START = 12
    const val TRIGGER_LEFT = 13
    const val TRIGGER_RIGHT = 14
    const val BUMPER_LEFT = 15
    const val BUMPER_RIGHT = 16
    const val MACRO_0 = 17 // 17..24 = macros 0-7
    const val TRACKPAD_CENTER = 25
    const val TRACKPAD_LEFT = 26
    const val TRACKPAD_RIGHT = 27
    const val KEYBOARD = 28
    const val MACRO_1_FINGER = 30
    const val MACRO_2_FINGER = 31
    const val MAGNIFYING_GLASS = 29
    const val PASTE = 34

    /** Controls a layout may place though no binding asks for them (Steam Link's tray). */
    val OPTIONAL = setOf(PASTE)

    /** A control's place: centre as a fraction of the screen, and its scale. */
    data class Element(val type: Int, val visible: Boolean, val x: Float, val y: Float, val xScale: Float = 1f, val yScale: Float = 1f)

    data class Layout(val actionSet: Int, val elements: List<Element>, val color: FloatArray?, val version: Int?)

    /** A decoded `touch_layout`; [rest] is every other top-level field, written back untouched. */
    class Layouts(val layouts: List<Layout>, val rest: ByteArray) {
        fun forActionSet(id: Int): Layout? = layouts.firstOrNull { it.actionSet == id }

        /** The per-game options Steam Link keeps beside the layouts (CVirtualControllerLayouts
         *  fields 2-4): input mode, mouse mode, trackpad sensitivity. */
        val options: Options get() = Options.decode(rest)
    }

    /** EInputMode: 1 mouse, 2 controller, 3 both. EMouseMode: 2 absolute cursor (direct touch),
     *  3 touch, 4 relative (trackpad); 0 = not set. */
    data class Options(val inputMode: Int = INPUT_CONTROLLER, val mouseMode: Int = 0, val trackpadSensitivity: Float = 1f) {
        /** [rest] with these options in place of its own fields 2-4, the rest kept as it was. */
        fun encodeInto(rest: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            val p = Proto(rest)
            while (p.more()) {
                val start = p.pos
                val (field, wire) = p.key()
                p.skip(wire)
                if (field !in 2..4) out.write(rest, start, p.pos - start)
            }
            tag(out, 2, 0); varint(out, inputMode.toLong())
            if (mouseMode != 0) { tag(out, 3, 0); varint(out, mouseMode.toLong()) }
            tag(out, 4, 5); fixed(out, trackpadSensitivity)
            return out.toByteArray()
        }

        companion object {
            fun decode(rest: ByteArray): Options {
                var input = INPUT_CONTROLLER
                var mouse = 0
                var sensitivity = 1f
                val p = Proto(rest)
                while (p.more()) {
                    val (field, wire) = p.key()
                    when {
                        field == 2 && wire == 0 -> input = p.varint().toInt()
                        field == 3 && wire == 0 -> mouse = p.varint().toInt()
                        field == 4 && wire == 5 -> sensitivity = p.float()
                        else -> p.skip(wire)
                    }
                }
                return Options(input, mouse, sensitivity)
            }
        }
    }

    const val INPUT_MOUSE = 1
    const val INPUT_CONTROLLER = 2
    const val INPUT_BOTH = 3
    const val MOUSE_ABSOLUTE = 2
    const val MOUSE_TOUCH = 3
    const val MOUSE_RELATIVE = 4

    /** The config's action sets in preset order: id as the device is told (preset id + 1) and title. */
    fun actionSets(config: Config): List<Pair<Int, String>> {
        val mappings = config.mappings ?: return listOf(1 to "Default")
        val titles = mappings.child("actions")?.entries?.mapNotNull { (key, v) ->
            (v as? KeyValues.Node)?.let { key to (it.string("title") ?: key) }
        }?.toMap().orEmpty()
        val sets = mappings.children("preset").mapNotNull { preset ->
            val id = preset.string("id")?.toIntOrNull() ?: return@mapNotNull null
            val name = preset.string("name") ?: ""
            (id + 1) to (titles[name]?.takeUnless { it.startsWith("#") } ?: name.ifEmpty { "Set ${id + 1}" })
        }.sortedBy { it.first }
        return sets.ifEmpty { listOf(1 to "Default") }
    }

    /** A config as the overlay needs it. */
    class Config(
        val file: File?,
        val text: String?,
        val layouts: Layouts?,
        /** Bound controls per preset (action set id - 1). */
        val available: Map<Int, Set<Int>>,
        /** The parsed config, for its bindings (SteamTouchBindings). */
        val mappings: KeyValues.Node? = null,
    ) {
        /** The controls to show for [actionSet] (as the device is told it) and [layers]. */
        fun availableFor(actionSet: Int, layers: List<Int>): Set<Int> {
            val base = available[presetOf(actionSet)] ?: available.values.firstOrNull() ?: DEFAULT_SET
            return layers.fold(base) { set, layer -> set + (available[presetOf(layer)] ?: emptySet()) } + ALWAYS
        }
    }

    /** Big Picture's own config uses Steam's basic UI bindings: the default set. */
    private val DEFAULT_SET = setOf(DPAD, A, B, X, Y, SELECT, START)
    private val ALWAYS = setOf(STEAM, THUMB, KEYBOARD)

    /** The layout entry for the action set the device was told about (the client counts from 1,
     *  0 for an app with none). */
    fun layoutIdOf(actionSet: Int) = if (actionSet <= 0) 1 else actionSet
    private fun presetOf(actionSet: Int) = layoutIdOf(actionSet) - 1

    // ---- Where Steam keeps them ----

    fun steamRoot(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam")

    fun configDir(context: Context): File? {
        val base = File(steamRoot(context), "steamapps/common/Steam Controller Configs")
        val account = accountId(context)
        if (account != null) File(base, "$account/config").takeIf { it.isDirectory }?.let { return it }
        return base.listFiles()?.filter { it.name.all(Char::isDigit) && File(it, "config").isDirectory }
            ?.maxByOrNull { it.lastModified() }?.let { File(it, "config") }
    }

    /** The signed-in account (loginusers.vdf's MostRecent) as a 32-bit account id. */
    private fun accountId(context: Context): Long? = try {
        val users = KeyValues.parse(File(steamRoot(context), "config/loginusers.vdf").readText())
        val node = users.child("users")
        val entries = node?.entries?.mapNotNull { (k, v) -> (v as? KeyValues.Node)?.let { k to it } }.orEmpty()
        val chosen = entries.firstOrNull { it.second.string("MostRecent") == "1" } ?: entries.firstOrNull()
        chosen?.first?.toLongOrNull()?.minus(76561197960265728L)
    } catch (_: Exception) {
        null
    }

    /** The config the client has for app [appId] on the touch controller. Blocking: reads files. */
    fun load(context: Context, appId: Int): Config {
        val dir = configDir(context)
        // The app's own saved config is what the client loads when there is one (its configset
        // entry); otherwise what it logged loading - a template, an official or a workshop config.
        val file = dir?.let { autosaveConfig(it, appId) }
            ?: loggedConfig(context, appId)?.takeIf { isTouchConfig(it) }
            ?: dir?.let { configsetConfig(context, it, appId) }
        if (file == null) {
            Log.i(TAG, "steam touch: app $appId has no touch config on disk; default layout")
            return Config(null, null, null, emptyMap())
        }
        return try {
            val text = file.readText()
            val kv = KeyValues.parse(text.removePrefix("﻿"))
            val mappings = kv.child("controller_mappings") ?: kv
            val layouts = mappings.string("touch_layout")?.let { hex -> decodeLayouts(hexToBytes(hex)) }
            Log.i(TAG, "steam touch: app $appId uses ${file.path} (${layouts?.layouts?.size ?: 0} layouts)")
            Config(file, text, layouts, availability(mappings), mappings)
        } catch (e: Exception) {
            Log.w(TAG, "steam touch: ${file.path} unreadable: $e")
            Config(file, null, null, emptyMap())
        }
    }

    private fun isTouchConfig(file: File) = file.isFile &&
        file.bufferedReader().use { r -> generateSequence { r.readLine() }.take(40).any { it.contains("controller_mobile_touch") } }

    /** From the client's console log: the last config it loaded for [appId] on the touch controller
     *  (`Loaded Config for ... App ID <n>, Controller <i>: <path>`, where controller i reported
     *  type 43). Guest paths come back as the app's own. */
    private fun loggedConfig(context: Context, appId: Int): File? = try {
        val log = File(steamRoot(context), "logs/console_log.txt")
        val tail = RandomAccessFile(log, "r").use { raf ->
            val start = maxOf(0L, raf.length() - 2_000_000L)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            String(bytes)
        }
        val touchIndex = Regex("""!! Controller (\d+) attributes:\s*\n\s*Type: 43""").findAll(tail).lastOrNull()?.groupValues?.get(1)
        if (touchIndex == null) null else {
            val loaded = Regex("""Loaded Config for [^\n]*? Path for App ID $appId, Controller $touchIndex: ([^\n]+)""")
                .findAll(tail).lastOrNull()?.groupValues?.get(1)?.trim()
            loaded?.let { guestPath -> File(LinuxRuntime.rootDir(context), guestPath.trimStart('/').replace("//", "/")) }
        }
    } catch (_: Exception) {
        null
    }

    private fun autosaveConfig(dir: File, appId: Int): File? = try {
        val set = KeyValues.parse(File(dir, CONFIGSET).readText()).child("controller_config")?.child(appId.toString())
        if (set?.string("autosave") == "1") File(dir, "$appId/controller_mobile_touch.vdf").takeIf { it.isFile } else null
    } catch (_: Exception) {
        null
    }

    /** The app's entry in configset_controller_mobile_touch.vdf. */
    private fun configsetConfig(context: Context, dir: File, appId: Int): File? = try {
        val set = KeyValues.parse(File(dir, CONFIGSET).readText()).child("controller_config")?.child(appId.toString())
        when {
            set == null -> null
            set.string("autosave") == "1" -> File(dir, "$appId/controller_mobile_touch.vdf")
            set.string("template") != null -> File(steamRoot(context), "controller_base/templates/" + set.string("template"))
            else -> null
        }?.takeIf { it.isFile }
    } catch (_: Exception) {
        null
    }

    private const val CONFIGSET = "configset_controller_mobile_touch.vdf"

    // ---- What a config binds ----

    /** Per preset, the controls its active sources bind - what Steam Link shows (UpdateElementAvailability). */
    private fun availability(mappings: KeyValues.Node): Map<Int, Set<Int>> {
        val groups = mappings.children("group").associateBy({ it.string("id") ?: "" }) { group ->
            group.child("inputs")?.entries?.filter { (_, v) -> (v as? KeyValues.Node)?.let(::hasBinding) == true }
                ?.map { it.first }?.toSet().orEmpty()
        }
        return mappings.children("preset").associate { preset ->
            val id = preset.string("id")?.toIntOrNull() ?: 0
            val set = mutableSetOf<Int>()
            preset.child("group_source_bindings")?.entries?.forEach { (groupId, value) ->
                val parts = (value as? String)?.split(' ') ?: return@forEach
                if (parts.getOrNull(1) != "active" || parts.contains("modeshift")) return@forEach
                val bound = groups[groupId].orEmpty()
                when (parts[0]) {
                    "button_diamond" -> mapOf("button_a" to A, "button_b" to B, "button_x" to X, "button_y" to Y)
                        .forEach { (input, element) -> if (input in bound) set += element }
                    "dpad" -> set += DPAD
                    "joystick" -> { set += JOYSTICK_LEFT; if ("click" in bound) set += JOYSTICK_LEFT_BUTTON }
                    "right_joystick" -> { set += JOYSTICK_RIGHT; if ("click" in bound) set += JOYSTICK_RIGHT_BUTTON }
                    "left_trigger" -> set += TRIGGER_LEFT
                    "right_trigger" -> set += TRIGGER_RIGHT
                    "left_trackpad" -> set += TRACKPAD_LEFT
                    "right_trackpad" -> set += TRACKPAD_RIGHT
                    "center_trackpad" -> set += TRACKPAD_CENTER
                    "switch" -> bound.forEach { input ->
                        when (input) {
                            "button_escape" -> set += START
                            "button_menu" -> set += SELECT
                            "left_bumper" -> set += BUMPER_LEFT
                            "right_bumper" -> set += BUMPER_RIGHT
                            "button_macro_1finger" -> set += MACRO_1_FINGER
                            "button_macro_2finger" -> set += MACRO_2_FINGER
                            else -> Regex("button_macro(\\d)").matchEntire(input)?.let { set += MACRO_0 + it.groupValues[1].toInt() }
                        }
                    }
                }
            }
            id to set.toSet()
        }
    }

    private fun hasBinding(input: KeyValues.Node): Boolean =
        input.child("activators")?.entries?.any { (_, activator) ->
            (activator as? KeyValues.Node)?.child("bindings")?.entries?.any { (k, v) -> k == "binding" && (v as? String)?.isNotBlank() == true } == true
        } == true

    // ---- Steam Link's defaults ----

    /** Where Steam Link puts a control with no layout (CVirtualController::BInitializeDefaultElement,
     *  on a 1280x720 screen), and the same scheme for the controls its table leaves out, made room
     *  for when a config binds sticks. */
    fun defaultElement(type: Int, available: Set<Int>): Element? {
        val leftStick = JOYSTICK_LEFT in available
        val rightStick = JOYSTICK_RIGHT in available
        val faceY = if (rightStick) -0.30f else 0f
        val p: Pair<Float, Float> = when (type) {
            THUMB -> 75f / 1280 to 75f / 720
            STEAM -> 636f / 1280 to 75f / 720
            SELECT -> 516f / 1280 to 75f / 720
            START -> 756f / 1280 to 75f / 720
            KEYBOARD -> 1205f / 1280 to 75f / 720
            PASTE -> 1115f / 1280 to 75f / 720
            DPAD -> if (leftStick) 0.147f to 0.47f else 200f / 1280 to 525f / 720
            A -> 1083f / 1280 to 607f / 720 + faceY
            B -> 1163f / 1280 to 527f / 720 + faceY
            X -> 1003f / 1280 to 527f / 720 + faceY
            Y -> 1083f / 1280 to 447f / 720 + faceY
            JOYSTICK_LEFT -> 0.137f to 0.76f
            JOYSTICK_RIGHT -> 0.85f to 0.76f
            JOYSTICK_LEFT_BUTTON -> 0.29f to 0.88f
            JOYSTICK_RIGHT_BUTTON -> 0.71f to 0.88f
            BUMPER_LEFT -> 0.06f to 0.205f
            TRIGGER_LEFT -> 0.16f to 0.205f
            BUMPER_RIGHT -> 0.94f to 0.205f
            TRIGGER_RIGHT -> 0.84f to 0.205f
            TRACKPAD_LEFT -> 0.3f to 0.55f
            TRACKPAD_CENTER -> 0.5f to 0.55f
            TRACKPAD_RIGHT -> 0.7f to 0.55f
            MACRO_1_FINGER -> 0.42f to 0.92f
            MACRO_2_FINGER -> 0.58f to 0.92f
            in MACRO_0..MACRO_0 + 7 -> (0.3f + 0.057f * (type - MACRO_0)) to 0.22f
            else -> return null
        }
        return Element(type, true, p.first, p.second)
    }

    /** The controls to draw: the layout's own where it has one, Steam Link's defaults otherwise,
     *  only those the config binds. */
    fun elementsFor(config: Config, actionSet: Int, layers: List<Int>): List<Element> {
        val available = config.availableFor(actionSet, layers)
        val layout = config.layouts?.forActionSet(layoutIdOf(actionSet))
        val placed = layout?.elements?.associateBy { it.type }.orEmpty()
        val optional = OPTIONAL.filter { placed[it]?.visible == true && it !in available }
        return (available + optional).mapNotNull { type ->
            val own = placed[type]
            when {
                own != null && !own.visible -> null
                own != null -> own
                else -> defaultElement(type, available)
            }
        }.sortedBy { it.type }
    }

    fun layoutColor(config: Config, actionSet: Int): FloatArray? = config.layouts?.forActionSet(layoutIdOf(actionSet))?.color

    // ---- Saving ----

    /**
     * Saves [elements] as the layout of [actionSet] in app [appId]'s touch config: into the app's
     * autosave config (`<appid>/controller_mobile_touch.vdf`, made from the config in use when the
     * app has none) and selected for the app in the configset, as the client saves a layout Steam
     * Link sends it. Returns the file written, or null.
     */
    fun saveLayout(
        context: Context,
        appId: Int,
        config: Config,
        actionSet: Int,
        elements: List<Element>,
        color: FloatArray? = null,
        bindings: Map<SteamTouchBindings.Ref, (SteamTouchBindings.Binding) -> SteamTouchBindings.Binding> = emptyMap(),
    ): File? = saveLayouts(context, appId, config, mapOf(layoutIdOf(actionSet) to elements), color?.let { c -> setOf(layoutIdOf(actionSet)).associateWith { c } } ?: emptyMap(), bindings)

    /**
     * Saves the layouts of several action sets (by layout id) at once, with their colours, any
     * binding edits and the per-game [options], into the game's autosave touch config.
     */
    fun saveLayouts(
        context: Context,
        appId: Int,
        config: Config,
        sets: Map<Int, List<Element>>,
        colors: Map<Int, FloatArray> = emptyMap(),
        bindings: Map<SteamTouchBindings.Ref, (SteamTouchBindings.Binding) -> SteamTouchBindings.Binding> = emptyMap(),
        options: Options? = null,
    ): File? {
        val dir = configDir(context) ?: return null
        // Icons, labels and colours live in the binding strings (SteamTouchBindings), as the
        // configurator writes them.
        val source = bindings.entries.fold(config.text ?: return null) { text, (ref, change) ->
            SteamTouchBindings.rewrite(text, ref, change) ?: text
        }
        val target = File(dir, "$appId/controller_mobile_touch.vdf")
        val old = config.layouts
        val keep = old?.layouts?.filter { it.actionSet !in sets.keys }.orEmpty()
        val written = sets.map { (id, elements) ->
            Layout(id, elements, colors[id] ?: old?.forActionSet(id)?.color ?: floatArrayOf(1f, 1f, 1f, 0.4f), old?.forActionSet(id)?.version)
        }
        val rest = old?.rest ?: byteArrayOf(0x10, 0x02) // input_mode = controller
        val layouts = Layouts((keep + written).sortedBy { it.actionSet }, options?.encodeInto(rest) ?: rest)
        val hex = bytesToHex(encodeLayouts(layouts))
        val line = "\t\"touch_layout\"\t\t\"$hex\""
        val existing = Regex("""\n\s*"touch_layout"\s+"[0-9a-fA-F]*"""")
        val text = if (existing.containsMatchIn(source)) source.replace(existing, "\n$line")
        else source.replaceFirst(Regex("""("controller_type"\s+"controller_mobile_touch"[^\n]*)"""), "$1\n$line")
        if (!text.contains("touch_layout")) return null
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) return null
        selectAutosave(dir, appId)
        Log.i(TAG, "steam touch: saved layouts ${sets.keys} for app $appId to ${target.path}")
        return target
    }

    /** Points the app's configset entry at its autosave config. */
    private fun selectAutosave(dir: File, appId: Int) {
        val file = File(dir, CONFIGSET)
        val text = if (file.isFile) file.readText() else "\"controller_config\"\n{\n}\n"
        val entry = "\t\"$appId\"\n\t{\n\t\t\"autosave\"\t\t\"1\"\n\t}"
        val block = Regex("""\n\s*"$appId"\s*\{[^}]*\}""")
        val next = if (block.containsMatchIn(text)) text.replace(block, "\n$entry")
        else text.substring(0, text.lastIndexOf('}')).trimEnd() + "\n$entry\n}\n"
        File(file.path + ".tmp").apply { writeText(next) }.renameTo(file)
    }

    // ---- touch_layout: CVirtualControllerLayouts ----

    fun decodeLayouts(bytes: ByteArray): Layouts {
        val layouts = mutableListOf<Layout>()
        val rest = ByteArrayOutputStream()
        val p = Proto(bytes)
        while (p.more()) {
            val start = p.pos
            val (field, wire) = p.key()
            if (field == 1 && wire == 2) layouts += decodeLayout(p.bytes())
            else { p.skip(wire); rest.write(bytes, start, p.pos - start) }
        }
        return Layouts(layouts, rest.toByteArray())
    }

    private fun decodeLayout(bytes: ByteArray): Layout {
        var actionSet = 0
        var version: Int? = null
        var color: FloatArray? = null
        val elements = mutableListOf<Element>()
        val p = Proto(bytes)
        while (p.more()) {
            val (field, wire) = p.key()
            when {
                field == 1 && wire == 0 -> version = p.varint().toInt()
                field == 2 && wire == 0 -> actionSet = p.varint().toInt()
                field == 4 && wire == 2 -> elements += decodeElement(p.bytes())
                field == 5 && wire == 2 -> color = decodeColor(p.bytes())
                else -> p.skip(wire)
            }
        }
        return Layout(actionSet, elements, color, version)
    }

    private fun decodeElement(bytes: ByteArray): Element {
        var type = -1
        var visible = false
        var x = 0f
        var y = 0f
        var sx = 1f
        var sy = 1f
        val p = Proto(bytes)
        while (p.more()) {
            val (field, wire) = p.key()
            when {
                field == 1 && wire == 0 -> type = p.varint().toInt()
                field == 2 && wire == 0 -> visible = p.varint() != 0L
                field == 3 && wire == 5 -> x = p.float()
                field == 4 && wire == 5 -> y = p.float()
                field == 5 && wire == 5 -> sx = p.float()
                field == 6 && wire == 5 -> sy = p.float()
                else -> p.skip(wire)
            }
        }
        return Element(type, visible, x, y, sx, sy)
    }

    private fun decodeColor(bytes: ByteArray): FloatArray {
        val c = floatArrayOf(1f, 1f, 1f, 1f)
        val p = Proto(bytes)
        while (p.more()) {
            val (field, wire) = p.key()
            if (field in 1..4 && wire == 5) c[field - 1] = p.float() else p.skip(wire)
        }
        return c
    }

    fun encodeLayouts(layouts: Layouts): ByteArray {
        val out = ByteArrayOutputStream()
        layouts.layouts.forEach { layout ->
            val l = ByteArrayOutputStream()
            layout.version?.let { tag(l, 1, 0); varint(l, it.toLong()) }
            tag(l, 2, 0); varint(l, layout.actionSet.toLong())
            layout.elements.forEach { e ->
                val m = ByteArrayOutputStream()
                tag(m, 1, 0); varint(m, e.type.toLong())
                tag(m, 2, 0); varint(m, if (e.visible) 1 else 0)
                tag(m, 3, 5); fixed(m, e.x)
                tag(m, 4, 5); fixed(m, e.y)
                tag(m, 5, 5); fixed(m, e.xScale)
                tag(m, 6, 5); fixed(m, e.yScale)
                tag(l, 4, 2); bytes(l, m.toByteArray())
            }
            layout.color?.let { c ->
                val m = ByteArrayOutputStream()
                for (i in 0 until 4) { tag(m, i + 1, 5); fixed(m, c[i]) }
                tag(l, 5, 2); bytes(l, m.toByteArray())
            }
            tag(out, 1, 2); bytes(out, l.toByteArray())
        }
        out.write(layouts.rest)
        return out.toByteArray()
    }

    private fun tag(out: ByteArrayOutputStream, field: Int, wire: Int) = varint(out, (field shl 3 or wire).toLong())
    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7fL.inv() == 0L) { out.write(v.toInt()); return }
            out.write((v and 0x7f or 0x80).toInt())
            v = v ushr 7
        }
    }
    private fun fixed(out: ByteArrayOutputStream, f: Float) {
        val bits = java.lang.Float.floatToIntBits(f)
        for (i in 0 until 4) out.write(bits ushr (8 * i) and 0xff)
    }
    private fun bytes(out: ByteArrayOutputStream, b: ByteArray) { varint(out, b.size.toLong()); out.write(b) }

    private class Proto(val data: ByteArray) {
        var pos = 0
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (pos < data.size) {
                val b = data[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }
        fun float(): Float {
            var bits = 0
            for (i in 0 until 4) bits = bits or ((data[pos + i].toInt() and 0xff) shl (8 * i))
            pos += 4
            return java.lang.Float.intBitsToFloat(bits)
        }
        fun bytes(): ByteArray {
            val n = varint().toInt()
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt()
                5 -> pos += 4
                else -> pos = data.size
            }
        }
        fun more() = pos < data.size
        fun key(): Pair<Int, Int> = varint().toInt().let { (it ushr 3) to (it and 7) }
    }

    fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    fun bytesToHex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Valve's KeyValues text format, as far as controller configs use it. */
class KeyValues private constructor() {
    class Node(val entries: MutableList<Pair<String, Any>> = mutableListOf()) {
        fun child(key: String): Node? = entries.firstOrNull { it.first.equals(key, true) && it.second is Node }?.second as Node?
        fun children(key: String): List<Node> = entries.filter { it.first.equals(key, true) && it.second is Node }.map { it.second as Node }
        fun string(key: String): String? = entries.firstOrNull { it.first.equals(key, true) && it.second is String }?.second as String?
    }

    companion object {
        fun parse(text: String): Node {
            val tokens = tokenize(text)
            var i = 0
            fun node(): Node {
                val n = Node()
                while (i < tokens.size) {
                    val t = tokens[i++]
                    if (t == "}" ) return n
                    if (t == "{") continue
                    if (i >= tokens.size) break
                    val next = tokens[i]
                    if (next == "{") { i++; n.entries += t to node() } else { i++; n.entries += t to next }
                }
                return n
            }
            return node()
        }

        private fun tokenize(text: String): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && i + 1 < text.length && text[i + 1] == '/' -> while (i < text.length && text[i] != '\n') i++
                    c == '{' || c == '}' -> { out += c.toString(); i++ }
                    c == '"' -> {
                        val sb = StringBuilder()
                        i++
                        while (i < text.length && text[i] != '"') {
                            if (text[i] == '\\' && i + 1 < text.length) {
                                i++
                                sb.append(when (text[i]) { 'n' -> '\n'; 't' -> '\t'; else -> text[i] })
                            } else sb.append(text[i])
                            i++
                        }
                        i++
                        out += sb.toString()
                    }
                    c == '[' -> { while (i < text.length && text[i] != ']') i++; i++ }
                    else -> {
                        val start = i
                        while (i < text.length && !text[i].isWhitespace() && text[i] != '{' && text[i] != '}' && text[i] != '"') i++
                        out += text.substring(start, i)
                    }
                }
            }
            return out
        }
    }
}
