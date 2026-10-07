package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.zip.CRC32

/** Windows game folders shared with the Steam session. */
object AddedGames {
    private const val TAG = "AddedGames"
    private const val LIBRARY = "/mnt/droiddeck-sd"
    private const val LEGACY_LIBRARY = "/mnt/bannerlator-sd"

    class Game(
        val folder: File, val name: String, val exe: File, val guestExe: String, val guestDir: String,
        /** The client's 32-bit appid for this shortcut, as an unsigned value. */
        val appId: Long,
        /** What steam://rungameid/ takes for a shortcut. */
        val gameId: Long,
        val candidates: List<File>,
        val steamAppId: Int? = null,
    ) {
        fun folderName(): String = folder.name
    }

    /** One Games folder and where the session sees it. */
    class Root(val host: File, val guest: String)

    /**
     * The chosen folders with their guest paths: /root/Games/<folder name>, or with a short hash of
     * the host path when two chosen folders share a name (so a folder's guest path, and with it
     * every shortcut's appid, does not change when another folder is added or removed).
     */
    fun roots(context: Context): List<Root> {
        val hosts = SessionPrefs.addedGamesDirs(context).map { File(it) }
        val names = hosts.groupingBy { it.name.lowercase() }.eachCount()
        return hosts.map { host ->
            val name = host.name.ifEmpty { "games" }
            val guestName = if ((names[name.lowercase()] ?: 0) > 1) name + "-" + "%08x".format(CRC32().apply { update(host.path.toByteArray()) }.value).take(4) else name
            Root(host, "$GUEST_DIR/$guestName")
        }
    }

    private val SKIP = Regex(
        "(?i)^(unins.*|setup.*|.*redist.*|vcredist.*|dxsetup.*|dxwebsetup.*|.*crash.*|.*report.*|dotnet.*|directx.*|.*prereq.*" +
            "|.*installer.*|.*uninstall.*|.*updater?.*|.*config(ur.*)?|.*settings.*|.*editor.*|.*server.*|.*benchmark.*|.*helper.*|.*eac.*|.*easyanticheat.*|.*battleye.*)\\.exe$",
    )

    /** Under here the chosen Games folders are bound inside the session, one each. */
    const val GUEST_DIR = "/root/Games"

    /** Where a host path appears inside the session, or null when the session cannot see it. */
    fun guestPath(context: Context, host: File): String? {
        val path = host.absolutePath
        // The Games folders are bound on their own, so a folder anywhere - an SD card, a USB
        // drive - works without being inside one of the other binds.
        for (root in roots(context)) {
            val dir = root.host.absolutePath
            if (path == dir) return root.guest
            if (path.startsWith("$dir/")) return root.guest + "/" + path.removePrefix("$dir/")
        }
        SessionPrefs.romsDir(context).takeIf { it.isNotEmpty() }?.let { roms ->
            if (path.startsWith("$roms/")) return "/root/ROMs/" + path.removePrefix("$roms/")
        }
        GameStorage.effective(context)?.let { lib ->
            if (path.startsWith("${lib.path}/")) return "$LIBRARY/" + path.removePrefix("${lib.path}/")
        }
        return null
    }

