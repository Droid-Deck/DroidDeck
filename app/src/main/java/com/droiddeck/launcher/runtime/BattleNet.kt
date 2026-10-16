package com.droiddeck.launcher.runtime

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.GameEnvironmentStore
import java.io.File

/**
 * Battle.net's installer, client and games share one Proton prefix, compatdata/[APP_ID], and therefore
 * one environment profile and one FEX choice.
 */
object BattleNet {
    private const val TAG = "BattleNet"

    // Must match droiddeck-battlenet: crc32("\"droiddeck-battlenet\"Battle.net") | 0x80000000.
    const val APP_ID = 2596113170L

    const val FEX_REQUEST = "battlenet-fex.request"
    const val FEX_RESULT = "battlenet-fex.result"

    const val PROGRAM = "/usr/local/bin/droiddeck-battlenet"

    const val SOURCE = "battlenet"

    class Title(val uid: String, val name: String, val code: String)

    // From Playnite's BattleNetLibrary and GOG Galaxy's Blizzard plugin. An installed id not here has no
    // known launch code and is not listed (World of Warcraft Classic starts through its own launcher).
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

    val STORE: List<Title> get() = TITLES.distinctBy { it.code }.filter { it.uid != "d3cn" }

    fun notInstalled(title: Title, installedIds: Collection<String?>) =
        installedIds.mapNotNull { id -> id?.let(::title)?.code }.none { it == title.code }

    private const val COVER_RETRY_MS = 24 * 3600_000L

    private fun coverDir(context: Context, title: Title) = File(context.filesDir, "battlenet-art/${title.uid}")

    fun cover(context: Context, title: Title): File? = File(coverDir(context, title), "p.jpg").takeIf { it.isFile }

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

    private val LOCALES = setOf(
        "enus", "engb", "dede", "eses", "esmx", "frfr", "itit", "kokr", "plpl", "ptbr", "ptpt", "ruru", "zhcn", "zhtw", "jajp", "thth", "trtr", "arsa",
    )

    private val NOT_GAMES = setOf("agent", "bna", "battle.net")

    class Game(val uid: String, val name: String, val code: String, val folder: File)

