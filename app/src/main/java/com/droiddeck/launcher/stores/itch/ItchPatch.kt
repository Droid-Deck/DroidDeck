package com.droiddeck.launcher.stores.itch

import android.content.Context
import com.droiddeck.launcher.core.AddonPack
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal object ItchPatch {
    private const val DIR = "/usr/local/lib/droiddeck-butler"

    sealed interface Outcome {
        data object Applied : Outcome
        data object Cancelled : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun ready(context: Context): Boolean {
        if (!AddonPack.BUTLER.available || !LinuxRuntime.isInstalled(context)) return false
        if (!AddonPack.BUTLER.installed(context)) {
            AddonPack.BUTLER.ensure(context)
            return false
        }
        return stage(context)
    }

    private val stageLock = Any()

    private fun stage(context: Context): Boolean = synchronized(stageLock) { stageLocked(context) }

    private fun stageLocked(context: Context): Boolean {
        val pack = AddonPack.BUTLER.dir(context)
        val root = File(LinuxRuntime.rootDir(context), DIR.removePrefix("/"))
        val packaged = File(pack, "version").readText()
        if (runCatching { File(root, "version").readText() }.getOrNull() == packaged && File(root, "butler").isFile) return true
        root.mkdirs()
        pack.listFiles().orEmpty().filter { it.isFile && it.name != "version" && it.name != AddonPack.COMPLETE }.forEach { source ->
            val staged = File(root, source.name + ".staged")
            source.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
            staged.setExecutable(true, false)
            check(staged.renameTo(File(root, source.name)))
        }
        File(root, "version").writeText(packaged)
        return true
    }

    fun run(
        app: Context, key: String, downloadKeyId: String, from: String, to: String, fullSize: Long, installed: File, out: File, scratch: File,
        handle: DownloadQueue.JobHandle, cancelled: AtomicBoolean,
    ): Outcome {
        if (!ready(app)) return Outcome.Failed("the patch tool is not ready")
        val steps = ItchBuilds.path(key, from, to, downloadKeyId) ?: return Outcome.Failed("no patch path from build $from to $to")
        if (fullSize > 0 && steps.sumOf { it.patchSize } > fullSize) return Outcome.Failed("the patches are larger than a full download")
        val dir = File(scratch, "patches").apply { deleteRecursively(); mkdirs() }
        try {
            handle.stage(DownloadStage.DOWNLOAD)
            val total = steps.sumOf { it.patchSize }
            var finished = 0L
            val patches = steps.mapIndexed { index, step ->
                val file = File(dir, "$index.pwr")
                val base = finished
                val ok = ItchBuilds.download(ItchBuilds.fileUrl(step.buildId, step.patchFile, downloadKeyId), key, file, cancelled) {
                    handle.progress(base + it, total)
                }
                if (cancelled.get()) return Outcome.Cancelled
                if (!ok) return Outcome.Failed("build ${step.buildId}: the patch did not download")
                finished += step.patchSize
                file
            }
            val last = steps.last()
            val signature = File(dir, "final.sig")
            val got = ItchBuilds.download(ItchBuilds.fileUrl(last.buildId, last.signatureFile, downloadKeyId), key, signature, cancelled) {}
            if (cancelled.get()) return Outcome.Cancelled
            if (!got) return Outcome.Failed("build ${last.buildId}: the signature did not download")
            handle.stage(DownloadStage.INSTALL)
            return apply(app, patches, signature, installed, out, scratch, cancelled)
        } finally {
            StoreInstalls.deleteTree(dir)
        }
    }

    private fun apply(app: Context, patches: List<File>, signature: File, installed: File, out: File, scratch: File, cancelled: AtomicBoolean): Outcome {
        var source = installed
        var intermediate: File? = null
        try {
            patches.forEachIndexed { index, patch ->
                val finalStep = index == patches.lastIndex
                val target = if (finalStep) out else File(scratch, "patched-$index")
                ItchStaging.delete(target)
                target.mkdirs()
                val staging = File(scratch, "butler-staging-$index").apply { deleteRecursively(); mkdirs() }
                val args = mutableListOf("$DIR/butler", "apply", "--assume-yes", "--staging-dir", staging.absolutePath, "-d", target.absolutePath)
                if (finalStep) args += listOf("--signature", signature.absolutePath)
                args += listOf(patch.absolutePath, source.absolutePath)
                val binds = listOf(installed, out, scratch).map { it.absolutePath }
                    .filter { path -> listOf(app.filesDir, app.cacheDir).none { path.startsWith(it.absolutePath + "/") } }
                    .distinct().takeIf { it.isNotEmpty() }
                val tail = ArrayDeque<String>()
                val code = try {
                    GuestCommand.run(app, args, logName = "itch-butler", extraBinds = binds, fastPath = true, cancelled = cancelled) { line ->
                        if (!line.startsWith("proot ")) {
                            if (tail.size == 4) tail.removeFirst()
                            tail.addLast(line)
                        }
                    }
                } finally {
                    StoreInstalls.deleteTree(staging)
                }
                if (cancelled.get()) return Outcome.Cancelled
                if (code != 0) return Outcome.Failed("butler exited $code: ${tail.joinToString(" | ")}")
                intermediate?.let { StoreInstalls.deleteTree(it) }
                intermediate = if (finalStep) null else target
                source = target
            }
            return Outcome.Applied
        } finally {
            intermediate?.let { StoreInstalls.deleteTree(it) }
        }
    }
}
