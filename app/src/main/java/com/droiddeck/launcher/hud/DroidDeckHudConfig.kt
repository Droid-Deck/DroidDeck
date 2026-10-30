package com.droiddeck.launcher.hud

import android.content.Context
import com.droiddeck.launcher.session.SessionPrefs

/** DroidDeck HUD's settings as one "key=value,…" string (DroidDeckHudView.applyConfig), stored in SessionPrefs. */
internal object DroidDeckHudConfig {
    /**
     * Bannerlator's defaults (DeckOverlaySettings), with the FPS graph from the old DroidDeck HUD. The HUD starts
     * locked, so like the old HUD it lets touches through until it is unlocked to move it.
     */
    val DEFAULTS: Map<String, String> = linkedMapOf(
        "hudSize" to "minimal", "hudLocked" to "1", "hudScale" to "100", "hudDock" to "none", "hudOpacity" to "80", "hudColor" to "mid",
        "hudOutline" to "40", "hudOutlineAccent" to "1",
        "showFPS" to "1", "showFPSGraph" to "1", "showCPUUsage" to "1", "showCPULoad" to "1", "showGPULoad" to "1",
        "showVram" to "1", "showRAM" to "1", "showPower" to "1", "showTemp" to "1", "showBatteryTemp" to "1",
        "showGpuTemp" to "0", "showBattery" to "0", "showLow001" to "1", "fpsDecimal" to "1", "showPerCore" to "1",
        "showSwap" to "1", "showNet" to "1", "showResolution" to "1", "showProton" to "1", "showSession" to "1",
        "showClock" to "1", "showEngine" to "1", "showRenderer" to "1", "showGpuModel" to "1",
        "tempUnit" to "c", "tempBands" to "1", "tempAuto" to "1", "tempRedCpu" to "90", "tempRedGpu" to "90", "tempRedBat" to "48",
    )

    /** Switches Bannerlator sets together (DeckOverlaySettings twins). */
    private val TWINS = mapOf("showCPUUsage" to "showCPULoad", "showTemp" to "showBatteryTemp", "showEngine" to "showRenderer")

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
        TWINS[key]?.let { values[it] = value }
        SessionPrefs.setDroidDeckHudConfig(context, encode(values))
        return values
    }

    fun reset(context: Context): Map<String, String> {
        SessionPrefs.setDroidDeckHudConfig(context, encode(DEFAULTS))
        return DEFAULTS
    }

    private fun flag(on: Boolean) = if (on) "1" else "0"
}
