package com.droiddeck.launcher.frontend

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import androidx.core.util.readText
import androidx.core.util.writeBytes
import androidx.core.util.writeText
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/** Small, portable launch files for frontends that scan ROM folders. No guest paths or commands. */
object GameFiles {
    const val EXTENSION = "steam"
    private const val MAX_BYTES = 128
    private const val INVENTORY = ".droiddeck-sync.json"
    private const val FORMAT = "droiddeck-game-files-v1"

    /** Called on a worker: content providers and shared storage may block. */
    fun readIntent(context: Context, request: Intent): String? = runCatching {
        if (request.action != Intent.ACTION_VIEW) return null
        val uri = request.data ?: return null
        GameLaunchLink.parse(uri.toString())?.let { return it }
        val stream = when (uri.scheme) {
            "content" -> context.contentResolver.openInputStream(uri)
            "file" -> {
                if (!uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) return null
                File(uri.path ?: return null).inputStream()
            }
            null -> File(uri.toString()).takeIf { it.isAbsolute }?.inputStream()
            else -> null
        } ?: return null
        stream.use(::read)
    }.getOrNull()

    fun read(stream: InputStream): String? {
        // An untrusted URI must not make us buffer an arbitrary ROM or parse a command.
        val bytes = ByteArray(MAX_BYTES + 1)
        var count = 0
        while (count < bytes.size) {
            val n = stream.read(bytes, count, bytes.size - count)
            if (n < 0) break
            if (n == 0) return null
            count += n
        }
        if (count > MAX_BYTES) return null
        val text = String(bytes, 0, count, Charsets.UTF_8).trim()
        return text.takeIf(GameLaunchLink::validId)
    }

    fun filename(game: Library.SteamGame, disambiguate: Boolean = false): String {
        val sanitized = game.name.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_")
            .trim().trim('.').ifBlank { "Game" }
        val points = sanitized.codePoints().limit(40).toArray()
        val title = String(points, 0, points.size)
        val suffix = if (disambiguate) " (${game.gameIdString})" else ""
        return "$title$suffix.$EXTENSION"
    }

    private fun contents(id: String): String {
        require(GameLaunchLink.validId(id))
        return id
    }

    @Synchronized fun export(folder: File, game: Library.SteamGame): File =
        exportNamed(folder, game, filename(game))

    private fun exportNamed(folder: File, game: Library.SteamGame, name: String): File {
        require(folder.isDirectory) { "Folder is unavailable" }
        val text = contents(game.gameIdString)
        val target = exportTarget(folder, game, name)
        require(!target.exists() || matches(target, text)) { "A different file already uses ${target.name}" }
        if (!target.exists()) write(target, text)
        exportArt(folder, game)
        return target
    }

    private fun exportTarget(folder: File, game: Library.SteamGame, name: String): File {
        val target = child(folder, name)
        return if (target.exists() && !matches(target, contents(game.gameIdString))) {
            child(folder, filename(game, disambiguate = true))
        } else target
    }

    /** Cocoon matches icons in its data folder's images directory. Never replace custom art. */
    private fun exportArt(folder: File, game: Library.SteamGame) {
        val art = listOfNotNull(game.icon, game.art).firstOrNull {
            it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp")
        } ?: return
        val title = filename(game).substringBeforeLast(".")
            .replace(Regex("\\[[^]]*]|\\([^)]*\\)"), "")
            .replace(Regex("[^a-zA-Z0-9\\s]"), "").trim()
            .replace(Regex("\\s+"), "-").lowercase(java.util.Locale.ROOT).take(50).trimEnd('-')
        if (title.isEmpty()) return
        val images = child(folder, "images")
        check(images.isDirectory || images.mkdir()) { "Artwork folder is unavailable" }
        val target = child(images, "$title-icon.${art.extension.lowercase()}")
        if (!target.exists()) AtomicFile(target).writeBytes(art.readBytes())
    }

    /** Only remove files in our inventory whose content is still exactly what we wrote. */
    @Synchronized fun sync(folder: File, games: List<Library.SteamGame>) {
        require(folder.isDirectory) { "Folder is unavailable" }
        val inventory = child(folder, INVENTORY)
        val previous = if (inventory.exists()) {
            val record = JSONObject(AtomicFile(inventory).readText())
            require(record.optString("format") == FORMAT) { "Folder has an unrelated sync inventory" }
            record.getJSONObject("games")
        } else JSONObject()
        val next = JSONObject()
        val unique = games.distinctBy { it.gameId }
        val names = unique.groupingBy { filename(it).lowercase(java.util.Locale.ROOT) }.eachCount()
        val exports = unique.map { game ->
            val name = filename(game, disambiguate = names.getValue(filename(game).lowercase(java.util.Locale.ROOT)) > 1)
            val preferred = child(folder, name)
            val target = if (inventoryOwns(folder, previous, preferred, game.gameIdString)) preferred
                else exportTarget(folder, game, name)
            val text = contents(game.gameIdString)
            val existed = target.exists()
            // Keep an edited export without letting it block the rest of the library sync.
            val owned = inventoryOwns(folder, previous, target, game.gameIdString)
            val file = if (owned && target.exists() &&
                !matches(target, text)) target else exportNamed(folder, game, target.name)
            if (!existed || owned) next.put(file.name, game.gameIdString)
            file
        }
        // Write new exports before pruning; a failed write must not remove old launch files.
        for (name in previous.keys()) {
            val id = previous.getString(name)
            if (!next.has(name) && name.endsWith(".$EXTENSION") && GameLaunchLink.validId(id)) {
                val file = child(folder, name)
                if (matches(file, contents(id))) {
                    // Android shared storage is often case insensitive. A case-only rename may
                    // still resolve to the live export, which must not be pruned as an old file.
                    val retained = exports.any { java.nio.file.Files.isSameFile(it.toPath(), file.toPath()) }
                    if (!retained) check(file.delete()) { "Could not remove $name" }
                }
            }
        }
        if (previous.toString() != next.toString()) write(inventory, JSONObject().put("format", FORMAT).put("games", next).toString())
    }

    private fun inventoryOwns(folder: File, record: JSONObject, file: File, id: String): Boolean {
        if (record.optString(file.name) == id) return true
        if (!file.exists()) return false
        return record.keys().asSequence().any { name ->
            record.optString(name) == id && child(folder, name).let { old ->
                old.exists() && java.nio.file.Files.isSameFile(old.toPath(), file.toPath())
            }
        }
    }

    private fun child(folder: File, name: String): File {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..")
        val file = File(folder, name)
        require(file.canonicalFile.parentFile == folder.canonicalFile) { "File leaves the export folder" }
        return file
    }

    private fun matches(file: File, text: String) = file.isFile && file.length() == text.toByteArray().size.toLong() &&
        file.readText() == text

    private fun write(file: File, text: String) = AtomicFile(file).writeText(text)
}
