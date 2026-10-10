package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import org.json.JSONObject
import java.io.File

/**
 * Flatpak in the Linux runtime: putting it there (droiddeck-flatpak-setup), the apps installed
 * from Flathub, and installing, removing and updating them (droiddeck-flatpak). One per-user
 * installation at /root/.local/share/flatpak, so nothing needs the system helper or polkit.
 *
 * Every command runs in a proot of its own, outside any session: the store works whether or not
 * Steam or the desktop is up, and a session starting meanwhile leaves it running (OrphanReaper).
 */
object FlatpakManager {
    private const val TAG = "FlatpakManager"
    const val FLATPAK = "/usr/bin/flatpak"
    const val BWRAP = "/usr/local/bin/droiddeck-bwrap"
    /** The program the front end starts a Flatpak app through, in a session of its own. */
    const val LAUNCHER = "/usr/local/bin/droiddeck-flatpak-run"
    private const val HELPER = "/usr/local/bin/droiddeck-flatpak"
    private const val SETUP = "/usr/local/bin/droiddeck-flatpak-setup"
    private const val USER_DIR = "root/.local/share/flatpak"

    /** One store operation at a time: Flatpak locks its installation per transaction anyway. */
    @Volatile var busy: String? = null
        private set

    class App(val id: String, val name: String, val icon: File?, val branch: String, val summary: String?)

    /** Flatpak is in the runtime, with Flathub to install from. */
    fun ready(context: Context): Boolean {
        val root = LinuxRuntime.rootDir(context)
        return File(root, FLATPAK.substring(1)).isFile &&
            File(root, "var/cache/droiddeck-flatpak/installed").isFile &&
            (FileUtils.readString(File(root, "$USER_DIR/repo/config"))?.contains("[remote \"flathub\"]") == true)
    }

    private fun arch(): String = when (android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
        "x86_64" -> "x86_64"
        else -> "aarch64"
    }

