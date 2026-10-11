package com.droiddeck.launcher.input

import android.content.Context
import android.os.Environment
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * How one kind of physical controller is treated: its stick dead zone and any key it reports under
 * another name. It matches by USB/Bluetooth vendor and product id, by a piece of the device name,
 * or both; a profile that names neither matches nothing, so a typo cannot catch every pad.
 *
 * Anything a profile leaves out follows the global setting (Setup › Controller).
 */
class ControllerProfile(
    val name: String,
    val vendorId: Int = ANY,
    val productId: Int = ANY,
    val nameContains: String? = null,
    /** 0..[ControllerProfiles.MAX_DEAD_ZONE] of the stick's travel, or null for the global setting. */
    val deadZone: Float? = null,
    /** Android key code the pad reports → the key code it is treated as. */
    val keyMap: Map<Int, Int> = emptyMap(),
) {
    private val hasIds get() = vendorId != ANY || productId != ANY
    private val hasName get() = !nameContains.isNullOrBlank()

    /** 0 when this profile does not match the device; higher is more specific. */
    fun specificity(vendor: Int, product: Int, deviceName: String?): Int {
        if (!hasIds && !hasName) return 0
        if (vendorId != ANY && vendorId != vendor) return 0
        if (productId != ANY && productId != product) return 0
        if (hasName && deviceName?.contains(nameContains!!, ignoreCase = true) != true) return 0
        return (if (hasIds) 2 else 0) + (if (hasName) 1 else 0)
    }

    companion object {
        const val ANY = -1
    }
}

/** A device's profile folded into what the input path reads per event. */
class ResolvedController(val profile: ControllerProfile?, val deadZone: Float, private val keyMap: Map<Int, Int>) {
    fun key(keyCode: Int): Int = keyMap[keyCode] ?: keyCode
}

/**
 * The profiles in force, and the lookup the pad bridge makes for each controller.
 *
 * Profiles are read from two files, the first match winning, so a person can share one by dropping
 * it in Downloads: `Download/droiddeck-controller-profiles.json`, then
 * `files/controller-profiles.json` in the app's data folder. The format is
 *
 *     {"profiles": [{"name": "My pad", "vendor": "0x045e", "product": "0x028e",
 *                    "nameContains": "pad", "deadZone": 0.06,
 *                    "keys": {"BUTTON_MODE": "BUTTON_SELECT"}}]}
 *
 * Every field but `name` is optional. The log prints a ready-made entry when a controller connects.
 */
object ControllerProfiles {
    const val FILE_NAME = "controller-profiles.json"
    const val DOWNLOAD_NAME = "droiddeck-controller-profiles.json"
    const val DEFAULT_DEAD_ZONE = 0.12f
    const val MAX_DEAD_ZONE = 0.5f

    @Volatile private var profiles: List<ControllerProfile> = emptyList()
    @Volatile private var globalDeadZone = DEFAULT_DEAD_ZONE
    private val cache = ConcurrentHashMap<Int, ResolvedController>()
    private val plain get() = ResolvedController(null, globalDeadZone, emptyMap())

    /** Reads the global dead zone and the profile files again; the next event of each pad sees them. */
    @JvmStatic fun reload(context: Context) {
        globalDeadZone = ControllerPrefs.deadZone(context) / 100f
        profiles = runCatching { loadFiles(context) }.getOrDefault(emptyList())
        cache.clear()
    }

    @JvmStatic fun forget(deviceId: Int) {
        cache.remove(deviceId)
    }

    /** The treatment for this controller; one map lookup per event after the first. */
    @JvmStatic fun resolve(device: InputDevice?): ResolvedController {
        if (device == null) return plain
        cache[device.id]?.let { return it }
        val found = find(profiles, device.vendorId, device.productId, device.name)
        val resolved = ResolvedController(found, found?.deadZone ?: globalDeadZone, found?.keyMap.orEmpty())
        cache[device.id] = resolved
        return resolved
    }

    /** The most specific profile that matches; the earlier one on a tie. */
    fun find(list: List<ControllerProfile>, vendor: Int, product: Int, deviceName: String?): ControllerProfile? {
        var best: ControllerProfile? = null
        var bestScore = 0
        for (profile in list) {
            val score = profile.specificity(vendor, product, deviceName)
            if (score > bestScore) {
                best = profile
                bestScore = score
            }
        }
        return best
    }

