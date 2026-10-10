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
        /** The exe is the picker's guess and nothing clearly won ([GameExePicker.Ranking.uncertain]). */
        val exeUncertain: Boolean = false,
        /** Steam's launch options for the shortcut; "" for none. */
        val launchOptions: String = "",
        /** Added from the Games tab (an entry of its own), not found in a Games folder. */
        val single: Boolean = false,
    ) {
        fun folderName(): String = folder.name
    }

    /** A Games folder an earlier build had, and where the session sees it. */
    class Root(val host: File, val guest: String)

    /**
     * The Games folders earlier builds had, bound into the session at the guest paths they had
     * then (/root/Games/<name>), so the games in them (entries now, [GamesFolderMigration]) keep
     * their paths and shortcuts. Nothing adds one any more.
     */
    fun roots(context: Context): List<Root> {
        GamesFolderMigration.run(context)
        return SessionPrefs.gameFolderBinds(context).map { (host, guest) -> Root(File(host), guest) }
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
        // Internal storage, bound at /root/Storage in every session: an exe picked anywhere on it.
        val storage = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (path.startsWith("$storage/")) return "/root/Storage/" + path.removePrefix("$storage/")
        return null
    }

    /** The .exe files a game folder offers, best first ([GameExePicker]); declared Store exes lead. */
    fun candidates(folder: File): List<File> = rank(folder).exes

    /** [GameExePicker]'s ranking, with a Store-declared executable taking precedence when present. */
    fun rank(folder: File): GameExePicker.Ranking {
        val key = "${folder.path}|${folder.lastModified()}"
        val now = System.currentTimeMillis()
        ranked[key]?.takeIf { now - it.first < RANK_FRESH_MS }?.let { return it.second }
        val guessed = GameExePicker.rank(folder) { !SKIP.matches(it.name) }
        val declared = declaredExes(folder)
        val preferred = declared.map { it.path }.toSet()
        val result = if (declared.isEmpty()) guessed else GameExePicker.Ranking(
            declared + guessed.exes.filter { it.path !in preferred },
            List(declared.size) { Int.MAX_VALUE } + guessed.exes.zip(guessed.scores)
                .filter { it.first.path !in preferred }.map { it.second },
            uncertain = false,
        )
        if (ranked.size > 256) ranked.clear()
        ranked[key] = now to result
        return result
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
        GamesFolderMigration.run(context)
        val folders = listOfNotNull(
            GameStorage.effective(context)?.let { File(it.path) },
            GameStorage.effective(context)?.let { File(it.path, "steamapps/common") },
        )
        val removed = SessionPrefs.removedAddedGames(context).toSet()
        for (dir in folders.distinctBy { it.absolutePath }) {
            if (!dir.isDirectory) { Log.w(TAG, "$dir is not a folder; skipped"); continue }
            val steamInstalls = steamInstallDirs(dir)
            for (folder in dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()) {
                if (folder.name.lowercase() in steamInstalls || folder.name.equals("steamapps", ignoreCase = true)) continue
                // Removed from the list by the user (the folder stays on disk); restored from the settings page.
                if (folder.path in removed) continue
                // The store install root sits inside the Games storage; its games are scanned below, per store.
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
     * The game folder [exe] belongs to, without a scan: an entry from the Games tab (Games folders
     * of earlier builds included), a removed game, a store install, or the first-level folder under
     * the Steam library that holds it. Null when it is in none.
     */
    private fun ownerFolder(context: Context, exe: File): File? {
        GamesFolderMigration.run(context)
        val path = canonical(exe)
        fun holds(f: File) = inside(path, canonical(f))
        AddedExes.list(context).map { File(it.folder) }.firstOrNull(::holds)?.let { return it }
        SessionPrefs.removedAddedGames(context).map { File(it) }.firstOrNull(::holds)?.let { return it }
        StoreInstallRoot.gameFolders(context).firstOrNull(::holds)?.let { return it }
        // Games folders of earlier builds are entries now, found above.
        val dirs = listOfNotNull(
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
     * Steam library (as [scan] walks them), the Games tab's entries (Games folders of earlier builds
     * included) and the store installs. Canonical paths; removed games are left out.
     */
    private fun listedFolders(context: Context, removed: Set<String>): List<String> {
        val out = ArrayList<String>()
        GamesFolderMigration.run(context)
        val dirs = listOfNotNull(
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

    /** [single]: the entry a game added from the Games tab has; its exe stands in when no other is chosen and the user picked it. */
    private fun scanGame(context: Context, folder: File, out: MutableList<Game>, single: AddedExes.Entry? = null) {
        run {
            // A store install carries a sidecar; every one of them is a shortcut.
            val sidecar = StoreGameSidecar.read(folder)
            val ranking = rank(folder)
            val candidates = ranking.exes
            val picked = SessionPrefs.addedGameExe(context, folder.path)
            val chosen = picked.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
                ?: single?.takeIf { it.picked }?.let { File(it.exe) }?.takeIf { it.isFile }
            // A store install says which exe it is; the folder's guess only when that file is gone.
            val found = chosen ?: sidecar?.exeFile(folder)?.takeIf { it.isFile } ?: candidates.firstOrNull() ?: return
            // The store's launcher .bat stands in front of the exe when the game needs arguments or
            // environment; it is what the shortcut runs. Dropped when the user picked another exe.
            val launch = if (chosen == null) sidecar?.launcherFile(folder)?.takeIf { it.isFile } ?: found else found
            val foundGuest = guestPath(context, launch)
            if (foundGuest == null) { Log.w(TAG, "${folder.name}: the session cannot see ${launch.path}"); return }
            // A store install's title; else the title the disk gives (the shortcut's appid is kept
            // per folder from its first scan, so a name found later does not move it); else the folder.
            val autoName = sidecar?.title ?: identifiedName(folder, found) ?: folder.name
            // Keyed by the pre-rename path so shortcut ids, and the prefixes and saves under them, stay put.
            val crc = CRC32().apply { update(("\"${foundGuest.replaceFirst(Regex("^$LIBRARY/"), "$LEGACY_LIBRARY/")}\"" + autoName).toByteArray()) }.value
            val appId = SessionPrefs.addedGameAppId(context, folder.path, crc or 0x80000000L)
            val config = accountConfig(File(LinuxRuntime.rootDir(context), "root/.local/share/Steam"))
            val record = config?.let { shortcutRecord(it, appId) }
            val rev = record?.optInt("rev", 0) ?: 0
            // Name, Start in and launch options edited in Steam's own Properties become the app's.
            if (sidecar == null && record != null) adoptSteamEdits(context, folder, record)
            val name = SessionPrefs.addedGameName(context, folder.path).takeIf { sidecar == null && it.isNotEmpty() } ?: autoName
            val adopted = guestPath(context, folder)
                ?.let { steamTarget(folder, it, record, foundGuest, SessionPrefs.addedGameExeSeen(context, folder.path)) }
                ?.takeIf { SessionPrefs.adoptAddedGameExe(context, folder.path, picked, it.path, rev) }
            val exe = adopted ?: found
            val guestExe = if (adopted != null) guestPath(context, adopted) ?: return else foundGuest
            val guestDir = SessionPrefs.addedGameStartIn(context, folder.path).takeIf { sidecar == null && it.isNotEmpty() }
                ?: guestPath(context, (if (adopted != null) adopted else launch).parentFile ?: folder) ?: return
            val steamId = config?.let { steamRoute(it, appId) }
            out.add(Game(folder, name, exe, guestExe, guestDir, appId,
                steamId?.toLong() ?: ((appId shl 32) or 0x02000000L), candidates, steamId,
                source = sidecar?.store?.id ?: Library.ADDED, storeId = sidecar?.id,
                exeUncertain = sidecar == null && chosen == null && adopted == null && ranking.uncertain,
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
