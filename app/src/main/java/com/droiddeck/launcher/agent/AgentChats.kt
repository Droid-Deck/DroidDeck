package com.droiddeck.launcher.agent

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.droiddeck.launcher.agent.AgentHost.Entry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Conversations kept on the device, one JSON Lines file each in `files/agent/chats`: a header
 * line with the harness, a line with the CLI's conversation id once it has one, then the entries.
 */
object AgentChats {
    data class Chat(val id: String, val harness: String, val title: String, val updated: Long)

    class Loaded(val harness: String?, val conversation: String?, val entries: List<Entry>)

    private const val MAX_OUTPUT = 24_000

    fun dir(context: Context) = File(context.filesDir, "agent/chats")

    fun file(context: Context, id: String) = File(dir(context), "$id.jsonl")

    fun newId(context: Context): String {
        var stamp = System.currentTimeMillis()
        while (file(context, stamp.toString()).exists()) stamp++
        return stamp.toString()
    }

    /** Newest first; chats with nothing asked yet are left out. */
    fun list(context: Context): List<Chat> = dir(context).listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty()
        .mapNotNull { f ->
            val loaded = read(f)
            val title = loaded.entries.firstNotNullOfOrNull { (it as? Entry.Asked)?.text } ?: return@mapNotNull null
            Chat(f.name.removeSuffix(".jsonl"), loaded.harness ?: ClaudeCode.id, title.lineSequence().first().take(120), f.lastModified())
        }
        .sortedByDescending { it.updated }

    fun delete(context: Context, id: String) {
        file(context, id).delete()
        File(AgentAttachments.hostDir(context), id).deleteRecursively()
    }

    fun start(file: File, harness: String) {
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("harness", harness).toString() + "\n")
    }

    fun appendConversation(file: File, conversation: String) =
        runCatching { file.appendText(JSONObject().put("conversation", conversation).toString() + "\n") }

    fun append(file: File, entry: Entry) = runCatching { file.appendText(encode(entry) + "\n") }

    /** The transcript before chats were kept, moved into the first chat. */
    fun migrate(context: Context, harness: String, conversation: String?): String? {
        val old = File(context.filesDir, "agent/transcript.jsonl")
        if (!old.isFile) return null
        val id = newId(context)
        val target = file(context, id)
        start(target, harness)
        conversation?.let { appendConversation(target, it) }
        target.appendText(old.readText())
        old.delete()
        return id
    }

    @VisibleForTesting
    internal fun encode(entry: Entry): String = when (entry) {
        is Entry.Asked -> JSONObject().put("asked", entry.text).apply { if (entry.files.isNotEmpty()) put("files", JSONArray(entry.files)) }
        is Entry.Said -> JSONObject().put("said", entry.text)
        is Entry.Thought -> JSONObject().put("thought", entry.text)
        is Entry.Result -> JSONObject().put("result", entry.id).put("output", entry.output).put("failed", entry.failed)
        is Entry.Used -> JSONObject().put("used", entry.tool).put("detail", entry.detail).put("id", entry.id).put("input", entry.input)
            .apply { entry.output?.let { put("output", it).put("failed", entry.failed) } }
        is Entry.Finished -> JSONObject().put("finished", true).put("error", entry.error ?: JSONObject.NULL).put("stopped", entry.stopped).apply {
            entry.usage?.let { put("ms", it.durationMs).put("cost", it.costUsd).put("in", it.inputTokens).put("out", it.outputTokens) }
        }
    }.toString()

    @VisibleForTesting
    internal fun read(file: File): Loaded {
        var harness: String? = null
        var conversation: String? = null
        val entries = mutableListOf<Entry>()
        runCatching {
            file.forEachLine { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                when {
                    o.has("harness") -> harness = o.getString("harness")
                    o.has("conversation") -> conversation = o.getString("conversation")
                    else -> decode(o)?.let { merge(entries, it) }
                }
            }
        }
        return Loaded(harness, conversation, entries)
    }

    private fun decode(o: JSONObject): Entry? = when {
        o.has("asked") -> Entry.Asked(o.getString("asked"), o.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
        o.has("said") -> Entry.Said(o.getString("said"))
        o.has("thought") -> Entry.Thought(o.getString("thought"))
        o.has("used") -> Entry.Used(o.optString("id"), o.getString("used"), o.optString("detail"), o.optString("input"),
            if (o.has("output")) o.optString("output") else null, o.optBoolean("failed"))
        o.has("result") -> Entry.Result(o.getString("result"), o.optString("output"), o.optBoolean("failed"))
        o.has("finished") -> Entry.Finished(
            if (o.isNull("error")) null else o.optString("error"), o.optBoolean("stopped"),
            if (o.has("ms")) AgentHost.Usage(o.optLong("ms"), o.optDouble("cost", 0.0), o.optLong("in"), o.optLong("out")) else null,
        )
        else -> null
    }

    /**
     * A tool's result is shown on the call it answers, and a call seen again (an updated todo list)
     * replaces it. Only the current turn is searched: Codex numbers its items again in each process.
     */
    fun merge(entries: MutableList<Entry>, entry: Entry): Boolean {
        val turn = entries.indexOfLast { it is Entry.Asked }
        if (entry is Entry.Used && entry.id.isNotEmpty()) {
            val same = entries.indexOfLast { it is Entry.Used && it.id == entry.id }
            if (same > turn) {
                entries[same] = entry
                return true
            }
        }
        if (entry !is Entry.Result) {
            entries += entry
            return true
        }
        val index = entries.indexOfLast { it is Entry.Used && it.id == entry.id && entry.id.isNotEmpty() }
        if (index <= turn) return false
        entries[index] = (entries[index] as Entry.Used).copy(output = clip(entry.output), failed = entry.failed)
        return true
    }

    /** Long outputs keep their end, where errors and summaries usually are. */
    fun clip(output: String) = if (output.length <= MAX_OUTPUT) output else "…\n" + output.takeLast(MAX_OUTPUT)
}
