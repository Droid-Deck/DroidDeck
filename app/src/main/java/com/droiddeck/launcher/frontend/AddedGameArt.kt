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

/**
 * Artwork for an added game: what the user put in the game's folder first, else what Steam has
 * for it - by the Steam appid its files name, else by a store search on its title - else what
 * SteamGridDB has ([SteamGridDb]), fetched once into the app's cache. Four pieces, named the way
 * the client names them in its grid folder: the portrait capsule, the wide header, the hero
 * background and the logo; plus an icon pulled out of the game's own .exe. The session hands
 * the paths to the runtime's shortcuts writer, which copies them into the client's grid.
 */
object AddedGameArt {
    private const val TAG = "AddedGameArt"
    private const val STORE_SEARCH = "https://store.steampowered.com/api/storesearch/?l=english&cc=US&term="
    private const val CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps/"
    private const val RETRY_AFTER_MS = 7L * 24 * 3600 * 1000

    class Art(val portrait: File?, val header: File?, val hero: File?, val logo: File?, val icon: File?) {
        val any get() = portrait != null || header != null || hero != null || logo != null
    }

    /**
     * The pieces a user can choose, as the client takes them: [assetType] is its custom-artwork
     * number (null: the icon, set by path); [cacheName] the automatic file in the game's cache.
     */
    enum class Slot(val key: String, val cacheName: String, val assetType: Int?) {
        COVER("p", "p.jpg", 0), BACKGROUND("hero", "hero.jpg", 1), LOGO("logo", "logo.png", 2), ICON("icon", "icon.png", null),
    }

    /** Where a piece came from; [AUTO] = nothing found yet. */
    const val FOLDER = "folder"
    const val STEAM = "steam"
    const val SGDB = "sgdb"
    const val FILE = "file"
    const val EXE = "exe"
    const val AUTO = ""

    /** A slot as it stands: the file shown and where it came from (one of [FOLDER].. [AUTO]); [chosen] = the user picked it. */
    class SlotState(val slot: Slot, val file: File?, val source: String, val chosen: Boolean)

    /** One image the chooser offers: its group ([FOLDER], [STEAM], [SGDB]), a thumbnail and the full image (URL or path). */
    class Option(val group: String, val thumb: String, val full: String)

    private val CHOSEN = Regex("""chosen-(\w+)\.(\w+)""")

    private fun chosen(cache: File, slot: Slot): File? =
        cache.listFiles { f -> CHOSEN.matchEntire(f.name)?.groupValues?.get(1) == slot.key }?.firstOrNull()

    private val IMAGE = listOf("png", "jpg", "jpeg")
    private val PORTRAIT = listOf("cover", "poster", "grid", "boxart", "capsule", "folder", "library_600x900", "portrait")
    private val HEADER = listOf("header", "banner", "library_header", "wide")
    private val HERO = listOf("hero", "background", "library_hero")
    private val LOGO = listOf("logo")

    internal fun cacheDir(context: Context, game: AddedGames.Game) = File(context.filesDir, "added-art/${game.appId}")

    /** An image in the folder (or its `art` subfolder) under one of the given names, any image extension. */
    private fun find(folder: File, names: List<String>): File? {
        val dirs = listOf(folder, File(folder, "art"), File(folder, ".art"))
        for (dir in dirs) for (name in names) for (ext in IMAGE) {
            val f = File(dir, "$name.$ext")
            if (f.isFile) return f
        }
        return null
    }

    /** What the folder itself offers; the file named after the folder counts as the portrait. */
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

    /** The user's choice for each piece, else the folder's art, else the cached art; no network. */
    fun resolve(context: Context, game: AddedGames.Game): Art {
        val own = local(game)
        val cache = cacheDir(context, game)
        fun cached(name: String) = File(cache, name).takeIf { it.isFile }
        return Art(
            portrait = chosen(cache, Slot.COVER) ?: own.portrait ?: cached("p.jpg") ?: exeArt(context, game, cover = true),
            header = own.header ?: cached("header.jpg") ?: exeArt(context, game, cover = false),
            hero = chosen(cache, Slot.BACKGROUND) ?: own.hero ?: cached("hero.jpg"),
            logo = chosen(cache, Slot.LOGO) ?: own.logo ?: cached("logo.png"),
            icon = chosen(cache, Slot.ICON) ?: own.icon ?: cached("icon-sgdb.png") ?: exeIcon(context, game),
        )
    }

