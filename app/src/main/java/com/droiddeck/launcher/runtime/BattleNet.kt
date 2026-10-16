package com.droiddeck.launcher.runtime

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.GameEnvironmentStore
import java.io.File

/**
 * Blizzard's Battle.net on the desktop (the guest side is droiddeck-battlenet). Its installer, the
 * client and the games it installs share one Proton prefix, compatdata/[APP_ID], so one FEX choice
 * covers all of them: the newest d2rfix FEXCore build from the Nightlies, set as that id's FEX in
 * Components. The Proton wrapper applies it before every start in that prefix, from the desktop or
 * from Steam, and puts the Proton's own FEX back for every other game. Its environment profile (the
 * same id) gets a video memory cap for the games ([ensureVideoMemory]).
 *
 * The games it installs ([games]: its uninstall entries and product.db) become shortcuts in Steam
 * like the added games ([shortcut]): Battle.net.exe in its prefix with --exec="launch CODE", which
 * the Proton wrapper hands to droiddeck-battlenet (droiddeck-store-launch) to open Battle.net, have it
 * launch the game, and last until the game exits.
 */
object BattleNet {
    private const val TAG = "BattleNet"

    /** The prefix's id, as droiddeck-battlenet names it: crc32("\"droiddeck-battlenet\"Battle.net") | 0x80000000. */
    const val APP_ID = 2596113170L

    /** What droiddeck-battlenet leaves in the session root before a start, and the answer it waits for. */
    const val FEX_REQUEST = "battlenet-fex.request"
    const val FEX_RESULT = "battlenet-fex.result"

    /** Opens Battle.net, installing it first when it is not there; the desktop runs it (DesktopRequests). */
    const val PROGRAM = "/usr/local/bin/droiddeck-battlenet"

    /** The [com.droiddeck.launcher.frontend.Library.SteamGame.source] of Battle.net's games and of itself. */
    const val SOURCE = "battlenet"

    /** A Blizzard title: Battle.net's id for it (an uninstall entry's --uid), its name, and its code for --exec="launch CODE". */
    class Title(val uid: String, val name: String, val code: String)

    /**
     * The titles Battle.net installs, from Playnite's BattleNetLibrary and GOG Galaxy's Blizzard plugin.
     * An installed id not here is not listed (its launch code is not known): World of Warcraft Classic,
     * for one, which starts through its own launcher.
     */
    internal val TITLES = listOf(
        Title("wow", "World of Warcraft", "WoW"),
        Title("diablo3", "Diablo III", "D3"),
        Title("d3cn", "Diablo III", "D3CN"),
        Title("s2", "StarCraft II", "S2"),
        Title("s1", "StarCraft", "S1"),
        Title("hs_beta", "Hearthstone", "WTCG"),
        Title("heroes", "Heroes of the Storm", "Hero"),
        Title("prometheus", "Overwatch 2", "Pro"),
        Title("w3", "Warcraft III: Reforged", "W3"),
        Title("viper", "Call of Duty: Black Ops 4", "VIPR"),
        Title("odin", "Call of Duty: Modern Warfare", "ODIN"),
        Title("lazarus", "Call of Duty: Modern Warfare 2 Campaign Remastered", "LAZR"),
        Title("zeus", "Call of Duty: Black Ops Cold War", "ZEUS"),
        Title("fore", "Call of Duty: Vanguard", "FORE"),
        Title("auks", "Call of Duty: Modern Warfare II", "AUKS"),
        Title("pinta", "Call of Duty: Modern Warfare III", "PNTA"),
        Title("wlby", "Crash Bandicoot 4: It's About Time", "WLBY"),
        Title("osi", "Diablo II: Resurrected", "OSI"),
        Title("rtro", "Blizzard Arcade Collection", "RTRO"),
        Title("anbs", "Diablo Immortal", "ANBS"),
        Title("fenris", "Diablo IV", "Fen"),
        Title("fen", "Diablo IV", "Fen"),
        Title("d1", "Diablo", "D1"),
        Title("w1", "Warcraft: Orcs & Humans", "W1"),
        Title("w2", "Warcraft II: Battle.net Edition", "W2"),
        Title("w1r", "Warcraft: Remastered", "W1R"),
        Title("w2r", "Warcraft II: Remastered", "W2R"),
        Title("gryphon", "Warcraft Rumble", "GRY"),
        Title("aris", "Doom: The Dark Ages", "ARIS"),
        Title("scorpio", "Sea of Thieves", "SCOR"),
        Title("arkansas", "The Outer Worlds 2", "ARK"),
        Title("libra", "Tony Hawk's Pro Skater 3 + 4", "LBRA"),
        Title("aqua", "Avowed", "AQUA"),
    )

