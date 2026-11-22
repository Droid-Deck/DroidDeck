package com.droiddeck.launcher.frontend

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.droiddeck.launcher.R
import androidx.core.util.readText
import androidx.core.util.writeBytes
import androidx.core.util.writeText
import org.json.JSONObject
import java.io.File
import java.io.InputStream

object GameFiles {
    const val EXTENSION = "steam"
    private const val MAX_BYTES = 4096
    private const val MAX_NAME_BYTES = 200
    private const val INVENTORY = ".droiddeck-sync.json"
    private const val FORMAT = "droiddeck-game-files-v1"

    fun readIntent(context: Context, request: Intent): GameRequest? = runCatching {
        if (request.action != Intent.ACTION_VIEW) return null
        val uri = request.data ?: return null
        GameRequest.link(uri.toString())?.let { return GameRequest(it) }
        val name = displayName(context, uri)
        val extension = name?.substringAfterLast('.', "").orEmpty()
        if (extension.lowercase(java.util.Locale.ROOT) in GameRequest.PROGRAMS) {
            val program = localFile(context, uri)?.takeIf { it.isFile } ?: return null
            return GameRequest(title = name?.substringBeforeLast('.'), program = program.canonicalFile)
        }
        val stream = when (uri.scheme) {
            "content" -> runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                ?: localFile(context, uri)?.takeIf { it.isFile }?.inputStream()
            "file", null -> localFile(context, uri)?.inputStream()
            else -> null
        } ?: return null
        val id = stream.use(::read) ?: return null
        GameRequest(id, GameRequest.sourceOf(extension), name?.substringBeforeLast('.'))
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String? = when (uri.scheme) {
        "content" -> runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        "file" -> uri.path?.let { File(it).name }
        null -> File(uri.toString()).name
        else -> null
    }?.takeIf { it.isNotEmpty() }

    private fun localFile(context: Context, uri: Uri): File? = when (uri.scheme) {
        "file" -> {
            if (!uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) null
            else uri.path?.let(::File)
        }
        null -> File(uri.toString()).takeIf { it.isAbsolute }
        "content" -> storageDocument(context, uri) ?: uri.pathSegments.let { parts ->
            (0..1).asSequence().map { File("/" + parts.drop(it).joinToString("/")) }.firstOrNull { it.isFile }
        }
        else -> null
    }

    // Frontends pass SAF document URIs or FileProvider URIs whose path mirrors shared storage.
    private fun storageDocument(context: Context, uri: Uri): File? {
        if (uri.authority != "com.android.externalstorage.documents" || !DocumentsContract.isDocumentUri(context, uri)) return null
        val id = DocumentsContract.getDocumentId(uri)
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val root = if (volume.equals("primary", ignoreCase = true)) android.os.Environment.getExternalStorageDirectory()
            else File("/storage", volume)
        return File(root, path)
    }

    fun read(stream: InputStream): String? {
        val bytes = ByteArray(MAX_BYTES + 1)
        var count = 0
        while (count < bytes.size) {
            val n = stream.read(bytes, count, bytes.size - count)
            if (n < 0) break
            if (n == 0) return null
            count += n
        }
        if (count > MAX_BYTES) return null
        return GameRequest.parse(String(bytes, 0, count, Charsets.UTF_8))
    }

    fun filename(game: Library.SteamGame, disambiguate: Boolean = false): String {
        val sanitized = game.name.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_")
            .trim().trim('.').ifBlank { "Game" }
        val points = sanitized.codePoints().toArray()
        var end = 0
        var bytes = 0
        while (end < points.size) {
            bytes += String(points, end, 1).toByteArray().size
            if (bytes > MAX_NAME_BYTES) break
            end++
        }
        val title = String(points, 0, end).trimEnd { it.isWhitespace() || it == '.' }.ifBlank { "Game" }
        val suffix = if (disambiguate) " (${game.gameIdString})" else ""
        return "$title$suffix.${GameRequest.extension(game)}"
    }

    private fun contents(id: String): String {
        require(GameLaunchLink.validId(id))
        return id
    }

    @Synchronized fun export(context: Context, folder: File, game: Library.SteamGame): File =
        exportNamed(context, folder, game, filename(game))

    private fun exportNamed(context: Context, folder: File, game: Library.SteamGame, name: String): File {
        require(folder.isDirectory) { context.getString(R.string.gfiles_folder_unavailable) }
        val text = contents(game.gameIdString)
        val target = exportTarget(context, folder, game, name)
        require(!target.exists() || matches(target, text)) { context.getString(R.string.gfiles_name_taken, target.name) }
        if (!target.exists()) write(target, text)
        exportArt(context, folder, game)
        return target
    }

    private fun exportTarget(context: Context, folder: File, game: Library.SteamGame, name: String): File {
        val target = child(context, folder, name)
        return if (target.exists() && !matches(target, contents(game.gameIdString))) {
            child(context, folder, filename(game, disambiguate = true))
        } else target
    }

    private fun exportArt(context: Context, folder: File, game: Library.SteamGame) {
        val art = listOfNotNull(game.icon, game.art).firstOrNull {
            it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp")
        } ?: return
        val title = filename(game).substringBeforeLast(".")
            .replace(Regex("\\[[^]]*]|\\([^)]*\\)"), "")
            .replace(Regex("[^a-zA-Z0-9\\s]"), "").trim()
            .replace(Regex("\\s+"), "-").lowercase(java.util.Locale.ROOT).take(50).trimEnd('-')
        if (title.isEmpty()) return
        val images = child(context, folder, "images")
        check(images.isDirectory || images.mkdir()) { context.getString(R.string.gfiles_art_unavailable) }
        val target = child(context, images, "$title-icon.${art.extension.lowercase()}")
        if (!target.exists()) AtomicFile(target).writeBytes(art.readBytes())
    }

    @Synchronized fun sync(context: Context, folder: File, games: List<Library.SteamGame>) {
        require(folder.isDirectory) { context.getString(R.string.gfiles_folder_unavailable) }
        val inventory = child(context, folder, INVENTORY)
        val previous = if (inventory.exists()) {
            val record = JSONObject(AtomicFile(inventory).readText())
            require(record.optString("format") == FORMAT) { context.getString(R.string.gfiles_unrelated_inventory) }
            record.getJSONObject("games")
        } else JSONObject()
        val next = JSONObject()
        val unique = games.distinctBy { it.gameId }
        val names = unique.groupingBy { filename(it).lowercase(java.util.Locale.ROOT) }.eachCount()
        val exports = unique.map { game ->
            val name = filename(game, disambiguate = names.getValue(filename(game).lowercase(java.util.Locale.ROOT)) > 1)
            val preferred = child(context, folder, name)
            val target = if (inventoryOwns(context, folder, previous, preferred, game.gameIdString)) preferred
                else exportTarget(context, folder, game, name)
            val text = contents(game.gameIdString)
            val existed = target.exists()
            val owned = inventoryOwns(context, folder, previous, target, game.gameIdString)
            val file = if (owned && target.exists() &&
                !matches(target, text)) target else exportNamed(context, folder, game, target.name)
            if (!existed || owned) next.put(file.name, game.gameIdString)
            file
        }
        for (name in previous.keys()) {
            val id = previous.getString(name)
            if (!next.has(name) && ours(name) && GameLaunchLink.validId(id)) {
                val file = child(context, folder, name)
                if (matches(file, contents(id))) {
                    val retained = exports.any { java.nio.file.Files.isSameFile(it.toPath(), file.toPath()) }
                    if (!retained) check(file.delete()) { context.getString(R.string.gfiles_remove_failed, name) }
                }
            }
        }
        if (previous.toString() != next.toString()) write(inventory, JSONObject().put("format", FORMAT).put("games", next).toString())
    }

    private fun ours(name: String) = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        .let { it == EXTENSION || it in GameRequest.extensions }

    private fun inventoryOwns(context: Context, folder: File, record: JSONObject, file: File, id: String): Boolean {
        if (record.optString(file.name) == id) return true
        if (!file.exists()) return false
        return record.keys().asSequence().any { name ->
            record.optString(name) == id && child(context, folder, name).let { old ->
                old.exists() && java.nio.file.Files.isSameFile(old.toPath(), file.toPath())
            }
        }
    }

    private fun child(context: Context, folder: File, name: String): File {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..")
        val file = File(folder, name)
        require(file.canonicalFile.parentFile == folder.canonicalFile) { context.getString(R.string.gfiles_outside_folder) }
        return file
    }

    private fun matches(file: File, text: String) = file.isFile && file.length() == text.toByteArray().size.toLong() &&
        file.readText() == text

    private fun write(file: File, text: String) = AtomicFile(file).writeText(text)
}
