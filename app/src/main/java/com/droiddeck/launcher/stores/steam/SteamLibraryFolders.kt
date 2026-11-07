package com.droiddeck.launcher.stores.steam

import com.droiddeck.launcher.storage.SteamStorage
import com.droiddeck.launcher.stores.download.DownloadStage
import java.io.File

internal object SteamLibraryFolders {
    data class Folder(val path: String, val label: String)

    class Mount(guest: String, val host: File) {
        val guest: String = guest.trimEnd('/')
    }

    data class Library(val guest: String, val host: File, val label: String, val main: Boolean)

    data class Installed(val appId: Int, val name: String, val installDir: String?, val library: Library, val sizeOnDisk: Long)

    /** Guest folders that exist only as binds of the session; without their bind they are empty mount points in the rootfs. */
    val BIND_ONLY = listOf("/mnt", "/media", "/storage", "/sdcard", "/root/Storage", "/root/ROMs", "/root/Games")

    private val TOKEN = Regex("\"((?:\\\\.|[^\"\\\\])*)\"|[{}]|//[^\n]*")
    private val ACF = Regex("appmanifest_\\d+\\.acf")
    private val SIZE_ON_DISK = Regex("^\\s*\"SizeOnDisk\"\\s*\"(\\d+)\"", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    private fun walk(text: String, pair: (blocks: List<String>, key: String, value: String) -> Unit, closed: (blocks: List<String>) -> Unit = {}) {
        val blocks = ArrayList<String>()
        var key: String? = null
        for (m in TOKEN.findAll(text)) {
            val token = m.value
            when {
                token.startsWith("//") -> {}
                token == "{" -> { blocks.add(key.orEmpty()); key = null }
                token == "}" -> { if (blocks.isNotEmpty()) { closed(blocks); blocks.removeAt(blocks.lastIndex) }; key = null }
                key == null -> key = unescape(m.groupValues[1])
                else -> { pair(blocks, key, unescape(m.groupValues[1])); key = null }
            }
        }
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) { out.append(s[i + 1]); i += 2 } else { out.append(s[i]); i++ }
        }
        return out.toString()
    }

    private fun inRoot(blocks: List<String>) = blocks.firstOrNull()?.equals("libraryfolders", ignoreCase = true) == true

    fun parse(text: String): List<Folder> {
        val out = ArrayList<Folder>()
        var path: String? = null
        var label = ""
        walk(text, pair = { blocks, key, value ->
            if (!inRoot(blocks)) return@walk
            if (blocks.size == 1 && key.isNotEmpty() && key.all(Char::isDigit)) out += Folder(value, "")
            if (blocks.size == 2) when (key.lowercase()) {
                "path" -> path = value
                "label" -> label = value
            }
        }, closed = { blocks ->
            if (blocks.size == 2 && inRoot(blocks)) path?.let { out += Folder(it, label) }
            if (blocks.size == 2) { path = null; label = "" }
        })
        return out
    }

    fun normalize(path: String): String? {
        val p = path.trim().replace(Regex("/+"), "/").trimEnd('/')
        if (!p.startsWith("/") || p.split('/').any { it == ".." || it == "." }) return null
        return p
    }

    private fun under(path: String, prefix: String) = path == prefix || path.startsWith("$prefix/")

    fun hostFor(guest: String, mounts: List<Mount>, rootfs: File): File? {
        val path = normalize(guest) ?: return null
        mounts.filter { it.guest.isNotEmpty() && under(path, it.guest) }.maxByOrNull { it.guest.length }?.let { m ->
            val rest = path.removePrefix(m.guest).trimStart('/')
            return if (rest.isEmpty()) m.host else File(m.host, rest)
        }
        if (BIND_ONLY.any { under(path, it) } || !under(path, "/root")) return null
        return File(rootfs, path.removePrefix("/"))
    }

    fun resolve(folders: List<Folder>, mainGuest: String, aliases: Collection<String>, mainHost: File, mounts: List<Mount>, rootfs: File): List<Library> {
        val mains = (aliases + mainGuest).mapNotNull(::normalize).toSet()
        val listed = if (folders.any { normalize(it.path) in mains }) folders else listOf(Folder(mainGuest, "")) + folders
        val out = ArrayList<Library>()
        for (f in listed) {
            val guest = normalize(f.path) ?: continue
            val main = guest in mains
            val host = if (main) mainHost else hostFor(guest, mounts, rootfs) ?: continue
            if (out.any { it.host.path == host.path || (main && it.main) }) continue
            out += Library(guest, host, f.label.trim(), main)
        }
        return out.sortedByDescending { it.main }
    }

    fun installed(libraries: List<Library>, read: (File) -> String? = { runCatching { it.readText() }.getOrNull() }): Map<Int, Installed> {
        val out = LinkedHashMap<Int, Installed>()
        for (library in libraries) {
            val apps = File(library.host, "steamapps")
            val names = apps.list() ?: continue
            for (name in names.sorted()) {
                if (!ACF.matches(name)) continue
                val file = File(apps, name)
                val text = read(file) ?: continue
                val manifest = SteamStorage.parseManifest(file, text) ?: continue
                if (!manifest.installed || SteamStorage.isTool(manifest) || manifest.appId in out) continue
                val size = SIZE_ON_DISK.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                out[manifest.appId] = Installed(manifest.appId, manifest.name, manifest.installDir, library, size)
            }
        }
        return out
    }

    /** The account the client signs in with by itself: remembered, the most recent one first. */
    fun savedAccount(loginUsers: String): String? {
        val users = LinkedHashMap<String, HashMap<String, String>>()
        walk(loginUsers, pair = { blocks, key, value ->
            if (blocks.size == 2 && blocks[0].equals("users", ignoreCase = true)) users.getOrPut(blocks[1]) { HashMap() }[key.lowercase()] = value
        })
        val remembered = users.values.filter { it["rememberpassword"] == "1" }
        val user = remembered.firstOrNull { it["mostrecent"] == "1" }
            ?: remembered.maxByOrNull { it["timestamp"]?.toLongOrNull() ?: 0L }
            ?: return null
        return user["personaname"]?.takeIf { it.isNotBlank() } ?: user["accountname"]?.takeIf { it.isNotBlank() }
    }

    fun language(registry: String): String? {
        var found: String? = null
        walk(registry, pair = { blocks, key, value ->
            if (found == null && key.equals("language", ignoreCase = true) && blocks.lastOrNull().equals("Steam", ignoreCase = true) && value.isNotBlank()) found = value
        })
        return found
    }

    fun stageOf(phase: String): DownloadStage {
        val p = phase.lowercase()
        return when {
            "verif" in p || "validat" in p -> DownloadStage.VERIFY
            listOf("install", "regist", "final", "commit", "acf", "apply").any { it in p } -> DownloadStage.INSTALL
            listOf("manifest", "plan", "prepar", "check", "scan", "clean", "alloc", "connect", "logon", "login", "sign").any { it in p } -> DownloadStage.MANIFEST
            else -> DownloadStage.DOWNLOAD
        }
    }
}
