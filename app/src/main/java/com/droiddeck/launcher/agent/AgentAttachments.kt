package com.droiddeck.launcher.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

/**
 * Files the user attaches to a message, copied into the Linux userland so the agent's own tools
 * can read them (Claude Code's Read shows images and PDFs to the model).
 */
object AgentAttachments {
    /** Guest paths waiting to go with the next message. */
    val pending = mutableStateListOf<String>()

    private const val GUEST_DIR = "/root/.cache/droiddeck/attachments"
    private val IMAGE = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    fun hostDir(context: Context) = File(LinuxRuntime.rootDir(context.applicationContext), GUEST_DIR.removePrefix("/"))

    fun host(context: Context, guestPath: String) = File(LinuxRuntime.rootDir(context.applicationContext), guestPath.removePrefix("/"))

    fun isImage(path: String) = path.substringAfterLast('.', "").lowercase() in IMAGE

    /** Copies [uris] for [chat] (or a new conversation) on the calling thread; returns their guest paths. */
    fun copy(context: Context, chat: String?, uris: List<Uri>): List<String> {
        val app = context.applicationContext
        val folder = chat ?: "new"
        val dir = File(hostDir(app), folder).apply { mkdirs() }
        return uris.mapNotNull { uri ->
            runCatching {
                val name = displayName(app, uri).replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "file" }
                var target = File(dir, name)
                var n = 1
                while (target.exists()) target = File(dir, "${name.substringBeforeLast('.')}-${n++}${name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }}")
                app.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
                "$GUEST_DIR/$folder/${target.name}"
            }.getOrNull()
        }
    }

    /** The message the CLI gets: the user's text, then where the attached files are. */
    fun prompt(text: String, files: List<String>): String {
        if (files.isEmpty()) return text
        val list = files.joinToString("\n") { "- $it" }
        return "$text\n\nAttached files (read them with your tools):\n$list".trim()
    }

    private fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
}
