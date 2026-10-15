package com.droiddeck.launcher.frontend

import java.io.File
import java.util.Locale

object DependencyDetector {

    enum class Kind {
        BUNDLED,
        SHIPPED,
        STEAM,
    }

    data class Recommendation(val componentName: String, val label: String, val reason: String, val kind: Kind = Kind.BUNDLED)

    private const val MAX_DEPTH = 5
    private const val MAX_ENTRIES = 8000

    private const val VCREDIST_LOOSE = "__vcredist_loose"

    private val REDIST_DIR_RE = Regex(
        """(?i)^(_?commonredist|_?redist(ributables)?|__?installer|vcredist|vc_redist|directx|direct_x|physx|openal|dotnet(fx)?|\.net|xna|xact|redist|support)$"""
    )

    private val SKIP_DIR_RE = Regex("""(?i)^(d3d12.*|.*agility.*|dxvk|vkd3d|battleye|easyanticheat|eac|anticheat)$""")

    private val YEAR_TO_VCREDIST = mapOf(
        "2005" to "vcredist2005", "2008" to "vcredist2008", "2010" to "vcredist2010",
        "2012" to "vcredist2012", "2013" to "vcredist2013", "2015" to "vcredist2015",
        "2017" to "vcredist2015", "2019" to "vcredist2019", "2022" to "vcredist2022",
    )

    private val LABELS = mapOf(
        "vcredist2005" to "Visual C++ 2005", "vcredist2008" to "Visual C++ 2008",
        "vcredist2010" to "Visual C++ 2010", "vcredist2012" to "Visual C++ 2012",
        "vcredist2013" to "Visual C++ 2013", "vcredist2015" to "Visual C++ 2015-2017",
        "vcredist2019" to "Visual C++ 2019", "vcredist2022" to "Visual C++ 2015-2022",
        "physx" to "NVIDIA PhysX", "oalinst" to "OpenAL", "xact" to "DirectX XACT audio",
        "xact_x64" to "DirectX XACT audio (x64)", "xna40" to "XNA Framework 4.0",
        "xna31" to "XNA Framework 3.1", "d3dx9" to "DirectX 9 (d3dx9)", "d3dx11" to "DirectX 11 (d3dx11)",
        "XLiveRedist" to "Games for Windows Live", "dotnet40" to ".NET Framework 4.0",
        "dotnet45" to ".NET Framework 4.5", "dotnet452" to ".NET Framework 4.5.2",
        "dotnet46" to ".NET Framework 4.6", "dotnet472" to ".NET Framework 4.7.2",
        "dotnet48" to ".NET Framework 4.8", "dotnet35" to ".NET Framework 3.5",
        "dotnetcoredesktop6" to ".NET Desktop 6", "dotnetcoredesktop7" to ".NET Desktop 7",
        "dotnetcoredesktop8" to ".NET Desktop 8",
    )

    private val INSTALL_ORDER = listOf(
        "vcredist2005", "vcredist2008", "vcredist2010", "vcredist2012", "vcredist2013",
        "vcredist2015", "vcredist2019", "vcredist2022",
        "dotnet35", "dotnet40", "dotnet45", "dotnet452", "dotnet46", "dotnet472", "dotnet48",
        "dotnetcoredesktop6", "dotnetcoredesktop7", "dotnetcoredesktop8",
        "d3dx9", "d3dx11", "xact", "xact_x64", "xna31", "xna40", "physx", "oalinst", "XLiveRedist",
    )

    fun detectForExe(exeFile: File): List<Recommendation> {
        val start = exeFile.absoluteFile.parentFile ?: return emptyList()
        var d: File? = start
        var depth = 0
        while (d != null && depth < 4) {
            val hasRedistContainer = try {
                d.listFiles()?.any { it.isDirectory && REDIST_DIR_RE.matches(it.name.lowercase(Locale.US)) } == true
            } catch (_: Throwable) { false }
            if (hasRedistContainer) return detect(d)
            d = d.parentFile
            depth++
        }
        return detect(start)
    }

