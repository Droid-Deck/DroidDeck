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
            for (folder in dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()) scanGame(context, folder, out)
        }
        return out.distinctBy { it.folder.canonicalPath }
    }

    private fun scanGame(context: Context, folder: File, out: MutableList<Game>) {
        run {
            val candidates = candidates(folder)
            val chosen = SessionPrefs.addedGameExe(context, folder.path).takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
            val exe = chosen ?: candidates.firstOrNull() ?: return
            val guestExe = guestPath(context, exe)
            if (guestExe == null) { Log.w(TAG, "${folder.name}: the session cannot see ${exe.path}"); return }
            val guestDir = guestPath(context, exe.parentFile ?: folder) ?: return
            val name = folder.name
            // Keyed by the pre-rename path so shortcut ids, and the prefixes and saves under them, stay put.
            val crc = CRC32().apply { update(("\"${guestExe.replaceFirst(Regex("^$LIBRARY/"), "$LEGACY_LIBRARY/")}\"" + name).toByteArray()) }.value
            val appId = crc or 0x80000000L
            val steamRoot = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam")
            val steamId = steamRoute(steamRoot, appId)
            out.add(Game(folder, name, exe, guestExe, guestDir, appId,
                steamId?.toLong() ?: ((appId shl 32) or 0x02000000L), candidates, steamId))
        }
    }

    private fun steamRoute(root: File, appId: Long): Int? = runCatching {
        val users = File(root, "config/loginusers.vdf").readText()
        val blocks = Regex(""""(\d{5,})"\s*\{([^}]*)\}""")
        val recent = blocks.findAll(users).firstOrNull { Regex(""""MostRecent"\s*"1"""").containsMatchIn(it.groupValues[2]) }
            ?: return@runCatching null
        val account = recent.groupValues[1].toLong() - 76561197960265728L
        JSONObject(File(root, "userdata/$account/config/.droiddeck-routes.json").readText())
            .optInt(appId.toString()).takeIf { it > 0 }
    }.getOrNull()

    /** The list the session hands the runtime's shortcuts writer; one file per session start. */
    fun writeListing(context: Context, games: List<Game>): File {
        val file = File(context.filesDir, "session/added-games.json").apply { parentFile?.mkdirs() }
        val json = StringBuilder("[")
        games.forEachIndexed { i, g ->
            if (i > 0) json.append(',')
            json.append("{\"name\":").append(quote(g.name)).append(",\"exe\":").append(quote(g.guestExe))
                .append(",\"folder\":").append(quote(guestPath(context, g.folder) ?: g.guestDir))
                .append(",\"dir\":").append(quote(g.guestDir)).append(",\"appid\":").append(g.appId)
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
