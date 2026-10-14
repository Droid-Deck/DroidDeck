package com.droiddeck.launcher.frontend

import java.io.File

object GameExePicker {
    const val MAX_DEPTH = 5

    const val WALK_BUDGET = 400

    const val CONFIDENT_SCORE = 60

    const val AMBIGUOUS_MARGIN = 15

    private val SKIP_DIRS = setOf(
        "_commonredist", "commonredist", "redist", "redistributable", "redistributables",
        "directx", "dotnet", "dotnetfx", "vcredist", "vc_redist", "prerequisites", "prereq",
        "installer", "installers", "__installer", "support", "extras", "docs", "documentation", "manual",
        "soundtrack", "ost", "artbook", "bonus", "dlc", "mods", "saves", "savegames",
        "crashreportclient", "easyanticheat", "battleye", "punkbuster", "steamworks shared", ".git",
    )
    private val SKIP_DIR_PREFIX = listOf("dotnet", "vcredist", "$")
    private val SKIP_PATHS = listOf("engine/extras", "engine/binaries/thirdparty")

    private val JUNK_EXE_RE = Regex(
        """(?i)^(unins\w*|setup|install\w*|vc_?redist.*|vcredist.*|dxsetup|dxwebsetup|directx.*|""" +
            """oalinst|openal.*|dotnetfx.*|ndp\d.*|unitycrashhandler\d*|crashreport\w*|crashpad\w*|""" +
            """crashsender\w*|easyanticheat\w*|eac\w*|battleye\w*|beservice\w*|punkbuster\w*|""" +
            """steamerrorreporter\d*|gameoverlayui|touchup|cleanup|config|settings|benchmark|""" +
            """activation\w*|register\w*|readme|report\w*|.*_debug|.*-debug)$""",
    )

    class Ranking(val exes: List<File>, val scores: List<Int>, val uncertain: Boolean)

    fun rank(folder: File, keep: (File) -> Boolean = { true }): Ranking {
        val scored = collect(folder).filter { keep(it.first) }.map { (exe, depth) -> exe to score(exe, folder, depth) }
            .sortedWith(compareByDescending<Pair<File, Int>> { it.second }.thenBy { it.first.path.lowercase() })
        val best = scored.firstOrNull()?.second
        val runnerUp = scored.getOrNull(1)?.second
        val uncertain = best != null && (best < CONFIDENT_SCORE || (runnerUp != null && best - runnerUp < AMBIGUOUS_MARGIN))
        return Ranking(scored.map { it.first }, scored.map { it.second }, uncertain)
    }

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

    internal fun score(exe: File, folder: File, depth: Int): Int {
        var score = 0
        val base = exe.nameWithoutExtension
        val exeKey = key(base)
        val folderKey = key(folder.name)
        if (exeKey.isNotEmpty() && folderKey.isNotEmpty()) {
            when {
                exeKey == folderKey -> score += 100
                folderKey.contains(exeKey) || exeKey.contains(folderKey) -> score += 60
            }
        }
        if (base.endsWith("-Win64-Shipping", true) || base.endsWith("-Win32-Shipping", true)) score += 70
        val path = exe.absolutePath.lowercase()
        if (path.contains("/binaries/win64") || path.contains("/binaries/win32")) score += 30
        if (path.contains("/bin/") || path.contains("/bin64/")) score += 10
        if (GameIdentifier.isLauncherExeName(base)) score -= 40
        if (GameIdentifier.isJunkPeName(base)) score -= 30
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

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
