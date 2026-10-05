package com.droiddeck.launcher.session

import android.content.Context
import android.util.AtomicFile
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Windows components (OpenAL, d3dx, XAudio...) for a game's Proton prefix, picked per game.
 *
 * The packages are hosted like the desktop ones, in a catalog of their own, and each extracts
 * over the runtime to /opt/droiddeck/wincomponents/<id>. The picks go to the runtime as
 * ~/.config/droiddeck/wincomponents.json, and droiddeck-wincomponents copies the picked
 * components into the game's prefix at every launch (see that script for why every launch).
 */
object WinComponents {
    const val CATALOG_URL = "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/wincomponents-r1/wincomponents.json"
    private const val SELECTION = "wincomponents.json"
    private const val GUEST_SELECTION = "root/.config/droiddeck/wincomponents.json"
    private const val STORE = "opt/droiddeck/wincomponents"
    private val ID = Regex("[a-z][a-z0-9_]*")

    fun fetch(): List<DesktopCatalog.Entry>? =
        DesktopCatalog.fetch(CATALOG_URL)?.filter { ID.matches(it.id) && it.kind == "tar" }

    /** The installed version of a component, or null. */
    fun installed(context: Context, id: String): String? = runCatching {
        JSONObject(File(LinuxRuntime.rootDir(context), "$STORE/$id/component.json").readText()).optString("version", "?")
    }.getOrNull()

    /** Downloads, verifies and extracts one component. Returns null on success, else a message. */
    fun install(context: Context, entry: DesktopCatalog.Entry, listener: LinuxRuntimeInstaller.ProgressListener?): String? {
        require(ID.matches(entry.id))
        // A new version replaces the old one whole, so a DLL it dropped is not left behind.
        FileUtils.delete(File(LinuxRuntime.rootDir(context), "$STORE/${entry.id}"))
        // Its own marker name, so a component never reads as a desktop package of the same id.
        val error = DesktopCatalog.install(context, DesktopCatalog.Entry(
            "wincomponent-${entry.id}", entry.name, entry.tier, entry.version, entry.kind,
            entry.url, entry.sha256, entry.size, entry.notes, entry.icon, entry.category,
        ), listener)
        if (error == null && installed(context, entry.id) == null) return "The package did not contain ${entry.id}"
        return error
    }

    /** Removes a component from the runtime and from every game's picks. */
    @Synchronized
    fun uninstall(context: Context, id: String) {
        require(ID.matches(id))
        val root = LinuxRuntime.rootDir(context)
        FileUtils.delete(File(root, "$STORE/$id"))
        File(root, ".droiddeck-pkg-wincomponent-$id").delete()
        save(context, read(context).mapValues { (_, ids) -> ids - id }.filterValues { it.isNotEmpty() })
    }

    /** Each game's picks, by the appid its prefix is named after. */
    @Synchronized
    fun read(context: Context): Map<String, List<String>> = try {
        val json = JSONObject(AtomicFile(File(context.filesDir, SELECTION)).readFully().toString(Charsets.UTF_8))
        val games = json.optJSONObject("games") ?: JSONObject()
        games.keys().asSequence().associateWith { app ->
            val ids = games.getJSONArray(app)
            (0 until ids.length()).map { ids.getString(it) }.filter { ID.matches(it) }
        }
    } catch (_: java.io.FileNotFoundException) {
        emptyMap()
    }

    fun picks(context: Context, appId: Long): List<String> = read(context)[appId.toString()].orEmpty()

    @Synchronized
    fun setPicks(context: Context, appId: Long, ids: List<String>) {
        require(ids.all { ID.matches(it) })
        val all = read(context).toMutableMap()
        if (ids.isEmpty()) all.remove(appId.toString()) else all[appId.toString()] = ids.distinct()
        save(context, all)
    }

    private fun save(context: Context, games: Map<String, List<String>>) {
        val text = JSONObject().put("version", 1).put("games", JSONObject().apply {
            games.forEach { (app, ids) -> put(app, JSONArray(ids)) }
        }).toString()
        write(File(context.filesDir, SELECTION), text)
        publish(context, text)
    }

    /**
     * Writes the picks where the launch script reads them; SessionFiles calls this at every session
     * start too, so a runtime reinstalled since keeps them. An empty selection still writes a file,
     * which is how a component turned off for every game is taken out of their prefixes.
     */
    @Synchronized
    fun publish(context: Context, text: String? = null) {
        val body = text ?: runCatching { AtomicFile(File(context.filesDir, SELECTION)).readFully().toString(Charsets.UTF_8) }.getOrNull() ?: return
        val root = LinuxRuntime.rootDir(context)
        if (root.isDirectory) write(File(root, GUEST_SELECTION), body)
    }

    private fun write(file: File, text: String) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
