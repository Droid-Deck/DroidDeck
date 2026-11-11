package com.droiddeck.launcher.hud

import android.content.Context
import com.droiddeck.launcher.session.SessionPrefs

internal object DroidDeckHudConfig {
    val DEFAULTS: Map<String, String> = linkedMapOf(
        "hudSize" to "minimal", "hudLocked" to "1", "hudSnap" to "1", "hudScale" to "100", "hudDock" to "none", "hudOpacity" to "80", "hudColor" to "mid",
        "hudOutline" to "40", "hudOutlineAccent" to "1",
        "showGpuModel" to "1", "showGPULoad" to "1", "showCPUUsage" to "1", "showCPULoad" to "1",
        "showPerCore" to "1", "showTemp" to "1", "showBatteryTemp" to "1", "showGpuTemp" to "0",
        "showVram" to "1", "showRAM" to "1", "showSwap" to "1", "showBattery" to "0", "showPower" to "1",
        "showFPS" to "1", "showFPSGraph" to "1", "fpsDecimal" to "1",
        "showLatency" to "1", "showAvg" to "1", "showLow1" to "1", "showLow01" to "1", "showLow001" to "1",
        "showEngine" to "1", "showRenderer" to "1",
        "showDisplay" to "1", "showHdr" to "1", "showNet" to "1", "showResolution" to "1",
        "showProton" to "1", "showSession" to "1", "showClock" to "1", "hudOrder" to "",
        "tempUnit" to "c", "tempBands" to "1", "tempAuto" to "1", "tempRedCpu" to "90", "tempRedGpu" to "90", "tempRedBat" to "48",
    )

    private val NOT_READINGS = setOf("showCPULoad", "showRenderer", "fpsDecimal")

    /** Orders saved before the GPU switch was named after the alias the HUD actually reads. */
    private val RENAMED = mapOf("showGPU" to "showGPULoad")

    fun load(context: Context): MutableMap<String, String> {
        val values = LinkedHashMap(DEFAULTS)
        val stored = SessionPrefs.droidDeckHudConfig(context)
        if (stored == null) {
            values["showFPS"] = flag(SessionPrefs.hudShowFps(context))
            values["showFPSGraph"] = flag(SessionPrefs.hudShowGraph(context))
            values["hudOpacity"] = SessionPrefs.hudOpacity(context).toString()
        } else {
            for (entry in KeyValueSet(stored)) if (entry.size == 2 && entry[0] in DEFAULTS) values[entry[0]] = entry[1]
        }
        return values
    }

    fun encode(values: Map<String, String>): String = values.entries.joinToString(",") { "${it.key}=${it.value}" }

    fun set(context: Context, key: String, value: String): Map<String, String> {
        val values = load(context)
        if (key !in DEFAULTS) return values
        values[key] = value
        SessionPrefs.setDroidDeckHudConfig(context, encode(values))
        return values
    }

    /** Writes several switches and the row order in one go, so the HUD is rebuilt once. */
    fun choose(context: Context, switches: Map<String, Boolean>, order: List<String>): Map<String, String> {
        val values = load(context)
        for ((key, on) in switches) if (key in DEFAULTS) {
            values[key] = flag(on)
        }
        val size = DroidDeckHudSize.from(values["hudSize"])
        val groups = sizeOrders(values).toMutableMap()
        if (groups.isEmpty()) {
            val legacy = legacyOrder(values)
            if (legacy.isNotEmpty()) for (each in DroidDeckHudSize.values()) groups[each] = legacy.filter { it in each.rows }
        }
        groups[size] = order.filter { it in ORDER }
        values["hudOrder"] = encodeOrders(groups)
        SessionPrefs.setDroidDeckHudConfig(context, encode(values))
        return values
    }

    fun clearOrder(context: Context): Map<String, String> {
        val values = load(context)
        values["hudOrder"] = ""
        SessionPrefs.setDroidDeckHudConfig(context, encode(values))
        return values
    }

    fun reset(context: Context): Map<String, String> {
        SessionPrefs.setDroidDeckHudConfig(context, encode(DEFAULTS))
        return DEFAULTS
    }

    /** Every metric switch, in the order the HUD lists them before the user moves any. Derived from
     *  [DEFAULTS], so a switch added there shows up here without a second list to keep in step. */
    val ORDER: List<String> = DEFAULTS.keys.filter { it.startsWith("show") && it !in NOT_READINGS }

    /** Only the rows the user moved for [size], in the order they left them. */
    fun storedOrder(values: Map<String, String>, size: DroidDeckHudSize): List<String> =
        sizeOrders(values)[size] ?: legacyOrder(values)

    /** Orders saved before each size kept its own: one list, applied to every size. */
    private fun legacyOrder(values: Map<String, String>): List<String> {
        val raw = values["hudOrder"].orEmpty()
        if (raw.isBlank() || raw.contains(':')) return emptyList()
        return raw.split('|').map { RENAMED[it] ?: it }.filter { it in ORDER }
    }

    private fun sizeOrders(values: Map<String, String>): Map<DroidDeckHudSize, List<String>> {
        val raw = values["hudOrder"].orEmpty()
        if (raw.isBlank()) return emptyMap()
        val groups = LinkedHashMap<DroidDeckHudSize, List<String>>()
        for (group in raw.split(';')) {
            val colon = group.indexOf(':')
            if (colon <= 0) continue
            val size = DroidDeckHudSize.values().firstOrNull { it.token == group.substring(0, colon) } ?: continue
            groups[size] = group.substring(colon + 1).split('|').map { RENAMED[it] ?: it }.filter { it in ORDER }
        }
        return groups
    }

    private fun encodeOrders(groups: Map<DroidDeckHudSize, List<String>>): String =
        groups.entries.filter { it.value.isNotEmpty() }
            .joinToString(";") { "${it.key.token}:${it.value.joinToString("|")}" }

    private fun flag(on: Boolean) = if (on) "1" else "0"
}
