package com.droiddeck.launcher.runtime

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.GameEnvironmentStore
import java.io.File

object BattleNet {
    private const val TAG = "BattleNet"

    const val APP_ID = 2596113170L

    const val FEX_REQUEST = "battlenet-fex.request"
    const val FEX_RESULT = "battlenet-fex.result"

    const val PROGRAM = "/usr/local/bin/droiddeck-battlenet"

    class Title(val uid: String, val name: String, val code: String, val steam: Int? = null, val shop: String? = null)

    internal val TITLES = listOf(
        Title("wow", "World of Warcraft", "WoW", shop = "world-of-warcraft-midnight"),
        Title("wow_classic", "World of Warcraft Classic", "WoWC", shop = "world-of-warcraft-burning-crusade-classic-anniversary-edition-upgrades"),
        Title("wow_classic_era", "World of Warcraft Classic", "WoWC"),
        Title("wow_classic_anniversary", "World of Warcraft Classic", "WoWC"),
        Title("diablo3", "Diablo III", "D3", shop = "diablo-iii"),
        Title("d3cn", "Diablo III", "D3CN", shop = "diablo-iii"),
        Title("s2", "StarCraft II", "S2", shop = "starcraft-ii"),
        Title("s1", "StarCraft", "S1", shop = "starcraft-remastered"),
        Title("hs_beta", "Hearthstone", "WTCG", shop = "hearthstone"),
        Title("heroes", "Heroes of the Storm", "Hero", shop = "heroes-of-the-storm"),
        Title("prometheus", "Overwatch 2", "Pro", 2357570, shop = "overwatch"),
        Title("w3", "Warcraft III: Reforged", "W3", shop = "warcraft-3-reforged"),
        Title("viper", "Call of Duty: Black Ops 4", "VIPR", shop = "call-of-duty-black-ops-4"),
        Title("odin", "Call of Duty: Modern Warfare", "ODIN", 2000950, shop = "call-of-duty-modern-warfare"),
        Title("lazarus", "Call of Duty: Modern Warfare 2 Campaign Remastered", "LAZR", shop = "call-of-duty-modern-warfare-2-campaign-remastered"),
        Title("zeus", "Call of Duty: Black Ops Cold War", "ZEUS", shop = "call-of-duty-black-ops-cold-war"),
        Title("fore", "Call of Duty: Vanguard", "FORE", shop = "call-of-duty-vanguard"),
        Title("auks", "Call of Duty: Modern Warfare II", "AUKS", shop = "call-of-duty-modern-warfare-ii"),
        Title("pinta", "Call of Duty: Modern Warfare III", "PNTA", 2519060, shop = "call-of-duty-modern-warfare-iii"),
        Title("wlby", "Crash Bandicoot 4: It's About Time", "WLBY", 1378990, shop = "crash-bandicoot-4"),
        Title("osi", "Diablo II: Resurrected", "OSI"),
        Title("rtro", "Blizzard Arcade Collection", "RTRO", shop = "blizzard-arcade-collection"),
        Title("anbs", "Diablo Immortal", "ANBS", shop = "diablo-immortal"),
        Title("fenris", "Diablo IV", "Fen", 2344520, shop = "diablo-iv"),
        Title("fen", "Diablo IV", "Fen", 2344520, shop = "diablo-iv"),
        Title("d1", "Diablo", "D1", shop = "diablo"),
        Title("w1", "Warcraft: Orcs & Humans", "W1", shop = "warcraft-orcs-and-humans"),
        Title("w2", "Warcraft II: Battle.net Edition", "W2", shop = "warcraft-2-battle-net-edition"),
        Title("w1r", "Warcraft: Remastered", "W1R", shop = "warcraft-1-remastered"),
        Title("w2r", "Warcraft II: Remastered", "W2R", shop = "warcraft-2-remastered"),
        Title("gryphon", "Warcraft Rumble", "GRY", shop = "warcraft-rumble"),
        Title("aris", "Doom: The Dark Ages", "ARIS", 3017860, shop = "doom-the-dark-ages"),
        Title("scorpio", "Sea of Thieves", "SCOR", 1172620, shop = "sea-of-thieves"),
        Title("arkansas", "The Outer Worlds 2", "ARK", 1449110, shop = "the-outer-worlds-2"),
        Title("libra", "Tony Hawk's Pro Skater 3 + 4", "LBRA", 2545710),
        Title("aqua", "Avowed", "AQUA", 2457220, shop = "avowed"),
    )

    val STORE: List<Title> get() = TITLES.distinctBy { it.code }.filter { it.uid != "d3cn" && it.code != "WoWC" }

