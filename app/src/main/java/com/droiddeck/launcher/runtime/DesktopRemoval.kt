package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import java.io.File
import java.util.zip.GZIPInputStream

/** Inventories are generated from pinned archives by tools/desktop-kde/removal-manifests.py. */
internal object DesktopRemoval {
    const val PENDING = ".droiddeck-desktop-removing"
    private const val CLEANING = ".droiddeck-lxqt-cleaning-v1"
    private const val CLEANED = ".droiddeck-lxqt-cleaned-v1"

    private fun lines(context: Context, name: String) =
        GZIPInputStream(context.assets.open("desktop-removal/$name")).bufferedReader().use { it.readLines() }

    private fun protected(context: Context, root: File) =
        DesktopFileRemoval.runtimeFiles(root) + lines(context, "emulators-keep.txt.gzip")

    /** Called before a guest starts or just after KDE extraction; retries after a process death. */
    fun cleanLegacy(context: Context, listener: LinuxRuntimeInstaller.ProgressListener? = null) {
        val root = LinuxRuntime.rootDir(context)
        if (FileUtils.readString(File(root, CLEANED))?.trim() == "1") return
        if (!File(root, CLEANING).exists() && !File(root, ".droiddeck-pkg-desktop").exists() && !File(root, "usr/bin/lxqt-session").exists()) return
        listener?.onProgress(context.getString(R.string.desktop_cleaning_legacy), -1)
        val keep = protected(context, root) + lines(context, "kde-keep.txt.gzip")
        val pending = File(root, CLEANING)
        FileUtils.writeString(pending, "1")
        check(FileUtils.readString(pending)?.trim() == "1") { "Could not reserve LXQt cleanup" }
        val result = GZIPInputStream(context.assets.open("desktop-removal/lxqt-r1.tsv.gzip")).bufferedReader().use { reader ->
            DesktopFileRemoval.remove(root, reader.lineSequence().map(DesktopFileRemoval.Entry::parse).asIterable(), keep)
        }
        FileUtils.writeString(File(root, CLEANED), "1")
        check(FileUtils.readString(File(root, CLEANED))?.trim() == "1") { "Could not finish LXQt cleanup" }
        File(root, ".droiddeck-pkg-desktop").delete()
        pending.delete()
        Log.i("DesktopRemoval", "LXQt: $result")
    }

    fun remove(context: Context, listener: LinuxRuntimeInstaller.ProgressListener): String? = try {
        val root = LinuxRuntime.rootDir(context)
        val pending = File(root, PENDING)
        val marker = File(root, ".droiddeck-pkg-${DesktopCatalog.DESKTOP_ID}")
        val version = FileUtils.readString(pending)?.trim() ?: FileUtils.readString(marker)?.trim()
        val versions = context.assets.open("desktop-removal/versions.txt").bufferedReader().use { it.readLines() }
        check(version in versions) { context.getString(R.string.desktop_removal_unknown) }
        // Validate all inventories and the runtime database before dropping the installed marker.
        val entries = lines(context, "kde-remove.tsv.gzip").map(DesktopFileRemoval.Entry::parse)
        val keep = protected(context, root)
        cleanLegacy(context, listener)
        FileUtils.writeString(pending, version!!)
        check(FileUtils.readString(pending)?.trim() == version) { "Could not reserve desktop removal" }
        if (marker.exists() && !marker.delete()) error("Could not clear desktop marker")
        val removing = context.getString(R.string.desktop_removing)
        val result = DesktopFileRemoval.remove(root, entries, keep) { listener.onProgress(removing, it) }
        // APK-staged desktop files are matched against the APK too; keep user-modified copies.
        for ((asset, path) in com.droiddeck.launcher.session.SessionFiles.DESKTOP_FILES) {
            val target = DesktopFileRemoval.safeFile(root, path) ?: continue
            if (!isRegularFile(target)) continue
            val content = context.assets.open("linuxfs/$asset").use { it.readBytes() }
            if (target.length() == content.size.toLong() && target.inputStream().use { it.readBytes() }.contentEquals(content)) {
                if (!target.delete()) error("Could not remove $path")
            }
        }
        if (!pending.delete()) error("Could not finish desktop removal")
        Log.i("DesktopRemoval", "KDE: $result")
        listener.onProgress(context.getString(R.string.desktop_removed), 100)
        null
    } catch (e: Exception) {
        Log.e("DesktopRemoval", "remove", e)
        e.message ?: context.getString(R.string.deskpkg_install_failed)
    }

    private fun isRegularFile(file: File) = java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
}
