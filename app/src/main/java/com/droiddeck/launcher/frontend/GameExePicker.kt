package com.droiddeck.launcher.frontend

import java.io.File

/**
 * Which `.exe` in a game folder is the game. A real install holds installers, crash handlers,
 * anti-cheat stubs and launchers beside the game, and the game itself may sit several levels down
 * (`Game/Binaries/Win64/Game-Win64-Shipping.exe`), so candidates are collected with a bounded walk
 * and ranked rather than guessed by position.
 */
object GameExePicker {
    /** How far below a game folder to look: Unreal puts the game three down, repacks deeper. */
    const val MAX_DEPTH = 5

    /** Folders and .exe files looked at per game before the walk stops, so a huge game stays quick. */
    const val WALK_BUDGET = 400

    /** Below this the pick is marked uncertain rather than trusted silently. */
    const val CONFIDENT_SCORE = 60

    /** The best and the runner-up this close together: nothing clearly won. */
    const val AMBIGUOUS_MARGIN = 15

    /** Folders that never hold the game itself. */
    private val SKIP_DIRS = setOf(
        "_commonredist", "commonredist", "redist", "redistributable", "redistributables",
        "directx", "dotnet", "dotnetfx", "vcredist", "vc_redist", "prerequisites", "prereq",
        "installer", "installers", "__installer", "support", "extras", "docs", "documentation", "manual",
        "soundtrack", "ost", "artbook", "bonus", "dlc", "mods", "saves", "savegames",
        "crashreportclient", "easyanticheat", "battleye", "punkbuster", "steamworks shared", ".git",
    )
    private val SKIP_DIR_PREFIX = listOf("dotnet", "vcredist", "$")
    private val SKIP_PATHS = listOf("engine/extras", "engine/binaries/thirdparty")

    /** Exe names that are never the game. */
    private val JUNK_EXE_RE = Regex(
        """(?i)^(unins\w*|setup|install\w*|vc_?redist.*|vcredist.*|dxsetup|dxwebsetup|directx.*|""" +
            """oalinst|openal.*|dotnetfx.*|ndp\d.*|unitycrashhandler\d*|crashreport\w*|crashpad\w*|""" +
            """crashsender\w*|easyanticheat\w*|eac\w*|battleye\w*|beservice\w*|punkbuster\w*|""" +
            """steamerrorreporter\d*|gameoverlayui|touchup|cleanup|config|settings|benchmark|""" +
            """activation\w*|register\w*|readme|report\w*|.*_debug|.*-debug)$""",
    )

    /** A folder's exes best first, with their scores, and whether the first clearly won. */
    class Ranking(val exes: List<File>, val scores: List<Int>, val uncertain: Boolean)

    /** Ranks the .exe files under [folder]; [keep] drops names the caller never offers. */
    fun rank(folder: File, keep: (File) -> Boolean = { true }): Ranking {
        val scored = collect(folder).filter { keep(it.first) }.map { (exe, depth) -> exe to score(exe, folder, depth) }
            .sortedWith(compareByDescending<Pair<File, Int>> { it.second }.thenBy { it.first.path.lowercase() })
        val best = scored.firstOrNull()?.second
        val runnerUp = scored.getOrNull(1)?.second
        val uncertain = best != null && (best < CONFIDENT_SCORE || (runnerUp != null && best - runnerUp < AMBIGUOUS_MARGIN))
        return Ranking(scored.map { it.first }, scored.map { it.second }, uncertain)
    }

    /**
     * Every plausible exe under [folder] with its depth (0 = in the folder itself): up to
     * [MAX_DEPTH] folders down, nearest levels first, at most [WALK_BUDGET] entries, never through a
     * symlink, past folders that never hold the game.
     */
    private fun collect(folder: File): List<Pair<File, Int>> {
        val found = ArrayList<Pair<File, Int>>()
        var visited = 0
        var level = listOf(folder)
        var depth = 0
        walk@ while (level.isNotEmpty()) {
            val next = ArrayList<File>()
            for (dir in level) {
                val entries = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: continue
                for (f in entries) {
                    if (visited >= WALK_BUDGET) break@walk
                    if (isLink(f)) continue
                    if (f.isDirectory) {
                        visited++
                        if (depth < MAX_DEPTH && !skipDir(f, f.relativeTo(folder).invariantSeparatorsPath)) next.add(f)
                    } else if (f.isFile && f.name.endsWith(".exe", ignoreCase = true)) {
                        visited++
                        if (!JUNK_EXE_RE.matches(f.nameWithoutExtension)) found.add(f to depth)
                    }
                }
            }
            level = next
            depth++
        }
        return found
    }

    /**
     * How likely [exe] is to be the game in [folder]. Relative only: the numbers rank candidates
     * against each other and decide whether anything won clearly enough to trust.
     */
    internal fun score(exe: File, folder: File, depth: Int): Int {
        var score = 0
        val base = exe.nameWithoutExtension
        val exeKey = key(base)
        val folderKey = key(folder.name)
        // The exe named after its folder is the single strongest signal.
        if (exeKey.isNotEmpty() && folderKey.isNotEmpty()) {
            when {
                exeKey == folderKey -> score += 100
                folderKey.contains(exeKey) || exeKey.contains(folderKey) -> score += 60
            }
        }
        // Unreal ships as <Game>-Win64-Shipping.exe under Binaries/Win64.
        if (base.endsWith("-Win64-Shipping", true) || base.endsWith("-Win32-Shipping", true)) score += 70
        val path = exe.absolutePath.lowercase()
        if (path.contains("/binaries/win64") || path.contains("/binaries/win32")) score += 30
        if (path.contains("/bin/") || path.contains("/bin64/")) score += 10
        // A launcher is a real entry point, so it stays a candidate, just not the preferred one.
        if (GameIdentifier.isLauncherExeName(base)) score -= 40
        if (GameIdentifier.isJunkPeName(base)) score -= 30
        // Shallower is more likely the entry point; big binaries beat small helpers.
        score -= depth * 8
        score += (exe.length() / (8L * 1024 * 1024)).toInt().coerceAtMost(30)
        return score
    }

    private fun skipDir(dir: File, relative: String): Boolean {
        val name = dir.name.lowercase().trim()
        if (name in SKIP_DIRS || SKIP_DIR_PREFIX.any { name.startsWith(it) }) return true
        val rel = relative.lowercase()
        return SKIP_PATHS.any { rel == it || rel.endsWith("/$it") }
    }

    private fun isLink(f: File): Boolean = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    /** Comparison key: letters and digits only, so "GTA IV" and "gta-iv" match. */
    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
