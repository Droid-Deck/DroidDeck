package com.droiddeck.launcher.storage

import java.io.File

object SteamStorage {
    const val SCOUT = 1070560
    const val SOLDIER = 1391110
    const val SNIPER = 1628350
    const val ARM_RUNTIME = 4185400
    const val PROTON_ARM = 4427310
    const val PROTON_11_ARM = 4628740
    const val REDISTRIBUTABLES = 228980
    const val FEX = 3127680

    const val ARM_TOOL = "droiddeck-proton-arm64"
    const val ARM_11_TOOL = "droiddeck-proton-11-arm64"

    private val VALVE_TOOLS = setOf(
        858280, 961940, 1054830, 1113280, 1245040, 1420170, 1493710, 1580130, 1887720, 2180100, 2348590,
        2805730, 3029110, 3658110, 4183110, 4628710, 4690330,
        SCOUT, SOLDIER, SNIPER, ARM_RUNTIME, PROTON_ARM, PROTON_11_ARM, REDISTRIBUTABLES, FEX,
    )

    private val APP_ID = Regex("^\\s*\"appid\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val NAME = Regex("^\\s*\"name\"\\s*\"([^\"]*)\"", RegexOption.MULTILINE)
    private val INSTALL_DIR = Regex("^\\s*\"installdir\"\\s*\"([^\"]*)\"", RegexOption.MULTILINE)
    private val STATE = Regex("^\\s*\"StateFlags\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val LAST_PLAYED = Regex("^\\s*\"LastPlayed\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val MAPPING = Regex("\"(\\d+)\"\\s*\\{\\s*\"name\"\\s*\"([^\"]*)\"")
    private val COMPAT_NAME = Regex("\"compat_tools\"\\s*\\{\\s*\"([^\"]+)\"")
    private val VDF_TOKEN = Regex("\"((?:\\\\.|[^\"\\\\])*)\"|[{}]|//[^\n]*")
    private val SHADER_CACHE_OFF = listOf("InstallConfigStore", "Software", "Valve", "Steam", "ShaderCacheManager", "DisableShaderCache")

    data class Manifest(
        val file: File, val appId: Int, val name: String, val installDir: String?,
        val installed: Boolean, val lastPlayed: Long,
    )