    fun prefix(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps/compatdata/$APP_ID")

    fun client(context: Context) = File(prefix(context), "pfx/drive_c/Program Files (x86)/Battle.net/Battle.net.exe")

    fun installed(context: Context) = File(prefix(context), "pfx/drive_c/Program Files (x86)/Battle.net/Battle.net Launcher.exe").isFile

    fun fexInUse(context: Context): String? = ComponentsManager.gameComponents(context, APP_ID.toString())["fex"]

    fun launchArgs(game: com.droiddeck.launcher.frontend.Library.SteamGame): List<String>? {
        if (game.source != SOURCE) return null
        val code = game.storeId?.let(::title)?.code ?: return null
        return listOf("launch", code)
    }

    fun sessionName(args: List<String>): String? =
        if (args.getOrNull(0) == "launch") args.getOrNull(1)?.let { code -> TITLES.firstOrNull { it.code.equals(code, ignoreCase = true) }?.name }
        else null

    internal fun title(uid: String): Title? {
        val id = uid.lowercase()
        TITLES.firstOrNull { it.uid == id }?.let { return it }
        val base = id.substringBeforeLast('_', "")
        return if (base.isNotEmpty() && id.substringAfterLast('_') in LOCALES) TITLES.firstOrNull { it.uid == base } else null
    }

    // A game without an uninstall entry is listed only in the Agent's product.db.
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

    internal fun hostFolder(context: Context, path: String): File? {
        val relative = Regex("""^[Cc]:[\\/](.*)$""").matchEntire(path.trim())?.groupValues?.get(1)?.replace('\\', '/')?.trim('/') ?: return null
        if (relative.isEmpty() || relative.split('/').any { it == ".." }) return null
        return File(prefix(context), "pfx/drive_c/$relative")
    }

    private val KEY = Regex("""^\[(.+)\](?:\s+\d+)?\s*$""")
    private val UNINSTALL = Regex("""(?i)\\CurrentVersion\\Uninstall\\[^\\]+$""")
    private val VALUE = Regex("""^"((?:[^"\\]|\\.)*)"=(?:str\(\d+\):)?"((?:[^"\\]|\\.)*)"\s*$""")
    private val UID = Regex("""--uid=([A-Za-z0-9_.-]+)""")

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

    // product.db is protobuf: field 1 holds the installs, each with the id in field 1 and its settings
    // in field 3, whose field 1 is the install folder.
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

    fun shortcut(context: Context, game: Game): com.droiddeck.launcher.frontend.AddedGames.Game? {
        val added = com.droiddeck.launcher.frontend.AddedGames
        val client = client(context)
        val guestExe = added.guestPath(context, client) ?: return null
        val guestDir = added.guestPath(context, client.parentFile ?: return null) ?: return null
        val guestPrefix = added.guestPath(context, prefix(context)) ?: return null
        val crc = java.util.zip.CRC32().apply { update("\"$guestExe\"${game.name}".toByteArray()) }.value
        val appId = com.droiddeck.launcher.session.SessionPrefs.addedGameAppId(context, game.folder.path, crc or 0x80000000L)
        val exe = added.candidates(game.folder).firstOrNull() ?: client
        return com.droiddeck.launcher.frontend.AddedGames.Game(
            game.folder, game.name, exe, guestExe, guestDir, appId, (appId shl 32) or 0x02000000L, listOf(exe),
            source = SOURCE, storeId = game.uid,
            launchOptions = "STEAM_COMPAT_DATA_PATH=$guestPrefix %command% --exec=\"launch ${game.code}\"",
        )
    }

    // Only with no session running: one could have the prefix open.
    fun remove(context: Context) {
        val root = LinuxRuntime.rootDir(context)
        com.droiddeck.launcher.core.FileUtils.delete(prefix(context))
        File(root, "usr/local/share/applications/droiddeck-battlenet.desktop").delete()
        com.droiddeck.launcher.core.FileUtils.delete(File(root, "root/.cache/droiddeck/battlenet"))
        ComponentsManager.clearGameComponents(context, APP_ID.toString())
        Log.i(TAG, "removed")
    }

    private val D2RFIX = Regex("""(?i)FEXCore-(\d+(?:\.\d+)*)-d2rfix(\d+)-linux\.wcp""")

    private const val LISTING_MAX_AGE = 6 * 3600L

    private val NEWEST = Comparator<Pair<List<Long>, Long>> { a, b ->
        for (i in 0 until minOf(a.first.size, b.first.size)) if (a.first[i] != b.first[i]) return@Comparator a.first[i].compareTo(b.first[i])
        if (a.first.size != b.first.size) a.first.size.compareTo(b.first.size) else a.second.compareTo(b.second)
    }

    internal fun d2rfix(file: String): Pair<List<Long>, Long>? {
        val m = D2RFIX.matchEntire(file) ?: return null
        val version = m.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
        return version to (m.groupValues[2].toLongOrNull() ?: return null)
    }

    internal fun newestD2rFix(items: List<ComponentsManager.CatalogItem>): ComponentsManager.CatalogItem? =
        items.filter { it.comp == "fex" }.mapNotNull { item -> d2rfix(item.file)?.let { item to it } }
            .maxWithOrNull { a, b -> NEWEST.compare(a.second, b.second) }?.first

    fun ensureFex(context: Context): String = ensureFex(context) { ComponentsManager.catalog(it, true).items }

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

    // Never exactly 4 GB: a game counting video memory in 32 bits reads that as none.
    internal fun videoMemory(totalMb: Long): Int = (totalMb / 5 / 512 * 512).coerceIn(1536, 3584).toInt()

    // Turnip's single heap is reported as both dedicated and shared memory.
    internal fun videoMemoryOptions(megabytes: Int): String =
        "dxgi.maxDeviceMemory = $megabytes; dxgi.maxSharedMemory = $megabytes; d3d9.maxAvailableMemory = $megabytes"

    // Turnip reports three quarters of the phone's memory as video memory, and Blizzard's games size their
    // caches from it (Diablo IV kept 5.5 GB of textures on an 11 GB phone). vkd3d-proton's device memory
    // comes from DXVK's DXGI, so DXVK_CONFIG caps D3D12 games too.
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
