package com.droiddeck.launcher.stores

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What a store install leaves beside the game so the rest of the app can treat the folder like
 * any added game: `.droiddeck-store.json` in the game's folder.
 *
 * [AddedGames.scanGame] reads it to pick the exe (instead of guessing from the folder), to name
 * the shortcut after the store's title and to tag the game with its [store]. [launcher] is the
 * small .bat the install writes when the game needs arguments or environment at launch (Epic's
 * identity arguments, Amazon's FuelPump variables): a Steam shortcut can only carry a fixed Exe,
 * so the launcher is what the shortcut runs and [exe] is what it starts. Every store install is a
 * Steam shortcut; there is no switch for it (an `addToSteam` field in an older sidecar is ignored).
 *
 * Paths are relative to the game folder with forward slashes, so the folder can move between
 * storage volumes and stay valid.
 */
class StoreGameSidecar(
    val store: Store,
    val id: String,
    val title: String,
    val exe: String,
    val launcher: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val installVersion: String = "",
    val installedAt: Long = 0L,
    val cover: String? = null,
    val hero: String? = null,
    /** Per-store identifiers the launcher needs again (Epic namespace / catalog id, Amazon entitlement). */
    val extra: Map<String, String> = emptyMap(),
) {
    fun exeFile(folder: File): File = File(folder, exe)
    fun launcherFile(folder: File): File? = launcher?.takeIf { it.isNotEmpty() }?.let { File(folder, it) }

    fun copy(
        exe: String = this.exe, launcher: String? = this.launcher, args: List<String> = this.args,
        env: Map<String, String> = this.env, installVersion: String = this.installVersion,
        installedAt: Long = this.installedAt, cover: String? = this.cover, hero: String? = this.hero,
        extra: Map<String, String> = this.extra,
    ) = StoreGameSidecar(store, id, title, exe, launcher, args, env, installVersion, installedAt, cover, hero, extra)

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("store", store.id)
        put("id", id)
        put("title", title)
        put("exe", exe)
        if (!launcher.isNullOrEmpty()) put("launcher", launcher)
        if (args.isNotEmpty()) put("args", JSONArray(args))
        if (env.isNotEmpty()) put("env", JSONObject(env as Map<*, *>))
        if (installVersion.isNotEmpty()) put("installVersion", installVersion)
        if (installedAt > 0L) put("installedAt", installedAt)
        if (!cover.isNullOrEmpty()) put("cover", cover)
        if (!hero.isNullOrEmpty()) put("hero", hero)
        if (extra.isNotEmpty()) put("extra", JSONObject(extra as Map<*, *>))
    }

    fun write(folder: File) {
        folder.mkdirs()
        val file = File(folder, FILE_NAME)
        val tmp = File(folder, "$FILE_NAME.tmp")
        tmp.writeText(toJson().toString(2))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        const val FILE_NAME = ".droiddeck-store.json"
        const val VERSION = 1

        fun file(folder: File): File = File(folder, FILE_NAME)

        /** The sidecar in [folder], or null when there is none or it cannot be read. */
        fun read(folder: File): StoreGameSidecar? {
            val f = file(folder)
            if (!f.isFile) return null
            return runCatching { parse(f.readText()) }.getOrNull()
        }

        /** Pure parse; null for anything that is not a usable sidecar (so a scan falls back to guessing). */
        fun parse(text: String): StoreGameSidecar? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val store = Store.byId(o.optString("store")) ?: return null
            val id = o.optString("id", "")
            val title = o.optString("title", "")
            val exe = o.optString("exe", "").replace('\\', '/')
            if (id.isEmpty() || title.isEmpty() || exe.isEmpty()) return null
            // A relative path only: a sidecar that points outside its folder is not trusted.
            if (!relativeInside(exe)) return null
            val launcher = o.optString("launcher", "").replace('\\', '/').takeIf { it.isNotEmpty() && relativeInside(it) }
            val args = o.optJSONArray("args")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList()
            val env = LinkedHashMap<String, String>()
            o.optJSONObject("env")?.let { e -> e.keys().forEach { k -> env[k] = e.optString(k) } }
            val extra = LinkedHashMap<String, String>()
            o.optJSONObject("extra")?.let { e -> e.keys().forEach { k -> extra[k] = e.optString(k) } }
            return StoreGameSidecar(
                store, id, title, exe, launcher, args, env,
                installVersion = o.optString("installVersion", ""),
                installedAt = o.optLong("installedAt", 0L),
                cover = o.optString("cover", "").ifEmpty { null },
                hero = o.optString("hero", "").ifEmpty { null },
                extra = extra,
            )
        }

        /** Relative, no drive letter, no step up: an absolute or escaping path is refused, not trimmed into shape. */
        private fun relativeInside(path: String): Boolean =
            path.isNotEmpty() && !path.startsWith("/") && !path.contains(":") && path.split('/').none { it == ".." || it.isEmpty() }
    }
}
