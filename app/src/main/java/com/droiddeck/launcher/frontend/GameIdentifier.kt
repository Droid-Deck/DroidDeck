package com.droiddeck.launcher.frontend

import java.io.File

object GameIdentifier {

    private const val MAX_PARENT_DEPTH = 6

    enum class Source { STEAM_APPID_TXT, STEAM_MANIFEST_ACF, STEAM_EMU_INI, GOG_INFO, PE_VERSION, FILENAME, NONE }
    enum class Confidence { HIGH, MEDIUM, LOW }

    data class GameIdentity(
        val appId: Int? = null,
        val gogId: String? = null,
        val name: String? = null,
        val source: Source = Source.NONE,
        val confidence: Confidence = Confidence.LOW,
        val nameSource: Source = Source.NONE,
    ) {
        val hasStrongId: Boolean get() = appId != null
    }

    fun identify(exeFile: File): GameIdentity {
        val dirs = ancestorDirs(exeFile)

        val steamAppId = dirs.firstNotNullOfOrNull { readSteamAppIdTxt(it) }
        val emuAppId = dirs.firstNotNullOfOrNull { readEmuIniAppId(it) }
        val acf = resolveSteamManifest(exeFile, dirs)
        val gog = dirs.firstNotNullOfOrNull { readGogInfo(it) }
        val peName = PeVersionInfo.bestName(PeVersionInfo.read(exeFile))?.takeUnless { isJunkPeName(it) }

        val appId = acf?.appId ?: steamAppId ?: emuAppId

        val nameHit: Pair<String, Source>? = when {
            acf?.name != null -> acf.name to Source.STEAM_MANIFEST_ACF
            gog?.name != null -> gog.name to Source.GOG_INFO
            peName != null -> peName to Source.PE_VERSION
            else -> cleanedFallbackName(exeFile)?.let { it to Source.FILENAME }
        }

        val primary: Source = when {
            acf?.appId != null -> Source.STEAM_MANIFEST_ACF
            steamAppId != null -> Source.STEAM_APPID_TXT
            emuAppId != null -> Source.STEAM_EMU_INI
            else -> nameHit?.second ?: Source.NONE
        }

        val confidence = when {
            appId != null -> Confidence.HIGH
            nameHit?.second == Source.GOG_INFO -> Confidence.HIGH
            nameHit?.second == Source.PE_VERSION -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return GameIdentity(
            appId = appId,
            gogId = gog?.id,
            name = nameHit?.first?.let(::normalizeName)?.takeIf { it.isNotBlank() },
            source = primary,
            confidence = confidence,
            nameSource = nameHit?.second ?: Source.NONE,
        )
    }

    fun normalizeName(raw: String): String {
        var s = raw
        s = s.replace("™", "").replace("®", "").replace("©", "")
        s = s.replace(Regex("""(?i)\(\s*(tm|r|c)\s*\)"""), "")
        s = s.replace(":", " - ")
        s = s.replace(Regex("""[\\/]"""), " ")
        s = s.replace(Regex("""["*?<>|]"""), "")
        s = s.replace(Regex("""(?i)[ \-]+(executable|application|launcher|redistributable|installer|setup)$"""), "")
        s = s.replace(Regex("""\s+"""), " ").trim()
        return s.trim(' ', '-').trim()
    }


    private fun ancestorDirs(exeFile: File): List<File> {
        val out = ArrayList<File>(MAX_PARENT_DEPTH + 1)
        var d: File? = exeFile.absoluteFile.parentFile
        var depth = 0
        while (d != null && depth <= MAX_PARENT_DEPTH) {
            out.add(d)
            d = d.parentFile
            depth++
        }
        return out
    }


    private fun readSteamAppIdTxt(dir: File): Int? {
        for (candidate in arrayOf(File(dir, "steam_appid.txt"), File(dir, "steam_settings/steam_appid.txt"))) {
            val id = readSmallTextFile(candidate)?.removePrefix("\uFEFF")?.trim()
                ?.takeWhile { it.isDigit() }?.toIntOrNull()
            if (id != null && id > 0) return id
        }
        return null
    }

    private fun readEmuIniAppId(dir: File): Int? {
        val re = Regex("""(?im)^\s*appid\s*=\s*(\d+)""")
        val named = arrayOf(
            "ColdClientLoader.ini", "steam_emu.ini", "flt.ini", "hlm.ini",
            "valve.ini", "cream_api.ini", "SmartSteamEmu.ini", "GreenLuma.ini",
        )
        val seen = HashSet<String>()
        val candidates = ArrayList<File>()
        for (n in named) File(dir, n).let { if (it.isFile) { candidates.add(it); seen.add(n.lowercase()) } }
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".ini", ignoreCase = true) && f.name.lowercase() !in seen }
            ?: emptyArray()).sortedBy { it.name }.forEach { candidates.add(it) }
        for (f in candidates) {
            val text = readSmallTextFile(f) ?: continue
            val id = re.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (id != null && id > 0) return id
        }
        return null
    }

    private data class Acf(val appId: Int?, val name: String?)

    private fun resolveSteamManifest(exeFile: File, dirs: List<File>): Acf? {
        val commonIdx = dirs.indexOfFirst { it.name.equals("common", ignoreCase = true) }
        if (commonIdx < 0) return null
        val commonDir = dirs[commonIdx]
        val steamapps = commonDir.parentFile ?: return null
        val manifests = steamapps.listFiles { f ->
            f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf")
        } ?: return null
        if (manifests.isEmpty()) return null

        val gameFolder = if (commonIdx > 0) dirs[commonIdx - 1].name else null

        val match = manifests.firstOrNull { m ->
            val text = readSmallTextFile(m) ?: return@firstOrNull false
            val installdir = vdfValue(text, "installdir")
            gameFolder != null && installdir != null && installdir.equals(gameFolder, ignoreCase = true)
        } ?: return null

        val text = readSmallTextFile(match) ?: return null
        val appId = vdfValue(text, "appid")?.toIntOrNull()?.takeIf { it > 0 }
        val name = vdfValue(text, "name")?.takeIf { it.isNotBlank() }
        return if (appId == null && name == null) null else Acf(appId, name)
    }

    private data class Gog(val id: String?, val name: String?)

    private fun readGogInfo(dir: File): Gog? {
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith("goggame-") && f.name.endsWith(".info")
        } ?: return null
        for (f in files) {
            val text = readSmallTextFile(f) ?: continue
            val name = jsonStringValue(text, "name")?.takeIf { it.isNotBlank() } ?: continue
            val id = f.name.removePrefix("goggame-").removeSuffix(".info").takeIf { it.isNotBlank() }
            return Gog(id, name)
        }
        return null
    }


    private val REPACK_TOKENS = setOf(
        "fitgirl", "dodi", "codex", "plaza", "skidrow", "rune", "empress", "flt", "tenoke",
        "reloaded", "elamigos", "razor1911", "hoodlum", "gog", "repack", "multi", "goldberg",
        "ankergames", "steamrip", "onlinefix", "online-fix", "gload", "fitgirlrepacks",
    )
    private const val EXE_NOISE_WORDS =
        """win64|win32|shipping|wingdk|client|launcher|game|x64|x86|64bit|32bit"""

    private val EXE_NOISE_RE = Regex("""(?i)[-_. ]*(?:$EXE_NOISE_WORDS)""")

    private val EXE_SUFFIX_RE = Regex("""(?i)(?:[-_. ]*(?:$EXE_NOISE_WORDS))+$""")
    private val VERSION_TOKEN_RE = Regex("""(?i)\b(v?\d+([._]\d+)+|build[-_ ]?\d+|update[-_ ]?\d+)\b""")

    fun isLauncherExeName(base: String): Boolean {
        val lower = base.lowercase()
        if (lower == "gse" || lower.endsWith("launcher") || lower.endsWith("redirector")) return true
        for (p in arrayOf("play", "launch", "start", "run")) {
            if (!lower.startsWith(p)) continue
            val rest = base.substring(p.length)
            if (rest.isEmpty()) return true
            val c = rest[0]
            if (!c.isLetterOrDigit() || c.isUpperCase()) return true
        }
        return false
    }

    private val JUNK_NAME_RE = Regex(
        """(?i)^(gse|steam|steam client|goldberg.*|launcher|game|play|start|setup|.* launcher.*|.*launcher redirector|.* redirector|rockstar games launcher.*|epic games launcher|smartsteamemu|coldclient.*|steamemu.*|bootstrappackagedgame)$"""
    )

    fun isJunkPeName(name: String): Boolean {
        val n = name.trim()
        return n.isEmpty() || JUNK_NAME_RE.matches(n)
    }

    private fun cleanedFallbackName(exeFile: File): String? {
        val exeBase = exeFile.nameWithoutExtension
        val preferFolder = EXE_NOISE_RE.containsMatchIn(exeBase) ||
            isLauncherExeName(exeBase) ||
            exeBase.equals("game", true) || exeBase.equals("start", true)
        val folder = exeFile.absoluteFile.parentFile?.name
        val raw = if (preferFolder && !folder.isNullOrBlank()) folder else exeBase
        return cleanName(raw)
    }

    private fun cleanName(raw: String): String? {
        var s = raw
        s = s.replace(Regex("""[\[(][^\])]*[\])]"""), " ")
        s = VERSION_TOKEN_RE.replace(s, " ")
        s = EXE_SUFFIX_RE.replace(s, " ")
        s = s.replace(Regex("""[._]+"""), " ")
        s = s.split(Regex("""[\s-]+"""))
            .filter { it.isNotBlank() && it.lowercase() !in REPACK_TOKENS }
            .joinToString(" ")
        s = s.trim()
        return s.ifBlank { null }
    }


    private fun readSmallTextFile(f: File, maxBytes: Long = 1L * 1024 * 1024): String? = try {
        if (f.isFile && f.length() in 1..maxBytes) f.readText(Charsets.UTF_8) else null
    } catch (_: Throwable) {
        null
    }

    private fun vdfValue(text: String, key: String): String? =
        Regex(""""${Regex.escape(key)}"\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.trim()

    private fun jsonStringValue(text: String, key: String): String? =
        Regex(""""${Regex.escape(key)}"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(text)
            ?.groupValues?.getOrNull(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")?.trim()
}
