package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object CommunityControls {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val BASE = "https://raw.githubusercontent.com/Droid-Deck/DroidDeck/main/community/controls/"
    const val SUBMIT = "https://github.com/Droid-Deck/DroidDeck/issues/new?template=community-controls.yml"
    data class Pack(val layout: SavedInputLayout, val bindings: SavedInputBindings, val images: Map<String, ByteArray>)
    data class Entry(val name: String, val author: String, val game: String, val license: String,
        val path: String, val sha256: String, val screen: InputLayoutScreen, val preview: String? = null, val bundled: Boolean = false,
        val source: ControlSources.Source = ControlSources.DROIDDECK, val skin: Boolean = false) {
        val key get() = source.id + "/" + path
    }

    fun readLimited(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "Controls file is too large." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun export(context: Context, layout: SavedInputLayout, bindings: SavedInputBindings): ByteArray {
        require(layout.editable && bindings.compatible(layout))
        val library = InputLibraries.read(context).withLayout(layout).withPreset(bindings)
        val doc = JSONObject(InputLibraries.encode(library))
        fun item(key: String, id: String): JSONObject {
            val array = doc.getJSONArray(key)
            return (0 until array.length()).map { array.getJSONObject(it) }.first { it.getString("id") == id }
        }
        val manifest = JSONObject().put("format", "droiddeck-controls").put("version", 1)
            .put("layout", item("layouts", layout.id)).put("bindings", item("bindings", bindings.id))
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            add("controls.json", manifest.toString().toByteArray(Charsets.UTF_8))
            for (name in layout.elements.flatMap { listOf(it.image, it.stickImage) }.filter { it.isNotEmpty() }.distinct()) {
                val file = requireNotNull(InputButtonImages.file(context, name))
                add("images/$name", file.inputStream().use { readLimited(it, 1024 * 1024) })
            }
        }
        return output.toByteArray().also { parse(context, it) }
    }

    fun parse(context: Context, bytes: ByteArray): Pack {
        require(bytes.size <= MAX_BYTES)
        val files = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && files.size < 65 && entry.name !in files)
                require(entry.name == "controls.json" || entry.name.matches(Regex("images/[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}")))
                val data = readLimited(zip, minOf(if (entry.name == "controls.json") 2_000_000 else 1024 * 1024, MAX_BYTES - total))
                total += data.size
                files[entry.name] = data
            }
        }
        val manifest = JSONObject(requireNotNull(files.remove("controls.json")).toString(Charsets.UTF_8))
        require(manifest.getString("format") == "droiddeck-controls" && manifest.getInt("version") == 1)
        val layout = manifest.getJSONObject("layout")
        val bindings = manifest.getJSONObject("bindings")
        val layoutId = newInputId()
        val bindingId = newInputId()
        layout.put("id", layoutId).remove("origin")
        require(layout.getString("template") == InputLayoutTemplate.CUSTOM.name)
        bindings.put("id", bindingId).put("layout", layoutId)
        require(!bindings.getBoolean("physical") && bindings.getJSONArray("keys").length() == 0)
        val doc = JSONObject(InputLibraries.encode(InputLibraries.read(context)))
        doc.getJSONArray("layouts").put(layout)
        doc.getJSONArray("bindings").put(bindings)
        val decoded = requireNotNull(InputLibraries.decode(doc.toString())) { "Invalid controls or bindings." }
        val saved = decoded.layouts.first { it.id == layoutId }
        val names = saved.elements.flatMap { listOf(it.image, it.stickImage) }.filter { it.isNotEmpty() }.toSet()
        require(files.keys == names.map { "images/$it" }.toSet()) { "Missing or unexpected button images." }
        var pixels = 0L
        val images = names.associateWith { name ->
            val data = files.getValue("images/$name")
            require(data.take(8) == listOf<Byte>(-119, 80, 78, 71, 13, 10, 26, 10))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            require(bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024)
            pixels += bounds.outWidth.toLong() * bounds.outHeight
            require(pixels <= 16_777_216)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size))
            bitmap.recycle()
            data
        }
        return Pack(saved, decoded.preset(bindingId), images)
    }

    fun install(context: Context, pack: Pack, profile: String, role: InputRole): InputLibrary {
        require(pack.layout.supports(role))
        val written = mutableListOf<java.io.File>()
        try {
            val names = pack.images.mapValues { (_, bytes) ->
                val name = "key_${newInputId()}.png"
                val file = requireNotNull(InputButtonImages.file(context, name))
                file.parentFile!!.mkdirs()
                written += file
                file.writeBytes(bytes)
                name
            }
            val layout = pack.layout.copy(elements = pack.layout.elements.map { e -> e.copy(image = names[e.image] ?: "", stickImage = names[e.stickImage] ?: "") })
            return InputLibraries.update(context) {
                it.withLayout(layout).withPreset(pack.bindings).chooseLayout(profile, role, layout.id)
            }
        } catch (e: Exception) {
            written.forEach { it.delete() }
            throw e
        }
    }

    fun catalog(raw: String, source: ControlSources.Source = ControlSources.DROIDDECK): List<Entry> {
        require(raw.length <= 1_000_000)
        val doc = JSONObject(raw)
        val skin = doc.has("skins") && !doc.has("controls")
        require(skin || doc.getInt("version") == 1)
        val list = doc.getJSONArray(if (skin) "skins" else "controls")
        require(list.length() <= 256)
        return (0 until list.length()).map { index ->
            val e = list.getJSONObject(index)
            fun text(key: String) = e.getString(key).also { require(it.isNotBlank() && it.length <= 160) }
            val path = text("file")
            require(ControlSources.validPath(path) && path.endsWith(".zip", ignoreCase = true))
            val sha = e.optString("sha256").also { require(if (skin) it.isEmpty() || it.matches(Regex("[a-f0-9]{64}")) else it.matches(Regex("[a-f0-9]{64}"))) }
            Entry(text("name"), e.optString("author").take(160), e.optString("game").take(160), e.optString("license").take(160), path, sha,
                if (skin) InputLayoutScreen.BOTH else InputLayoutScreen.valueOf(e.getString("screen")), e.optString("preview").takeIf { it.isNotEmpty() }?.also {
                    require(ControlSources.validPath(it) && it.endsWith(".png", ignoreCase = true))
                }, source = source, skin = skin)
        }.also { require(it.map { e -> e.path }.distinct().size == it.size) }
    }

    fun bundled(context: Context): List<Entry> = context.assets.open("community-controls/catalog.json").use {
        catalog(readLimited(it, 1_000_000).toString(Charsets.UTF_8)).map { e -> e.copy(bundled = true) }
    }

    fun fetchCatalog(source: ControlSources.Source = ControlSources.DROIDDECK): List<Entry> =
        catalog(get(source.manifest, 1_000_000).toString(Charsets.UTF_8), source)
    fun preview(context: Context, entry: Entry): android.graphics.Bitmap? {
        val path = entry.preview ?: return null
        val bytes = if (entry.bundled) context.assets.open("community-controls/$path").use { readLimited(it, 512 * 1024) }
            else get(entry.source.url(path), 2 * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 512) inSampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    fun download(context: Context, entry: Entry, layout: SavedInputLayout? = null, bindings: SavedInputBindings? = null): Pack {
        val bytes = if (entry.bundled) context.assets.open("community-controls/${entry.path}").use { readLimited(it) }
            else get(entry.source.url(entry.path), if (entry.skin) 32 * 1024 * 1024 else MAX_BYTES)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(entry.sha256.isEmpty() && entry.skin || sha == entry.sha256) { "Controls download failed verification." }
        return if (entry.skin) ArmsControlSkins.parse(context, bytes, requireNotNull(layout), requireNotNull(bindings), entry.name)
        else parse(context, bytes).also { require(it.layout.screen == entry.screen) }
    }

    private fun get(url: String, limit: Int): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        return try {
            require(connection.responseCode == 200) { "Could not download community controls (${connection.responseCode})." }
            require(connection.contentLengthLong <= limit)
            connection.inputStream.use { readLimited(it, limit) }
        } finally { connection.disconnect() }
    }
}
