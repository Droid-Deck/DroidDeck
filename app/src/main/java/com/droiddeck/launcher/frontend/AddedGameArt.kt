package com.droiddeck.launcher.frontend

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.droiddeck.launcher.files.PeIconExtractor
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object AddedGameArt {
    private const val TAG = "AddedGameArt"
    private const val STORE_SEARCH = "https://store.steampowered.com/api/storesearch/?l=english&cc=US&term="
    private const val CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps/"
    private const val RETRY_AFTER_MS = 7L * 24 * 3600 * 1000

    class Art(val portrait: File?, val header: File?, val hero: File?, val logo: File?, val icon: File?) {
        val any get() = portrait != null || header != null || hero != null || logo != null
    }

    enum class Slot(val key: String, val cacheName: String, val assetType: Int?) {
        COVER("p", "p.jpg", 0), WIDE("header", "header.jpg", 3), BACKGROUND("hero", "hero.jpg", 1), LOGO("logo", "logo.png", 2), ICON("icon", "icon.png", null),
    }

    const val FOLDER = "folder"
    const val STEAM = "steam"
    const val SGDB = "sgdb"
    const val FILE = "file"
    const val EXE = "exe"
    const val AUTO = ""

    class SlotState(val slot: Slot, val file: File?, val source: String, val chosen: Boolean)

    class Option(val group: String, val thumb: String, val full: String)

    class Choices(val options: List<Option>, val sgdb: SteamGridDb.Status)

    class Found<T>(val items: List<T>, val status: SteamGridDb.Status)

    private val CHOSEN = Regex("""chosen-(p|header|hero|logo|icon)(?:-\d+)?\.(\w+)""")

    internal fun chosen(cache: File, slot: Slot): File? =
        cache.listFiles { f -> CHOSEN.matchEntire(f.name)?.groupValues?.get(1) == slot.key }?.maxWithOrNull(compareBy({ it.lastModified() }, { it.name }))

    private val IMAGE = listOf("png", "jpg", "jpeg")
    private val PORTRAIT = listOf("cover", "poster", "grid", "boxart", "capsule", "folder", "library_600x900", "portrait")
    private val HEADER = listOf("header", "banner", "library_header", "wide")
    private val HERO = listOf("hero", "background", "library_hero")
    private val LOGO = listOf("logo")

    internal fun cacheDir(context: Context, game: AddedGames.Game) = File(context.filesDir, "added-art/${game.appId}")

    private fun find(folder: File, names: List<String>): File? {
        val dirs = listOf(folder, File(folder, "art"), File(folder, ".art"))
        for (dir in dirs) for (name in names) for (ext in IMAGE) {
            val f = File(dir, "$name.$ext")
            if (f.isFile) return f
        }
        return null
    }

    private fun local(game: AddedGames.Game): Art {
        val folder = game.folder
        return Art(
            portrait = find(folder, PORTRAIT + listOf(folder.name)),
            header = find(folder, HEADER),
            hero = find(folder, HERO),
            logo = find(folder, LOGO),
            icon = listOf("icon.png", "icon.ico").map { File(folder, it) }.firstOrNull { it.isFile },
        )
    }

    fun resolve(context: Context, game: AddedGames.Game): Art {
        val own = local(game)
        val cache = cacheDir(context, game)
        fun cached(name: String) = File(cache, name).takeIf { it.isFile }
        return Art(
            portrait = chosen(cache, Slot.COVER) ?: own.portrait ?: cached("p.jpg") ?: exeArt(context, game, cover = true),
            header = chosen(cache, Slot.WIDE) ?: own.header ?: cached("header.jpg") ?: exeArt(context, game, cover = false),
            hero = chosen(cache, Slot.BACKGROUND) ?: own.hero ?: cached("hero.jpg"),
            logo = chosen(cache, Slot.LOGO) ?: own.logo ?: cached("logo.png"),
            icon = chosen(cache, Slot.ICON) ?: own.icon ?: cached("icon-sgdb.png") ?: exeIcon(context, game),
        )
    }

    fun describe(context: Context, game: AddedGames.Game): List<SlotState> {
        val own = local(game)
        val cache = cacheDir(context, game)
        fun cached(name: String): Pair<File, String>? = File(cache, name).takeIf { it.isFile }?.let { f ->
            f to (File(cache, "$name.src").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: STEAM)
        }
        return Slot.values().map { slot ->
            chosen(cache, slot)?.let { return@map SlotState(slot, it, chosenSource(context, game, slot).first.ifEmpty { FILE }, true) }
            val auto: Pair<File, String>? = when (slot) {
                Slot.COVER -> own.portrait?.let { it to FOLDER } ?: cached("p.jpg") ?: exeArt(context, game, cover = true)?.let { it to EXE }
                Slot.WIDE -> own.header?.let { it to FOLDER } ?: cached("header.jpg") ?: exeArt(context, game, cover = false)?.let { it to EXE }
                Slot.BACKGROUND -> own.hero?.let { it to FOLDER } ?: cached("hero.jpg")
                Slot.LOGO -> own.logo?.let { it to FOLDER } ?: cached("logo.png")
                Slot.ICON -> own.icon?.let { it to FOLDER } ?: File(cache, "icon-sgdb.png").takeIf { it.isFile }?.let { it to SGDB }
                    ?: exeIcon(context, game)?.let { it to EXE }
            }
            SlotState(slot, auto?.first, auto?.second ?: AUTO, false)
        }
    }

    private val CHOOSABLE = setOf("png", "jpg", "jpeg", "webp")

    internal fun folderImages(folder: File): List<File> =
        listOf(folder, File(folder, "art"), File(folder, ".art")).flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.extension.lowercase() in CHOOSABLE }?.sortedBy { it.name.lowercase() }.orEmpty()
        }.take(12)

    fun choices(context: Context, game: AddedGames.Game, slot: Slot): Choices {
        val out = ArrayList<Option>()
        folderImages(game.folder).forEach { out.add(Option(FOLDER, it.path, it.path)) }
        val cache = cacheDir(context, game)
        val steamId = SteamMatch.resolve(context, game.folder.path, game.exe, game.name, legacy = File(cache, "steam-appid")).appId?.toString()
        if (steamId != null) steamOption(steamId, slot)?.let { out.add(it) }
        val (found, status) = sgdbChoices(context, File(cache, "sgdb-id"), steamId?.toIntOrNull(), game.name, slot)
        out.addAll(found)
        return Choices(out, status)
    }

    internal fun steamOption(steamId: String, slot: Slot): Option? {
        val remote = when (slot) { Slot.COVER -> "library_600x900.jpg"; Slot.WIDE -> "header.jpg"; Slot.BACKGROUND -> "library_hero.jpg"; Slot.LOGO -> "logo.png"; Slot.ICON -> null } ?: return null
        val url = "$CDN$steamId/$remote".takeIf { exists(it) } ?: steamAssetUrl(steamId, remote) ?: return null
        return Option(STEAM, url, url)
    }

    internal fun sgdbChoices(context: Context, marker: File, steamId: Int?, name: String, slot: Slot): Pair<List<Option>, SteamGridDb.Status> {
        val key = SteamGridDb.key(context)
        if (key.isEmpty()) return emptyList<Option>() to SteamGridDb.Status.NO_KEY
        marker.parentFile?.mkdirs()
        return try {
            val gridId = remembered(marker, SteamGridDb.keyChangedAt(context), retryMiss = true) {
                SteamGridDb.gameId(key, steamId, name)?.toString()
            }?.toIntOrNull() ?: return emptyList<Option>() to SteamGridDb.Status.NO_GAME
            SteamGridDb.images(key, gridId, slot, if (slot == Slot.ICON) 3 else 5).map { (thumb, full) -> Option(SGDB, thumb, full) } to SteamGridDb.Status.OK
        } catch (e: Exception) {
            Log.w(TAG, "$name: SteamGridDB failed (${e.javaClass.simpleName})")
            emptyList<Option>() to ((e as? SteamGridDb.Failed)?.status ?: SteamGridDb.Status.SERVER)
        }
    }

    fun searchGames(context: Context, term: String): Found<SteamGridDb.Game> = sgdb(context) { SteamGridDb.search(it, term) }

    fun gameImages(context: Context, id: Int, slot: Slot): Found<Option> = sgdb(context) { key ->
        SteamGridDb.all(key, id, slot).map { (thumb, full) -> Option(SGDB, thumb, full) }
    }

    private fun <T> sgdb(context: Context, call: (String) -> List<T>): Found<T> {
        val key = SteamGridDb.key(context)
        if (key.isEmpty()) return Found(emptyList(), SteamGridDb.Status.NO_KEY)
        return try {
            Found(call(key), SteamGridDb.Status.OK)
        } catch (e: Exception) {
            Log.w(TAG, "SteamGridDB: ${e.javaClass.simpleName}")
            Found(emptyList(), (e as? SteamGridDb.Failed)?.status ?: SteamGridDb.Status.SERVER)
        }
    }

    fun choose(context: Context, game: AddedGames.Game, slot: Slot, option: Option): Boolean {
        if (!store(cacheDir(context, game), slot, option)) return false
        SessionPrefs.setAddedGameArtSource(context, game.folder.path, slot.key, option.group + "|" + option.full)
        return true
    }

    internal fun store(cache: File, slot: Slot, option: Option): Boolean {
        val fresh = storeNew(cache, slot, option) ?: return false
        dropOlder(cache, slot, fresh)
        return true
    }

    internal fun dropOlder(cache: File, slot: Slot, keep: File) {
        cache.listFiles { f -> f != keep && CHOSEN.matchEntire(f.name)?.groupValues?.get(1) == slot.key }?.forEach { it.delete() }
    }

    private val lastStamp = java.util.concurrent.atomic.AtomicLong(0)

    private fun nextStamp(): Long = lastStamp.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }

    internal fun storeNew(cache: File, slot: Slot, option: Option): File? {
        val bytes = if (option.full.startsWith("https://")) get(option.full) else runCatching { File(option.full).readBytes() }.getOrNull()
        bytes ?: return null
        val ext = option.full.substringAfterLast('.', "png").substringBefore('?').lowercase().takeIf { it in CHOOSABLE + "ico" } ?: "png"
        cache.mkdirs()
        val suffix = if (ext == "jpeg") "jpg" else ext
        var dst = File(cache, "chosen-${slot.key}-" + "%013d".format(nextStamp()) + "." + suffix)
        while (dst.exists()) dst = File(cache, "chosen-${slot.key}-" + "%013d".format(nextStamp()) + "." + suffix)
        val tmp = File(cache, dst.name + ".tmp")
        tmp.writeBytes(bytes)
        return if (tmp.renameTo(dst)) dst else null
    }

    fun chosenSource(context: Context, game: AddedGames.Game, slot: Slot): Pair<String, String> {
        val value = SessionPrefs.addedGameArtSource(context, game.folder.path, slot.key)
        return value.substringBefore('|') to value.substringAfter('|', "")
    }

    fun reset(context: Context, game: AddedGames.Game, slot: Slot) {
        chosen(cacheDir(context, game), slot)?.delete()
        SessionPrefs.setAddedGameArtSource(context, game.folder.path, slot.key, "")
    }

    private fun exists(url: String): Boolean = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.setRequestProperty("User-Agent", "DroidDeck-launcher")
        try { c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)

    private fun exeIcon(context: Context, game: AddedGames.Game): File? {
        val cache = cacheDir(context, game)
        val png = File(cache, "icon.png")
        if (png.isFile) return png
        val none = File(cache, "icon.none")
        if (none.isFile) return null
        cache.mkdirs()
        val bitmap = runCatching { PeIconExtractor.extract(game.exe) }.getOrNull()
        if (bitmap == null) { none.writeText(""); return null }
        val tmp = File(cache, "icon.png.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return if (tmp.renameTo(png)) png else null
    }

    fun fetchMissing(context: Context, games: List<AddedGames.Game>): Boolean {
        if (!SessionPrefs.addedGamesArt(context)) return false
        val sgdbKey = SteamGridDb.key(context)
        val keyChangedAt = SteamGridDb.keyChangedAt(context)
        var sgdbDown = false
        var changed = false
        for (game in games) {
            if (local(game).portrait != null) continue
            val cache = cacheDir(context, game)
            if (File(cache, "p.jpg").isFile) continue
            cache.mkdirs()
            val match = steamMatch(context, game) { name ->
                runCatching { search(name) }.onFailure { Log.w(TAG, "${game.name}: store search failed (${it.message})") }.getOrNull()?.toIntOrNull()
            }
            val appId = match.appId?.takeIf { match.certainty == SteamMatch.Certainty.FILES }
            val id = match.appId?.toString()
            if (id != null && download(id, cache)) changed = true
            if (File(cache, "p.jpg").isFile || sgdbKey.isEmpty() || sgdbDown) continue
            val stored: String? = try {
                remembered(File(cache, "sgdb-id"), keyChangedAt) {
                    SteamGridDb.gameId(sgdbKey, appId ?: id?.toIntOrNull(), game.name)?.toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "${game.name}: SteamGridDB lookup failed (${e.javaClass.simpleName}); not asking again this pass")
                sgdbDown = true
                null
            }
            val gridId = stored?.toIntOrNull() ?: continue
            val grabbed = try {
                SteamGridDb.download(sgdbKey, gridId, cache)
            } catch (e: Exception) {
                sgdbDown = true
                false
            }
            if (grabbed) changed = true
        }
        return changed
    }

    internal fun steamMatch(context: Context, game: AddedGames.Game, search: (String) -> Int?): SteamMatch.Match {
        val match = SteamMatch.resolve(context, game.folder.path, game.exe, game.name, legacy = File(cacheDir(context, game), "steam-appid"), search = search)
        match.appId?.let { Log.i(TAG, "${game.name}: Steam app $it (${match.certainty.name.lowercase()})") }
        return match
    }

    internal fun remembered(marker: File, notBefore: Long = 0L, retryMiss: Boolean = false, find: () -> String?): String? {
        if (marker.isFile) {
            val value = marker.readText().trim()
            if (value != "none") return value
            val at = marker.lastModified()
            if (!retryMiss && at >= notBefore && System.currentTimeMillis() - at < RETRY_AFTER_MS) return null
        }
        val found = find()
        marker.writeText(found ?: "none")
        return found
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private val EDITION = Regex("(?i)\\b(complete|definitive|legendary|goty|game of the year|deluxe|ultimate|remastered)( edition)?\\b")

    internal fun nameMatch(name: String, got: String): Int {
        val want = normalize(name)
        val wantShort = normalize(name.replace(EDITION, " ").trim().ifEmpty { name })
        val have = normalize(got)
        if (have.isEmpty() || wantShort.isEmpty()) return 0
        return when {
            have == want -> 3
            have == wantShort -> 2
            have.startsWith(wantShort) || wantShort.startsWith(have) -> 1
            else -> 0
        }
    }

    internal fun pickStoreResult(name: String, items: List<Pair<String, String>>): String? =
        items.map { (id, got) -> id to nameMatch(name, got) }
            .filter { it.second > 0 && it.first.isNotEmpty() }
            .maxByOrNull { it.second }?.first

    private fun search(name: String): String? {
        val term = name.replace(EDITION, " ").trim().ifEmpty { name }
        val json = get(STORE_SEARCH + URLEncoder.encode(term, "UTF-8"))?.toString(Charsets.UTF_8) ?: return null
        val items = JSONObject(json).optJSONArray("items") ?: return null
        return pickStoreResult(name, (0 until items.length()).map { items.getJSONObject(it) }.map { it.optString("id") to it.optString("name") })
    }

    private fun download(id: String, cache: File): Boolean {
        var got = false
        for ((remote, localName) in listOf(
            "library_600x900.jpg" to "p.jpg", "header.jpg" to "header.jpg", "library_hero.jpg" to "hero.jpg", "logo.png" to "logo.png",
        )) {
            val dst = File(cache, localName)
            if (dst.isFile) continue
            val bytes = get("$CDN$id/$remote") ?: steamAssetUrl(id, remote)?.let { get(it) } ?: continue
            val tmp = File(cache, "$localName.tmp")
            if (runCatching { tmp.writeBytes(bytes) }.isFailure) { tmp.delete(); continue }
            if (tmp.renameTo(dst)) { got = true; runCatching { File(cache, "$localName.src").writeText(STEAM) } }
        }
        return got
    }

    private fun steamAssetUrl(id: String, remote: String): String? {
        val assets = steamAssets.getOrPut(id) {
            runCatching {
                val input = """{"ids":[{"appid":$id}],"context":{"language":"english","country_code":"US"},"data_request":{"include_assets":true}}"""
                val json = get("https://api.steampowered.com/IStoreBrowseService/GetItems/v1/?input_json=" + URLEncoder.encode(input, "UTF-8"))
                    ?.toString(Charsets.UTF_8) ?: return@runCatching emptyMap()
                val a = JSONObject(json).optJSONObject("response")?.optJSONArray("store_items")?.optJSONObject(0)?.optJSONObject("assets")
                    ?: return@runCatching emptyMap()
                val format = a.optString("asset_url_format").takeIf { it.contains("\${FILENAME}") } ?: return@runCatching emptyMap()
                fun url(vararg keys: String) = keys.firstNotNullOfOrNull { k -> a.optString(k).takeIf { it.isNotEmpty() } }
                    ?.let { "https://shared.akamai.steamstatic.com/store_item_assets/" + format.replace("\${FILENAME}", it) }
                mapOf(
                    "library_600x900.jpg" to url("library_capsule_2x", "library_capsule"),
                    "header.jpg" to url("header"),
                    "library_hero.jpg" to url("library_hero_2x", "library_hero"),
                ).filterValues { it != null }.mapValues { it.value!! }
            }.getOrDefault(emptyMap())
        }
        return assets[remote]
    }

    private val steamAssets = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    private fun exeArt(context: Context, game: AddedGames.Game, cover: Boolean): File? {
        val cache = cacheDir(context, game)
        val out = File(cache, if (cover) "p-exe.png" else "header-exe.png")
        if (out.isFile) return out
        val icon = exeIcon(context, game) ?: return null
        val src = android.graphics.BitmapFactory.decodeFile(icon.path) ?: return null
        val (w, h) = if (cover) 600 to 900 else 920 to 430
        val tint = android.graphics.Bitmap.createScaledBitmap(src, 1, 1, true).getPixel(0, 0)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        fun shade(c: Int, f: Float) = android.graphics.Color.rgb(
            (android.graphics.Color.red(c) * f).toInt(), (android.graphics.Color.green(c) * f).toInt(), (android.graphics.Color.blue(c) * f).toInt(),
        )
        canvas.drawPaint(android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), shade(tint, 0.7f), shade(tint, 0.15f), android.graphics.Shader.TileMode.CLAMP)
        })
        val side = (minOf(w, h) * 0.5f).toInt()
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        canvas.drawBitmap(src, null, android.graphics.RectF(left, top, left + side, top + side), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        cache.mkdirs()
        val tmp = File(cache, "${out.name}.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return if (tmp.renameTo(out)) out else null
    }

    internal fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "DroidDeck-launcher")
        return try {
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "$url: ${e.message}"); null
        } finally {
            c.disconnect()
        }
    }
}