    private fun loadFiles(context: Context): List<ControllerProfile> {
        val shared = File(Environment.getExternalStorageDirectory(), "Download/$DOWNLOAD_NAME")
        val own = File(context.filesDir, FILE_NAME)
        return listOf(shared, own).filter { it.isFile }.flatMap { file ->
            runCatching { parse(file.readText()) }.getOrDefault(emptyList())
        }
    }

    /** Tolerant: a malformed entry is skipped, a malformed file is empty. */
    fun parse(text: String): List<ControllerProfile> {
        val array = runCatching {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("profiles")
        }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::parseEntry) }
    }

    private fun parseEntry(o: JSONObject): ControllerProfile? {
        val name = o.optString("name").trim().ifEmpty { return null }
        val keys = o.optJSONObject("keys")
        val keyMap = HashMap<Int, Int>()
        if (keys != null) {
            for (from in keys.keys()) {
                val a = keyCode(from)
                val b = keyCode(keys.optString(from))
                if (a != KeyEvent.KEYCODE_UNKNOWN && b != KeyEvent.KEYCODE_UNKNOWN) keyMap[a] = b
            }
        }
        val deadZone = if (o.has("deadZone")) o.optDouble("deadZone", Double.NaN).takeIf { !it.isNaN() }
            ?.toFloat()?.coerceIn(0f, MAX_DEAD_ZONE) else null
        return ControllerProfile(
            name = name,
            vendorId = id(o.opt("vendor")),
            productId = id(o.opt("product")),
            nameContains = o.optString("nameContains").trim().ifEmpty { null },
            deadZone = deadZone,
            keyMap = keyMap,
        )
    }

    /** "0x045e", "1118" or 1118; anything else is "any". */
    private fun id(value: Any?): Int {
        val parsed: Int? = when (value) {
            is Number -> value.toInt()
            is String -> value.trim().let { s ->
                if (s.startsWith("0x", ignoreCase = true)) s.substring(2).toIntOrNull(16) else s.toIntOrNull()
            }
            else -> null
        }
        return parsed?.takeIf { it >= 0 } ?: ControllerProfile.ANY
    }

    /** "BUTTON_MODE", "KEYCODE_BUTTON_MODE" or a number. */
    private fun keyCode(name: String): Int {
        val s = name.trim().uppercase(Locale.ROOT)
        return s.toIntOrNull()
            ?: KeyEvent.keyCodeFromString(if (s.startsWith("KEYCODE_")) s else "KEYCODE_$s")
    }

    fun toJson(list: List<ControllerProfile>): String {
        val array = JSONArray()
        for (p in list) {
            val o = JSONObject().put("name", p.name)
            if (p.vendorId != ControllerProfile.ANY) o.put("vendor", hex(p.vendorId))
            if (p.productId != ControllerProfile.ANY) o.put("product", hex(p.productId))
            p.nameContains?.let { o.put("nameContains", it) }
            p.deadZone?.let { o.put("deadZone", it.toString().toDouble()) }
            if (p.keyMap.isNotEmpty()) {
                val keys = JSONObject()
                for ((a, b) in p.keyMap) keys.put(KeyEvent.keyCodeToString(a).removePrefix("KEYCODE_"), KeyEvent.keyCodeToString(b).removePrefix("KEYCODE_"))
                o.put("keys", keys)
            }
            array.put(o)
        }
        return JSONObject().put("profiles", array).toString(2)
    }

    private fun hex(v: Int) = String.format(Locale.ROOT, "0x%04x", v)

    private val reportedKeys = intArrayOf(
        KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
    )

    /**
     * What a controller reports, for the log: the buttons it declares and each axis's range, flat
     * (the dead zone the pad itself asks for) and fuzz, then an entry to start a profile from. A
     * report with this in it is enough to write a profile for a pad nobody here owns.
     */
    @JvmStatic fun describe(device: InputDevice): String {
        val has = device.hasKeys(*reportedKeys)
        val keys = reportedKeys.indices.filter { has[it] }.joinToString(",") { KeyEvent.keyCodeToString(reportedKeys[it]).removePrefix("KEYCODE_") }
        val axes = device.motionRanges.joinToString("; ") { r ->
            String.format(Locale.ROOT, "%s[%.2f..%.2f flat %.3f fuzz %.3f]", MotionEvent.axisToString(r.axis).removePrefix("AXIS_"), r.min, r.max, r.flat, r.fuzz)
        }
        val template = toJson(listOf(ControllerProfile(device.name, device.vendorId, device.productId, deadZone = DEFAULT_DEAD_ZONE)))
            .replace(Regex("\\s+"), " ")
        return "keys [$keys]; axes [$axes]; profile entry $template"
    }
}