    /** What the store list offers: one entry a title, the China-only Diablo III left out. */
    val STORE: List<Title> get() = TITLES.distinctBy { it.code }.filter { it.uid != "d3cn" }

    /** Whether the store list offers [title] still: no installed game has its code. */
    fun notInstalled(title: Title, installedIds: Collection<String?>) =
        installedIds.mapNotNull { id -> id?.let(::title)?.code }.none { it == title.code }

    /** How long a title found with no cover is left before it is looked for again. */
    private const val COVER_RETRY_MS = 24 * 3600_000L

    /** Where a title's cover for the store list is kept: Steam's art for it, else SteamGridDB's. */
    private fun coverDir(context: Context, title: Title) = File(context.filesDir, "battlenet-art/${title.uid}")

    /** [title]'s cover, when one has been fetched. */
    fun cover(context: Context, title: Title): File? = File(coverDir(context, title), "p.jpg").takeIf { it.isFile }

    /**
     * Fetches the covers [titles] lack, as an added game's art is found: the Steam store's for a game
     * sold there too, else SteamGridDB's by name. A title found with none is tried again a day later.
     * Blocking; off the main thread. Whether any arrived.
     */
    fun fetchCovers(context: Context, titles: List<Title> = STORE): Boolean {
        if (!com.droiddeck.launcher.session.SessionPrefs.addedGamesArt(context)) return false
        val art = com.droiddeck.launcher.frontend.AddedGameArt
        val key = com.droiddeck.launcher.frontend.SteamGridDb.key(context)
        var got = false
        for (title in titles) {
            val dir = coverDir(context, title)
            val tried = File(dir, ".tried")
            if (File(dir, "p.jpg").isFile || System.currentTimeMillis() - tried.lastModified() < COVER_RETRY_MS) continue
            dir.mkdirs()
            val steam = runCatching { art.search(title.name) }.getOrNull()
            if (steam != null && runCatching { art.download(steam, dir) }.getOrDefault(false)) got = true
            if (!File(dir, "p.jpg").isFile && key.isNotEmpty()) {
                val id = runCatching { com.droiddeck.launcher.frontend.SteamGridDb.gameId(key, steam?.toIntOrNull(), title.name) }.getOrNull()
                if (id != null && runCatching { com.droiddeck.launcher.frontend.SteamGridDb.download(key, id, dir) }.getOrDefault(false)) got = true
            }
            runCatching { tried.writeText("") ; tried.setLastModified(System.currentTimeMillis()) }
        }
        return got
    }

    /** The locale an id can end in (diablo3_enus). */
    private val LOCALES = setOf(
        "enus", "engb", "dede", "eses", "esmx", "frfr", "itit", "kokr", "plpl", "ptbr", "ptpt", "ruru", "zhcn", "zhtw", "jajp", "thth", "trtr", "arsa",
    )

    /** Battle.net's own entries in product.db and the registry, which are not games. */
    private val NOT_GAMES = setOf("agent", "bna", "battle.net")

    /** One of Battle.net's games installed in its prefix: its id, name, launch code and folder. */
    class Game(val uid: String, val name: String, val code: String, val folder: File)

