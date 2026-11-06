package com.droiddeck.launcher.session

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale

object ComponentUpdates {
    const val AUTO = "auto"
    const val MANUAL = "manual"
    const val ORIGINAL = ""
    const val BOOT_SECONDS = 30L
    const val BOOTED_SECONDS = 60L
    const val FAILED_BOOTS_BEFORE_ROLLBACK = 2

    data class Line(val key: String, val comp: String, val prefix: String)

    val LINES = listOf(
        Line("dxvk", "dxvk", "DD-dxvk-"),
        Line("dxvk-arm64ec", "dxvk", "DD-dxvk-arm64ec-"),
        Line("vkd3d", "vkd3d", "DD-vkd3d-proton-"),
        Line("vkd3d-arm64ec", "vkd3d", "DD-vkd3d-proton-arm64ec-"),
        Line("fex", "fex", "DD-FEXCore-"),
    )

    fun line(comp: String, arm64ec: Boolean): Line = LINES.first {
        it.comp == comp && (comp == "fex" || it.key.endsWith("-arm64ec") == arm64ec)
    }

    fun pick(items: List<ComponentsManager.CatalogItem>, line: Line, bad: Set<String>): ComponentsManager.CatalogItem? {
        val newestBad = bad.filter { belongs(it, line) }.maxWithOrNull { a, b -> ProtonExtras.compareVersions(a, b) }
        return items.filter { it.release == ComponentsManager.DD_NIGHTLY && it.comp == line.comp && belongs(it.file, line) }
            .filter { newestBad == null || ProtonExtras.compareVersions(it.file, newestBad) > 0 }
            .maxWithOrNull { a, b -> ProtonExtras.compareVersions(a.file, b.file) }
    }

    fun belongs(file: String, line: Line): Boolean =
        file.startsWith(line.prefix) && file.removePrefix(line.prefix).firstOrNull()?.isDigit() == true

    fun arm64ec(pe: ByteArray): Boolean? = runCatching {
        val b = ByteBuffer.wrap(pe).order(ByteOrder.LITTLE_ENDIAN)
        val header = b.getInt(0x3c)
        if (b.getInt(header) != 0x4550) return null
        val sections = b.getShort(header + 6).toInt() and 0xffff
        val optionalSize = b.getShort(header + 20).toInt() and 0xffff
        val optional = header + 24
        if ((b.getShort(optional).toInt() and 0xffff) != 0x20b) return false
        val loadConfigRva = b.getInt(optional + 112 + 10 * 8)
        if (loadConfigRva == 0) return false
        val table = optional + optionalSize
        var offset = -1
        for (i in 0 until sections) {
            val s = table + i * 40
            val size = b.getInt(s + 8)
            val va = b.getInt(s + 12)
            if (loadConfigRva >= va && loadConfigRva < va + maxOf(size, 1)) offset = b.getInt(s + 20) + loadConfigRva - va
        }
        if (offset < 0) return null
        b.getInt(offset) >= 0xC8 + 8 && b.getLong(offset + 0xC8) != 0L
    }.getOrNull()

    fun arm64x(pe: ByteArray): Boolean = runCatching {
        val b = ByteBuffer.wrap(pe).order(ByteOrder.LITTLE_ENDIAN)
        val header = b.getInt(0x3c)
        b.getInt(header) == 0x4550 && (b.getShort(header + 4).toInt() and 0xffff) == 0xaa64 && arm64ec(pe) == true
    }.getOrDefault(false)

    fun needsArm64x(line: Line, stocks: List<Boolean?>): Boolean =
        line.key.endsWith("-arm64ec") && stocks.any { it != false }

    data class Launch(val app: String, val tool: String, val start: String, val seconds: Long)

    private val PROCESS = Regex("""^\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)] Game process (added|removed)\s*: AppID (\d+)(?: "(.*))?""")
    private val TOOL = Regex("""compatibilitytools\.d/([^/'"]+)['"]?/|steamapps/common/([^/'"]+)['"]?/proton""")

    fun launches(log: Sequence<String>, after: String): List<Launch> {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val open = HashMap<String, Pair<String, String>>()
        val out = ArrayList<Launch>()
        for (line in log) {
            val m = PROCESS.find(line) ?: continue
            val (time, what, app) = m.destructured
            if (time <= after) continue
            if (what == "added") {
                val tool = TOOL.find(m.groupValues[4])?.let { t -> t.groupValues[1].ifEmpty { t.groupValues[2] } } ?: continue
                open[app] = time to tool
            } else {
                val (start, tool) = open.remove(app) ?: continue
                val seconds = runCatching { (format.parse(time)!!.time - format.parse(start)!!.time) / 1000 }.getOrNull() ?: continue
                out += Launch(app, tool, start, seconds)
            }
        }
        return out
    }

    data class Trial(
        val current: String,
        val previous: String,
        val since: String,
        val booted: Map<String, String>,
        val failed: Map<String, Int>,
    )

    data class Verdict(val booted: Map<String, String>, val failed: Map<String, Int>, val rollBack: Boolean)

    fun judge(trial: Trial, runs: List<Launch>): Verdict {
        val booted = trial.booted.toMutableMap()
        val failed = trial.failed.toMutableMap()
        var rollBack = false
        for (run in runs) {
            val build = if (trial.since.isNotEmpty() && run.start > trial.since) trial.current else trial.previous
            if (run.seconds >= BOOTED_SECONDS) {
                booted[run.app] = build
                if (build == trial.current) failed.remove(run.app)
            } else if (run.seconds < BOOT_SECONDS && build == trial.current && trial.current != trial.previous &&
                booted[run.app] == trial.previous) {
                val n = (failed[run.app] ?: 0) + 1
                failed[run.app] = n
                if (n >= FAILED_BOOTS_BEFORE_ROLLBACK) rollBack = true
            }
        }
        return Verdict(booted, failed, rollBack)
    }

    fun keep(current: String, previous: String, selected: Set<String>): Set<String> =
        (setOf(current, previous) + selected) - ORIGINAL

    fun manual(choice: ComponentsManager.Choice?, applied: String?, auto: Set<String>): Boolean =
        choice != null && !choice.auto && !choice.file.startsWith("original ") &&
            (if (applied.isNullOrEmpty()) choice.file !in auto else choice.file != applied)

    fun released(choice: ComponentsManager.Choice?, auto: Set<String>): Boolean =
        choice != null && !choice.auto && choice.file in auto

    fun stale(line: Line?, owned: Set<String>, stored: List<String>, keep: Set<String>): List<String> =
        (owned + stored.filter { line != null && belongs(it, line) }).filter { it !in keep }.distinct()
}