    /** Each choosable piece with its file and where it came from, for the game's editor; no network. */
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
                Slot.BACKGROUND -> own.hero?.let { it to FOLDER } ?: cached("hero.jpg")
                Slot.LOGO -> own.logo?.let { it to FOLDER } ?: cached("logo.png")
                Slot.ICON -> own.icon?.let { it to FOLDER } ?: File(cache, "icon-sgdb.png").takeIf { it.isFile }?.let { it to SGDB }
                    ?: exeIcon(context, game)?.let { it to EXE }
            }
            SlotState(slot, auto?.first, auto?.second ?: AUTO, false)
        }
    }

    private val CHOOSABLE = setOf("png", "jpg", "jpeg", "webp")

    /** Images in the game's folder (and its art folders) that could stand for a piece. */
    internal fun folderImages(folder: File): List<File> =
        listOf(folder, File(folder, "art"), File(folder, ".art")).flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.extension.lowercase() in CHOOSABLE }?.sortedBy { it.name.lowercase() }.orEmpty()
        }.take(12)

    /**
     * What the chooser offers for [slot]: the game folder's images, Steam's official piece (when the
     * game has a Steam appid and Steam has the piece), and SteamGridDB's best few (with a key).
     * Blocking: it asks the network.
     */
    fun options(context: Context, game: AddedGames.Game, slot: Slot): List<Option> {
        val out = ArrayList<Option>()
        folderImages(game.folder).forEach { out.add(Option(FOLDER, it.path, it.path)) }
        val cache = cacheDir(context, game)
        val steamId = SteamMatch.resolve(context, game.folder.path, game.exe, game.name, legacy = File(cache, "steam-appid")).appId?.toString()
        val remote = when (slot) { Slot.COVER -> "library_600x900.jpg"; Slot.BACKGROUND -> "library_hero.jpg"; Slot.LOGO -> "logo.png"; Slot.ICON -> null }
        if (steamId != null && remote != null) {
            val url = "$CDN$steamId/$remote".takeIf { exists(it) } ?: steamAssetUrl(steamId, remote)
            if (url != null) out.add(Option(STEAM, url, url))
        }
        val key = SteamGridDb.key(context)
        if (key.isNotEmpty()) {
            cache.mkdirs()
            val gridId = remembered(File(cache, "sgdb-id")) {
                runCatching { SteamGridDb.gameId(key, steamId?.toIntOrNull(), game.name) }.getOrNull()?.toString()
            }?.toIntOrNull()
            if (gridId != null) runCatching { SteamGridDb.images(key, gridId, slot, if (slot == Slot.ICON) 3 else 5) }.getOrNull()
                ?.forEach { (thumb, full) -> out.add(Option(SGDB, thumb, full)) }
        }
        return out
    }

    /** Sets [slot] to [option] (a file is copied, a URL fetched) and records where it came from. False when it could not be had. Blocking. */
    fun choose(context: Context, game: AddedGames.Game, slot: Slot, option: Option): Boolean {
        val bytes = if (option.full.startsWith("https://")) get(option.full) else runCatching { File(option.full).readBytes() }.getOrNull()
        bytes ?: return false
        val ext = option.full.substringAfterLast('.', "png").substringBefore('?').lowercase().takeIf { it in CHOOSABLE + "ico" } ?: "png"
        val cache = cacheDir(context, game).apply { mkdirs() }
        chosen(cache, slot)?.delete()
        val dst = File(cache, "chosen-${slot.key}.${if (ext == "jpeg") "jpg" else ext}")
        val tmp = File(cache, dst.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dst)) return false
        SessionPrefs.setAddedGameArtSource(context, game.folder.path, slot.key, option.group + "|" + option.full)
        return true
    }

    /** Where the user's choice for [slot] came from and which image it was ("" both when automatic). */
    fun chosenSource(context: Context, game: AddedGames.Game, slot: Slot): Pair<String, String> {
        val value = SessionPrefs.addedGameArtSource(context, game.folder.path, slot.key)
        return value.substringBefore('|') to value.substringAfter('|', "")
    }

    /** [slot] back to what is found by itself. */
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

    /** The first icon inside the game's .exe, kept as a PNG beside the cached art. */
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

    /**
     * Fetches the art that is missing: Steam first (the appid the game's files name, else a store
     * search on its title whose result carries the same name), then SteamGridDB when a key is
     * set. Each lookup runs once per game; a miss is retried after a week. Blocking: run it off
     * the main thread. Returns true when anything new arrived.
     */
    fun fetchMissing(context: Context, games: List<AddedGames.Game>): Boolean {
        if (!SessionPrefs.addedGamesArt(context)) return false
        val sgdbKey = SteamGridDb.key(context)
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
            if (File(cache, "p.jpg").isFile || sgdbKey.isEmpty()) continue
            val gridId = remembered(File(cache, "sgdb-id")) {
                runCatching { SteamGridDb.gameId(sgdbKey, appId ?: id?.toIntOrNull(), game.name) }.getOrNull()?.toString()
            }?.toIntOrNull() ?: continue
            if (runCatching { SteamGridDb.download(sgdbKey, gridId, cache) }.getOrDefault(false)) changed = true
        }
        return changed
    }

    /**
     * The game's Steam app, the one record its Windows components use too ([SteamMatch]): what its
     * files name, else a store search on its title ([search]) whose result has the same name.
     */
    internal fun steamMatch(context: Context, game: AddedGames.Game, search: (String) -> Int?): SteamMatch.Match {
        val match = SteamMatch.resolve(context, game.folder.path, game.exe, game.name, legacy = File(cacheDir(context, game), "steam-appid"), search = search)
        match.appId?.let { Log.i(TAG, "${game.name}: Steam app $it (${match.certainty.name.lowercase()})") }
        return match
    }

    /** What [marker] remembers (an id, or "none" for a week), else [find]'s answer written to it. */
    private fun remembered(marker: File, find: () -> String?): String? {
        if (marker.isFile) {
            val value = marker.readText().trim()
            if (value != "none") return value
            if (System.currentTimeMillis() - marker.lastModified() < RETRY_AFTER_MS) return null
        }
        val found = find()
        marker.writeText(found ?: "none")
        return found
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private val EDITION = Regex("(?i)\\b(complete|definitive|legendary|goty|game of the year|deluxe|ultimate|remastered)( edition)?\\b")

    /**
     * How well a result's name [got] matches the game's [name]: 3 the same, 2 the same once
     * edition words are dropped, 1 one starts with the other; 0 no match. Nothing is taken for
     * being the first result.
     */
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

    /** The id of the store result whose name matches [name] best, or null when none does. */
    internal fun pickStoreResult(name: String, items: List<Pair<String, String>>): String? =
        items.map { (id, got) -> id to nameMatch(name, got) }
            .filter { it.second > 0 && it.first.isNotEmpty() }
            .maxByOrNull { it.second }?.first

    /** The Steam appid whose store name matches [name], or null for no match. */
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
            tmp.writeBytes(bytes)
            if (tmp.renameTo(dst)) { got = true; File(cache, "$localName.src").writeText(STEAM) }
        }
        return got
    }

    /**
     * Newer Steam games keep their art under hashed paths (store_item_assets/steam/apps/<id>/<hash>/…),
     * where the fixed CDN names above 404. The store's item API names those paths; once per appid.
     */
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

    /**
     * When nothing else gave a cover (or header), one made from the exe's icon: the icon centred on
     * its own colour, darkened toward the bottom, so the game still has a face in the list and in
     * Steam. Made once per game; real art found later takes over because it is looked at first.
     */
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
