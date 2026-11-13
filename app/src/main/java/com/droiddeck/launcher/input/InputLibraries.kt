package com.droiddeck.launcher.input

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object InputLibraries {
    private const val VERSION = 14
    private const val KEY = "library"
    private const val RECOVERY = "library-recovery."
    const val OVERLAY = "builtin.overlay"
    const val DECK = "builtin.deck"
    const val TRACKPAD = "builtin.trackpad"

    private fun prefs(context: Context) = context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE)

    @Synchronized
    fun read(context: Context): InputLibrary {
        val p = prefs(context)
        val raw = p.getString(KEY, null)
        val decoded = raw?.let(::decode)
        if (raw != null && decoded == null) return scopeLayouts(upgradeStockLayouts(upgradeOverlay(context, migrate(context))))
        var library = decoded ?: scopeLayouts(upgradeStockLayouts(upgradeOverlay(context, migrate(context))))
        val version = raw?.let { JSONObject(it).getInt("version") } ?: 0
        if (version < 5) library = scopeLayouts(upgradeStockLayouts(upgradeOverlay(context, library)))
        if (version < 11) library = neutralStockLayouts(library)
        if (version < 10) library = restyleGamepads(context, library)
        if (version < 12) library = barStockLayouts(library)
        if (version < 13) library = LegacyScreenKeyMigration.pin(library)
        if (version < 14) library = deckGamepadSurfaces(context, library)
        if (version < 6 || !p.contains(LegacyScreenKeyMigration.BACKUP)) {
            val legacy = LegacyScreenKeyMigration.snapshot(context)
            if (legacy != null) {
                val migrated = runCatching { LegacyScreenKeyMigration.migrate(context, library, legacy) }.getOrElse {
                    android.util.Log.e("InputLibraries", "Could not migrate screen keys; keeping the original settings", it)
                    return library
                }
                val merged = mergeScreenKeys(migrated, legacy)
                val upgraded = merged ?: migrated
                val encoded = encode(upgraded)
                require(encoded.length <= 2_000_000)
                val edit = p.edit().putString(KEY, encoded).putString(LegacyScreenKeyMigration.BACKUP, legacy)
                if (merged != null) edit.putBoolean(LegacyScreenKeyMigration.MERGED, true)
                if (!edit.commit()) return library
                LegacyScreenKeyMigration.clean(context)
                return upgraded
            }
        }
        val backup = p.getString(LegacyScreenKeyMigration.BACKUP, null)
        if (backup != null && !p.contains(LegacyScreenKeyMigration.MERGED)) {
            val merged = mergeScreenKeys(library, backup)
            if (merged != null) {
                val encoded = encode(merged)
                if (encoded.length <= 2_000_000 && p.edit().putString(KEY, encoded).putBoolean(LegacyScreenKeyMigration.MERGED, true).commit()) library = merged
            }
        }
        if (raw == null || version < VERSION) write(context, library)
        if (p.contains(LegacyScreenKeyMigration.BACKUP)) LegacyScreenKeyMigration.clean(context)
        return library
    }

    private fun mergeScreenKeys(library: InputLibrary, legacy: String): InputLibrary? = runCatching { LegacyScreenKeyMigration.merge(library, legacy) }
        .onFailure { android.util.Log.e("InputLibraries", "Could not show the imported screen keys with the gamepad", it) }.getOrNull()

    fun hasPendingScreenKeyMigration(context: Context) = !prefs(context).contains(LegacyScreenKeyMigration.BACKUP) && LegacyScreenKeyMigration.snapshot(context) != null

    fun hasLibraryRecovery(context: Context) = prefs(context).all.keys.any { it.startsWith(RECOVERY) }

    @Synchronized
    fun update(context: Context, change: (InputLibrary) -> InputLibrary): InputLibrary {
        val p = prefs(context)
        val stored = p.getString(KEY, null)
        val invalid = stored != null && decode(stored) == null
        require(!invalid || runCatching { JSONObject(stored!!).getInt("version") }.getOrNull()?.let { it <= VERSION } != false) {
            "The saved input library cannot be edited by this version."
        }
        val updated = change(read(context)).validate()
        if (invalid) {
            val encoded = encode(updated)
            require(encoded.length <= 2_000_000) { "The input library is too large to save." }
            check(p.edit().putString(RECOVERY + UUID.nameUUIDFromBytes(stored!!.toByteArray(Charsets.UTF_8)), stored)
                .putString(KEY, encoded).commit()) { "Could not save the input library and its recovery copy." }
        } else write(context, updated)
        return updated
    }

    private fun write(context: Context, library: InputLibrary) {
        val encoded = encode(library.validate())
        require(encoded.length <= 2_000_000) { "The input library is too large to save." }
        prefs(context).edit().putString(KEY, encoded).apply()
    }

    private fun migrate(context: Context): InputLibrary {
        val profiles = mutableListOf<SavedInputProfile>()
        val stock = InputStockLayouts.layouts()
        val layouts = (listOf(SavedInputLayout(OVERLAY, "Built-in gamepad", template = InputLayoutTemplate.OVERLAY)) + stock).toMutableList()
        val bindings = InputStockLayouts.presets(stock).toMutableList()
        for (id in listOf(InputProfile.GAME, InputProfile.DESKTOP)) {
            val old = InputProfiles.readLegacy(context, id)
            val name = if (id == InputProfile.GAME) "Game" else "Desktop"
            val legacySurface = InputSurfaceLayout.hasLegacy(context, id) ||
                old.bindings[InputRole.SECONDARY] != InputProfile.defaults(id).bindings[InputRole.SECONDARY]
            val layout = if (legacySurface) SavedInputLayout("$id.layout", "$name touch", InputSurfaceLayout.readLegacy(context, id)).also { layouts += it }
                else stock.first { it.id == if (id == InputProfile.DESKTOP) TRACKPAD else DECK }
            val parts = InputRole.entries.associateWith { role ->
                if (role == InputRole.SECONDARY && !legacySurface) {
                    val preset = bindings.first { it.layoutId == layout.id }
                    return@associateWith InputPart(preset.id, layout.id)
                }
                val preset = SavedInputBindings("$id.${role.name.lowercase()}", "$name / ${role.name.lowercase()}",
                    role == InputRole.PHYSICAL, if (role == InputRole.SECONDARY) layout.id else null,
                    old.bindings[role].orEmpty(), keys = if (role == InputRole.PHYSICAL) old.keys else emptyList())
                bindings += preset
                InputPart(preset.id, when (role) { InputRole.PHYSICAL -> null; InputRole.PRIMARY -> OVERLAY; InputRole.SECONDARY -> layout.id })
            }
            profiles += SavedInputProfile(id, name, id, parts)
        }
        val selected = prefs(context).getString("selected", InputProfiles.AUTO)
            ?.takeIf { it == InputProfiles.AUTO || profiles.any { p -> p.id == it } } ?: InputProfiles.AUTO
        return InputLibrary(profiles, layouts, bindings, selected).validate()
    }

    fun setAdaptiveSticks(context: Context, layoutId: String, on: Boolean): Boolean = runCatching {
        update(context) { library ->
            library.copy(layouts = library.layouts.map { layout ->
                if (layout.id != layoutId || !layout.editable) layout
                else layout.copy(elements = layout.elements.map { if (it.kind == InputElementKind.STICK) it.copy(floating = on, hideWhenIdle = on) else it })
            })
        }
    }.onSuccess { ControllerPrefs.setAdaptiveSticks(context, on) }.isSuccess

    fun setQuickButton(context: Context, layoutId: String, button: InputCommand, shown: Boolean): Boolean = runCatching {
        update(context) { library -> library.layouts.firstOrNull { it.id == layoutId && it.editable }?.let { library.withLayout(it.withQuickButton(button, shown)) } ?: library }
    }.isSuccess

    fun defaultPositions(context: Context, library: InputLibrary, layout: SavedInputLayout): List<InputSurfaceElement>? {
        val origin = layout.origin ?: layout.id
        InputStockLayouts.layouts().firstOrNull { it.id == origin }?.let { return it.elements }
        if (origin == OVERLAY || origin.startsWith("migrated.layout.")) {
            val (surfaceContext, metrics) = primaryDisplay(context)
            val size = if (origin == OVERLAY) listOf(metrics.widthPixels, metrics.heightPixels)
                else origin.removePrefix("migrated.layout.").split('x').mapNotNull { it.toIntOrNull() }
            return size.takeIf { it.size == 2 && it.all { side -> side > 0 } }?.let { LegacyGamepadLayout(surfaceContext, it[0], it[1]).elements() }
        }
        return library.layouts.firstOrNull { it.id == origin && it.id != layout.id }?.elements
    }

    @Suppress("DEPRECATION")
    internal fun primaryDisplay(context: Context): Pair<Context, DisplayMetrics> {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val surfaceContext = display?.let(context::createDisplayContext) ?: context
        return surfaceContext to DisplayMetrics().also { if (display != null) display.getRealMetrics(it) else it.setTo(context.resources.displayMetrics) }
    }

    private fun restyleGamepads(context: Context, library: InputLibrary): InputLibrary {
        if (library.layouts.none { it.id == OVERLAY || it.id.startsWith("migrated.layout.") }) return library
        val (surfaceContext, metrics) = primaryDisplay(context)
        return library.copy(layouts = library.layouts.map { layout ->
            val size = when {
                layout.id == OVERLAY -> listOf(metrics.widthPixels, metrics.heightPixels)
                layout.id.startsWith("migrated.layout.") -> layout.id.removePrefix("migrated.layout.").split('x').mapNotNull { it.toIntOrNull() }
                else -> emptyList()
            }
            if (size.size != 2 || size.any { it <= 0 }) layout
            else layout.copy(elements = LegacyGamepadLayout(surfaceContext, size[0], size[1]).restyle(layout.elements))
        })
    }

    private fun upgradeOverlay(context: Context, library: InputLibrary): InputLibrary {
        if (library.layouts.none { it.template == InputLayoutTemplate.OVERLAY }) return library
        val (surfaceContext, metrics) = primaryDisplay(context)
        val settings = ControllerPrefs.read(context)
        val elements = LegacyGamepadLayout(surfaceContext, metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1)).elements()
        val style = InputSurfaceStyle(settings.tint, settings.opacity)
        val replacements = library.layouts.filter { it.template == InputLayoutTemplate.OVERLAY }.associate { old ->
            old.id to old.copy(name = "Gamepad", elements = elements, template = InputLayoutTemplate.CUSTOM, style = style,
                aspect = (metrics.widthPixels.coerceAtLeast(1).toFloat() / metrics.heightPixels.coerceAtLeast(1)).coerceIn(.05f, 20f))
        }
        var result = library.copy(layouts = library.layouts.map { replacements[it.id] ?: it })
        val migrated = mutableMapOf<Pair<String, String>, String>()
        for (profile in library.profiles) {
            val parts = profile.parts.mapValues { (role, part) ->
                val layout = replacements[part.layout] ?: return@mapValues part
                val key = part.bindings to layout.id
                val id = migrated.getOrPut(key) {
                    val old = library.preset(part.bindings)
                    val newId = migrationBindingId(result, old.id, layout.id)
                    val keyboard = elements.filter { it.id == "builtin:keyboard" }.associate { it.id to InputAction.Command(InputCommand.KEYBOARD) }
                    val clicks = if (settings.stickClick) old.clicks else old.clicks + elements.filter { it.hasClick }.associate { it.id to InputAction.Disabled }
                    result = result.withPreset(old.copy(id = newId, layoutId = layout.id, elements = old.elements + keyboard, clicks = clicks))
                    newId
                }
                part.copy(bindings = id)
            }
            result = result.withProfile(profile.copy(parts = parts))
        }
        val oldBindings = migrated.keys.map { it.first }.toSet()
        result = result.copy(bindings = result.bindings.filter { it.id !in oldBindings || result.usersOfBindings(it.id).isNotEmpty() })
        // Older editor layouts were resolution-specific. Keep each as a selectable saved layout.
        for (key in ControllerPrefs.prefs(context).all.keys.filter { it.startsWith("layout.") }.sorted()) {
            val size = key.removePrefix("layout.").split('x').mapNotNull { it.toIntOrNull() }
            if (size.size != 2 || size.any { it <= 0 } || result.layouts.size >= 128) continue
            if (size[0] == metrics.widthPixels && size[1] == metrics.heightPixels) continue
            val id = "migrated.$key"
            if (result.layouts.none { it.id == id }) result = result.withLayout(SavedInputLayout(id, "Gamepad ${size[0]} × ${size[1]}",
                LegacyGamepadLayout(surfaceContext, size[0], size[1]).elements(), style = style, aspect = (size[0].toFloat() / size[1]).coerceIn(.05f, 20f)))
        }
        return result.validate()
    }

    private fun neutralStockLayouts(library: InputLibrary): InputLibrary = library.copy(layouts = library.layouts.map { layout ->
        if ((layout.id == DECK || layout.id == TRACKPAD) && layout.style.tint == ControllerPrefs.STEAM_BLUE) layout.copy(style = layout.style.copy(tint = null))
        else layout
    })

    private fun upgradeStockLayouts(library: InputLibrary): InputLibrary {
        val stock = InputStockLayouts.layouts()
        val replacements = library.layouts.filter { it.template == InputLayoutTemplate.DECK || it.template == InputLayoutTemplate.TRACKPAD }
            .associate { old -> old.id to stock.first { it.id == if (old.template == InputLayoutTemplate.DECK) DECK else TRACKPAD }.copy(id = old.id, name = old.name) }
        var result = library.copy(layouts = library.layouts.map { replacements[it.id] ?: it })
        val migrated = mutableMapOf<Pair<String, String>, String>()
        for (profile in library.profiles) {
            val parts = profile.parts.mapValues { (_, part) ->
                val layout = replacements[part.layout] ?: return@mapValues part
                val id = migrated.getOrPut(part.bindings to layout.id) {
                    val old = library.preset(part.bindings)
                    val id = migrationBindingId(result, old.id, layout.id)
                    val original = library.layouts.first { it.id == layout.id }
                    val stockLayout = layout.copy(id = if (original.template == InputLayoutTemplate.TRACKPAD) TRACKPAD else DECK)
                    result = result.withPreset(old.copy(id = id, layoutId = layout.id,
                        elements = InputStockLayouts.actions(stockLayout) + old.elements, clicks = InputStockLayouts.clicks(stockLayout) + old.clicks))
                    id
                }
                part.copy(bindings = id)
            }
            result = result.withProfile(profile.copy(parts = parts))
        }
        val oldBindings = migrated.keys.map { it.first }.toSet()
        result = result.copy(bindings = result.bindings.filter { it.id !in oldBindings || result.usersOfBindings(it.id).isNotEmpty() })
        return addStockLayouts(result)
    }

    private fun addStockLayouts(library: InputLibrary): InputLibrary {
        val stock = InputStockLayouts.layouts()
        var result = library
        for (layout in stock) if (result.layouts.none { it.id == layout.id }) result = result.withLayout(layout)
        for (preset in InputStockLayouts.presets(stock)) if (result.bindings.none { it.id == preset.id }) result = result.withPreset(preset)
        return result.validate()
    }

    private fun barStockLayouts(library: InputLibrary): InputLibrary {
        var result = library
        for (stock in InputStockLayouts.layouts().filter { it.id == DECK || it.id == TRACKPAD }) {
            val saved = result.layouts.firstOrNull { it.id == stock.id } ?: continue
            val (commands, rest) = saved.elements.partition { it.id.startsWith("command:") }
            val untouched = commands.isNotEmpty() && commands.all { it.control == InputControl.A && it.kind == InputElementKind.BUTTON } &&
                rest.size == stock.elements.size && rest.all { e -> stock.elements.any { s -> s.id == e.id && s.copy(y = e.y, height = e.height) == e &&
                    kotlin.math.abs(s.y + s.height - e.y - e.height) < 1e-3f } }
            if (untouched) result = result.withLayout(saved.copy(elements = stock.elements))
        }
        return addStockLayouts(result)
    }

    private fun deckGamepadSurfaces(context: Context, library: InputLibrary): InputLibrary {
        val leftPad = SessionPrefs.gamepadSurface(context, right = false) == SessionPrefs.SURFACE_TRACKPAD
        val rightStick = SessionPrefs.gamepadSurface(context, right = true) == SessionPrefs.SURFACE_STICK
        if (!leftPad && !rightStick) return library
        val saved = library.layouts.firstOrNull { it.id == DeckPanel.GAMEPAD.layout } ?: return library
        if (saved.elements != InputStockLayouts.gamepad(leftPad = false, rightStick = false)) return library
        return library.withLayout(saved.copy(elements = InputStockLayouts.gamepad(leftPad, rightStick))).validate()
    }

    private fun migrationBindingId(library: InputLibrary, binding: String, layout: String): String {
        val seed = JSONArray(listOf(binding, layout)).toString().toByteArray(Charsets.UTF_8)
        val base = "migrated.bindings." + UUID.nameUUIDFromBytes(seed)
        var id = base
        var suffix = 0
        while (library.bindings.any { it.id == id }) id = "$base.${++suffix}"
        return id
    }

    private fun scopeLayouts(library: InputLibrary): InputLibrary = library.copy(layouts = library.layouts.map { layout ->
        val roles = library.profiles.flatMap { p -> p.parts.filterValues { it.layout == layout.id }.keys } +
            library.selections.values.flatMap { s -> s.parts.filterValues { library.preset(it).layoutId == layout.id }.keys }
        val screen = when {
            InputRole.PRIMARY in roles && InputRole.SECONDARY in roles -> InputLayoutScreen.BOTH
            InputRole.PRIMARY in roles -> InputLayoutScreen.MAIN
            InputRole.SECONDARY in roles -> InputLayoutScreen.SECONDARY
            layout.id == OVERLAY || layout.id.startsWith("migrated.layout.") -> InputLayoutScreen.MAIN
            else -> InputLayoutScreen.SECONDARY
        }
        layout.copy(screen = screen)
    })

    internal fun encode(library: InputLibrary): String {
        val layouts = JSONArray()
        for (layout in library.layouts) {
            val elements = JSONArray()
            for (e in layout.elements) elements.put(JSONObject().put("id", e.id).put("kind", e.kind.name)
                .put("control", e.control.id).put("name", e.name).put("x", e.x.toDouble()).put("y", e.y.toDouble())
                .put("width", e.width.toDouble()).put("height", e.height.toDouble()).put("floating", e.floating).put("hideWhenIdle", e.hideWhenIdle).put("mouseGestures", e.mouseGestures).put("toggle", e.toggle).put("icon", e.icon).put("image", e.image)
                .put("elementOpacity", e.opacity.toDouble()).put("buttonShape", e.buttonShape.name).put("showLabel", e.showLabel).put("alwaysShown", e.alwaysShown).put("builtIn", e.builtIn))
            layouts.put(JSONObject().put("id", layout.id).put("name", layout.name).put("template", layout.template.name).put("elements", elements)
                .put("tint", layout.style.tint ?: JSONObject.NULL).put("opacity", layout.style.opacity).put("screen", layout.screen.name).put("aspect", layout.aspect.toDouble()).put("scale", layout.scale.toDouble())
                .put("hidden", JSONArray(layout.style.hidden.map { it.name }.sorted())).put("origin", layout.origin ?: JSONObject.NULL))
        }
        val bindings = JSONArray()
        for (b in library.bindings) {
            val controls = JSONObject(); b.controls.forEach { (c, a) -> controls.put(c.id, InputProfiles.encode(a)) }
            val elements = JSONObject(); b.elements.forEach { (id, a) -> elements.put(id, InputProfiles.encode(a)) }
            val clicks = JSONObject(); b.clicks.forEach { (id, a) -> clicks.put(id, InputProfiles.encode(a)) }
            val keys = JSONArray()
            b.keys.forEach { binding -> keys.put(JSONObject().put("device", binding.key.device).put("name", binding.key.name)
                .put("keyCode", binding.key.keyCode).put("scanCode", binding.key.scanCode).put("action", InputProfiles.encode(binding.action))) }
            bindings.put(JSONObject().put("id", b.id).put("name", b.name).put("physical", b.physical)
                .put("layout", b.layoutId).put("controls", controls).put("elements", elements).put("keys", keys).put("clicks", clicks))
        }
        val profiles = JSONArray()
        for (p in library.profiles) {
            val parts = JSONObject()
            p.parts.forEach { (role, part) -> parts.put(role.name, JSONObject().put("bindings", part.bindings).put("layout", part.layout)) }
            profiles.put(JSONObject().put("id", p.id).put("name", p.name).put("base", p.base).put("parts", parts))
        }
        val selections = JSONObject()
        for ((mode, selection) in library.selections) {
            val parts = JSONObject()
            selection.parts.forEach { (role, id) -> parts.put(role.name, id) }
            selections.put(mode, JSONObject().put("profile", selection.profile).put("parts", parts).put("profileSelected", selection.profileSelected))
        }
        return JSONObject().put("selections", selections).put("version", VERSION).put("selected", library.selected).put("steam", library.steam).put("desktop", library.desktop)
            .put("profiles", profiles).put("layouts", layouts).put("bindings", bindings).toString()
    }

    internal fun decode(raw: String): InputLibrary? = runCatching {
        require(raw.length <= 2_000_000)
        val doc = JSONObject(raw); require(doc.getInt("version") in 2..VERSION)
        val layouts = doc.getJSONArray("layouts").objects(256).map { layout ->
            val elements = layout.getJSONArray("elements").objects(64).map { e ->
                val control = requireNotNull(InputControl.fromId(e.getString("control")))
                InputSurfaceElement(control, e.getDouble("x").toFloat(),
                    e.getDouble("y").toFloat(), e.getDouble("width").toFloat(), e.getDouble("height").toFloat(),
                    e.getString("id"), InputElementKind.valueOf(e.getString("kind")), e.getString("name"),
                    e.optBoolean("floating", true), e.optBoolean("hideWhenIdle", false), e.optBoolean("mouseGestures", false), e.optBoolean("toggle", false), e.optInt("icon", 0), e.optString("image", ""),
                    e.optDouble("elementOpacity", 1.0).toFloat(), InputButtonShape.valueOf(e.optString("buttonShape", InputButtonShape.ROUNDED.name)), e.optBoolean("showLabel", true), e.optBoolean("alwaysShown", false),
                    e.optBoolean("builtIn", isBuiltInId(e.getString("id"), control)))
            }
            SavedInputLayout(layout.getString("id"), layout.getString("name"), elements, InputLayoutTemplate.valueOf(layout.getString("template")),
                InputSurfaceStyle(if (!layout.has("tint")) ControllerPrefs.STEAM_BLUE else if (layout.isNull("tint")) null else layout.getInt("tint"), layout.optInt("opacity", 100),
                    layout.optJSONArray("hidden")?.let { hidden -> (0 until hidden.length()).map { InputCommand.valueOf(hidden.getString(it)) }.toSet() }.orEmpty()),
                InputLayoutScreen.valueOf(layout.optString("screen", InputLayoutScreen.BOTH.name)), layout.optDouble("aspect", 0.0).toFloat(), layout.optionalString("origin"),
                    layout.optDouble("scale", 1.0).toFloat())
        }
        val bindings = doc.getJSONArray("bindings").objects(512).map { b ->
            val controls = b.getJSONObject("controls").actions(28).mapKeys { requireNotNull(InputControl.fromId(it.key)) }
            val keys = b.getJSONArray("keys").objects(64).map { k -> PhysicalInputBinding(
                PhysicalInputKey(k.getString("device"), k.getString("name"), k.getInt("keyCode"), k.getInt("scanCode")),
                requireNotNull(InputProfiles.decode(k.getJSONObject("action")))) }
            SavedInputBindings(b.getString("id"), b.getString("name"), b.getBoolean("physical"), b.optionalString("layout"),
                controls, b.getJSONObject("elements").actions(64), keys, b.optJSONObject("clicks")?.actions(64).orEmpty())
        }
        val profiles = doc.getJSONArray("profiles").objects(64).map { p ->
            val parts = p.getJSONObject("parts")
            SavedInputProfile(p.getString("id"), p.getString("name"), p.getString("base"), InputRole.entries.associateWith { role ->
                val part = parts.getJSONObject(role.name); InputPart(part.getString("bindings"), part.optionalString("layout"))
            })
        }
        val selections = doc.optJSONObject("selections")?.let { saved ->
            require(saved.length() <= 2)
            saved.keys().asSequence().associateWith { mode ->
                val value = saved.getJSONObject(mode)
                val parts = value.getJSONObject("parts")
                require(parts.length() <= InputRole.entries.size)
                val profile = value.getString("profile")
                InputSelection(profile, parts.keys().asSequence().associate { role -> InputRole.valueOf(role) to parts.getString(role) },
                    value.optBoolean("profileSelected", profile != InputProfiles.AUTO || parts.length() == 0))
            }
        }.orEmpty()
        InputLibrary(profiles, layouts, bindings, doc.getString("selected"), doc.getString("steam"), doc.getString("desktop"), selections).validate()
    }.getOrNull()

    private fun JSONArray.objects(limit: Int): List<JSONObject> {
        require(length() <= limit)
        return (0 until length()).map { getJSONObject(it) }
    }

    private fun JSONObject.actions(limit: Int): Map<String, InputAction> {
        require(length() <= limit)
        return keys().asSequence().associateWith { requireNotNull(InputProfiles.decode(getJSONObject(it))) }
    }

    private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else getString(key)
}