    fun detect(gameDir: File): List<Recommendation> {
        if (!gameDir.isDirectory) return emptyList()
        val hits = LinkedHashMap<String, Recommendation>()
        var visited = 0

        fun consider(f: File, isDir: Boolean, insideRedist: Boolean) {
            val name = f.name.lowercase(Locale.US)
            val path = relativeReason(gameDir, f).lowercase(Locale.US)
            val kind = if (insideRedist || isDir || name.endsWith(".exe") || name.endsWith(".msi") || name.endsWith(".cab"))
                Kind.BUNDLED else Kind.SHIPPED
            for (comp in classify(path, name, isDir)) {
                val existing = hits[comp]
                if (existing == null) {
                    hits[comp] = Recommendation(comp, LABELS[comp] ?: comp, relativeReason(gameDir, f), kind)
                } else if (existing.kind == Kind.SHIPPED && kind == Kind.BUNDLED) {
                    hits[comp] = existing.copy(kind = Kind.BUNDLED)
                }
            }
        }

        fun walk(dir: File, depth: Int, insideRedist: Boolean) {
            if (depth > MAX_DEPTH || visited > MAX_ENTRIES) return
            val entries = dir.listFiles() ?: return
            for (f in entries) {
                if (visited++ > MAX_ENTRIES) return
                val lower = f.name.lowercase(Locale.US)
                if (f.isDirectory) {
                    if (SKIP_DIR_RE.matches(lower)) continue
                    if (lower == "managed" && dir.name.endsWith("_Data", ignoreCase = true)) continue
                    val isRedistDir = insideRedist || REDIST_DIR_RE.matches(lower)
                    if (isRedistDir) consider(f, isDir = true, insideRedist = true)
                    if (isRedistDir || depth < 3) walk(f, depth + 1, isRedistDir)
                } else {
                    val ext = lower.substringAfterLast('.', "")
                    if (insideRedist || ext == "dll" || ext == "exe" || ext == "msi") consider(f, isDir = false, insideRedist = insideRedist)
                }
            }
        }

        return try {
            walk(gameDir, 0, false)
            resolveLooseVcredist(hits)
            hits.values.sortedBy { INSTALL_ORDER.indexOf(it.componentName).let { i -> if (i < 0) 999 else i } }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun componentsFor(path: String): List<String> {
        val p = path.replace('\\', '/').lowercase(Locale.US)
        return classify(p, p.substringAfterLast('/'), isDir = false).map { if (it == VCREDIST_LOOSE) "vcredist2022" else it }
    }

    fun label(name: String): String = LABELS[name] ?: name

    private fun resolveLooseVcredist(hits: LinkedHashMap<String, Recommendation>) {
        val loose = hits.remove(VCREDIST_LOOSE) ?: return
        val hasSpecific = hits.keys.any { it in YEAR_TO_VCREDIST.values }
        if (!hasSpecific) hits["vcredist2022"] = loose.copy(componentName = "vcredist2022", label = LABELS["vcredist2022"]!!)
    }

    private fun relativeReason(gameDir: File, f: File): String {
        val g = gameDir.path.replace('\\', '/')
        val p = f.path.replace('\\', '/')
        return if (p.startsWith("$g/")) p.substring(g.length + 1) else f.name
    }

    private fun classify(path: String, name: String, isDir: Boolean): List<String> {
        if (path.contains("d3d12") || path.contains("agility") ||
            path.contains("battleye") || path.contains("anticheat") || path.contains("easyanticheat") ||
            name.contains("socialclub") || name.contains("social-club") ||
            name.contains("rockstar") || name == "setup.exe"
        ) return emptyList()

        if (path.contains("vcredist") || path.contains("vc_redist") || name.startsWith("vcredist") || name.startsWith("vc_redist")) {
            val year = YEAR_TO_VCREDIST.keys.firstOrNull { path.contains(it) }
            return when {
                year != null -> listOf(YEAR_TO_VCREDIST.getValue(year))
                isDir -> emptyList()
                else -> listOf(VCREDIST_LOOSE)
            }
        }
        VCRUNTIME_DLL.entries.firstOrNull { name.startsWith(it.key) }?.let { return listOf(it.value) }
        if (path.contains("openal") || name.startsWith("oalinst") || name == "openal32.dll" || name == "wrap_oal.dll") return listOf("oalinst")
        if (path.contains("physx") || name.startsWith("physx")) return listOf("physx")
        if (path.contains("xact") || name.startsWith("xactengine") || name.startsWith("x3daudio")) return listOf("xact", "xact_x64")
        if (path.contains("xliveredist") || name.contains("xlive") || name.startsWith("gfwl") || name.startsWith("dbxlive")) {
            return listOf("XLiveRedist")
        }
        if (path.contains("xna")) return listOf(if (path.contains("3.1") || path.contains("3_1") || path.contains("v31")) "xna31" else "xna40")
        if (path.contains("dotnet") || path.contains(".net") || name.startsWith("ndp") || name.startsWith("dotnetfx") || name.contains("windowsdesktop-runtime")) {
            val comp = dotnetComponent("$path $name")
            return when {
                comp != null -> listOf(comp)
                isDir -> emptyList()
                else -> listOf("dotnet48")
            }
        }
        if (D3DX11_RE.matches(name)) return listOf("d3dx11")
        if (name.startsWith("dxsetup") || name.startsWith("dxwebsetup") ||
            D3DX9_RE.matches(name) || D3DCOMPILER_RE.matches(name) ||
            (path.contains("directx") && name.endsWith(".exe"))
        ) return listOf("d3dx9")
        return emptyList()
    }

    private val VCRUNTIME_DLL = linkedMapOf(
        "vcruntime140" to "vcredist2022", "msvcp140" to "vcredist2022", "msvcr140" to "vcredist2022", "concrt140" to "vcredist2022",
        "msvcp120" to "vcredist2013", "msvcr120" to "vcredist2013",
        "msvcp110" to "vcredist2012", "msvcr110" to "vcredist2012",
        "msvcp100" to "vcredist2010", "msvcr100" to "vcredist2010",
        "msvcp90" to "vcredist2008", "msvcr90" to "vcredist2008",
        "msvcp80" to "vcredist2005", "msvcr80" to "vcredist2005",
    )
    private val D3DX9_RE = Regex("""d3dx9_\d+\.dll""")
    private val D3DX11_RE = Regex("""d3dx11_\d+\.dll""")
    private val D3DCOMPILER_RE = Regex("""d3dcompiler_\d+\.dll""")

    private fun dotnetComponent(s: String): String? = when {
        s.contains("desktop-runtime-8") || Regex("""\b8\.\d""").containsMatchIn(s) -> "dotnetcoredesktop8"
        s.contains("desktop-runtime-7") || Regex("""\b7\.\d""").containsMatchIn(s) -> "dotnetcoredesktop7"
        s.contains("desktop-runtime-6") || Regex("""\b6\.\d""").containsMatchIn(s) -> "dotnetcoredesktop6"
        s.contains("ndp48") || s.contains("fx48") || s.contains("4.8") -> "dotnet48"
        s.contains("ndp472") || s.contains("4.7.2") -> "dotnet472"
        s.contains("ndp46") || s.contains("4.6") -> "dotnet46"
        s.contains("ndp452") || s.contains("4.5.2") -> "dotnet452"
        s.contains("dotnetfx45") || s.contains("ndp45") || s.contains("4.5") -> "dotnet45"
        s.contains("dotnetfx40") || s.contains("ndp40") || s.contains("4.0") -> "dotnet40"
        s.contains("dotnetfx35") || s.contains("3.5") -> "dotnet35"
        else -> null
    }
}