    /**
     * The installed apps, read from the installation itself: each app's deployed files carry the
     * desktop entry and icons it exports, so listing them needs no process in the runtime.
     */
    fun installedApps(context: Context): List<App> {
        val apps = File(LinuxRuntime.rootDir(context), "$USER_DIR/app")
        val arch = arch()
        return apps.listFiles()?.mapNotNull { dir ->
            val id = dir.name
            val archDir = File(dir, arch)
            val branch = archDir.listFiles()?.firstOrNull { File(it, "active").exists() } ?: return@mapNotNull null
            val active = File(branch, "active")
            val export = File(active, "export/share")
            val desktop = FileUtils.readString(File(export, "applications/$id.desktop"))
            val name = desktop?.let { entry(it, "Name") } ?: id.substringAfterLast('.')
            val summary = desktop?.let { entry(it, "Comment") }
            App(id, name, icon(export, id), branch.name, summary)
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    fun isInstalled(context: Context, id: String): Boolean =
        File(LinuxRuntime.rootDir(context), "$USER_DIR/app/$id/${arch()}").listFiles()?.any { File(it, "active").exists() } == true

    /** A key of the desktop entry's main group ([Desktop Entry]), unlocalised. */
    private fun entry(desktop: String, key: String): String? {
        var inMain = false
        for (line in desktop.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("[")) { inMain = t == "[Desktop Entry]"; continue }
            if (inMain && t.startsWith("$key=")) return t.substringAfter('=').trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** The largest PNG the app exports (the front end draws no SVG). */
    private fun icon(export: File, id: String): File? {
        val hicolor = File(export, "icons/hicolor")
        return hicolor.listFiles()
            ?.mapNotNull { size -> size.name.substringBefore('x').toIntOrNull()?.let { it to File(size, "apps/$id.png") } }
            ?.filter { it.second.isFile }
            ?.maxByOrNull { it.first }?.second
    }

    /** Runs [argv] in the runtime, its output also in files/logs/tools/flatpak-<verb>.log. */
    private fun runGuest(context: Context, argv: List<String>, fakeRoot: Boolean, onLine: (String) -> Unit): Int {
        val name = "flatpak-" + (argv.getOrNull(2)?.takeIf { argv.getOrNull(1) == HELPER } ?: "setup")
        return GuestCommand.run(context, argv, fakeRoot, name, onLine = onLine)
    }

    /** [block]'s result, or [ifBusy]'s while another operation has the installation. */
    private inline fun <T> exclusive(what: String, ifBusy: () -> T, block: () -> T): T {
        synchronized(this) {
            if (busy != null) return ifBusy()
            busy = what
        }
        try { return block() } finally { busy = null }
    }

    /** Puts Flatpak into the runtime and adds Flathub. Null on success, else what went wrong. */
    fun setup(context: Context, onProgress: (String, Int) -> Unit): String? {
        if (!LinuxRuntime.isInstalled(context)) return context.getString(R.string.user_apps_runtime_required)
        return exclusive("setup", { context.getString(R.string.flatpakmgr_busy) }) {
            var failure: String? = null
            val status = runGuest(context, listOf("/bin/bash", SETUP), fakeRoot = true) { line ->
                Log.i(TAG, "setup: $line")
                when {
                    line.startsWith("== STEP ") -> onProgress(line.removePrefix("== STEP "), -1)
                    line.startsWith("== FAIL ") -> failure = line.removePrefix("== FAIL ")
                }
            }
            when {
                failure != null -> failure
                status != 0 -> context.getString(R.string.flatpakmgr_setup_failed, status)
                !ready(context) -> context.getString(R.string.flatpakmgr_setup_unfinished)
                else -> null
            }
        }
    }

    /**
     * One store operation - install, uninstall or update - with its progress as a stage line and
     * an overall percentage across the steps Flatpak plans (a runtime, its extensions, the app).
     */
    private fun transaction(
        context: Context, verb: String, id: String?, onProgress: (String, Int) -> Unit, onDone: (JSONObject) -> Unit = {},
    ): String? {
        if (!ready(context)) return context.getString(R.string.flatpakmgr_not_ready)
        return exclusive("$verb:${id ?: "all"}", { context.getString(R.string.flatpakmgr_busy) }) {
            var error: String? = null
            // Progress lines carry no ref; they belong to the step the last "op" line started.
            var step = context.getString(R.string.flatpakmgr_starting)
            val argv = listOfNotNull("/usr/bin/python3", HELPER, verb, id)
            val status = runGuest(context, argv, fakeRoot = false) { line ->
                val o = runCatching { JSONObject(line) }.getOrNull()
                if (o == null) { Log.i(TAG, "$verb: $line"); return@runGuest }
                when (o.optString("e")) {
                    "op" -> { step = stage(context, o); onProgress(step, overall(o, 0)) }
                    "progress" -> onProgress(step, overall(o, o.optInt("percent")))
                    "error" -> { error = o.optString("message"); Log.w(TAG, "$verb $id: $error") }
                    "warning" -> Log.w(TAG, "$verb $id: ${o.optString("message")}")
                    "done" -> onDone(o)
                }
            }
            error ?: if (status != 0) context.getString(R.string.flatpakmgr_exit_status, status) else null
        }
    }

    private fun stage(context: Context, o: JSONObject): String {
        val ref = o.optString("ref")
        if (ref.isEmpty()) return o.optString("status").ifEmpty { context.getString(R.string.setup_check_busy) }
        val name = refLabel(ref) { id, args -> context.getString(id, *args) }
        val n = o.optInt("n")
        val kind = o.optString("kind")
        return if (n > 1) context.getString(when (kind) {
            "uninstall" -> R.string.flatpakmgr_removing_step
            "update" -> R.string.flatpakmgr_updating_step
            else -> R.string.flatpakmgr_installing_step
        }, name, o.optInt("i"), n)
        else context.getString(when (kind) {
            "uninstall" -> R.string.flatpakmgr_removing
            "update" -> R.string.flatpakmgr_updating
            else -> R.string.flatpakmgr_installing
        }, name)
    }

    /**
     * A ref in words: "app/org.supertuxproject.SuperTux/aarch64/stable" is "SuperTux". [text]
     * resolves a string resource with its arguments.
     */
    internal fun refLabel(ref: String, text: (Int, Array<out Any>) -> String): String {
        val parts = ref.split('/')
        val id = parts.getOrNull(1) ?: return ref
        if (parts.firstOrNull() == "app") return id.substringAfterLast('.')
        val branch = parts.getOrNull(3)
        return when {
            id.endsWith(".Locale") -> text(R.string.flatpakmgr_ref_translations, emptyArray())
            ".GL." in id || ".GL32." in id -> text(R.string.flatpakmgr_ref_graphics_drivers, emptyArray())
            "codecs" in id.lowercase() || id.endsWith(".ffmpeg-full") -> text(R.string.flatpakmgr_ref_media_codecs, emptyArray())
            id.endsWith(".Platform") -> when (val vendor = id.removeSuffix(".Platform").substringAfterLast('.')) {
                "gnome", "kde" -> vendor.uppercase()
                else -> vendor.replaceFirstChar { it.uppercase() }
            }.let { if (branch != null) text(R.string.flatpakmgr_ref_runtime_branch, arrayOf(it, branch)) else text(R.string.flatpakmgr_ref_runtime, arrayOf(it)) }
            else -> id.substringAfterLast('.')
        }
    }

    /** The whole transaction's percentage: steps done, plus this one's share. */
    private fun overall(o: JSONObject, percent: Int): Int {
        val n = o.optInt("n").coerceAtLeast(1)
        val i = o.optInt("i").coerceIn(1, n)
        return (((i - 1) * 100 + percent.coerceIn(0, 100)) / n)
    }

    fun install(context: Context, id: String, onProgress: (String, Int) -> Unit) = transaction(context, "install", id, onProgress)
    fun uninstall(context: Context, id: String, onProgress: (String, Int) -> Unit) = transaction(context, "uninstall", id, onProgress)

    /** Installs the .flatpak bundle at [guestPath], its runtimes from Flathub: the app's ID, and what went wrong if anything. */
    fun installBundle(context: Context, guestPath: String, onProgress: (String, Int) -> Unit): Pair<String?, String?> {
        var id: String? = null
        val problem = transaction(context, "install-bundle", guestPath, onProgress) { id = it.optString("id").ifEmpty { null } }
        return id to problem
    }
    fun update(context: Context, id: String?, onProgress: (String, Int) -> Unit) = transaction(context, "update", id, onProgress)

    /** Installed apps with an update on Flathub, or null when that could not be checked. */
    fun updates(context: Context): Set<String>? {
        if (!ready(context)) return emptySet()
        var apps: Set<String>? = null
        exclusive("updates", {}) {
            runGuest(context, listOf("/usr/bin/python3", HELPER, "updates"), fakeRoot = false) { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@runGuest
                if (o.optString("e") == "updates") {
                    val a = o.optJSONArray("apps")
                    apps = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet()
                }
            }
        }
        return apps
    }
}
