package com.droiddeck.launcher.stores.steam

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SecondaryLibrary
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SteamLibraries
import com.droiddeck.launcher.stores.StoreInstallRoot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The active Steam client's folders as the app sees them. Reads storage; call off the main thread. */
internal object SteamClient {
    const val X86_64 = "x86_64"

    class Target(
        val library: SteamLibraryFolders.Library, val engineHost: File, val label: String,
        val freeBytes: Long, val removable: Boolean, val folder: Boolean,
    )

    fun activeArch(context: Context): String = SessionPrefs.activeSteamClientArch(context)

    private fun guestHome(arch: String) = if (arch == X86_64) "/root/.droiddeck-x64" else "/root"

    fun guestRoot(arch: String) = "${guestHome(arch)}/.local/share/Steam"

    private fun aliases(arch: String) = listOf(guestRoot(arch), "${guestHome(arch)}/.steam/steam", "${guestHome(arch)}/.steam/root")

    fun root(context: Context, arch: String = activeArch(context)): File = SessionPrefs.steamRoot(context, arch)

    fun registry(context: Context, arch: String = activeArch(context)): File =
        File(LinuxRuntime.rootDir(context), guestHome(arch).removePrefix("/") + "/.steam/registry.vdf")

    fun loginUsers(context: Context, arch: String = activeArch(context)): File = File(root(context, arch), "config/loginusers.vdf")

    fun language(context: Context): String =
        runCatching { SteamLibraryFolders.language(registry(context).readText()) }.getOrNull() ?: "english"

    fun mounts(context: Context): List<SteamLibraryFolders.Mount> {
        val out = ArrayList<SteamLibraryFolders.Mount>()
        GameStorage.effective(context)?.let { card -> SecondaryLibrary.CARD_GUESTS.forEach { out += SteamLibraryFolders.Mount(it, File(card.path)) } }
        SteamLibraries.list(context).forEach { out += SteamLibraryFolders.Mount(it.guest, it.host) }
        AddedGames.roots(context).forEach { out += SteamLibraryFolders.Mount(it.guest, it.host) }
        StoreInstallRoot.externalRoots(context).forEach { (host, guest) -> out += SteamLibraryFolders.Mount(guest, host) }
        out += SteamLibraryFolders.Mount("/root/Storage", Environment.getExternalStorageDirectory())
        return out
    }

    /** Every library the client lists in libraryfolders.vdf that maps to a folder of the app. */
    fun libraries(context: Context, arch: String = activeArch(context)): List<SteamLibraryFolders.Library> {
        val root = root(context, arch)
        val text = runCatching { File(root, "steamapps/libraryfolders.vdf").readText() }.getOrNull().orEmpty()
        return SteamLibraryFolders.resolve(
            SteamLibraryFolders.parse(text), guestRoot(arch), aliases(arch), root, mounts(context), LinuxRuntime.rootDir(context),
        )
    }

    private fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    /** The card and added libraries are written through the raw card view when Android allows it, as the session binds them. */
    private fun engineView(context: Context): (File) -> File {
        val bases = listOfNotNull(GameStorage.effective(context)?.let { File(it.path) }) + SteamLibraries.list(context).map { it.host }
        val seen = HashMap<String, File>()
        return { host ->
            val path = host.absolutePath
            val base = bases.filter { path == it.absolutePath || path.startsWith(it.absolutePath + "/") }.maxByOrNull { it.absolutePath.length }
            if (base == null) host else {
                val content = seen.getOrPut(base.absolutePath) { SecondaryLibrary.contentPath(base) }
                if (content.absolutePath == base.absolutePath) host
                else path.removePrefix(base.absolutePath).trimStart('/').let { if (it.isEmpty()) content else File(content, it) }
            }
        }
    }

    class EngineLibraries(val libraries: List<Pair<SteamLibraryFolders.Library, File>>) {
        val json: String get() = JSONArray().apply {
            for ((library, host) in libraries) put(JSONObject().put("host", host.absolutePath).put("guest", library.guest))
        }.toString()

        fun hostOf(library: SteamLibraryFolders.Library): File =
            libraries.firstOrNull { it.first.guest == library.guest }?.second ?: library.host
    }

    fun engineLibraries(context: Context): EngineLibraries {
        val view = engineView(context)
        return EngineLibraries(libraries(context).filter { it.host.isDirectory }.map { it to view(it.host) })
    }

    fun targets(context: Context): List<Target> {
        val arch = activeArch(context)
        val card = GameStorage.effective(context)?.let { canonical(File(it.path)) }
        val known = Library.steamLibraries(context, arch).associate { (host, label) ->
            canonical(host) to if (label == "internal") context.getString(R.string.games_internal) else label
        }
        val engine = engineLibraries(context)
        return engine.libraries.mapNotNull { (library, host) ->
            if (!library.host.isDirectory || !library.host.canWrite()) return@mapNotNull null
            val path = canonical(library.host)
            val onCard = card != null && (path == card || path.startsWith("$card/"))
            val label = library.label.ifBlank {
                if (library.main) context.getString(R.string.stores_target_internal) else known[path] ?: File(library.guest).name
            }
            Target(library, host, label, StoreInstallRoot.availableBytes(host), removable = onCard, folder = !library.main && !onCard)
        }
    }

    /** True while a session that can run the Steam client is up or starting. */
    fun clientMayRun(): Boolean {
        val up = SessionState.running || (SessionState.phase != SessionPhase.IDLE && SessionState.phase != SessionPhase.FAILED)
        return up && SessionState.mode != SessionService.MODE_RUN
    }
}
