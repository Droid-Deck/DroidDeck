package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import java.io.File
import java.util.zip.CRC32

/** Windows game folders shared with the Steam session. */
object AddedGames {
    private const val TAG = "AddedGames"
    private const val LIBRARY = "/mnt/droiddeck-sd"
    private const val LEGACY_LIBRARY = "/mnt/bannerlator-sd"

    class Game(
        val folder: File, val name: String,
        /** The game's own .exe: what the icon is read from and what the settings page lists. */
        val exe: File,
        /** The guest path the shortcut runs: [exe], or a store install's launcher .bat in front of it. */
        val guestExe: String, val guestDir: String,
        /** The client's 32-bit appid for this shortcut, as an unsigned value. */
        val appId: Long,
        /** What steam://rungameid/ takes for a shortcut. */
        val gameId: Long,
        val candidates: List<File>,
        val steamAppId: Int? = null,
        /** Where the game came from: a store's id, or [Library.ADDED] for a folder the user added. */
        val source: String = Library.ADDED,
        /** The store's own id for the game, from its sidecar; null for a plain added folder. */
        val storeId: String? = null,
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
        // A store root on a card has a bind of its own, before the library rule: the same folder
        // keeps the same guest path (and appid) whether or not that card is also the Steam library.
        StoreInstallRoot.externalGuestPath(context, host)?.let { return it }
        GameStorage.effective(context)?.let { lib ->
            if (path.startsWith("${lib.path}/")) return "$LIBRARY/" + path.removePrefix("${lib.path}/")
        }
        // Inside the runtime's own tree (store installs on internal storage live at
        // root/Games/Stores): the tree is the session's / and needs no bind.
        val runtime = LinuxRuntime.rootDir(context).absolutePath
        if (path.startsWith("$runtime/")) return "/" + path.removePrefix("$runtime/")
        return null
    }

    /** The .exe files a game folder offers, best first. */
    fun candidates(folder: File): List<File> {
        val exes = ArrayList<File>()
        val roots = listOf(folder) + (folder.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList())
        for (dir in roots) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".exe", ignoreCase = true) && !SKIP.matches(f.name) }
                ?.let { exes.addAll(it) }
        }
        val key = folder.name.lowercase().replace(Regex("[^a-z0-9]"), "")
        return exes.sortedWith(
            compareByDescending<File> { it.parentFile == folder }
                .thenByDescending { it.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9]"), "").let { n -> n == key || key.startsWith(n) || n.startsWith(key) } }
                .thenByDescending { it.length() },
        )
    }

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
                // The store install root sits inside the Games storage; its games are scanned below, per store.
                if (folder.name == "Games" && StoreInstallRoot.roots(context).any { it.absolutePath == folder.absolutePath }) continue
                scanGame(context, folder, out)
            }
        }
        // Games the Stores section installed: one folder each under <root>/<Store>/, with a sidecar.
        for (folder in StoreInstallRoot.gameFolders(context)) scanGame(context, folder, out)
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
            // A store install carries a sidecar; every one of them is a shortcut.
            val sidecar = StoreGameSidecar.read(folder)
            val candidates = candidates(folder)
            val picked = SessionPrefs.addedGameExe(context, folder.path)
            val chosen = picked.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
            // A store install says which exe it is; the folder's guess only when that file is gone.
            val found = chosen ?: sidecar?.exeFile(folder)?.takeIf { it.isFile } ?: candidates.firstOrNull() ?: return
            // The store's launcher .bat stands in front of the exe when the game needs arguments or
            // environment; it is what the shortcut runs. Dropped when the user picked another exe.
            val launch = if (chosen == null) sidecar?.launcherFile(folder)?.takeIf { it.isFile } ?: found else found
            val foundGuest = guestPath(context, launch)
            if (foundGuest == null) { Log.w(TAG, "${folder.name}: the session cannot see ${launch.path}"); return }
            val name = sidecar?.title ?: folder.name
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
            val guestDir = guestPath(context, (if (adopted != null) adopted else launch).parentFile ?: folder) ?: return
            val steamId = config?.let { steamRoute(it, appId) }
            out.add(Game(folder, name, exe, guestExe, guestDir, appId,
                steamId?.toLong() ?: ((appId shl 32) or 0x02000000L), candidates, steamId,
                source = sidecar?.store?.id ?: Library.ADDED, storeId = sidecar?.id))
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
