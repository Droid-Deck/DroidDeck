package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import java.io.File
import java.util.zip.CRC32

object AddedGames {
    private const val TAG = "AddedGames"
    private const val LIBRARY = "/mnt/droiddeck-sd"
    private const val LEGACY_LIBRARY = "/mnt/bannerlator-sd"

    class Game(
        val folder: File, val name: String,
        val exe: File,
        val guestExe: String, val guestDir: String,
        val appId: Long,
        val gameId: Long,
        val candidates: List<File>,
        val steamAppId: Int? = null,
        val source: String = Library.ADDED,
        val storeId: String? = null,
        val exeUncertain: Boolean = false,
        val launchOptions: String = "",
        val single: Boolean = false,
    ) {
        fun folderName(): String = folder.name
    }

    class Root(val host: File, val guest: String)

    fun roots(context: Context): List<Root> {
        GamesFolderMigration.run(context)
        return SessionPrefs.gameFolderBinds(context).map { (host, guest) -> Root(File(host), guest) }
    }

    private val SKIP_PREFIX = Regex("(?i)^(unins|setup|vc_?redist|dxsetup|dxwebsetup|dotnet|directx|easyanticheat|battleye).*")
    private val SKIP_PART = Regex("(?i)redist|prereq|installer|uninstall|crashreport|crashhandler|crashpad|crashsender|launchhelper")
    private val SKIP_WORDS = setOf(
        "eac", "report", "reporter", "editor", "server", "benchmark", "helper",
        "update", "updater", "config", "configure", "configurator", "configuration", "settings",
    )
    private val WORD = Regex("[A-Z]?[a-z]+|[A-Z]+(?![a-z])|[0-9]+")

    internal fun skipExe(name: String): Boolean {
        if (!name.endsWith(".exe", ignoreCase = true)) return false
        val base = name.dropLast(4)
        if (SKIP_PREFIX.matches(base) || SKIP_PART.containsMatchIn(base)) return true
        return WORD.findAll(base).any { it.value.lowercase() in SKIP_WORDS }
    }

    const val GUEST_DIR = "/root/Games"

    fun guestPath(context: Context, host: File): String? {
        val path = host.absolutePath
        for (root in roots(context)) {
            val dir = root.host.absolutePath
            if (path == dir) return root.guest
            if (path.startsWith("$dir/")) return root.guest + "/" + path.removePrefix("$dir/")
        }
        SessionPrefs.romsDir(context).takeIf { it.isNotEmpty() }?.let { roms ->
            if (path.startsWith("$roms/")) return "/root/ROMs/" + path.removePrefix("$roms/")
        }
        StoreInstallRoot.externalGuestPath(context, host)?.let { return it }
        GameStorage.effective(context)?.let { lib ->
            if (path.startsWith("${lib.path}/")) return "$LIBRARY/" + path.removePrefix("${lib.path}/")
        }
        val runtime = LinuxRuntime.rootDir(context).absolutePath
        if (path.startsWith("$runtime/")) return "/" + path.removePrefix("$runtime/")
        val storage = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (path.startsWith("$storage/")) return "/root/Storage/" + path.removePrefix("$storage/")
        return null
    }

    fun hostPath(context: Context, guest: String): File? {
        if (!guest.startsWith("/") || guest.split('/').any { it == ".." }) return null
        val mapped = roots(context).map { it.host to it.guest } + StoreInstallRoot.externalRoots(context) +
            listOfNotNull(GameStorage.effective(context)?.let { File(it.path) to LIBRARY }) +
            (android.os.Environment.getExternalStorageDirectory() to "/root/Storage")
        for ((host, root) in mapped.sortedByDescending { it.second.length }) {
            if (guest == root) return host
            if (guest.startsWith("$root/")) return File(host, guest.removePrefix("$root/"))
        }
        return File(LinuxRuntime.rootDir(context), guest.removePrefix("/"))
    }

    fun candidates(folder: File): List<File> = rank(folder).exes

