package com.droiddeck.launcher.stores

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
    val extra: Map<String, String> = emptyMap(),
    val state: String = STATE_INSTALLED,
    val epic: EpicOptions = EpicOptions(),
    val cloud: Boolean = true,
    val achievements: Boolean = true,
) {
    val isInstalled: Boolean get() = state == STATE_INSTALLED

    fun exeFile(folder: File): File = File(folder, exe)
    fun launcherFile(folder: File): File? = launcher?.takeIf { it.isNotEmpty() }?.let { File(folder, it) }

    fun copy(
        exe: String = this.exe, launcher: String? = this.launcher, args: List<String> = this.args,
        env: Map<String, String> = this.env, installVersion: String = this.installVersion,
        installedAt: Long = this.installedAt, cover: String? = this.cover, hero: String? = this.hero,
        extra: Map<String, String> = this.extra, state: String = this.state, epic: EpicOptions = this.epic, cloud: Boolean = this.cloud, achievements: Boolean = this.achievements,
    ) = StoreGameSidecar(store, id, title, exe, launcher, args, env, installVersion, installedAt, cover, hero, extra, state, epic, cloud, achievements)

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("store", store.id)
        put("id", id)
        put("title", title)
        if (exe.isNotEmpty()) put("exe", exe)
        if (state != STATE_INSTALLED) put("state", state)
        if (store == Store.EPIC) put("epic", JSONObject().put("eos", epic.eos).put("offline", epic.offline))
        if (store != Store.AMAZON) put("cloud", cloud)
        if (store == Store.GOG) put("achievements", achievements)
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
        const val STATE_INSTALLED = "installed"
        const val STATE_INSTALLING = "installing"

        fun file(folder: File): File = File(folder, FILE_NAME)

        fun find(context: Context, store: Store, id: String): StoreGameSidecar? =
            StoreInstallRoot.gameFolders(context.applicationContext).asSequence()
                .mapNotNull { read(it) }
                .firstOrNull { it.store == store && it.id == id }

        fun updateEpic(folder: File, change: (EpicOptions) -> EpicOptions): StoreGameSidecar? {
            val current = read(folder) ?: return null
            val next = current.copy(epic = change(current.epic))
            next.write(folder)
            return read(folder)?.takeIf { it.epic == next.epic }
        }

        fun updateCloud(folder: File, on: Boolean): StoreGameSidecar? {
            val current = read(folder) ?: return null
            current.copy(cloud = on).write(folder)
            return read(folder)?.takeIf { it.cloud == on }
        }

        fun updateAchievements(folder: File, on: Boolean): StoreGameSidecar? {
            val current = read(folder) ?: return null
            current.copy(achievements = on).write(folder)
            return read(folder)?.takeIf { it.achievements == on }
        }

        fun read(folder: File): StoreGameSidecar? {
            val f = file(folder)
            if (!f.isFile) return null
            return runCatching { parse(f.readText()) }.getOrNull()
        }

        fun parse(text: String): StoreGameSidecar? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val store = Store.byId(o.optString("store")) ?: return null
            val id = o.optString("id", "")
            val title = o.optString("title", "")
            val exe = o.optString("exe", "").replace('\\', '/')
            val state = o.optString("state", STATE_INSTALLED).ifEmpty { STATE_INSTALLED }
            if (id.isEmpty() || title.isEmpty()) return null
            if (exe.isEmpty() && state == STATE_INSTALLED) return null
            if (exe.isNotEmpty() && !relativeInside(exe)) return null
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
                state = state,
                epic = o.optJSONObject("epic")?.let { e -> EpicOptions(eos = e.optBoolean("eos", true), offline = e.optBoolean("offline", false)) } ?: EpicOptions(),
                cloud = o.optBoolean("cloud", true),
                achievements = o.optBoolean("achievements", true),
            )
        }

        private fun relativeInside(path: String): Boolean =
            path.isNotEmpty() && !path.startsWith("/") && !path.contains(":") && path.split('/').none { it == ".." || it.isEmpty() }
    }
}

data class EpicOptions(val eos: Boolean = true, val offline: Boolean = false) {
    val wantsCode: Boolean get() = eos && !offline
}
