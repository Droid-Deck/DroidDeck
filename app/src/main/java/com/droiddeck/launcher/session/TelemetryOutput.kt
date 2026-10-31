package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.frontend.Library
import java.io.File

/**
 * Codemasters games keep their UDP / motion output in My Games/<game>/hardwaresettings/hardware_settings_config.xml
 * inside the game's Proton prefix and read it when the game starts. Turning it on points that element at the
 * In-game stats listener; the file as it was is kept beside it, and its element is put back when turned off.
 */
object TelemetryOutput {
    data class Game(val name: String, val prefixId: String, val file: File, val on: Boolean) {
        /** DiRT 3 and DiRT Showdown can stop at the end of a race with WinSock error 10038 while this output is on. */
        val raceEndCrash: Boolean get() = name.lowercase().replace(" ", "") in RACE_END_CRASH
    }

    data class MenuGame(val name: String, val setting: String, val port: Int)

    /** Games whose output is only a setting in the game's own menus, by Steam app id. */
    val MENU_GAMES: Map<Int, MenuGame> = mapOf(
        1293830 to MenuGame("Forza Horizon 4", "Data Out", GameTelemetry.FORZA_PORT),
        1551360 to MenuGame("Forza Horizon 5", "Data Out", GameTelemetry.FORZA_PORT),
        2440510 to MenuGame("Forza Motorsport", "Data Out", GameTelemetry.FORZA_PORT),
        284160 to MenuGame("BeamNG.drive", "OutGauge", GameTelemetry.OUTGAUGE_PORT),
    )

    internal const val ORIGINAL_SUFFIX = ".droiddeck-original"
    private const val MY_GAMES = "pfx/drive_c/users/steamuser/Documents/My Games"
    private const val CONFIG = "hardwaresettings/hardware_settings_config.xml"
    private const val ADDRESS = "127.0.0.1"
    private const val EXTRADATA = "3"
    private val TAGS = listOf("udp", "motion")
    private val RACE_END_CRASH = setOf("dirtshowdown", "dirt3")
    // Byte-for-byte: every byte maps to one char and back, whatever the file's own encoding is.
    private val BYTES = Charsets.ISO_8859_1

    fun scan(compatdata: List<File>): List<Game> = compatdata.asSequence()
        .flatMap { root -> root.listFiles { f -> f.isDirectory }.orEmpty().asSequence() }
        .flatMap { prefix -> File(prefix, MY_GAMES).listFiles { f -> f.isDirectory }.orEmpty().asSequence().map { prefix to it } }
        .mapNotNull { (prefix, game) ->
            val file = File(game, CONFIG)
            val text = runCatching { file.readText(BYTES) }.getOrNull() ?: return@mapNotNull null
            val element = element(text) ?: return@mapNotNull null
            Game(game.name, prefix.name, file, pointsHere(element.value))
        }
        .sortedBy { it.name.lowercase() }
        .toList()

    /** Turns the game's output on or off; false when the file could not be read or written. */
    fun set(game: Game, on: Boolean): Boolean = runCatching {
        val file = game.file
        val original = File(file.path + ORIGINAL_SUFFIX)
        val xml = file.readText(BYTES)
        val next = if (on) {
            if (!original.exists()) file.copyTo(original)
            turnedOn(xml)
        } else {
            turnedOff(xml, original.takeIf { it.isFile }?.readText(BYTES))
        }
        checkNotNull(next)
        val tmp = File(file.path + ".droiddeck-tmp")
        tmp.writeText(next, BYTES)
        check(tmp.renameTo(file))
        if (!on) original.delete()
        true
    }.getOrDefault(false)

    /**
     * Automatic: turns the output on for every found game the user has not switched off and that is not running.
     * Games with the race-end crash are left to the user, and ones Automatic switched on go back to their own setting.
     */
    fun applyAutomatic(context: Context, running: String?) {
        if (!SessionPrefs.telemetryAuto(context)) return
        val games = scan(Library.compatdataDirs(context))
        automaticTargets(games, SessionPrefs.telemetryOff(context), running).forEach { set(it, true) }
        automaticRestores(games, SessionPrefs.telemetryOnByHand(context), running).forEach { set(it, false) }
    }

    internal fun key(game: Game) = "${game.prefixId}/${game.name}"

    internal fun automaticTargets(games: List<Game>, off: Set<String>, running: String?): List<Game> =
        games.filter { !it.on && !it.raceEndCrash && key(it) !in off && !isRunning(it.prefixId, running) }

    internal fun automaticRestores(games: List<Game>, onByHand: Set<String>, running: String?): List<Game> =
        games.filter { it.raceEndCrash && it.on && key(it) !in onByHand && File(it.file.path + ORIGINAL_SUFFIX).isFile && !isRunning(it.prefixId, running) }

    /** Games DroidDeck changed, which go back to their own setting when Automatic is switched off. */
    internal fun restoreTargets(games: List<Game>, running: String?): List<Game> =
        games.filter { File(it.file.path + ORIGINAL_SUFFIX).isFile && !isRunning(it.prefixId, running) }

    /** Whether [running] (the focused Steam app id, or another key for non-Steam programs) may be the game in [prefixId]. */
    internal fun isRunning(prefixId: String, running: String?): Boolean {
        val id = running?.toLongOrNull() ?: return running != null
        return prefixId == id.toString() || prefixId == id.toInt().toString()
    }

    internal fun element(xml: String): MatchResult? = TAGS.firstNotNullOfOrNull { tag -> tagRegex(tag).find(xml) }

    internal fun pointsHere(element: String): Boolean =
        attr(element, "enabled") == "true" && attr(element, "ip") == ADDRESS &&
            attr(element, "port") == GameTelemetry.CODEMASTERS_PORT.toString() &&
            (attr(element, "extradata") ?: EXTRADATA) == EXTRADATA

    internal fun turnedOn(xml: String): String? {
        val element = element(xml) ?: return null
        var on = withAttr(element.value, "enabled", "true")
        on = withAttr(on, "ip", ADDRESS)
        on = withAttr(on, "port", GameTelemetry.CODEMASTERS_PORT.toString())
        if (attr(on, "extradata") != null) on = withAttr(on, "extradata", EXTRADATA)
        return xml.replaceRange(element.range, on)
    }

    internal fun turnedOff(xml: String, original: String?): String? {
        val element = element(xml) ?: return null
        val tag = element.value.drop(1).takeWhile { !it.isWhitespace() }
        val before = original?.let { tagRegex(tag).find(it)?.value }
        return xml.replaceRange(element.range, before ?: withAttr(element.value, "enabled", "false"))
    }

    internal fun attr(element: String, name: String): String? =
        Regex("\\s$name=\"([^\"]*)\"").find(element)?.groupValues?.get(1)

    internal fun withAttr(element: String, name: String, value: String): String {
        val existing = Regex("(\\s$name=\")[^\"]*\"")
        if (existing.containsMatchIn(element)) return existing.replace(element) { it.groupValues[1] + value + "\"" }
        val selfClosing = element.endsWith("/>")
        val body = element.dropLast(if (selfClosing) 2 else 1).trimEnd()
        return "$body $name=\"$value\"" + if (selfClosing) " />" else ">"
    }

    private fun tagRegex(tag: String) = Regex("<$tag\\s[^>]*>")
}