    fun rank(folder: File): GameExePicker.Ranking {
        val key = "${folder.path}|${folder.lastModified()}"
        val now = System.currentTimeMillis()
        ranked[key]?.takeIf { now - it.first < RANK_FRESH_MS }?.let { return it.second }
        val guessed = GameExePicker.rank(folder) { !skipExe(it.name) }
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

    private val ranked = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, GameExePicker.Ranking>>()
    private const val RANK_FRESH_MS = 60_000L

    private val identified = java.util.concurrent.ConcurrentHashMap<String, String>()

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
    internal fun declaredExes(folder: File): List<File> {
        val names = DECLARED.flatMap { (file, pattern) ->
            runCatching { pattern.findAll(File(folder, file).readText()).map { it.groupValues[1] }.toList() }.getOrDefault(emptyList())
        }
        val root = runCatching { folder.canonicalPath + File.separator }.getOrNull() ?: return emptyList()
        return names.asSequence()
            .map { File(folder, it.trim().replace('\\', '/').trimStart('/')) }
            .filter { it.name.endsWith(".exe", ignoreCase = true) && !skipExe(it.name) && it.isFile }
            .filter { runCatching { it.canonicalPath.startsWith(root) }.getOrDefault(false) }
            .distinctBy { it.path }
            .toList()
    }

    private val DECLARED = listOf(
        "MicrosoftGame.config" to Regex("<Executable\\b[^>]*?\\bName\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
        "appxmanifest.xml" to Regex("<Application\\b[^>]*?\\bExecutable\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
    )

    private val GAME_PARTS = setOf(
        "bin", "bin32", "bin64", "binaries", "x64", "x86", "win64", "win32", "windows",
        "launcher", "game", "engine", "system", "data", "app",
    )

    internal fun groupGames(folder: File): List<File>? {
        val files = folder.listFiles() ?: return null
        if (files.any { it.isFile && it.name.endsWith(".exe", ignoreCase = true) && !skipExe(it.name) }) return null
        if (declaredExes(folder).isNotEmpty()) return null
        val dirs = files.filter { it.isDirectory && !it.name.startsWith(".") && !isSteamLibraryFolder(it.name) }.sortedBy { it.name.lowercase() }
        if (dirs.any { it.name.lowercase() in GAME_PARTS || GameExePicker.auxiliaryDir(it) }) return null
        return dirs.filter { rank(it).exes.isNotEmpty() }.takeIf { it.size >= 2 }
    }

    private fun expand(folder: File, entries: Set<String>): List<File> =
        if (folder.path in entries) listOf(folder) else groupGames(folder) ?: listOf(folder)

    private fun entryFolders(context: Context): Set<String> = AddedExes.list(context).map { it.folder }.toSet()

    /** The card's Steam libraries: the arm64 client's steamapps and the x86-64 client's own folder beside it. */
    internal fun isSteamLibraryFolder(name: String): Boolean =
        name.equals("steamapps", ignoreCase = true) || name == com.droiddeck.launcher.session.GameStorage.X64_CARD_DIR

    fun scan(context: Context): List<Game> {
        val out = ArrayList<Game>()
        GamesFolderMigration.run(context)
        val folders = listOfNotNull(
            GameStorage.effective(context)?.let { File(it.path) },
            GameStorage.effective(context)?.let { File(it.path, "steamapps/common") },
        )
        val removed = SessionPrefs.removedAddedGames(context).toSet()
        val entries = entryFolders(context)
        for (dir in folders.distinctBy { it.absolutePath }) {
            if (!dir.isDirectory) { Log.w(TAG, "$dir is not a folder; skipped"); continue }
            val steamInstalls = steamInstallDirs(dir)
            for (folder in dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()) {
                if (folder.name.lowercase() in steamInstalls || isSteamLibraryFolder(folder.name)) continue
                if (folder.path in removed) continue
                if (folder.name == "Games" && StoreInstallRoot.roots(context).any { it.absolutePath == folder.absolutePath }) continue
                for (game in expand(folder, entries)) if (game.path !in removed) scanGame(context, game, out)
            }
        }
        for (entry in AddedExes.list(context)) {
            val folder = File(entry.folder)
            if (folder.path in removed || !folder.isDirectory) continue
            scanGame(context, folder, out, single = entry)
        }
        for (folder in StoreInstallRoot.gameFolders(context)) if (StoreInstallRoot.isFinished(folder)) scanGame(context, folder, out)
        for (game in com.droiddeck.launcher.runtime.BattleNet.games(context)) {
            if (game.folder.path !in removed) com.droiddeck.launcher.runtime.BattleNet.shortcut(context, game)?.let(out::add)
        }
        return out.distinctBy { it.folder.canonicalPath }
    }

    fun single(context: Context, folderPath: String): Game? {
        val folder = File(folderPath)
        if (!folder.isDirectory || folderPath in SessionPrefs.removedAddedGames(context)) return null
        val out = ArrayList<Game>(1)
        scanGame(context, folder, out, single = AddedExes.list(context).firstOrNull { it.folder == folderPath })
        return out.firstOrNull()
    }

    fun remove(context: Context, folder: File, appId: Long? = null, name: String = folder.name) {
        val app = context.applicationContext
        val started = android.os.SystemClock.elapsedRealtime()
        val id = appId ?: SessionPrefs.addedGameStoredAppId(app, folder.path)
        SessionPrefs.setAddedGameRemoved(app, folder.path, true)
        LibraryCache.save(app, LibraryCache.load(app).filterNot { it.library == Library.ADDED && it.gameFiles?.path == folder.path })
        Log.i(TAG, "games: remove $name took ${android.os.SystemClock.elapsedRealtime() - started}ms")
        AddedGamesPublisher.later(app) { if (id != null) com.droiddeck.launcher.stores.SteamLiveShortcuts.remove(app, id) }
    }

    sealed class AddResult {
        class Added(val game: Game) : AddResult()
        class Existing(val game: Game) : AddResult()
        class Switched(val game: Game) : AddResult()
        object Unreachable : AddResult()
        object NotFound : AddResult()
    }

    private fun canonical(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    private fun inside(path: String, folder: String) = path.startsWith(folder.trimEnd('/') + "/")

    private fun ownerFolder(context: Context, exe: File): File? {
        GamesFolderMigration.run(context)
        val path = canonical(exe)
        fun holds(f: File) = inside(path, canonical(f))
        val entries = AddedExes.list(context)
        fun owns(f: File) = holds(f) && sameGame(f, currentExe(context, entries, f), exe)
        entries.map { File(it.folder) }.filter(::owns).maxByOrNull { canonical(it).length }?.let { return it }
        SessionPrefs.removedAddedGames(context).map { File(it) }.filter { owns(it) && groupGames(it) == null }.maxByOrNull { canonical(it).length }?.let { return it }
        StoreInstallRoot.gameFolders(context).firstOrNull(::holds)?.let { return it }
        val removed = SessionPrefs.removedAddedGames(context)
        val dirs = listOfNotNull(
            GameStorage.effective(context)?.let { File(it.path, "steamapps/common") },
            GameStorage.effective(context)?.let { File(it.path) },
        )
        for (dir in dirs) {
            val base = canonical(dir)
            if (!inside(path, base)) continue
            val first = path.removePrefix("$base/").substringBefore('/')
            val top = File(dir, first).takeIf { it.isDirectory && !isSteamLibraryFolder(first) && it.path !in removed } ?: continue
            val group = groupGames(top) ?: return top
            if (top.path in entries.map { it.folder }) return null
            return group.firstOrNull(::holds)
        }
        return null
    }

    private fun currentExe(context: Context, entries: List<AddedExes.Entry>, folder: File): File? =
        SessionPrefs.addedGameExe(context, folder.path).takeIf { it.isNotEmpty() }?.let(::File)
            ?: entries.firstOrNull { it.folder == folder.path }?.let { File(it.exe) }
            ?: rank(folder).exes.firstOrNull()

    private fun sameGame(folder: File, current: File?, pick: File): Boolean {
        val base = canonical(folder)
        if (current == null || pick.parentFile?.let(::canonical) == base) return true
        val known = canonical(current)
        if (!inside(known, base) || known.substringBeforeLast('/') == base) return true
        val picked = canonical(pick)
        fun top(p: String) = p.removePrefix("$base/").substringBefore('/')
        fun stem(p: String) = p.substringAfterLast('/').lowercase().removeSuffix(".exe").replace(EXE_BUILD, "").filter { it.isLetterOrDigit() }
        return top(known) == top(picked) || stem(known).let { it.isNotEmpty() && it == stem(picked) }
    }

    private val EXE_BUILD = Regex("([-_. ](x64|x86|win64|win32|64|32|shipping))+$")

    fun addExe(context: Context, exe: File): AddResult {
        val app = context.applicationContext
        if (!exe.isFile) return AddResult.NotFound
        val path = canonical(exe)
        val owner = ownerFolder(app, exe)
        if (owner != null && owner.path !in SessionPrefs.removedAddedGames(app)) {
            single(app, owner.path)?.takeIf { canonical(it.exe) == path }?.let { return AddResult.Existing(it) }
        }
        if (guestPath(app, exe) == null) {
            (owner ?: exe.parentFile)?.takeIf { guestPath(app, it) == null }?.let { bindFolder(app, it) }
            if (guestPath(app, exe) == null) return AddResult.Unreachable
        }
        val folder = owner ?: exe.parentFile ?: return AddResult.NotFound
        SessionPrefs.setAddedGameRemoved(app, folder.path, false)
        SessionPrefs.setAddedGameExe(app, folder.path, exe.path)
        if (owner == null) AddedExes.put(app, AddedExes.Entry(folder.path, exe.path))
        val game = single(app, folder.path) ?: return AddResult.NotFound
        AddedGamesPublisher.later(app) { com.droiddeck.launcher.stores.SteamLiveShortcuts.sync(app, listOf(game)) { true } }
        Log.i(TAG, "added ${game.name} from its exe")
        return if (owner == null) AddResult.Added(game) else AddResult.Switched(game)
    }

    class FolderResult(val added: List<Game>, val already: Int)

    fun addFolder(context: Context, dir: File, onGame: (Game) -> Unit = {}, onChecking: (String?) -> Unit = {}, showHidden: Boolean = false): FolderResult {
        val app = context.applicationContext
        // Binds are mounted at session start, so a new one reaches the session from the next start.
        if (guestPath(app, dir) == null) bindFolder(app, dir)
        val removed = SessionPrefs.removedAddedGames(app).toSet()
        val listed = listedFolders(app, removed)
        val entries = entryFolders(app)
        var already = 0
        val fresh = ArrayList<String>()
        val added = ArrayList<Game>()
        for (sub in dir.listFiles { f -> f.isDirectory && (showHidden || !f.name.startsWith(".")) }?.sortedBy { it.name.lowercase() }.orEmpty().flatMap { expand(it, entries) }) {
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

    @Synchronized
    internal fun bindFolder(context: Context, dir: File) {
        if (!dir.isDirectory || !dir.canRead()) return
        val binds = SessionPrefs.gameFolderBinds(context)
        if (binds.any { it.first == dir.path }) return
        val taken = binds.map { it.second }.toSet()
        val base = GamesFolderMigration.legacyRoots(binds.map { it.first } + dir.path).last().guest
        var guest = base
        var n = 2
        while (guest in taken) guest = "$base-${n++}"
        SessionPrefs.setGameFolderBinds(context, binds + (dir.path to guest))
        Log.i(TAG, "games: bound $dir at $guest")
    }

    private fun listedFolders(context: Context, removed: Set<String>): List<String> {
        val out = ArrayList<String>()
        val entries = entryFolders(context)
        GamesFolderMigration.run(context)
        val dirs = listOfNotNull(
            GameStorage.effective(context)?.let { File(it.path) },
            GameStorage.effective(context)?.let { File(it.path, "steamapps/common") },
        )
        for (dir in dirs.distinctBy { it.absolutePath }) {
            for (folder in dir.listFiles { f -> f.isDirectory }.orEmpty()) {
                if (folder.path in removed || isSteamLibraryFolder(folder.name)) continue
                if (folder.name == "Games" && StoreInstallRoot.roots(context).any { it.absolutePath == folder.absolutePath }) continue
                expand(folder, entries).filter { it.path !in removed }.forEach { out.add(canonical(it)) }
            }
        }
        AddedExes.list(context).filter { it.folder !in removed }.forEach { out.add(canonical(File(it.folder))) }
        StoreInstallRoot.gameFolders(context).forEach { out.add(canonical(it)) }
        return out
    }

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

    private fun scanGame(context: Context, folder: File, out: MutableList<Game>, single: AddedExes.Entry? = null) {
        run {
            val sidecar = StoreGameSidecar.read(folder)
            sidecar?.takeIf { it.id.isNotEmpty() }?.let { SessionPrefs.followStoreGame(context, "${it.store.id}:${it.id}", folder.path) { old -> !File(old).exists() } }
            val ranking = rank(folder)
            val candidates = ranking.exes
            val picked = SessionPrefs.addedGameExe(context, folder.path)
            val chosen = picked.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
                ?: single?.takeIf { it.picked }?.let { File(it.exe) }?.takeIf { it.isFile }
            val found = chosen ?: sidecar?.exeFile(folder)?.takeIf { it.isFile } ?: candidates.firstOrNull() ?: return
            val epicLauncher = sidecar?.takeIf { it.store == Store.EPIC && (chosen == null || canonical(chosen) == canonical(it.exeFile(folder))) }
                ?.launcherFile(folder)?.takeIf { it.isFile }
            val launch = epicLauncher ?: if (chosen == null) sidecar?.launcherFile(folder)?.takeIf { it.isFile } ?: found else found
            val foundGuest = guestPath(context, launch)
            if (foundGuest == null) { Log.w(TAG, "${folder.name}: the session cannot see ${launch.path}"); return }
            val autoName = sidecar?.title ?: identifiedName(folder, found) ?: folder.name
            val crc = CRC32().apply { update(("\"${foundGuest.replaceFirst(Regex("^$LIBRARY/"), "$LEGACY_LIBRARY/")}\"" + autoName).toByteArray()) }.value
            val appId = SessionPrefs.addedGameAppId(context, folder.path, crc or 0x80000000L)
            val config = accountConfig(SessionPrefs.activeSteamRoot(context))
            val record = config?.let { shortcutRecord(it, appId) }
            val rev = record?.optInt("rev", 0) ?: 0
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

    private class LoginUser(val id: String, val mostRecent: Boolean, val autoLogin: Boolean, val timestamp: Long)

    internal fun accountConfig(root: File): File? {
        val users = runCatching { File(root, "config/loginusers.vdf").readText() }.getOrDefault("")
        val recent = Regex(""""(\d{5,})"\s*\{([^}]*)\}""").findAll(users).map {
            val body = it.groupValues[2]
            LoginUser(it.groupValues[1], Regex(""""MostRecent"\s*"1"""").containsMatchIn(body), Regex(""""AutoLogin"\s*"1"""").containsMatchIn(body),
                Regex(""""timestamp"\s*"(\d+)"""").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L)
        }.filter { it.mostRecent || it.autoLogin || it.timestamp > 0 }.toList().asReversed()
            .maxWithOrNull(compareBy<LoginUser>({ it.mostRecent }, { it.autoLogin }, { it.timestamp }))
        val account = recent?.id?.toLongOrNull()?.let { (it - 76561197960265728L).toString() }
            ?: File(root, "userdata").list()?.filter { it.isNotEmpty() && it.all(Char::isDigit) && it != "0" }?.singleOrNull()
        return account?.let { File(root, "userdata/$it/config") }
    }

    private fun steamRoute(config: File, appId: Long): Int? = runCatching {
        JSONObject(File(config, ".droiddeck-routes.json").readText()).optInt(appId.toString()).takeIf { it > 0 }
    }.getOrNull()

    fun inSteam(context: Context, appId: Long): Boolean =
        accountConfig(SessionPrefs.activeSteamRoot(context))?.let { shortcutRecord(it, appId) } != null

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

    fun writeListing(context: Context, games: List<Game>,
        extra: List<JSONObject> = listOfNotNull(com.droiddeck.launcher.stores.epic.EpicClient.steamListing(context))): File {
        val file = File(context.filesDir, "session/added-games.json").apply { parentFile?.mkdirs() }
        val json = StringBuilder("[")
        games.forEachIndexed { i, g ->
            if (i > 0) json.append(',')
            json.append("{\"name\":").append(quote(g.name)).append(",\"exe\":").append(quote(g.guestExe))
                .append(",\"folder\":").append(quote(guestPath(context, g.folder) ?: g.guestDir))
                .append(",\"dir\":").append(quote(g.guestDir)).append(",\"appid\":").append(g.appId)
                .append(",\"seen\":").append(SessionPrefs.addedGameExeSeen(context, g.folder.path))
            if (g.launchOptions.isNotEmpty()) json.append(",\"launch\":").append(quote(g.launchOptions))
            if (g.source != Library.ADDED) json.append(",\"store\":").append(quote(g.source))
            val art = AddedGameArt.resolve(context, g)
            val pieces = listOf("p" to art.portrait, "header" to art.header, "hero" to art.hero, "logo" to art.logo, "icon" to art.icon)
                .mapNotNull { (k, f) -> f?.let { file -> artGuestPath(context, file)?.let { k to it } } }
            if (pieces.isNotEmpty()) json.append(",\"art\":{").append(pieces.joinToString(",") { (k, v) -> quote(k) + ":" + quote(v) }).append('}')
            json.append('}')
        }
        extra.forEachIndexed { i, entry ->
            if (games.isNotEmpty() || i > 0) json.append(',')
            json.append(entry.toString())
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
