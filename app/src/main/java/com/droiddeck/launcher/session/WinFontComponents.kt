package com.droiddeck.launcher.session

import java.io.File
import java.nio.file.Files
import org.json.JSONObject

/** Microsoft core-font installers and family names pinned by the Winetricks recipes. */
object WinFontComponents {
    internal data class Font(val file: String, val family: String)
    private data class Package(val id: String, val title: String, val archive: String, val sha256: String, val fonts: List<Font>)

    // https://github.com/Winetricks/winetricks/blob/master/src/winetricks (trebuchet / verdana)
    private val packages = listOf(
        Package("trebuchet", "Trebuchet MS", "trebuc32.exe",
            "5a690d9bb8510be1b8b4fe49f1f2319651fe51bbe54775ddddd8ef0bd07fdac9", listOf(
                Font("trebuc.ttf", "Trebuchet MS"), Font("trebucbd.ttf", "Trebuchet MS Bold"),
                Font("trebucit.ttf", "Trebuchet MS Italic"), Font("trebucbi.ttf", "Trebuchet MS Bold Italic"),
            )),
        Package("verdana", "Verdana", "verdan32.exe",
            "c1cb61255e363166794e47664e2f21af8e3a26cb6346eb8d2ae2fa85dd5aad96", listOf(
                Font("verdana.ttf", "Verdana"), Font("verdanab.ttf", "Verdana Bold"),
                Font("verdanai.ttf", "Verdana Italic"), Font("verdanaz.ttf", "Verdana Bold Italic"),
            )),
    )
    private val ids = packages.map { it.id }.toSet()
    internal fun isPinned(id: String): Boolean = id in ids
    private val fontKeys = listOf(
        "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts",
        "Software\\Microsoft\\Windows\\CurrentVersion\\Fonts",
    )

    fun components(): List<WinComponents.Component> = packages.map { pack ->
        val archive = WinComponents.Step("archive_extract", JSONObject()
            .put("url", "https://github.com/pushcx/corefonts/raw/master/${pack.archive}")
            .put("file_name", pack.archive).put("file_checksum", pack.sha256))
        WinComponents.Component(pack.id, "${pack.title}: regular, bold, italic and bold italic fonts", "Microsoft", "ready",
            emptyList(), listOf(archive) + pack.fonts.map { font ->
                WinComponents.Step("install_font", JSONObject().put("file_name", font.file).put("family", font.family))
            })
    }

    // Remote entries must not replace the pinned archives or redirect their font/registry paths.
    fun merge(catalog: List<WinComponents.Component>): List<WinComponents.Component> =
        catalog.filterNot { it.name.removeSuffix("_dll") in ids } + components()

    fun requiredBy(appId: Int): List<String> = if (appId == 400) listOf("trebuchet", "verdana") else emptyList()

    internal fun font(component: String, file: String, family: String): Font? =
        packages.firstOrNull { it.id == component }?.fonts?.firstOrNull { it.file == file && it.family == family }

    internal fun stage(component: String, file: String, family: String, source: File, staging: File): List<JSONObject> {
        val font = requireNotNull(font(component, file, family)) { "Unrecognized font file or family" }
        val root = source.canonicalFile.toPath()
        val matches = source.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter {
            it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.equals(font.file, ignoreCase = true) &&
                it.canonicalFile.toPath().startsWith(root)
        }.toList()
        require(matches.size == 1) { "The archive must contain exactly one ${font.file}" }
        val dest = File(staging, "drive_c/windows/Fonts/${font.file}")
        dest.parentFile?.mkdirs()
        matches.single().copyTo(dest, overwrite = true)
        return fontKeys.map { key ->
            JSONObject().put("hive", "HKLM").put("key", key).put("name", "${font.family} (TrueType)")
                .put("type", "sz").put("data", font.file)
        }
    }
}
