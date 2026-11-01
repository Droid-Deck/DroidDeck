package com.droiddeck.launcher.stores.gog

import android.content.Context
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal object GogOffline {
    private const val HELPER = "/usr/local/bin/droiddeck-gog-maintenance"

    internal data class Layout(val source: File, val prefix: String)

    internal fun layout(extracted: File, installed: File, id: String, executable: String): Layout {
        val nested = executable.startsWith("app/", true) && !File(installed, "goggame-$id.info").isFile
        return Layout(extracted, if (nested) "app/" else "")
    }

    internal fun validateDepotInstaller(extracted: File, target: GogInstallRecord) {
        if (target.source != "depot") return
        val builds = listOf(extracted, File(extracted, "app")).map { GogInstallRecord.authenticBuild(it, target.id) }.filter { it.isNotBlank() }
        require(builds.isNotEmpty() && builds.all { it == target.build } && target.build.isNotBlank()) {
            "The offline installer does not identify the selected depot build. Use Update to preserve installed DLC."
        }
    }

    private fun download(context: Context, proposal: GogMaintenance.Proposal, handle: DownloadQueue.JobHandle, stop: AtomicBoolean): File {
        val patch = proposal.patch ?: error("GOG download selection is missing")
        val root = File(GogFiles.dir(proposal.folder), "downloads").apply { mkdirs() }
        var first: File? = null; var bytes = 0L
        for ((index, part) in patch.parts.withIndex()) {
            if (stop.get()) return first ?: File(root, "cancelled")
            val resolved = GogDownloadManager.resolveRedirect(part.url, GogContent.token(context)) ?: error("The GOG download link is unavailable")
            val serverName = GogContent.downloadFilename(resolved).takeIf { it.endsWith(".exe", true) || it.endsWith(".bin", true) }
            val name = part.name.ifBlank { serverName ?: error("GOG did not provide the installer part filename") }
            require('/' !in name && '\\' !in name && name !in setOf(".", "..")) { "Invalid GOG installer filename" }
            val file = GogFiles.inside(root, name)
            val receipt = File(root, "part-$index.json")
            val saved = GogFiles.read(receipt)
            val complete = file.isFile && (part.size <= 0 || file.length() == part.size) && when {
                part.md5.isNotBlank() -> GogFiles.hash(file).equals(part.md5, true)
                saved?.optString("id") == part.id && saved.optString("filename") == name -> GogFiles.hash(file, "SHA-256") == saved.optString("sha256")
                else -> false
            }
            if (!complete) {
                val staging = GogFiles.inside(root, "$name.partial")
                GogFallback.download(listOf(resolved), staging, part.size, stop, { n -> bytes += n; handle.progress(bytes, proposal.downloadBytes.coerceAtLeast(bytes)) })
                if (stop.get()) return first ?: file
                if (part.md5.isNotBlank()) check(GogFiles.hash(staging).equals(part.md5, true)) { "GOG installer checksum mismatch" }
                GogFiles.replace(staging, file)
                GogFiles.write(receipt, JSONObject().put("id", part.id).put("filename", name).put("sha256", GogFiles.hash(file, "SHA-256")))
            }
            if (index == 0) { check(file.extension.equals("exe", true)) { "GOG patch has no setup executable" }; first = file }
        }
        return first ?: error("GOG download has no files")
    }

    fun refresh(context: Context, proposal: GogMaintenance.Proposal, handle: DownloadQueue.JobHandle, stop: AtomicBoolean) {
        val installer = download(context, proposal, handle, stop)
        if (stop.get()) return
        val extracted = File(GogFiles.dir(proposal.folder), "extracted").apply { mkdirs() }
        val source = AddedGames.guestPath(context, installer) ?: error("The GOG installer is not visible to Linux")
        val target = AddedGames.guestPath(context, extracted) ?: error("The GOG staging folder is not visible to Linux")
        handle.stage(DownloadStage.INSTALL, "Extracting the full offline installer")
        val rc = GuestCommand.run(context, listOf("/usr/local/bin/droiddeck-innoextract", "--gog", "--silent", "-d", target, source),
            extraBinds = StoreInstallRoot.externalBindSpecs(context), cancelled = stop, logName = "gog-refresh", onLine = handle::log)
        if (stop.get()) return
        check(rc == 0) { "GOG installer extraction failed (exit $rc)" }
        applyRefresh(proposal, extracted, handle, stop)
    }

    internal fun applyRefresh(proposal: GogMaintenance.Proposal, extracted: File, handle: DownloadQueue.JobHandle?, stop: AtomicBoolean) {
        validateDepotInstaller(extracted, proposal.target)
        val sidecar = StoreGameSidecar.read(proposal.folder) ?: error("The original GOG settings are missing")
        val layout = layout(extracted, proposal.folder, proposal.before.id, sidecar.exe)
        val files = layout.source.walkTopDown().onEnter { directory ->
            check(!java.nio.file.Files.isSymbolicLink(directory.toPath())) { "GOG installer contains a symbolic link" }
            directory.parentFile != extracted || directory.name !in setOf("tmp", "commonappdata", "userappdata", "localappdata")
        }.filter { it != layout.source && it != File(extracted, "app") }.map { file ->
            check(!java.nio.file.Files.isSymbolicLink(file.toPath()))
            val relative = file.relativeTo(layout.source).invariantSeparatorsPath.removePrefix("app/")
            JSONObject().put("path", layout.prefix + relative)
                .put("size", if (file.isDirectory) 0 else file.length()).put("directory", file.isDirectory)
                .put("md5", if (file.isDirectory) "" else GogFiles.hash(file)).put("product", proposal.before.id) to file
        }.toList().groupBy { it.first.getString("path") }.map { (_, entries) ->
            check(entries.size == 1 || entries.all { it.first.getBoolean("directory") }) { "GOG installer has conflicting file locations" }
            entries.first()
        }
        check(files.any { !it.first.getBoolean("directory") }) { "GOG installer contained no game files" }
        val inventory = JSONArray(files.map { it.first })
        GogFiles.validate(inventory, proposal.folder)
        val executable = sidecar.exe.replace('\\', '/')
        check(files.any { !it.first.getBoolean("directory") && it.first.getString("path").equals(executable, true) } ||
            proposal.before.verified && proposal.before.files.length() > 0 &&
            proposal.before.files.objects().none { it.getString("path").equals(executable, true) } && GogFiles.inside(proposal.folder, executable).isFile) {
            "The offline installer did not extract the game's executable. No installed files were changed."
        }
        val installed = proposal.target.copy(files = inventory)
        GogFiles.write(File(GogFiles.dir(proposal.folder), "refresh-target.json"), installed.json())
        for ((index, entry) in files.withIndex()) {
            if (stop.get()) return
            val (file, sourceFile) = entry
            val destination = GogFiles.inside(proposal.folder, file.getString("path"))
            if (file.getBoolean("directory")) java.nio.file.Files.createDirectories(destination.toPath()) else {
                destination.parentFile!!.mkdirs()
                GogFiles.replace(sourceFile, destination)
            }
            handle?.stageProgress((index + 1).toLong(), files.size.toLong())
        }
        if (proposal.target.source != "depot") {
            val targetPaths = files.mapTo(HashSet()) { it.first.getString("path").lowercase(java.util.Locale.ROOT) }
            for (old in proposal.before.files.objects()) if (old.getString("path").lowercase(java.util.Locale.ROOT) !in targetPaths && GogFiles.matches(proposal.folder, old))
                check(GogFiles.inside(proposal.folder, old.getString("path")).delete())
            installed.write(proposal.folder)
        }
    }

    fun apply(context: Context, proposal: GogMaintenance.Proposal, handle: DownloadQueue.JobHandle, stop: AtomicBoolean, onApplying: () -> Unit): Boolean {
        check(GogFiles.read(GogFiles.journal(proposal.folder))?.optString("phase") in setOf("download", "patch-ready")) {
            "The patch result needs recovery. Inspect the installer or use a full offline refresh; the patch will not be run again."
        }
        val installer = download(context, proposal, handle, stop)
        if (stop.get()) return false
        val setup = GogContent.setupFiles(proposal.before, GogContent.token(context))
        if (setup != null) {
            GogFallback.transfer(proposal.folder, JSONObject().put("files", setup.first).put("links", setup.second)
                .put("downloadBytes", setup.first.objects().sumOf { it.getJSONArray("chunks").objects().sumOf { chunk -> chunk.getLong("compressedSize") } }), handle, stop)
            if (stop.get()) return false
        }
        val added = AddedGames.single(context, proposal.folder.path) ?: error("The GOG game shortcut is unavailable")
        val id = (added.steamAppId?.toLong() ?: added.appId) and 0xffffffffL
        val folder = AddedGames.guestPath(context, proposal.folder) ?: error("The GOG folder is unavailable to Linux")
        val executable = AddedGames.guestPath(context, installer) ?: error("The GOG patch is unavailable to Linux")
        context.startActivity(android.content.Intent(context, com.droiddeck.launcher.SessionActivity::class.java)
            .putExtra(SessionService.EXTRA_MODE, if (SessionState.running) SessionState.mode else SessionService.MODE_DESKTOP)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        val deadline = System.currentTimeMillis() + 120_000
        while (AgentGuest.hello(context) == null && System.currentTimeMillis() < deadline) { if (stop.get()) return false; Thread.sleep(250) }
        check(AgentGuest.hello(context) != null) { "The Linux session did not become ready; open it and retry" }
        val journal = GogFiles.read(GogFiles.journal(proposal.folder)) ?: error("GOG patch journal is missing")
        journal.put("phase", "patch-ready")
        File(GogFiles.dir(proposal.folder), "patch-status.json").delete()
        GogFiles.write(GogFiles.journal(proposal.folder), journal)
        val request = JSONObject().put("kind", "exec").put("timeout", 15)
            .put("env", JSONObject().put("DROIDDECK_GOG_DESKTOP", if (SessionState.mode == SessionService.MODE_DESKTOP) "1" else "0"))
            .put("argv", JSONArray(listOf(HELPER, "start", folder, id.toString(), executable, added.guestExe)))
        if (stop.get()) return false
        onApplying()
        val response = AgentGuest.call(context, request, 20_000, trusted = true)
        check(response.optBoolean("ok") && response.optInt("exitCode", -1) == 0) { "The GOG patch installer could not start; inspect the GOG log" }
        handle.stage(DownloadStage.INSTALL, "Use the Linux session to finish or cancel the patch installer")
        var nextStatus = System.currentTimeMillis() + 5_000
        while (true) {
            val status = GogFiles.read(File(GogFiles.dir(proposal.folder), "patch-status.json"))
            if (status?.optString("state") in setOf("done", "failed", "uncertain")) {
                check(status?.optString("state") == "done") { status?.optString("reason") ?: "GOG patch failed" }
                break
            }
            check(SessionState.running) { "The patch session ended. Its result needs recovery; the patch will not be rerun automatically." }
            check(!SessionState.suspended) { "The patch session was suspended. Resume it and verify recovery before retrying." }
            if (System.currentTimeMillis() >= nextStatus) {
                val query = JSONObject().put("kind", "exec").put("timeout", 5).put("argv", JSONArray(listOf(HELPER, "status", folder)))
                val answer = AgentGuest.call(context, query, 10_000, trusted = true)
                check(answer.optBoolean("ok") && answer.optInt("exitCode", -1) == 0) { "The patch helper could not be reached. Resume the Linux session and verify recovery." }
                nextStatus = System.currentTimeMillis() + 5_000
            }
            Thread.sleep(500)
        }
        val info = GogFiles.read(File(proposal.folder, "goggame-${proposal.before.id}.info"))
        val version = info?.optString("versionName").orEmpty()
        val build = GogInstallRecord.authenticBuild(proposal.folder, proposal.before.id)
        var record = proposal.target.copy(build = build, version = version, files = JSONArray(), manifest = JSONObject(), verified = false)
        record.write(proposal.folder)
        if (build.isNotBlank()) {
            record = runCatching {
                val token = GogContent.token(context)
                val target = GogBuild.select(GogContent.builds(record.id, token), pinned = build)
                val snapshot = target?.let { GogContent.snapshot(record, it, token) }
                snapshot?.takeIf { it.files.objects().all { f -> f.optBoolean("directory") || GogFiles.matches(proposal.folder, f) } } ?: record
            }.getOrDefault(record)
        }
        record.write(proposal.folder)
        if (!record.verified) {
            val before = StoreGameSidecar.read(proposal.folder) ?: error("The GOG installation record is missing")
            before.copy(installVersion = "", state = StoreGameSidecar.STATE_UPDATING).write(proposal.folder)
            val operation = GogFiles.read(GogFiles.journal(proposal.folder))!!
            GogFiles.write(GogFiles.journal(proposal.folder), operation.put("phase", "patch-uncertain"))
        }
        return true
    }
}