    fun notInstalled(title: Title, installedIds: Collection<String?>) =
        installedIds.mapNotNull { id -> id?.let(::title)?.code }.none { it == title.code }

    private const val COVER_RETRY_MS = 24 * 3600_000L

    private fun coverDir(context: Context, title: Title) =
        File(context.filesDir, "battlenet-art/${(TITLES.firstOrNull { it.code == title.code } ?: title).uid}")

    fun cover(context: Context, title: Title): File? = art(context, title).cover

    private val coverLock = Any()

    fun fetchCovers(context: Context, titles: List<Title> = TITLES.distinctBy { it.code }.filter { it.uid != "d3cn" }): Boolean = synchronized(coverLock) {
        val art = com.droiddeck.launcher.frontend.AddedGameArt
        val key = com.droiddeck.launcher.frontend.SteamGridDb.key(context)
        var got = false
        for (title in titles) {
            val dir = coverDir(context, title)
            val tried = File(dir, ".tried")
            val wide = File(dir, BLIZZARD)
            val done = File(dir, "p.jpg").isFile && (title.shop == null || wide.isFile)
            if (done || System.currentTimeMillis() - tried.lastModified() < COVER_RETRY_MS) continue
            dir.mkdirs()
            if (title.steam != null && runCatching { art.download(title.steam.toString(), dir) }.getOrDefault(false)) got = true
            if (title.shop != null && !wide.isFile) {
                val cover = File(dir, BLIZZARD_COVER).takeUnless { File(dir, "p.jpg").isFile }
                if (runCatching { com.droiddeck.launcher.stores.battlenet.BlizzardArt.fetch(title.shop, wide, cover) }
                        .onFailure { Log.w(TAG, "Blizzard art for ${title.uid}: ${it.javaClass.simpleName}") }.getOrDefault(false)) got = true
            }
            if (!File(dir, "p.jpg").isFile && !File(dir, BLIZZARD_COVER).isFile && key.isNotEmpty()) {
                val id = runCatching { com.droiddeck.launcher.frontend.SteamGridDb.gameId(key, title.steam, title.name) }.getOrNull()
                if (id != null && runCatching { com.droiddeck.launcher.frontend.SteamGridDb.download(key, id, dir) }.getOrDefault(false)) got = true
            }
            runCatching { tried.writeText("") ; tried.setLastModified(System.currentTimeMillis()) }
        }
        got
    }

    private val LOCALES = setOf(
        "enus", "engb", "dede", "eses", "esmx", "frfr", "itit", "kokr", "plpl", "ptbr", "ptpt", "ruru", "zhcn", "zhtw", "jajp", "thth", "trtr", "arsa",
    )

    private val NOT_GAMES = setOf("agent", "bna", "battle.net")

    class Game(val uid: String, val name: String, val code: String, val folder: File) {
        val title: Title get() = title(uid) ?: Title(uid, name, code)
    }

    class Art(val cover: File?, val header: File?)

    private const val BLIZZARD = "blizzard.jpg"
    private const val BLIZZARD_COVER = "p-blizzard.jpg"

    fun art(context: Context, title: Title): Art {
        val dir = coverDir(context, title)
        fun first(vararg names: String) = names.map { File(dir, it) }.firstOrNull { it.isFile }
        return Art(first("p.jpg", BLIZZARD_COVER), first(BLIZZARD, "header.jpg"))
    }

    fun prefix(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps/compatdata/$APP_ID")

    fun client(context: Context) = File(prefix(context), "pfx/drive_c/Program Files (x86)/Battle.net/Battle.net.exe")

    fun installed(context: Context) = File(prefix(context), "pfx/drive_c/Program Files (x86)/Battle.net/Battle.net Launcher.exe").isFile

    fun profile(context: Context): Pair<String, String>? =
        (APP_ID.toString() to context.getString(com.droiddeck.launcher.R.string.bnet_name)).takeIf { installed(context) }

    fun icon(context: Context): File? {
        val out = File(context.filesDir, "battlenet-art/launcher/icon.png")
        if (out.isFile) return out
        val exe = client(context).takeIf { it.isFile } ?: return null
        val bitmap = runCatching { com.droiddeck.launcher.files.PeIconExtractor.extract(exe) }.getOrNull() ?: return null
        return runCatching {
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".tmp")
            tmp.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            check(tmp.renameTo(out))
            out
        }.getOrNull()
    }

    fun launcherEntry(installed: Boolean, art: File? = null): com.droiddeck.launcher.frontend.Library.SteamGame? =
        if (!installed) null
        else com.droiddeck.launcher.frontend.Library.SteamGame(
            appId = APP_ID.toInt(), name = com.droiddeck.launcher.stores.Store.BATTLENET.label, art = art,
            library = com.droiddeck.launcher.frontend.Library.BATTLENET_LAUNCHER, gameId = APP_ID,
            source = com.droiddeck.launcher.stores.Store.BATTLENET.id,
        )

