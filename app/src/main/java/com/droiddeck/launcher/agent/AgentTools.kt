package com.droiddeck.launcher.agent

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
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
        else base + listOf(name.trim(), "--") + t.split(Regex("\\s+")).filter { it.isNotEmpty() }
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
        return File(LinuxRuntime.rootDir(context), "$SKILLS/$name").deleteRecursively()
    }

    private fun run(context: Context, command: List<String>): Result = runCatching {
        val child = AgentHost.guest(context.applicationContext, command, connectAdb = false).start()
        val output = child.inputStream.bufferedReader().readText()
        if (!child.waitFor(90, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            return@runCatching Result(false, output)
        }
        Result(child.exitValue() == 0, output.trim())
    }.getOrElse { Result(false, it.message.orEmpty()) }
}
