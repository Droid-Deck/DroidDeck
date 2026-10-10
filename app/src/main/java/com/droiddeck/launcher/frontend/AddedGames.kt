package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import org.json.JSONArray
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
        val nonSteam: Boolean = false,
        /** Where the game came from: a store's id, or [Library.ADDED] for a folder the user added. */
        val source: String = Library.ADDED,
        /** The store's own id for a store install; null for a plain added folder. */
        val storeId: String? = null,
        /** The exe is the picker's guess and nothing clearly won ([GameExePicker.Ranking.uncertain]). */
        val exeUncertain: Boolean = false,
        /** Steam's launch options for the shortcut; "" for none. */
        val launchOptions: String = "",
        /** Added from the Games tab (an entry of its own), not found in a Games folder. */
        val single: Boolean = false,
    ) {
        fun folderName(): String = folder.name
    }

    /** One Games folder and where the session sees it. */
    class Root(val host: File, val guest: String)

    data class Selection(val folder: File, val exe: File, val name: String = folder.name)
    private data class Entry(val selection: Selection, val id: Long)

    private fun entries(context: Context): List<Entry> = runCatching {
        val array = JSONArray(SessionPrefs.addedGameEntries(context))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Entry(Selection(File(o.getString("folder")), File(o.getString("exe")), o.getString("name")), o.getLong("id"))
        }
    }.getOrDefault(emptyList())

    /** Explicit executable choices keep their identity when their name or target changes. */
    fun add(context: Context, selection: Selection) {
        require(selection.name.isNotBlank())
        require(selection.exe.isFile && selection.exe.extension.equals("exe", true))
        require(selection.folder.isDirectory && selection.exe.canonicalPath.startsWith(selection.folder.canonicalPath + "/"))
        val old = entries(context)
        val key = selection.folder.canonicalPath
        val existing = old.firstOrNull { it.selection.folder.canonicalPath == key }
        val scanned = scan(context).firstOrNull { it.folder.canonicalPath == key }
        val id = existing?.id ?: scanned?.appId ?: (CRC32().apply {
            update((selection.exe.canonicalPath + selection.name).toByteArray())
        }.value or 0x80000000L)
        val array = JSONArray()
        for (entry in old.filterNot { it.selection.folder.canonicalPath == key } + Entry(selection, id)) {
            array.put(JSONObject().put("folder", entry.selection.folder.path).put("exe", entry.selection.exe.path)
                .put("name", entry.selection.name).put("id", entry.id))
        }
        SessionPrefs.setAddedGameEntries(context, array.toString())
        SessionPrefs.setAddedGameExe(context, selection.folder.path, selection.exe.path)
    }

    fun changeExecutable(context: Context, folder: String, exe: String) {
        val current = scan(context).firstOrNull { it.folder.path == folder } ?: return
        require(File(exe).isFile && exe.endsWith(".exe", true))
        require(File(exe).canonicalPath.startsWith(current.folder.canonicalPath + "/"))
        SessionPrefs.setAddedGameId(context, folder, current.appId)
        SessionPrefs.setAddedGameExe(context, folder, exe)
    }

    fun suggestedFolder(exe: File): File {
        var folder = requireNotNull(exe.parentFile)
        while (folder.name.lowercase() in setOf("bin", "binaries", "win64", "win32", "x64", "x86", "amd64")) {
            folder = folder.parentFile ?: break
        }
        return folder
    }

    /** Accept one game or a collection; preview only launchable games, not installers. */
    fun preview(folder: File): List<Selection> {
        val children = folder.listFiles().orEmpty()
        if (children.any { it.isFile && it.extension.equals("exe", true) && !SKIP.matches(it.name) } ||
            children.any { it.isDirectory && (it.name.equals("Binaries", true) || it.name.equals("bin", true)) }) {
            return candidates(folder).firstOrNull()?.let { listOf(Selection(folder, it)) } ?: emptyList()
        }
        return children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            .mapNotNull { game -> candidates(game).firstOrNull()?.let { Selection(game, it) } }
    }

    /**
     * The chosen folders with their guest paths: /root/Games/<folder name>, or with a short hash of
     * the host path when two chosen folders share a name (so a folder's guest path, and with it
     * every shortcut's appid, does not change when another folder is added or removed).
     */
    fun roots(context: Context): List<Root> {
        val hosts = SessionPrefs.addedGamesDirs(context).map { File(it) }
        val names = hosts.groupingBy { it.name.lowercase() }.eachCount()
        val collections = hosts.map { host ->
            val name = host.name.ifEmpty { "games" }
            val guestName = if ((names[name.lowercase()] ?: 0) > 1) name + "-" + "%08x".format(CRC32().apply { update(host.path.toByteArray()) }.value).take(4) else name
            Root(host, "$GUEST_DIR/$guestName")
        }
        return collections + entries(context).map { it.selection.folder }.distinctBy { it.canonicalPath }
            .filter { folder -> collections.none { folder.canonicalPath == it.host.canonicalPath || folder.canonicalPath.startsWith(it.host.canonicalPath + "/") } }
            .map { Root(it, "$GUEST_DIR/game-" + "%08x".format(CRC32().apply { update(it.canonicalPath.toByteArray()) }.value)) }
    }

    private val SKIP = Regex(
        "(?i)^(unins.*|setup.*|.*redist.*|vcredist.*|dxsetup.*|dxwebsetup.*|.*crash.*|.*report.*|dotnet.*|directx.*|.*prereq.*" +
            "|.*installer.*|.*uninstall.*|.*updater?.*|.*config(ur.*)?|.*settings.*|.*editor.*|.*server.*|.*benchmark.*|.*helper.*|.*eac.*|.*easyanticheat.*|.*battleye.*)\\.exe$",
    )

    /** Under here the chosen Games folders are bound inside the session, one each. */
    const val GUEST_DIR = "/root/Games"

    /** Where a host path appears inside the session, or null when the session cannot see it. */
    fun guestPath(context: Context, host: File): String? {
        val path = host.canonicalPath
        // The Games folders are bound on their own, so a folder anywhere - an SD card, a USB
        // drive - works without being inside one of the other binds.
        for (root in roots(context)) {
            val dir = root.host.canonicalPath
            if (path == dir) return root.guest
            if (path.startsWith("$dir/")) return root.guest + "/" + path.removePrefix("$dir/")
        }
        SessionPrefs.romsDir(context).takeIf { it.isNotEmpty() }?.let { roms ->
            val dir = File(roms).canonicalPath
            if (path.startsWith("$dir/")) return "/root/ROMs/" + path.removePrefix("$dir/")
        }
        // A store root on a card has a bind of its own, before the library rule: the same folder
        // keeps the same guest path (and appid) whether or not that card is also the Steam library.
        StoreInstallRoot.externalGuestPath(context, host)?.let { return it }
        GameStorage.effective(context)?.let { lib ->
            val dir = File(lib.path).canonicalPath
            if (path.startsWith("$dir/")) return "$LIBRARY/" + path.removePrefix("$dir/")
        }
        // Inside the runtime's own tree (store installs on internal storage live at
        // root/Games/Stores): the tree is the session's / and needs no bind.
        val runtime = LinuxRuntime.rootDir(context).absolutePath
        if (path.startsWith("$runtime/")) return "/" + path.removePrefix("$runtime/")
        // Internal storage, bound at /root/Storage in every session: an exe picked anywhere on it.
        val storage = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (path.startsWith("$storage/")) return "/root/Storage/" + path.removePrefix("$storage/")
        return null
    }

    /** The .exe files a game folder offers, best first ([GameExePicker]). */
    fun candidates(folder: File): List<File> = rank(folder).exes

    /** [GameExePicker]'s ranking of [folder], without the names this list never offers. */
    fun rank(folder: File): GameExePicker.Ranking {
        val key = "${folder.path}|${folder.lastModified()}"
        val now = System.currentTimeMillis()
        ranked[key]?.takeIf { now - it.first < RANK_FRESH_MS }?.let { return it.second }
        return GameExePicker.rank(folder) { !SKIP.matches(it.name) }.also {
            if (ranked.size > 256) ranked.clear()
            ranked[key] = now to it
        }
    }

    /** A folder's ranking for a minute, so one add (rank, then the game built from it) walks it once. */
    private val ranked = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, GameExePicker.Ranking>>()
    private const val RANK_FRESH_MS = 60_000L

    /**
     * Names identified from an exe (Steam manifest, GOG info, version resource), by the exe's path,
     * size and time and its game folder's time (a GOG info file dropped in changes the latter).
     */
    private val identified = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The game's own title for [exe] when the disk names one ([GameIdentifier]: a Steam manifest,
     * a GOG info file, the exe's version resource), else null (the folder name stands).
     */
    internal fun identifiedName(folder: File, exe: File): String? {
        val key = "${exe.path}|${exe.length()}|${exe.lastModified()}|${folder.lastModified()}"
        identified[key]?.let { return it.ifEmpty { null } }
        val id = runCatching { GameIdentifier.identify(exe) }.getOrNull()
        val found = id?.name?.takeIf {
            id.nameSource in setOf(GameIdentifier.Source.STEAM_MANIFEST_ACF, GameIdentifier.Source.GOG_INFO, GameIdentifier.Source.PE_VERSION)
        }
        val name = found?.let { if (id?.nameSource == GameIdentifier.Source.PE_VERSION) betterSpelling(underscoresToSpaces(it), folder.name) else it }
        identified[key] = name.orEmpty()
        return name
    }

    /**
     * The exe's version resource often spells the title the way a programmer typed it ("Insane2")
     * while the folder has the real spacing ("Insane 2"). When both spell the same letters and
     * digits, the one with more word breaks wins; a tie keeps mixed case over all one case
     * ("DiRT 3" over "dirt 3"), else the exe's spelling. Different titles keep the exe's.
     */
    /** "Watch_Dogs" → "Watch Dogs": an underscore in a version-resource title is a programmer's space. */
    internal fun underscoresToSpaces(name: String): String =
        name.replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifEmpty { name }

    internal fun betterSpelling(peName: String, folderName: String): String {
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        if (key(peName) != key(folderName) || folderName.isBlank()) return peName
        fun breaks(s: String) = s.count { !it.isLetterOrDigit() }
        fun mixed(s: String) = s.any { it.isUpperCase() } && s.any { it.isLowerCase() }
        return when {
            breaks(folderName) > breaks(peName) -> folderName.trim()
            breaks(folderName) < breaks(peName) -> peName
            mixed(folderName) && !mixed(peName) -> folderName.trim()
            else -> peName
        }
    }

    fun scan(context: Context): List<Game> {
        val out = ArrayList<Game>()
        val explicit = entries(context)
        val explicitFolders = explicit.map { it.selection.folder.canonicalPath }.toSet()
        for (entry in explicit) {
            val selection = entry.selection
            scanGame(context, selection.folder, out, selection, entry.id)
        }
        val folders = SessionPrefs.addedGamesDirs(context).map(::File) + listOfNotNull(
            GameStorage.effective(context)?.let { File(it.path) },
            GameStorage.effective(context)?.let { File(it.path, "steamapps/common") },
        )
        val removed = SessionPrefs.removedAddedGames(context).toSet()
        for (dir in folders.distinctBy { it.absolutePath }) {
            if (!dir.isDirectory) { Log.w(TAG, "$dir is not a folder; skipped"); continue }
            val steamInstalls = steamInstallDirs(dir)
            val selectedFolders = if (dir in SessionPrefs.addedGamesDirs(context).map(::File)) preview(dir).map { it.folder }
                else dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
            for (folder in selectedFolders) {
                if (folder.name.lowercase() in steamInstalls || folder.name.equals("steamapps", ignoreCase = true)) continue
                if (runCatching { folder.canonicalPath in explicitFolders }.getOrDefault(false)) continue
                // Removed from the list by the user (the folder stays on disk); restored from settings.
                if (folder.path in removed) continue
                // Store installs are scanned below, per store.
                if (folder.name == "Games" && StoreInstallRoot.roots(context).any { it.absolutePath == folder.absolutePath }) continue
                scanGame(context, folder, out)
            }
        }
        // Games added as one picked exe (the Games tab's +), by that exe's folder.
        for (entry in AddedExes.list(context)) {
            val folder = File(entry.folder)
            if (folder.path in removed || !folder.isDirectory) continue
            scanGame(context, folder, out, single = entry)
        }
        // Games the Stores section installed: one folder each under <root>/<Store>/, with a sidecar.
        // An unfinished one (sidecar still `installing`, or none at all) is neither a game nor Custom.
        for (folder in StoreInstallRoot.gameFolders(context)) if (StoreInstallRoot.isFinished(folder)) scanGame(context, folder, out)
        return out.distinctBy { it.folder.canonicalPath }
    }

    /**
     * The game in [folderPath] alone, built by the same rules as [scan] (store sidecar, an entry
     * from the Games tab, the chosen exe, the edits) without walking every other game: what the
     * editor needs for one game. Null when that folder is not a game, or was removed.
     */
    fun single(context: Context, folderPath: String): Game? {
        val folder = File(folderPath)
        if (!folder.isDirectory || folderPath in SessionPrefs.removedAddedGames(context)) return null
        val out = ArrayList<Game>(1)
        scanGame(context, folder, out, single = AddedExes.list(context).firstOrNull { it.folder == folderPath })
        return out.firstOrNull()
    }

    /**
     * Stops listing the added game in [folder] at once: marked removed and dropped from the
     * library cache; the running client loses its shortcut ([appId], else the one stored for the
     * folder) and the listing is rewritten afterwards ([AddedGamesPublisher]). Nothing on disk is
     * deleted. Quick; call off the main thread (preferences and a small file).
     */
    fun remove(context: Context, folder: File, appId: Long? = null, name: String = folder.name) {
        val app = context.applicationContext
        val started = android.os.SystemClock.elapsedRealtime()
        val id = appId ?: SessionPrefs.addedGameStoredAppId(app, folder.path)
        SessionPrefs.setAddedGameRemoved(app, folder.path, true)
        LibraryCache.save(app, LibraryCache.load(app).filterNot { it.library == Library.ADDED && it.gameFiles?.path == folder.path })
        Log.i(TAG, "games: remove $name took ${android.os.SystemClock.elapsedRealtime() - started}ms")
        AddedGamesPublisher.later(app) { if (id != null) com.droiddeck.launcher.stores.SteamLiveShortcuts.remove(app, id) }
    }

    /** What [addExe] did with a picked exe. */
    sealed class AddResult {
        /** A new game, or the existing one the exe already is, or the listed game whose exe it now is. */
        class Added(val game: Game) : AddResult()
        class Existing(val game: Game) : AddResult()
        class Switched(val game: Game) : AddResult()
        /** The session cannot see the exe's path. */
        object Unreachable : AddResult()
        object NotFound : AddResult()
    }

    private fun canonical(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    private fun inside(path: String, folder: String) = path.startsWith(folder.trimEnd('/') + "/")

    /**
     * The game folder [exe] belongs to, without a scan: an entry from the Games tab, a removed
     * game, a store install, or the first-level folder under a Games folder (or the Steam library)
     * that holds it. Null when it is in none.
     */
    private fun ownerFolder(context: Context, exe: File): File? {
        val path = canonical(exe)
        fun holds(f: File) = inside(path, canonical(f))
        AddedExes.list(context).map { File(it.folder) }.firstOrNull(::holds)?.let { return it }
        SessionPrefs.removedAddedGames(context).map { File(it) }.firstOrNull(::holds)?.let { return it }
        StoreInstallRoot.gameFolders(context).firstOrNull(::holds)?.let { return it }
        val dirs = roots(context).map { it.host } + listOfNotNull(
            GameStorage.effective(context)?.let { File(it.path, "steamapps/common") },
            GameStorage.effective(context)?.let { File(it.path) },
        )
        for (dir in dirs) {
            val base = canonical(dir)
            if (!inside(path, base)) continue
            val first = path.removePrefix("$base/").substringBefore('/')
            File(dir, first).takeIf { it.isDirectory && first != "steamapps" }?.let { return it }
        }
        return null
    }

    /**
     * Adds the game [exe] is: the game itself when it is listed already, that game's exe switched
     * when [exe] sits in a listed (or removed) game's folder, else a new game for [exe]'s folder.
     * Only that one game is read; Steam (the running client, then the listing) follows on
     * [AddedGamesPublisher]. Nothing is added when the session cannot see the path.
     */
    fun addExe(context: Context, exe: File): AddResult {
        val app = context.applicationContext
        if (!exe.isFile) return AddResult.NotFound
        val path = canonical(exe)
        val owner = ownerFolder(app, exe)
        if (owner != null && owner.path !in SessionPrefs.removedAddedGames(app)) {
            single(app, owner.path)?.takeIf { canonical(it.exe) == path }?.let { return AddResult.Existing(it) }
        }
        if (owner == null && guestPath(app, exe) == null) return AddResult.Unreachable
        val folder = owner ?: exe.parentFile ?: return AddResult.NotFound
        SessionPrefs.setAddedGameRemoved(app, folder.path, false)
        SessionPrefs.setAddedGameExe(app, folder.path, exe.path)
        if (owner == null) AddedExes.put(app, AddedExes.Entry(folder.path, exe.path))
        val game = single(app, folder.path) ?: return AddResult.NotFound
        AddedGamesPublisher.later(app) { com.droiddeck.launcher.stores.SteamLiveShortcuts.sync(app, listOf(game)) { true } }
        Log.i(TAG, "added ${game.name} from its exe")
        return if (owner == null) AddResult.Added(game) else AddResult.Switched(game)
    }

    /** What [addFolder] did: the games it added, and how many of the folder's games were listed already. */
    class FolderResult(val added: List<Game>, val already: Int)

    /**
     * "Add all games in this folder": every subfolder of [dir] with an .exe becomes a game with
     * [GameExePicker]'s best one (marked uncertain where nothing clearly won), as an entry of its
     * own, not a Games folder. A subfolder that is, holds or sits in a listed game counts as
     * already there; a removed one comes back. Steam follows once, on [AddedGamesPublisher]. Blocking.
     */
    fun addFolder(context: Context, dir: File, onGame: (Game) -> Unit = {}, onChecking: (String?) -> Unit = {}): FolderResult {
        val app = context.applicationContext
        val removed = SessionPrefs.removedAddedGames(app).toSet()
        val listed = listedFolders(app, removed)
        var already = 0
        val fresh = ArrayList<String>()
        val added = ArrayList<Game>()
        for (sub in dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name.lowercase() }.orEmpty()) {
            val path = canonical(sub)
            if (listed.any { it == path || inside(path, it) || inside(it, path) }) { already++; continue }
            onChecking(sub.name)
            val best = rank(sub).exes.firstOrNull() ?: continue
            if (guestPath(app, best) == null) continue
            if (sub.path in removed) SessionPrefs.setAddedGameRemoved(app, sub.path, false)
            AddedExes.put(app, AddedExes.Entry(sub.path, best.path, picked = false))
            fresh.add(sub.path)
            single(app, sub.path)?.let { added.add(it); onGame(it) }
        }
        onChecking(null)
        if (fresh.isEmpty()) return FolderResult(emptyList(), already)
        AddedGamesPublisher.later(app) { com.droiddeck.launcher.stores.SteamLiveShortcuts.sync(app, added) { true } }
        Log.i(TAG, "added ${added.size} game(s) from ${dir.name}, $already already listed")
        return FolderResult(added, already)
    }

    /**
     * The game folders already listed, without building any game: the first-level folders of the
     * Games folders and the Steam library (as [scan] walks them), the Games tab's entries and the
     * store installs. Canonical paths; removed games are left out.
     */
    private fun listedFolders(context: Context, removed: Set<String>): List<String> {
        val out = ArrayList<String>()
        val dirs = roots(context).map { it.host } + listOfNotNull(
            GameStorage.effective(context)?.let { File(it.path) },
            GameStorage.effective(context)?.let { File(it.path, "steamapps/common") },
        )
        for (dir in dirs.distinctBy { it.absolutePath }) {
            for (folder in dir.listFiles { f -> f.isDirectory }.orEmpty()) {
                if (folder.path in removed || folder.name.equals("steamapps", ignoreCase = true)) continue
                if (folder.name == "Games" && StoreInstallRoot.roots(context).any { it.absolutePath == folder.absolutePath }) continue
                out.add(canonical(folder))
            }
        }
        AddedExes.list(context).filter { it.folder !in removed }.forEach { out.add(canonical(File(it.folder))) }
        StoreInstallRoot.gameFolders(context).forEach { out.add(canonical(it)) }
        return out
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

    private fun scanGame(
        context: Context, folder: File, out: MutableList<Game>,
        selection: Selection? = null, id: Long? = null, single: AddedExes.Entry? = null,
    ) {
        run {
            val sidecar = StoreGameSidecar.read(folder)
            val ranking = rank(folder)
            val candidates = ranking.exes
            val picked = SessionPrefs.addedGameExe(context, folder.path)
            val saved = picked.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
                ?: single?.takeIf { it.picked }?.let { File(it.exe) }?.takeIf { it.isFile }
            val found = if (selection != null) saved ?: selection.exe.takeIf { it.isFile } ?: return
                else saved ?: sidecar?.exeFile(folder)?.takeIf { it.isFile } ?: candidates.firstOrNull() ?: return
            val launch = if (selection == null && saved == null) sidecar?.launcherFile(folder)?.takeIf { it.isFile } ?: found else found
            val foundGuest = guestPath(context, launch)
            if (foundGuest == null) { Log.w(TAG, "${folder.name}: the session cannot see ${launch.path}"); return }
            val autoName = selection?.name ?: sidecar?.title ?: identifiedName(folder, found) ?: folder.name
            val crc = CRC32().apply { update(("\"${foundGuest.replaceFirst(Regex("^$LIBRARY/"), "$LEGACY_LIBRARY/")}\"" + autoName).toByteArray()) }.value
            val appId = when {
                selection != null -> id ?: SessionPrefs.addedGameId(context, folder.path).takeIf { it != 0L } ?: (crc or 0x80000000L)
                else -> SessionPrefs.addedGameAppId(context, folder.path, crc or 0x80000000L)
            }
            val config = accountConfig(File(LinuxRuntime.rootDir(context), "root/.local/share/Steam"))
            val record = config?.let { shortcutRecord(it, appId) }
            val rev = record?.optInt("rev", 0) ?: 0
            if (selection == null && sidecar == null && record != null) adoptSteamEdits(context, folder, record)
            val name = selection?.name ?: SessionPrefs.addedGameName(context, folder.path).takeIf { sidecar == null && it.isNotEmpty() } ?: autoName
            val adopted = if (selection == null) guestPath(context, folder)
                ?.let { steamTarget(folder, it, record, foundGuest, SessionPrefs.addedGameExeSeen(context, folder.path)) }
                ?.takeIf { SessionPrefs.adoptAddedGameExe(context, folder.path, picked, it.path, rev) } else null
            val exe = adopted ?: found
            val guestExe = if (adopted != null) guestPath(context, adopted) ?: return else foundGuest
            val guestDir = if (selection != null) guestPath(context, found.parentFile ?: folder) ?: return
                else SessionPrefs.addedGameStartIn(context, folder.path).takeIf { sidecar == null && it.isNotEmpty() }
                    ?: guestPath(context, (if (adopted != null) adopted else launch).parentFile ?: folder) ?: return
            val steamId = if (selection != null) null else config?.let { steamRoute(it, appId) }
            out.add(Game(folder, name, exe, guestExe, guestDir, appId,
                steamId?.toLong() ?: ((appId shl 32) or 0x02000000L), candidates, steamId,
                nonSteam = selection != null,
                source = sidecar?.store?.id ?: Library.ADDED, storeId = sidecar?.id,
                exeUncertain = selection == null && sidecar == null && saved == null && adopted == null && ranking.uncertain,
                launchOptions = if (sidecar == null) SessionPrefs.addedGameLaunch(context, folder.path) else "",
                single = single != null))
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

    fun pending(context: Context, game: Game): Boolean = runCatching {
        val array = JSONArray(File(context.filesDir, "session/added-games.json").readText())
        (0 until array.length()).none { i ->
            val o = array.getJSONObject(i)
            o.getLong("appid") == game.appId && o.getString("exe") == game.guestExe && o.getString("name") == game.name
        }
    }.getOrDefault(true)

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

    /**
     * What the user changed in Steam's Properties since the app last wrote the shortcut ([record]:
     * what the app asked for, and what Steam had): a new name, launch options, or a Start in other
     * than the exe's folder, taken over as the app's own. Each Steam value is taken once, so an app
     * edit made after it is not undone before the shortcut is written again.
     */
    internal fun adoptSteamEdits(context: Context, folder: File, record: JSONObject) {
        val app = record.optJSONObject("app") ?: return
        val steam = record.optJSONObject("steam") ?: return
        fun adopt(field: String, value: String, apply: (String) -> Unit) {
            if (SessionPrefs.addedGameAdopted(context, folder.path, field) == value) return
            SessionPrefs.setAddedGameAdopted(context, folder.path, field, value)
            apply(value)
        }
        if (steam.has("AppName") && app.has("AppName")) {
            val s = steam.optString("AppName"); val a = app.optString("AppName")
            if (s.isNotEmpty() && s != a) adopt("AppName", s) { SessionPrefs.setAddedGameName(context, folder.path, it) }
        }
        if (steam.has("LaunchOptions") && app.has("LaunchOptions")) {
            val s = steam.optString("LaunchOptions"); val a = app.optString("LaunchOptions")
            if (s != a) adopt("LaunchOptions", s) { SessionPrefs.setAddedGameLaunch(context, folder.path, it) }
        }
        if (steam.has("StartDir") && app.has("StartDir")) {
            val s = unquote(steam.optString("StartDir")); val a = unquote(app.optString("StartDir"))
            val exeDir = unquote(steam.optString("Exe")).substringBeforeLast('/', "")
            if (s.isNotEmpty() && s != a) adopt("StartDir", s) { SessionPrefs.setAddedGameStartIn(context, folder.path, if (it == exeDir) "" else it) }
        }
    }

    /** The list the session hands the runtime's shortcuts writer; one file per session start. */
    fun writeListing(context: Context, games: List<Game>): File {
        val file = File(context.filesDir, "session/added-games.json").apply { parentFile?.mkdirs() }
        val json = StringBuilder("[")
        games.forEachIndexed { i, g ->
            if (i > 0) json.append(',')
            json.append("{\"name\":").append(quote(g.name)).append(",\"exe\":").append(quote(g.guestExe))
                .append(",\"nonSteam\":").append(g.nonSteam)
                .append(",\"folder\":").append(quote(guestPath(context, g.folder) ?: g.guestDir))
                .append(",\"dir\":").append(quote(g.guestDir)).append(",\"appid\":").append(g.appId)
                .append(",\"seen\":").append(SessionPrefs.addedGameExeSeen(context, g.folder.path))
            if (g.launchOptions.isNotEmpty()) json.append(",\"launch\":").append(quote(g.launchOptions))
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
