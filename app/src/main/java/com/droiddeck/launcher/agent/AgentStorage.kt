package com.droiddeck.launcher.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.mutableStateMapOf
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * What the agent adds to the Linux userland, measured and removable piece by piece. Sign-ins
 * (~/.claude, ~/.codex, gh's config) are left alone, so removing a CLI and installing it again
 * does not sign out.
 */
object AgentStorage {
    enum class Item(val paths: List<String>) {
        CLAUDE_CODE(listOf("/root/.local/share/claude", "/root/.local/bin/claude", "/root/cc")),
        CODEX(listOf("/root/.local/bin/codex", "/root/.local/bin/codex-code-mode-host")),
        BUILD_TOOLS(listOf("/root/android-sdk", "/root/aapt2", "/root/x86root", "/root/.gradle")),
        SOURCE(listOf("/root/DroidDeck")),
        CACHES(listOf("/root/.cache", "/root/.npm")),
    }

    /** Bytes per item; missing while it is being measured. */
    val sizes = mutableStateMapOf<Item, Long>()
    val removing = mutableStateMapOf<Item, Boolean>()

    private val main = Handler(Looper.getMainLooper())

    fun measure(context: Context) {
        val root = LinuxRuntime.rootDir(context.applicationContext)
        Thread({
            for (item in Item.entries) {
                val bytes = item.paths.sumOf { size(File(root, it.removePrefix("/")).toPath()) }
                main.post { sizes[item] = bytes }
            }
        }, "agent-storage").start()
    }

    fun remove(context: Context, item: Item) {
        val app = context.applicationContext
        val root = LinuxRuntime.rootDir(app)
        removing[item] = true
        Thread({
            for (path in item.paths) runCatching { delete(File(root, path.removePrefix("/")).toPath()) }
            main.post {
                removing.remove(item)
                AgentHost.refresh(app)
                AgentSetup.check(app)
                measure(app)
            }
        }, "agent-storage-remove").start()
    }

    /** Walks without following links, so a link out of the rootfs (into /sdcard, say) counts and removes only itself. */
    @VisibleForTesting
    internal fun size(path: Path): Long {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0
        var total = 0L
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                total += attrs.size()
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
        return total
    }

    @VisibleForTesting
    internal fun delete(path: Path) {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                runCatching { Files.deleteIfExists(file) }
                return FileVisitResult.CONTINUE
            }
        })
    }
}
