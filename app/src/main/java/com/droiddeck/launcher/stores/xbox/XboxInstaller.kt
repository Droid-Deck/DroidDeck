package com.droiddeck.launcher.stores.xbox

import android.content.Context
import com.droiddeck.launcher.stores.*
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class XboxInstaller(private val app: Context, private val item: CatalogItem, private val folder: File) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)
    private val paused = CountDownLatch(1)
    @Volatile private var child: Xodus.Child? = null
    @Volatile private var delete = false
    /** A legacy container has no pause protocol: the downloader is killed instead. */
    private val legacy = item.extra["format"]?.lowercase() in setOf("eappx", "eappxbundle")
    /** Release builds hide logcat from adb, so the tail is reported in the failure itself. */
    private val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()

    override fun run(handle: DownloadQueue.JobHandle): String? {
        if (cancelled.get()) return null
        check(StoreAccounts.signedInAs(app, Store.XBOX) != null) { "Sign in to Xbox before installing." }
        val market = item.extra["market"]?.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: Xodus.market()
        val selected = item.extra["contentId"].orEmpty()
        require(selected.matches(Regex("[a-fA-F0-9-]{36}"))) { "Select a PC game package before installing." }
        val content = !item.extra["baseId"].isNullOrBlank()
        if (content) {
            check(!XboxRuntime.isActive(item.extra["baseId"].orEmpty())) { "Close the base game before changing Xbox content." }
            check(item.id in XboxManifest.read(XboxContent.base(app, item).folder).related) { "This content is not related to the base game." }
            val base = XboxContent.base(app, item)
            require(folder.canonicalPath == base.folder.canonicalPath + "/.xbox-content/${item.id}")
            File(folder, ".droiddeck-content.json").delete()
            folder.mkdirs()
        } else {
            check(!XboxRuntime.isActive(item.id)) { "Close this Xbox game before updating it." }
            StoreInstalls.markUpdating(folder)
            StoreInstalls.begin(folder, Store.XBOX, item.id, item.title, item.tallImageUrl, item.imageUrl)
        }
        var complete = false
        var sequence = -1L
        try {
            val source = if (content) selected else item.id
            val guestFolder = StoreInstallRoot.guestPathForXodus(app, folder)
            val selection = if (content) emptyList() else listOf("--selected-content-id", selected)
            // Streaming rejects a legacy container outright, so dispatch on the
            // format the catalogue recorded rather than fail after a download.
            val command = if (legacy) "legacy-install" else "streaming"
            Xodus.start(app, if (legacy) {
                listOf(command, source, guestFolder, "--market", market, "--progress-json") + selection
            } else {
                listOf(command, source, guestFolder, "--market", market, "--progress-json", "--control-stdin") + selection
            }).use { process ->
                child = process
                val rejected = AtomicBoolean(false)
                val rejectedLine = Regex(Xodus.SIGN_IN_REJECTED)
                val diagnostics = Xodus.diagnostics(process) { line ->
                    StoresState.logLine("Xbox: $line")
                    errors.add(line)
                    while (errors.size > MAX_ERROR_LINES) errors.poll()
                    if (line.matches(rejectedLine)) rejected.set(true)
                }
                if (cancelled.get()) process.process.destroy()
                process.process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    if (line.startsWith("NEXUS_PROGRESS ")) {
                        val progress = JSONObject(line.removePrefix("NEXUS_PROGRESS "))
                        require(progress.getInt("schema") == 1 && progress.getLong("sequence") > sequence)
                        sequence = progress.getLong("sequence")
                        if (progress.optBoolean("paused")) paused.countDown()
                        when (progress.getString("phase")) {
                            "complete" -> complete = true
                            "downloading", "download", "transfer" -> handle.progress(progress.getLong("completedBytes"), progress.optLong("totalBytes"))
                            // Unpacking reports files, not bytes: the frontend hands
                            // over one container and the backend counts what it has
                            // written, which is the only measure that moves while a
                            // single multi-gigabyte member is decrypted.
                            "installing" -> {
                                handle.stage(DownloadStage.INSTALL)
                                val total = progress.optLong("totalBytes")
                                if (total > 0L) handle.stageProgress(progress.getLong("completedBytes"), total)
                            }
                            "finalizing" -> handle.stage(DownloadStage.INSTALL)
                            else -> handle.stage(DownloadStage.MANIFEST)
                        }
                    }
                } }
                check(process.process.waitFor(15, TimeUnit.SECONDS)) { "Xbox installer did not exit." }
                diagnostics.join(2000)
                if (cancelled.get()) return null
                check(!rejected.get()) { app.getString(com.droiddeck.launcher.R.string.stores_xbox_sign_in_again) }
                val exit = process.process.exitValue()
                if (exit != 0 || !complete) {
                    val tail = errors.toList().takeLast(MAX_ERROR_LINES)
                    report(command, source, market, exit, tail)
                    if (tail.any { it.startsWith(DEVICE_GROUP_FULL) }) error(app.getString(com.droiddeck.launcher.R.string.stores_xbox_device_group_full))
                    if (tail.any { it.startsWith(DEVICE_UNREGISTRABLE) }) error(app.getString(com.droiddeck.launcher.R.string.stores_xbox_license_refused))
                    error("Xbox download did not complete. Resume to retry with fresh licenses." +
                        tail.takeLast(3).joinToString("") { "\n$it" })
                }
            }
            if (content) {
                XboxContent.complete(app, item, folder)
                return folder.path
            }
            val exe = executable(folder)
            StoreInstalls.complete(app, folder, StoreGameSidecar(Store.XBOX, item.id, item.title, exe,
                installVersion = XboxManifest.read(folder).version, installedAt = System.currentTimeMillis(), cover = item.tallImageUrl, hero = item.imageUrl,
                extra = item.extra + mapOf("contentId" to selected, "market" to market)))
            return folder.path
        } finally {
            child = null
            if (delete && !item.extra["baseId"].isNullOrBlank()) StoreInstalls.deleteTree(folder)
            else if (delete) StoreInstalls.discard(app, folder, emptyList())
        }
    }

    /** Writes the backend's words beside the app, where adb can read them. */
    private fun report(command: String, source: String, market: String, exit: Int, tail: List<String>) {
        val text = buildString {
            appendLine("command: $command $source --market $market")
            appendLine("legacy: $legacy")
            appendLine("exit: $exit")
            appendLine("--- stderr (last ${tail.size} lines) ---")
            tail.forEach { appendLine(it) }
        }
        runCatching {
            app.getExternalFilesDir(null)?.let { File(it, "xbox-install.log").writeText(text) }
        }
        StoresState.logLine("Xbox install failed (exit $exit): ${tail.lastOrNull().orEmpty()}")
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        delete = deleteFiles
        val process = child
        if (process == null) {
            if (deleteFiles && !item.extra["baseId"].isNullOrBlank()) StoreInstalls.deleteTree(folder)
            else if (deleteFiles) StoreInstalls.discard(app, folder, emptyList())
            return
        }
        Thread({
            runCatching {
                if (!legacy) {
                    process.send(JSONObject().put("schema", 1).put("command", "pause"))
                    paused.await(3, TimeUnit.SECONDS)
                }
                process.close()
            }
        }, "xbox-download-stop").start()
    }

    companion object {
        private const val MAX_ERROR_LINES = 40
        const val DEVICES_URL = "https://account.microsoft.com/devices/content"
        internal const val DEVICE_GROUP_FULL = "NEXUS_CONTENT_LICENSE_DIAGNOSTIC_V1 content-device-limit "
        internal const val DEVICE_UNREGISTRABLE = "NEXUS_CONTENT_LICENSE_DIAGNOSTIC_V1 content-device-name "
        internal fun executable(folder: File): String = XboxManifest.executable(folder)
    }
}
