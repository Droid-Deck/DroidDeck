package com.droiddeck.launcher.agent

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** Claude Code's MCP servers and skills in the userland, through its own CLI and skills folder. */
internal object AgentTools {
    data class Server(val name: String, val target: String, val status: String) {
        /** Connectors come from the claude.ai account and are managed there, not by the CLI. */
        val account get() = name.startsWith("claude.ai ")
    }

    data class Skill(val name: String, val description: String)

    class Result(val ok: Boolean, val output: String)

    private const val SKILLS = "root/.claude/skills"
    private val SERVER = Regex("""^(.+?): (.+) - (.+)$""")

    fun servers(context: Context): List<Server>? {
        val result = run(context, listOf(ClaudeCode.binary, "mcp", "list"))
        if (!result.ok) return null
        return parseServers(result.output)
    }

    internal fun parseServers(output: String): List<Server> = output.lines().mapNotNull { line ->
        SERVER.find(line.trim())?.destructured?.let { (name, target, status) -> Server(name, target, status) }
    }

    /** A URL is added as an HTTP server; anything else is the command that starts a stdio server. */
    internal fun addCommand(name: String, target: String): List<String> {
        val t = target.trim()
        val base = listOf(ClaudeCode.binary, "mcp", "add", "--scope", "user")
        return if (t.startsWith("http://") || t.startsWith("https://")) base + listOf("--transport", "http", name.trim(), t)
        else base + listOf(name.trim(), "--") + words(t)
    }

    /** Splits a command line the way a shell would for quotes and backslashes, without expanding anything. */
    internal fun words(line: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote == '\'' -> if (c == '\'') quote = null else word.append(c)
                c == '\\' && i + 1 < line.length && (quote == null || line[i + 1] == '"' || line[i + 1] == '\\') -> {
                    word.append(line[++i])
                    inWord = true
                }
                quote == '"' -> if (c == '"') quote = null else word.append(c)
                c == '\'' || c == '"' -> {
                    quote = c
                    inWord = true
                }
                c.isWhitespace() -> if (inWord) {
                    words += word.toString()
                    word.clear()
                    inWord = false
                }
                else -> {
                    word.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (inWord) words += word.toString()
        return words
    }

    fun add(context: Context, name: String, target: String) = run(context, addCommand(name, target))

    fun remove(context: Context, name: String) = run(context, listOf(ClaudeCode.binary, "mcp", "remove", name))

    fun skills(context: Context): List<Skill> =
        File(LinuxRuntime.rootDir(context), SKILLS).listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name.lowercase() }.map { dir ->
            Skill(dir.name, description(File(dir, "SKILL.md")))
        }

    internal fun description(file: File): String = runCatching {
        val lines = file.readLines()
        if (lines.firstOrNull()?.trim() != "---") return@runCatching ""
        lines.drop(1).takeWhile { it.trim() != "---" }.firstOrNull { it.startsWith("description:") }
            ?.removePrefix("description:")?.trim()?.trim('"', '\'').orEmpty()
    }.getOrDefault("")

    fun deleteSkill(context: Context, name: String): Boolean {
        if (name.isEmpty() || name.contains('/') || name.startsWith(".")) return false
        val root = File(LinuxRuntime.rootDir(context), SKILLS)
        val dir = File(root, name)
        if (dir.parentFile?.path != root.path) return false
        return deleteTree(dir.toPath())
    }

    /** Deletes [path] and what is under it; a symbolic link is removed itself, never what it points to. */
    internal fun deleteTree(path: Path): Boolean = runCatching {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@runCatching true
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        true
    }.getOrDefault(false)

    private fun run(context: Context, command: List<String>): Result {
        val child = runCatching { AgentHost.guest(context.applicationContext, command, connectAdb = false).start() }
            .getOrElse { return Result(false, it.message.orEmpty()) }
        val output = StringBuffer()
        // The CLI can hold its output open past the deadline, so it is read beside the wait rather than before it.
        val reader = Thread({ runCatching { child.inputStream.bufferedReader().forEachLine { output.append(it).append('\n') } } }, "agent-tools").apply { start() }
        return try {
            val ended = child.waitFor(90, TimeUnit.SECONDS)
            if (!ended) child.destroyForcibly()
            reader.join(2_000)
            Result(ended && child.exitValue() == 0, output.toString().trim())
        } catch (e: InterruptedException) {
            child.destroyForcibly()
            Thread.currentThread().interrupt()
            Result(false, output.toString().trim())
        } finally {
            runCatching { child.inputStream.close() }
        }
    }
}