    fun parseManifest(file: File, text: String): Manifest? {
        val id = APP_ID.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: file.name.removePrefix("appmanifest_").removeSuffix(".acf").toIntOrNull() ?: return null
        val dir = INSTALL_DIR.find(text)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it }
        return Manifest(
            file, id, NAME.find(text)?.groupValues?.get(1)?.trim().orEmpty(), dir,
            (STATE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0) and 4 != 0,
            LAST_PLAYED.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
        )
    }

    /** The folder alone does not mean installed: Decky's CEF marker creates it. */
    fun x64ClientPresent(root: File): Boolean = File(root, "steamrt64/steam").isFile ||
        File(root, "package/droiddeck-installed").isFile || File(root, "steamapps").isDirectory

    class Listing(val manifests: List<Manifest>, val otherIds: Set<Int>, val complete: Boolean) {
        val ids: Set<Int> get() = manifests.map { it.appId }.toSet() + otherIds
    }

    private val PENDING_MANIFEST = Regex("appmanifest_(\\d+)\\.acf\\..+")

    // A partial download has no manifest yet, only a temporary one and its staging folder.
    fun manifests(library: File): Listing? {
        val apps = File(library, "steamapps")
        val names = apps.list() ?: return null
        var complete = true
        val other = HashSet<Int>()
        val read = ArrayList<Manifest>()
        for (name in names.sorted()) {
            val pending = PENDING_MANIFEST.matchEntire(name)
            if (pending != null) {
                pending.groupValues[1].toIntOrNull()?.let(other::add)
                continue
            }
            if (!name.startsWith("appmanifest_") || !name.endsWith(".acf")) continue
            val f = File(apps, name)
            if (!f.isFile) continue
            val named = name.removePrefix("appmanifest_").removeSuffix(".acf").toIntOrNull()
            val text = runCatching { f.readText() }.getOrNull()
            if (text == null) {
                if (named != null) other.add(named) else complete = false
                continue
            }
            parseManifest(f, text)?.let(read::add)
        }
        File(apps, "downloading").list()?.forEach { it.toIntOrNull()?.let(other::add) }
        return Listing(read, other, complete)
    }

    fun isTool(m: Manifest): Boolean = m.appId in VALVE_TOOLS ||
        m.installDir?.let { it.startsWith("Proton ") || it.startsWith("SteamLinuxRuntime") } == true

    enum class Kind { ARM_PROTON, REQUIRED, OLD_RUNTIME, X86_PROTON }

    // DroidDeck's ARM64 Steam client cannot run Valve's x86 Protons, and its own Proton and FEX tools
    // replace the scout and soldier container runtimes.
    fun kind(m: Manifest): Kind {
        val dir = m.installDir.orEmpty()
        return when {
            m.appId == SCOUT || m.appId == SOLDIER || dir == "SteamLinuxRuntime" || dir == "SteamLinuxRuntime_soldier" -> Kind.OLD_RUNTIME
            dir.startsWith("Proton") && "ARM64" in dir -> Kind.ARM_PROTON
            m.appId == PROTON_ARM || m.appId == PROTON_11_ARM -> Kind.ARM_PROTON
            dir.startsWith("Proton ") -> Kind.X86_PROTON
            else -> Kind.REQUIRED
        }
    }

    fun compatMappings(configVdf: String): Map<String, String> {
        val start = configVdf.indexOf("\"CompatToolMapping\"")
        if (start < 0) return emptyMap()
        val open = configVdf.indexOf('{', start)
        if (open < 0) return emptyMap()
        var depth = 0
        var end = configVdf.length
        for (i in open until configVdf.length) {
            when (configVdf[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) { end = i; break }
            }
        }
        return MAPPING.findAll(configVdf.substring(open, end)).associate { it.groupValues[1] to it.groupValues[2] }
    }

    fun shaderPrecache(configVdf: String): Boolean = vdfValue(configVdf, SHADER_CACHE_OFF) != "1"

    private fun vdfValue(text: String, path: List<String>): String? {
        val blocks = ArrayList<String>()
        var key: String? = null
        for (token in VDF_TOKEN.findAll(text)) {
            val name = key
            when {
                token.value.startsWith("//") -> {}
                token.value == "{" -> { blocks.add(name.orEmpty()); key = null }
                token.value == "}" -> { blocks.removeLastOrNull(); key = null }
                name == null -> key = token.groupValues[1]
                else -> {
                    val at = blocks + name
                    if (at.size == path.size && at.zip(path).all { (a, b) -> a.equals(b, ignoreCase = true) }) return token.groupValues[1]
                    key = null
                }
            }
        }
        return null
    }

    fun compatToolName(compatibilityToolVdf: String): String? = COMPAT_NAME.find(compatibilityToolVdf)?.groupValues?.get(1)

    private val ARM_IN_NAME = Regex("arm64|aarch64")

    // The x86-64 client ignores tools named for arm64; droiddeck-steam-x64 names its wrappers with a64 instead.
    fun x64ToolName(name: String): String = name.replace(ARM_IN_NAME, "a64")

    class ToolUse(val mappings: Map<String, String>, val installed: Set<String>, val x64: Boolean = false) {
        private fun key(tool: String) = if (x64) x64ToolName(tool) else tool
        fun games(tool: String): Int = mappings.count { (app, name) -> name == key(tool) && app in installed }
        fun isDefault(tool: String): Boolean = mappings["0"] == key(tool)
    }

    fun leftoverId(name: String): Int? = name.toLongOrNull()?.takeIf { it in 1 until (1L shl 31) }?.toInt()

    fun isStubPrefix(prefix: File): Boolean = prefix.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
        .none { it.isFile && it.name != "steam_autocloud.vdf" && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
}