    fun fexInUse(context: Context): String? = ComponentsManager.gameComponents(context, APP_ID.toString())["fex"]

    internal fun title(uid: String): Title? {
        val id = uid.lowercase()
        TITLES.firstOrNull { it.uid == id }?.let { return it }
        val base = id.substringBeforeLast('_', "")
        return if (base.isNotEmpty() && id.substringAfterLast('_') in LOCALES) TITLES.firstOrNull { it.uid == base } else null
    }

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
            found[title.code] = Game(title.uid, title.name, title.code, folder)
        }
        return found.values.toList()
    }

    internal fun hostFolder(context: Context, path: String): File? {
        val drive = Regex("""^([CcZz]):[\\/](.*)$""").matchEntire(path.trim()) ?: return null
        val relative = drive.groupValues[2].replace('\\', '/').trim('/')
        if (relative.isEmpty() || relative.split('/').any { it == ".." }) return null
        return if (drive.groupValues[1].equals("c", ignoreCase = true)) File(prefix(context), "pfx/drive_c/$relative")
        else com.droiddeck.launcher.frontend.AddedGames.hostPath(context, "/$relative")
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

    internal fun productDbEntries(bytes: ByteArray): List<Pair<String, String>> {
        val installs = runCatching { fields(bytes, 0, bytes.size) }.getOrDefault(emptyList()).filter { it.first == 1 }
        return installs.mapNotNull { (_, start, end) ->
            runCatching {
                val install = fields(bytes, start, end)
                val uid = install.firstOrNull { it.first == 1 }?.let { String(bytes, it.second, it.third - it.second) }
                val path = install.firstOrNull { it.first == 3 }?.let { s -> fields(bytes, s.second, s.third).firstOrNull { it.first == 1 } }
                    ?.let { String(bytes, it.second, it.third - it.second) }
                val installed = install.firstOrNull { it.first == 4 }?.let { s -> fields(bytes, s.second, s.third).firstOrNull { it.first == 1 } }
                    ?.let { s -> varints(bytes, s.second, s.third)[1] }
                if (uid.isNullOrEmpty() || path.isNullOrEmpty() || installed == 0L) null else uid to path
            }.getOrNull()
        }
    }

    private fun fields(bytes: ByteArray, from: Int, to: Int): List<Triple<Int, Int, Int>> = walk(bytes, from, to) { _, _ -> }

    private fun varints(bytes: ByteArray, from: Int, to: Int): Map<Int, Long> =
        HashMap<Int, Long>().also { found -> walk(bytes, from, to) { field, value -> found.putIfAbsent(field, value) } }

    private fun walk(bytes: ByteArray, from: Int, to: Int, varint: (Int, Long) -> Unit): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        var i = from
        fun next(): Long {
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
            val key = next()
            when ((key and 7).toInt()) {
                0 -> varint((key ushr 3).toInt(), next())
                1 -> { require(to - i >= 8) { "truncated" }; i += 8 }
                2 -> {
                    val n = next()
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
            source = com.droiddeck.launcher.stores.Store.BATTLENET.id, storeId = game.uid,
            launchOptions = "STEAM_COMPAT_DATA_PATH=$guestPrefix %command% --exec=\"launch ${game.code}\"",
        )
    }

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

    internal fun videoMemory(totalMb: Long): Int = (totalMb / 5 / 512 * 512).coerceIn(1536, 3584).toInt()

    internal fun videoMemoryOptions(megabytes: Int): String =
        "dxgi.maxDeviceMemory = $megabytes; dxgi.maxSharedMemory = $megabytes; d3d9.maxAvailableMemory = $megabytes"

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

    fun ensureNoSteam(context: Context): Boolean {
        if (com.droiddeck.launcher.session.SessionPrefs.battleNetNoSteamSet(context)) return false
        GameEnvironmentStore.setGameNoSteam(context, APP_ID.toString(), true)
        com.droiddeck.launcher.session.SessionPrefs.setBattleNetNoSteamSet(context)
        Log.i(TAG, "Start without Steam for the Battle.net profile")
        return true
    }

    fun answerFexRequest(context: Context, dir: File) {
        val app = context.applicationContext
        Thread({
            runCatching { ensureVideoMemory(app) }.onFailure { Log.w(TAG, "video memory cap for the Battle.net profile", it) }
            runCatching { ensureNoSteam(app) }.onFailure { Log.w(TAG, "Start without Steam for the Battle.net profile", it) }
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