    /** The prefix, in the internal Steam library as droiddeck-battlenet keeps it. */
    fun prefix(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps/compatdata/$APP_ID")

    /** Battle.net itself, which every game's shortcut runs with the game's launch code. */
    fun client(context: Context) = File(prefix(context), "pfx/drive_c/Program Files (x86)/Battle.net/Battle.net.exe")

    /** Whether the client is installed: what droiddeck-battlenet opens is there. */
    fun installed(context: Context) = File(prefix(context), "pfx/drive_c/Program Files (x86)/Battle.net/Battle.net Launcher.exe").isFile

    /** The FEX package Components has set for the prefix; null until the first start sets one. */
    fun fexInUse(context: Context): String? = ComponentsManager.gameComponents(context, APP_ID.toString())["fex"]

    /** The title an installed id names: the id itself, or the id with its locale (diablo3_enus); null when not known. */
    internal fun title(uid: String): Title? {
        val id = uid.lowercase()
        TITLES.firstOrNull { it.uid == id }?.let { return it }
        val base = id.substringBeforeLast('_', "")
        return if (base.isNotEmpty() && id.substringAfterLast('_') in LOCALES) TITLES.firstOrNull { it.uid == base } else null
    }

    /**
     * The games Battle.net has installed in its prefix, one a title: what its uninstall entries
     * (Playnite reads these) and its Agent's product.db (where a game has none) say, as long as the
     * folder is there. Reads two files; off the main thread.
     */
    fun games(context: Context): List<Game> {
        val pfx = File(prefix(context), "pfx")
        val entries = runCatching { File(pfx, "system.reg").useLines { uninstallEntries(it) } }.getOrDefault(emptyList()) +
            runCatching { productDbEntries(File(pfx, "drive_c/ProgramData/Battle.net/Agent/product.db").readBytes()) }.getOrDefault(emptyList())
        val found = LinkedHashMap<String, Game>()
        for ((uid, path) in entries) {
            val title = title(uid)
            if (title == null) {
                if (uid.lowercase() !in NOT_GAMES) Log.i(TAG, "not listed: $uid (no launch code known)")
                continue
            }
            if (title.code in found) continue
            val folder = hostFolder(context, path)?.takeIf { it.isDirectory } ?: continue
            found[title.code] = Game(uid, title.name, title.code, folder)
        }
        return found.values.toList()
    }

    /** Where a C: path in the prefix is; null for another drive or a path out of drive_c. */
    internal fun hostFolder(context: Context, path: String): File? {
        val relative = Regex("""^[Cc]:[\\/](.*)$""").matchEntire(path.trim())?.groupValues?.get(1)?.replace('\\', '/')?.trim('/') ?: return null
        if (relative.isEmpty() || relative.split('/').any { it == ".." }) return null
        return File(prefix(context), "pfx/drive_c/$relative")
    }

    private val KEY = Regex("""^\[(.+)\](?:\s+\d+)?\s*$""")
    private val UNINSTALL = Regex("""(?i)\\CurrentVersion\\Uninstall\\[^\\]+$""")
    private val VALUE = Regex("""^"((?:[^"\\]|\\.)*)"=(?:str\(\d+\):)?"((?:[^"\\]|\\.)*)"\s*$""")
    private val UID = Regex("""--uid=([A-Za-z0-9_.-]+)""")

    /** (id, install folder) of every Battle.net uninstall entry in a Wine registry file's [lines]. */
    internal fun uninstallEntries(lines: Sequence<String>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var inside = false
        var uid: String? = null
        var location: String? = null
        fun flush() {
            val u = uid
            val l = location
            if (u != null && l != null) out.add(u to l)
            uid = null
            location = null
        }
        for (line in lines) {
            if (line.startsWith("[")) {
                flush()
                inside = KEY.matchEntire(line)?.groupValues?.get(1)?.let { UNINSTALL.containsMatchIn(unescape(it)) } == true
                continue
            }
            if (!inside) continue
            val value = VALUE.matchEntire(line) ?: continue
            val text = unescape(value.groupValues[2])
            when (unescape(value.groupValues[1]).lowercase()) {
                "uninstallstring" -> if (text.contains("battle.net", ignoreCase = true)) uid = UID.find(text)?.groupValues?.get(1)
                "installlocation" -> location = text
            }
        }
        flush()
        return out
    }

    /** A Wine registry string as written: backslash escapes, \xHHHH for what is not plain text. */
    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\' || i >= s.length) { out.append(c); continue }
            when (val e = s[i++]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                '0' -> out.append('\u0000')
                'x' -> {
                    var end = i
                    while (end < s.length && end - i < 4 && s[end].lowercaseChar() in "0123456789abcdef") end++
                    if (end > i) { out.append(s.substring(i, end).toInt(16).toChar()); i = end } else out.append('x')
                }
                else -> out.append(e)
            }
        }
        return out.toString()
    }

    /**
     * (id, install folder) of every install in product.db, the Agent's protobuf database: its field 1
     * holds the installs, each with the id in field 1 and its settings in field 3, the folder in that
     * one's field 1. Nothing when it cannot be read.
     */
    internal fun productDbEntries(bytes: ByteArray): List<Pair<String, String>> {
        val installs = runCatching { fields(bytes, 0, bytes.size) }.getOrDefault(emptyList()).filter { it.first == 1 }
        return installs.mapNotNull { (_, start, end) ->
            runCatching {
                val install = fields(bytes, start, end)
                val uid = install.firstOrNull { it.first == 1 }?.let { String(bytes, it.second, it.third - it.second) }
                val path = install.firstOrNull { it.first == 3 }?.let { s -> fields(bytes, s.second, s.third).firstOrNull { it.first == 1 } }
                    ?.let { String(bytes, it.second, it.third - it.second) }
                if (uid.isNullOrEmpty() || path.isNullOrEmpty()) null else uid to path
            }.getOrNull()
        }
    }

    /** The length-delimited fields of a protobuf message, (number, start, end); the others are skipped. */
    private fun fields(bytes: ByteArray, from: Int, to: Int): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        var i = from
        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                require(i < to && shift < 64) { "truncated" }
                val b = bytes[i++].toInt() and 0xff
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b < 0x80) return value
                shift += 7
            }
        }
        while (i < to) {
            val key = varint()
            when ((key and 7).toInt()) {
                0 -> varint()
                1 -> { require(to - i >= 8) { "truncated" }; i += 8 }
                2 -> {
                    val n = varint()
                    require(n in 0..(to - i).toLong()) { "truncated" }
                    out.add(Triple((key ushr 3).toInt(), i, i + n.toInt()))
                    i += n.toInt()
                }
                5 -> { require(to - i >= 4) { "truncated" }; i += 4 }
                else -> error("wire type ${key and 7}")
            }
        }
        return out
    }

    /**
     * [game]'s Steam shortcut, as an added game: Battle.net.exe in its prefix with the game's launch
     * code. Its launch options also point Steam at that prefix, for a launch that reaches Proton without
     * droiddeck-battlenet. The appid is kept for the folder, as an added game's is. Null when the
     * session cannot see the client.
     */
    fun shortcut(context: Context, game: Game): com.droiddeck.launcher.frontend.AddedGames.Game? {
        val added = com.droiddeck.launcher.frontend.AddedGames
        val client = client(context)
        val guestExe = added.guestPath(context, client) ?: return null
        val guestDir = added.guestPath(context, client.parentFile ?: return null) ?: return null
        val guestPrefix = added.guestPath(context, prefix(context)) ?: return null
        val crc = java.util.zip.CRC32().apply { update("\"$guestExe\"${game.name}".toByteArray()) }.value
        val appId = com.droiddeck.launcher.session.SessionPrefs.addedGameAppId(context, game.folder.path, crc or 0x80000000L)
        // Its own exe gives the shortcut its icon; Battle.net's when none is found.
        val exe = added.candidates(game.folder).firstOrNull() ?: client
        return com.droiddeck.launcher.frontend.AddedGames.Game(
            game.folder, game.name, exe, guestExe, guestDir, appId, (appId shl 32) or 0x02000000L, listOf(exe),
            source = SOURCE, storeId = game.uid,
            launchOptions = "STEAM_COMPAT_DATA_PATH=$guestPrefix %command% --exec=\"launch ${game.code}\"",
        )
    }

    /**
     * Battle.net gone: its prefix (the client, its settings and every game installed in it), the
     * desktop's menu entry, the installer and logs droiddeck-battlenet keeps, and the prefix's FEX
     * choice. Blocking; only with no session running, which could have the prefix open.
     */
    fun remove(context: Context) {
        val root = LinuxRuntime.rootDir(context)
        com.droiddeck.launcher.core.FileUtils.delete(prefix(context))
        File(root, "usr/local/share/applications/droiddeck-battlenet.desktop").delete()
        com.droiddeck.launcher.core.FileUtils.delete(File(root, "root/.cache/droiddeck/battlenet"))
        ComponentsManager.clearGameComponents(context, APP_ID.toString())
        Log.i(TAG, "removed")
    }

    /** FEXCore-2610-d2rfix28-linux.wcp: the FEX version, then the fix. */
    private val D2RFIX = Regex("""(?i)FEXCore-(\d+(?:\.\d+)*)-d2rfix(\d+)-linux\.wcp""")

    /** How old the Nightlies listing may be before a start fetches it again, in seconds. */
    private const val LISTING_MAX_AGE = 6 * 3600L

    private val NEWEST = Comparator<Pair<List<Long>, Long>> { a, b ->
        for (i in 0 until minOf(a.first.size, b.first.size)) if (a.first[i] != b.first[i]) return@Comparator a.first[i].compareTo(b.first[i])
        if (a.first.size != b.first.size) a.first.size.compareTo(b.first.size) else a.second.compareTo(b.second)
    }

    /** (FEX version, fix) of a d2rfix package's name; null for any other package. */
    internal fun d2rfix(file: String): Pair<List<Long>, Long>? {
        val m = D2RFIX.matchEntire(file) ?: return null
        val version = m.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
        return version to (m.groupValues[2].toLongOrNull() ?: return null)
    }

    /** The newest d2rfix FEX in [items]: the highest FEX version, then the highest fix. */
    internal fun newestD2rFix(items: List<ComponentsManager.CatalogItem>): ComponentsManager.CatalogItem? =
        items.filter { it.comp == "fex" }.mapNotNull { item -> d2rfix(item.file)?.let { item to it } }
            .maxWithOrNull { a, b -> NEWEST.compare(a.second, b.second) }?.first

    /**
     * The newest d2rfix FEX stored and set for the Battle.net prefix; its package name. The listing
     * is fetched again when it is older than [LISTING_MAX_AGE] or has no d2rfix build; a fetch that
     * fails falls back on what is cached. Blocking; throws when there is no d2rfix build to set.
     */
    fun ensureFex(context: Context): String = ensureFex(context) { ComponentsManager.catalog(it, true).items }

    /** [ensureFex], with [refresh] fetching the listing again. */
    @Synchronized
    internal fun ensureFex(context: Context, refresh: (Context) -> List<ComponentsManager.CatalogItem>): String {
        val item = newestD2rFix(listing(context, refresh)) ?: error("no d2rfix FEXCore build in the Nightlies listing")
        val file = ComponentsManager.safeName(item.file)
        if (ComponentsManager.packages(context).none { it.file == file }) {
            Log.i(TAG, "downloading ${item.file}")
            ComponentsManager.download(context, item) {}
        }
        val scope = APP_ID.toString()
        if (ComponentsManager.gameComponents(context, scope)["fex"] != file) {
            ComponentsManager.setGameComponent(context, scope, "fex", file)
            Log.i(TAG, "FEX for the Battle.net prefix: $file")
        }
        return file
    }

    private fun listing(context: Context, refresh: (Context) -> List<ComponentsManager.CatalogItem>): List<ComponentsManager.CatalogItem> {
        val cached = ComponentsManager.catalog(context, false)
        val age = System.currentTimeMillis() / 1000 - cached.fetchedAt
        if (age in 0 until LISTING_MAX_AGE && newestD2rFix(cached.items) != null) return cached.items
        val fetched = runCatching { refresh(context) }
            .onFailure { Log.w(TAG, "Nightlies listing: ${it.message}") }.getOrNull().orEmpty()
        return if (newestD2rFix(fetched) != null) fetched else cached.items
    }

    /** The video memory, in MB, the games are told of: a fifth of the phone's, in 512 MB steps, 1.5 to 3.5 GB (an exact 4 GB reads as none to a game counting in 32 bits). */
    internal fun videoMemory(totalMb: Long): Int = (totalMb / 5 / 512 * 512).coerceIn(1536, 3584).toInt()

    /** DXVK's options for [megabytes]: DXGI's dedicated and shared memory (Turnip's one heap is reported as both), D3D9's available memory. */
    internal fun videoMemoryOptions(megabytes: Int): String =
        "dxgi.maxDeviceMemory = $megabytes; dxgi.maxSharedMemory = $megabytes; d3d9.maxAvailableMemory = $megabytes"

    /**
     * The Battle.net profile's DXVK_CONFIG, unless it has its own: a video memory cap for every game
     * in the prefix. Turnip offers three quarters of the phone's memory as video memory and Blizzard's
     * games size their caches from what DXGI reports - Diablo IV kept 5.5 GB of textures on an 11 GB
     * phone, in memory it shares with everything else - so they are told of [videoMemory] instead, and
     * pick textures and caches to fit. D3D12's games too: vkd3d-proton's device comes from DXVK's DXGI.
     * It is the profile's own entry, so it shows and is changed in the game's environment. Whether it was set.
     */
    fun ensureVideoMemory(context: Context): Boolean {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory) ?: return false
        if (memory.totalMem <= 0) return false
        val options = videoMemoryOptions(videoMemory(memory.totalMem / (1024 * 1024)))
        val set = GameEnvironmentStore.setDefault(context, APP_ID.toString(), DXVK_CONFIG, options)
        if (set) Log.i(TAG, "DXVK_CONFIG for the Battle.net profile: $options")
        return set
    }

    private const val DXVK_CONFIG = "DXVK_CONFIG"

    /**
     * A start's request from droiddeck-battlenet in the session root [dir]: the FEX set on a thread
     * of its own, the profile's video memory cap ([ensureVideoMemory]), then the answer beside the
     * request, "ok <package>" or "error <why>".
     */
    fun answerFexRequest(context: Context, dir: File) {
        val app = context.applicationContext
        Thread({
            runCatching { ensureVideoMemory(app) }.onFailure { Log.w(TAG, "video memory cap for the Battle.net profile", it) }
            val answer = try {
                "ok " + ensureFex(app)
            } catch (e: Exception) {
                Log.w(TAG, "FEX for the Battle.net prefix", e)
                "error " + (e.message ?: e.javaClass.simpleName)
            }
            Log.i(TAG, "FEX request: $answer")
            val result = File(dir, FEX_RESULT)
            val partial = File(dir, "$FEX_RESULT.tmp")
            runCatching { partial.writeText(answer.replace('\n', ' ') + "\n"); partial.renameTo(result) }
        }, "battlenet-fex").start()
    }
}
