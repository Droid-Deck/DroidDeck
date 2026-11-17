package com.droiddeck.launcher.stores.battlenet

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * A cancelled Battle.net install that could not be cleaned up yet. The marker is written before
 * the cleanup starts and removed only after the registration and the folder are both gone, so a
 * restart can reconcile a registration that the client writes late.
 */
internal object BattleNetCleanup {
    private const val TAG = "BattleNetCleanup"
    private const val DIR = "battlenet-cleanup"
    internal const val ACTIVE = "active"
    internal const val PENDING = "pending"
    internal const val WATCHING = "watching"

    internal data class Pending(
        val folder: File,
        val id: String,
        val createdFolder: Boolean,
        val state: String,
        val attempts: Int,
        val nextAt: Long,
    )

    internal fun dir(context: Context): File = File(context.filesDir, DIR)

    internal fun mark(context: Context, folder: File, id: String, createdFolder: Boolean, state: String = ACTIVE) {
        val current = get(context, folder)
        write(context, Pending(folder, id, createdFolder, state, current?.attempts ?: 0, current?.nextAt ?: 0L))
    }

    internal fun update(context: Context, folder: File, state: String, attempts: Int, nextAt: Long) {
        val current = get(context, folder) ?: return
        write(context, current.copy(state = state, attempts = attempts, nextAt = nextAt))
    }

    internal fun get(context: Context, folder: File): Pending? {
        val file = file(context, folder)
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        return runCatching {
            Pending(
                folder = File(json.getString("folder")),
                id = json.getString("id"),
                createdFolder = json.optBoolean("createdFolder"),
                state = json.optString("state", PENDING),
                attempts = json.optInt("attempts"),
                nextAt = json.optLong("nextAt"),
            )
        }.getOrNull()
    }

    internal fun pending(context: Context): List<Pending> = dir(context).listFiles()
        ?.filter { it.isFile && it.name.endsWith(".json") }
        ?.mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
        ?.mapNotNull { json ->
            runCatching {
                Pending(
                    folder = File(json.getString("folder")),
                    id = json.getString("id"),
                    createdFolder = json.optBoolean("createdFolder"),
                    state = json.optString("state", PENDING),
                    attempts = json.optInt("attempts"),
                    nextAt = json.optLong("nextAt"),
                )
            }.getOrNull()
        }
        ?.filter { it.state != ACTIVE }
        ?.sortedBy { it.nextAt }
        .orEmpty()

    internal fun clear(context: Context, folder: File) {
        file(context, folder).delete()
    }

    private fun file(context: Context, folder: File) =
        File(dir(context), MessageDigest.getInstance("SHA-256").run {
            update(folder.absolutePath.toByteArray())
            digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } + ".json")

    private fun write(context: Context, pending: Pending) {
        val target = file(context, pending.folder)
        target.parentFile?.mkdirs()
        val json = JSONObject().apply {
            put("folder", pending.folder.absolutePath)
            put("id", pending.id)
            put("createdFolder", pending.createdFolder)
            put("state", pending.state)
            put("attempts", pending.attempts)
            put("nextAt", pending.nextAt)
        }
        val tmp = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            tmp.writeText(json.toString())
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { Log.w(TAG, "could not write ${target.path}", it) }
    }
}
