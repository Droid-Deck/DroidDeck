package com.droiddeck.launcher.input

import android.content.Context
import org.json.JSONArray
import java.net.URI
import java.net.URLEncoder

internal object ControlSources {
    data class Source(val name: String, val manifest: String, val submit: String? = null, val builtIn: Boolean = false) {
        val id get() = manifest
        fun url(path: String): String {
            require(validPath(path)) { "Invalid controls source path." }
            return manifest.substringBeforeLast('/') + "/" + path.split('/').joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
        }
    }
    val DROIDDECK = Source("DroidDeck", CommunityControls.BASE + "catalog.json", CommunityControls.SUBMIT, true)
    val ARMSX2 = Source("ARMSX2 community", "https://raw.githubusercontent.com/bagasromadon/ARMSX2-CustomControllerSkins/main/manifest.json",
        "https://github.com/bagasromadon/ARMSX2-CustomControllerSkins/issues/new", true)
    private fun prefs(context: Context) = context.getSharedPreferences("community-control-sources", Context.MODE_PRIVATE)

    fun validPath(path: String) = path.length in 1..512 && path.split('/').all { it.isNotBlank() && it != "." && it != ".." } &&
        path.none { it.isISOControl() || it in "\\:%?#" } && !path.startsWith('/')

    private fun https(raw: String): URI {
        require(raw.length <= 2048)
        val uri = URI(raw.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null && !uri.path.isNullOrBlank() && !uri.path.endsWith('/')) {
            "Enter an HTTPS catalog URL or a GitHub repository URL."
        }
        return uri
    }

    fun candidates(raw: String): List<Source> {
        val uri = https(raw)
        val parts = uri.path.trim('/').split('/')
        if (uri.host.equals("github.com", true)) {
            require(parts.size == 2 || parts.size >= 5 && parts[2] == "blob") { "Use a GitHub repository or manifest file URL." }
            require(parts.take(2).all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it != "." && it != ".." })
            val repo = parts.take(2).joinToString("/")
            val submit = "https://github.com/$repo/issues/new"
            val base = "https://raw.githubusercontent.com/$repo/"
            return if (parts.size == 2) listOf("catalog.json", "manifest.json").map { Source(repo, base + "main/" + it, submit) }
            else listOf(Source(repo, base + parts.drop(3).joinToString("/"), submit))
        }
        val submit = if (uri.host.equals("raw.githubusercontent.com", true) && parts.size >= 4)
            "https://github.com/${parts[0]}/${parts[1]}/issues/new" else null
        return listOf(Source(if (submit != null) parts.take(2).joinToString("/") else uri.host, uri.toASCIIString(), submit))
    }

    fun read(context: Context): List<Source> {
        val custom = runCatching {
            val raw = prefs(context).getString("sources", "[]")!!
            require(raw.length <= 32_000)
            val array = JSONArray(raw)
            require(array.length() <= 16)
            (0 until array.length()).map { candidates(array.getString(it)).single() }
        }.getOrDefault(emptyList())
        return (listOf(DROIDDECK, ARMSX2) + custom).distinctBy { it.id }
    }

    fun add(context: Context, source: Source) {
        val list = read(context)
        require(list.none { it.id == source.id }) { "This source is already connected." }
        require(list.size < 18) { "Remove a source before adding another." }
        save(context, list + source)
    }
    fun remove(context: Context, source: Source) {
        require(!source.builtIn)
        save(context, read(context).filterNot { it.id == source.id })
    }
    private fun save(context: Context, sources: List<Source>) {
        check(prefs(context).edit().putString("sources", JSONArray(sources.filterNot { it.builtIn }.map { it.manifest }).toString()).commit()) {
            "Could not save controls sources."
        }
    }
}