    /** The .exe files a game folder offers, best first; a Store package's declared game leads. */
    fun candidates(folder: File): List<File> {
        val exes = ArrayList<File>()
        val roots = listOf(folder) + (folder.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList())
        for (dir in roots) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".exe", ignoreCase = true) && !SKIP.matches(f.name) }
                ?.let { exes.addAll(it) }
        }
        val key = folder.name.lowercase().replace(Regex("[^a-z0-9]"), "")
        val declared = declaredExes(folder)
        return declared + exes.filter { exe -> declared.none { it.path == exe.path } }.sortedWith(
            compareByDescending<File> { it.parentFile == folder }
                .thenByDescending { it.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9]"), "").let { n -> n == key || key.startsWith(n) || n.startsWith(key) } }
                .thenByDescending { it.length() },
        )
    }

    /** The executables MicrosoftGame.config or appxmanifest.xml declares that exist inside [folder]. */
    internal fun declaredExes(folder: File): List<File> {
        val names = DECLARED.flatMap { (file, pattern) ->
            runCatching { pattern.findAll(File(folder, file).readText()).map { it.groupValues[1] }.toList() }.getOrDefault(emptyList())
        }
        val root = runCatching { folder.canonicalPath + File.separator }.getOrNull() ?: return emptyList()
        return names.asSequence()
            .map { File(folder, it.trim().replace('\\', '/').trimStart('/')) }
            .filter { it.name.endsWith(".exe", ignoreCase = true) && !SKIP.matches(it.name) && it.isFile }
            .filter { runCatching { it.canonicalPath.startsWith(root) }.getOrDefault(false) }
            .distinctBy { it.path }
            .toList()
    }

    private val DECLARED = listOf(
        "MicrosoftGame.config" to Regex("<Executable\\b[^>]*?\\bName\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
        "appxmanifest.xml" to Regex("<Application\\b[^>]*?\\bExecutable\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
    )

    fun scan(context: Context): List<Game> {
        val out = ArrayList<Game>()
        val folders = roots(context).map { it.host } + listOfNotNull(
            GameStorage.effective(context)?.let { File(it.path) },
            GameStorage.effective(context)?.let { File(it.path, "steamapps/common") },
        )
        for (dir in folders.distinctBy { it.absolutePath }) {
            if (!dir.isDirectory) { Log.w(TAG, "$dir is not a folder; skipped"); continue }
            val steamInstalls = steamInstallDirs(dir)
            for (folder in dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()) {
                if (folder.name.lowercase() in steamInstalls || folder.name.equals("steamapps", ignoreCase = true)) continue
                scanGame(context, folder, out)
            }
        }
        return out.distinctBy { it.folder.canonicalPath }
    }

    /**
     * The folders under a library's steamapps/common that a manifest beside it already claims.
     * Steam lists those itself, so a shortcut would only add a non-Steam copy of the game. Steam
     * may lowercase installdir on Android's case-insensitive storage, so names compare lowercased.
     */
    internal fun steamInstallDirs(dir: File): Set<String> {
        val steamapps = dir.parentFile?.takeIf { dir.name == "common" && it.name == "steamapps" } ?: return emptySet()
        return steamapps.listFiles { f -> f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") }
            .orEmpty()
            .mapNotNull { manifest ->
                runCatching { INSTALL_DIR.find(manifest.readText())?.groupValues?.get(1)?.trim()?.lowercase() }.getOrNull()
            }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private val INSTALL_DIR = Regex("\"installdir\"\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

    private fun scanGame(context: Context, folder: File, out: MutableList<Game>) {
        run {
            val candidates = candidates(folder)
            val picked = SessionPrefs.addedGameExe(context, folder.path)
            val chosen = picked.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
            val found = chosen ?: candidates.firstOrNull() ?: return
            val foundGuest = guestPath(context, found)
            if (foundGuest == null) { Log.w(TAG, "${folder.name}: the session cannot see ${found.path}"); return }
            val name = folder.name
            // Keyed by the pre-rename path so shortcut ids, and the prefixes and saves under them, stay put.
            val crc = CRC32().apply { update(("\"${foundGuest.replaceFirst(Regex("^$LIBRARY/"), "$LEGACY_LIBRARY/")}\"" + name).toByteArray()) }.value
            val appId = SessionPrefs.addedGameAppId(context, folder.path, crc or 0x80000000L)
            val config = accountConfig(File(LinuxRuntime.rootDir(context), "root/.local/share/Steam"))
            val record = config?.let { shortcutRecord(it, appId) }
            val rev = record?.optInt("rev", 0) ?: 0
            val adopted = guestPath(context, folder)
                ?.let { steamTarget(folder, it, record, foundGuest, SessionPrefs.addedGameExeSeen(context, folder.path)) }
                ?.takeIf { SessionPrefs.adoptAddedGameExe(context, folder.path, picked, it.path, rev) }
            val exe = adopted ?: found
            val guestExe = if (adopted != null) guestPath(context, adopted) ?: return else foundGuest
            val guestDir = guestPath(context, exe.parentFile ?: folder) ?: return
            val steamId = config?.let { steamRoute(it, appId) }
            out.add(Game(folder, name, exe, guestExe, guestDir, appId,
                steamId?.toLong() ?: ((appId shl 32) or 0x02000000L), candidates, steamId))
        }
    }

    private fun accountConfig(root: File): File? {
        val users = runCatching { File(root, "config/loginusers.vdf").readText() }.getOrDefault("")
        val recent = Regex(""""(\d{5,})"\s*\{([^}]*)\}""").findAll(users)
            .lastOrNull { Regex(""""MostRecent"\s*"1"""").containsMatchIn(it.groupValues[2]) }
        val account = recent?.groupValues?.get(1)?.toLongOrNull()?.let { (it - 76561197960265728L).toString() }
            ?: File(root, "userdata").list()?.filter { it.isNotEmpty() && it.all(Char::isDigit) && it != "0" }?.singleOrNull()
        return account?.let { File(root, "userdata/$it/config") }
    }

    private fun steamRoute(config: File, appId: Long): Int? = runCatching {
        JSONObject(File(config, ".droiddeck-routes.json").readText()).optInt(appId.toString()).takeIf { it > 0 }
    }.getOrNull()

    private fun shortcutRecord(config: File, appId: Long): JSONObject? = runCatching {
        JSONObject(File(config, ".droiddeck-shortcuts.json").readText()).optJSONObject("games")?.optJSONObject(appId.toString())
    }.getOrNull()

    internal fun steamTarget(folder: File, folderGuest: String, record: JSONObject?, guestExe: String, seen: Int): File? {
        if (record == null || record.optInt("rev", 0) == seen) return null
        val asked = unquote(record.optJSONObject("app")?.optString("Exe"))
        val steam = unquote(record.optJSONObject("steam")?.optString("Exe"))
        if (asked != guestExe || steam.isEmpty() || steam == asked || !steam.startsWith("$folderGuest/")) return null
        val file = File(folder, steam.removePrefix("$folderGuest/"))
        val inside = runCatching { file.canonicalPath.startsWith(folder.canonicalPath + File.separator) }.getOrDefault(false)
        return file.takeIf { inside && it.isFile }
    }

    private fun unquote(value: String?): String = value.orEmpty().trim().trim('"')

    /** The list the session hands the runtime's shortcuts writer; one file per session start. */
    fun writeListing(context: Context, games: List<Game>): File {
        val file = File(context.filesDir, "session/added-games.json").apply { parentFile?.mkdirs() }
        val json = StringBuilder("[")
        games.forEachIndexed { i, g ->
            if (i > 0) json.append(',')
            json.append("{\"name\":").append(quote(g.name)).append(",\"exe\":").append(quote(g.guestExe))
                .append(",\"folder\":").append(quote(guestPath(context, g.folder) ?: g.guestDir))
                .append(",\"dir\":").append(quote(g.guestDir)).append(",\"appid\":").append(g.appId)
                .append(",\"seen\":").append(SessionPrefs.addedGameExeSeen(context, g.folder.path))
            // The art, as the session sees it: the app's cache is bound at its own path, a file in
            // the game's folder at the folder's guest path.
            val art = AddedGameArt.resolve(context, g)
            val pieces = listOf("p" to art.portrait, "header" to art.header, "hero" to art.hero, "logo" to art.logo, "icon" to art.icon)
                .mapNotNull { (k, f) -> f?.let { file -> artGuestPath(context, file)?.let { k to it } } }
            if (pieces.isNotEmpty()) json.append(",\"art\":{").append(pieces.joinToString(",") { (k, v) -> quote(k) + ":" + quote(v) }).append('}')
            json.append('}')
        }
        file.writeText(json.append(']').toString())
        return file
    }

    private fun artGuestPath(context: Context, file: File): String? {
        val files = context.filesDir.absolutePath
        if (file.absolutePath.startsWith("$files/")) return file.absolutePath
        return guestPath(context, file)
    }

    private fun quote(s: String): String = JSONObject.quote(s)
}
