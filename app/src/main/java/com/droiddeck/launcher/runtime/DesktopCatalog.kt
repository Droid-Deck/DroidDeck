package com.droiddeck.launcher.runtime

import com.droiddeck.launcher.core.Hashes
import android.content.Context
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import org.json.JSONObject
import java.io.File

object DesktopCatalog {
    private const val TAG = "DesktopCatalog"
    const val CATALOG_URL = "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/desktop.json"
    const val STEAM_SEED_URL = "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/steam-seed.json"
    const val PROTON_SEED_ID = "proton-arm64"

    class Entry(
        val id: String, val name: String, val tier: Int, val version: String, val kind: String,
        val url: String, val sha256: String, val size: Long, val notes: String,
        val icon: String, val category: String,
    )

    fun fetch(url: String = CATALOG_URL, cancelled: java.util.function.BooleanSupplier? = null): List<Entry>? {
        val body = Downloader.downloadString(url, cancelled) ?: return null
        return try {
            val json = JSONObject(body)
            val arr = json.getJSONArray("packages")
            val pageSize = try {
                android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE).toString()
            } catch (e: Exception) { "4096" }
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val file = o.optJSONObject("pages")?.optJSONObject(pageSize) ?: o
                Entry(
                    o.getString("id"), o.getString("name"), o.optInt("tier", 1), o.optString("version", ""),
                    o.optString("kind", "tar"), file.getString("url"), file.optString("sha256", ""),
                    file.optLong("size", 0L), o.optString("notes", ""),
                    o.optString("icon", "applications-games"), o.optString("category", "Game"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "catalog: $e"); null
        }
    }

    private fun marker(context: Context, id: String) = File(LinuxRuntime.rootDir(context), ".droiddeck-pkg-$id")

    private const val JOURNAL_SUFFIX = ".installing"

    private fun journal(root: File, id: String) = File(root, ".droiddeck-pkg-$id$JOURNAL_SUFFIX")

    /** Packages whose extraction over the runtime started and never finished. */
    fun interrupted(context: Context): List<Entry> = interrupted(LinuxRuntime.rootDir(context))

    fun interrupted(root: File): List<Entry> =
        root.listFiles { f -> f.name.startsWith(".droiddeck-pkg-") && f.name.endsWith(JOURNAL_SUFFIX) }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull { f -> FileUtils.readString(f)?.let { runCatching { entryFrom(JSONObject(it)) }.getOrNull() } }

    private val installLock = Any()

    private fun downloadFile(context: Context, entry: Entry) = File(context.cacheDir, "pkg-${entry.id}-${entry.sha256.take(12)}.download")

    /** Drops downloads kept for installs that no longer need finishing, such as after the runtime was reinstalled. */
    fun pruneDownloads(context: Context) = synchronized(installLock) {
        val keep = interrupted(context).map { downloadFile(context, it).name }.toSet()
        context.cacheDir.listFiles { f -> f.name.startsWith("pkg-") && f.name.removeSuffix(".resume.properties").endsWith(".download") }
            ?.filter { it.name.removeSuffix(".resume.properties") !in keep }?.forEach { it.delete() }
    }

    fun resumeInterrupted(context: Context, listener: LinuxRuntimeInstaller.ProgressListener?): String? = synchronized(installLock) {
        resumeInterrupted(context, null, listener)
    }

    fun resumeInterrupted(
        context: Context,
        cancelled: Downloader.Cancellation?,
        listener: LinuxRuntimeInstaller.ProgressListener?,
    ): String? = synchronized(installLock) {
        for (entry in interrupted(context)) {
            Log.w(TAG, "finishing the interrupted install of ${entry.id} ${entry.version}")
            val problem = if (cancelled == null) {
                install(context, entry, listener)
            } else {
                install(context, entry, cancelled, listener)
            }
            if (problem != null) return problem
        }
        null
    }

    internal fun entryJson(e: Entry): JSONObject = JSONObject()
        .put("id", e.id).put("name", e.name).put("tier", e.tier).put("version", e.version).put("kind", e.kind)
        .put("url", e.url).put("sha256", e.sha256).put("size", e.size).put("notes", e.notes)
        .put("icon", e.icon).put("category", e.category)

    internal fun entryFrom(o: JSONObject) = Entry(
        o.getString("id"), o.getString("name"), o.optInt("tier", 1), o.getString("version"), o.getString("kind"),
        o.getString("url"), o.getString("sha256"), o.optLong("size", 0L), o.optString("notes", ""),
        o.optString("icon", "applications-games"), o.optString("category", "Game"),
    )

    /** A finished download is kept until its extraction completes, so resuming does not fetch it again. */
    private fun downloaded(file: File, entry: Entry): Boolean =
        file.isFile && file.length() > 0 && !File(file.path + ".resume.properties").exists() &&
            (entry.size <= 0 || file.length() == entry.size) && entry.sha256.equals(Hashes.sha256(file), ignoreCase = true)

    fun installed(context: Context, id: String): String? =
        FileUtils.readString(marker(context, id))?.trim()?.takeIf { it.isNotEmpty() }

    fun protonSeedNeeded(context: Context): Boolean =
        installed(context, PROTON_SEED_ID) == null &&
            !File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps/appmanifest_4427310.acf").isFile

    const val DESKTOP_ID = "desktop-kde"

    fun desktopEntry(context: Context) = Entry(
        DESKTOP_ID, context.getString(R.string.content_linux_desktop), 1, BuildConfig.DESKTOP_KDE_TAG, "tar",
        BuildConfig.DESKTOP_KDE_URL, BuildConfig.DESKTOP_KDE_SHA256, BuildConfig.DESKTOP_KDE_SIZE, "",
        "", "",
    )

    fun desktopInstalled(context: Context): Boolean = desktopInstalled(LinuxRuntime.rootDir(context))

    fun desktopInstalled(root: File): Boolean =
        !File(root, DesktopRemoval.PENDING).exists() &&
        File(root, "usr/bin/kwin_wayland").isFile &&
            FileUtils.readString(File(root, ".droiddeck-pkg-$DESKTOP_ID"))?.trim() == BuildConfig.DESKTOP_KDE_TAG

    fun desktopPresent(context: Context): Boolean {
        val root = LinuxRuntime.rootDir(context)
        return File(root, DesktopRemoval.PENDING).exists() || marker(context, DESKTOP_ID).exists() ||
            journal(root, DESKTOP_ID).exists()
    }

    fun desktopSize(context: Context): Long = DesktopRemoval.installedSize(context)

    fun cleanLegacy(context: Context, listener: LinuxRuntimeInstaller.ProgressListener? = null) =
        DesktopRemoval.cleanLegacy(context, listener)

    fun removeDesktop(context: Context, listener: LinuxRuntimeInstaller.ProgressListener): String? = synchronized(installLock) {
        DesktopRemoval.remove(context, listener)
    }

    internal fun discardDesktopInstall(context: Context) {
        val entry = interrupted(context).firstOrNull { it.id == DESKTOP_ID }
        val journal = journal(LinuxRuntime.rootDir(context), DESKTOP_ID)
        entry?.let {
            val download = downloadFile(context, it)
            for (file in listOf(download, File(download.path + ".resume.properties"))) {
                check(!file.exists() || file.delete()) { "Could not remove desktop download ${file.name}" }
            }
        }
        check(!journal.exists() || journal.delete()) { "Could not clear desktop installation" }
    }

    fun install(context: Context, entry: Entry, listener: LinuxRuntimeInstaller.ProgressListener?): String? = synchronized(installLock) {
        installLocked(context, entry, null, listener)
    }

    fun install(context: Context, entry: Entry, cancelled: Downloader.Cancellation,
                listener: LinuxRuntimeInstaller.ProgressListener?): String? = synchronized(installLock) {
        installLocked(context, entry, cancelled, listener)
    }

    private fun installLocked(context: Context, entry: Entry, cancelled: Downloader.Cancellation?,
                              listener: LinuxRuntimeInstaller.ProgressListener?): String? {
        val root = LinuxRuntime.rootDir(context)
        if (!root.isDirectory) return context.getString(R.string.deskpkg_runtime_missing)
        if (entry.sha256.isEmpty()) return context.getString(R.string.deskpkg_no_checksum, entry.name)
        val download = downloadFile(context, entry)
        val journal = journal(root, entry.id)
        var keepDownload = false
        val release = RuntimeInstallService.holdForPackage(context, context.getString(R.string.deskpkg_installing, entry.name))
        val report = object : LinuxRuntimeInstaller.ProgressListener {
            override fun onProgress(stage: String, percent: Int) = onProgress(LinuxRuntimeInstaller.Step.OTHER, stage, percent)
            override fun onProgress(step: LinuxRuntimeInstaller.Step, stage: String, percent: Int) {
                RuntimeInstallService.packageProgress(stage, percent)
                listener?.onProgress(step, stage, percent)
            }
        }
        try {
            if (!downloaded(download, entry)) {
                val downloading = context.getString(R.string.user_apps_downloading, entry.name)
                report.onProgress(LinuxRuntimeInstaller.Step.DOWNLOADING, downloading, 0)
                val progress = java.util.function.Consumer<Float> { f ->
                    report.onProgress(LinuxRuntimeInstaller.Step.DOWNLOADING, downloading, if (f < 0) -1 else Math.round(f * 100f))
                }
                val ok = if (cancelled == null) {
                    Downloader.downloadFile(entry.url, download, true, entry.sha256, progress)
                } else {
                    Downloader.downloadFile(entry.url, download, true, entry.sha256, cancelled, progress)
                }
                if (!ok || cancelled?.getAsBoolean() == true) return context.getString(R.string.user_apps_download_failed)
                report.onProgress(LinuxRuntimeInstaller.Step.VERIFYING, context.getString(R.string.rtinst_verifying), -1)
                val actual = Hashes.sha256(download)
                if (!entry.sha256.equals(actual, ignoreCase = true)) return context.getString(R.string.deskpkg_checksum_mismatch)
            }
            if (cancelled?.getAsBoolean() == true) return context.getString(R.string.user_apps_download_failed)
            report.onProgress(context.getString(R.string.deskpkg_installing, entry.name), -1)
            when (entry.kind) {
                "appimage" -> {
                    val dir = File(root, "opt/appimages").apply { mkdirs() }
                    val target = File(dir, "${entry.id}.AppImage")
                    val placed = if (entry.url.endsWith(".zip", ignoreCase = true)) unzipAppImage(download, target)
                                 else download.renameTo(target)
                    if (!placed) return context.getString(R.string.deskpkg_place_failed)
                    target.setExecutable(true, false)
                    FileUtils.writeString(File(root, "usr/share/applications/droiddeck-${entry.id}.desktop"),
                        "[Desktop Entry]\nType=Application\nName=${entry.name}\n" +
                        "Exec=env APPIMAGE_EXTRACT_AND_RUN=1 /opt/appimages/${entry.id}.AppImage\n" +
                        "Icon=${entry.icon}\nTerminal=false\nCategories=${entry.category};\n")
                }
                else -> {
                    check(FileUtils.writeString(journal, entryJson(entry).toString())) { "Could not record the package installation" }
                    keepDownload = true
                    marker(context, entry.id).delete()
                    if (!LinuxRuntimeInstaller.extract(context, download, root, report)) return context.getString(R.string.deskpkg_extract_failed)
                }
            }
            if (entry.id == DESKTOP_ID) {
                cleanLegacy(context, report)
                val pending = File(root, DesktopRemoval.PENDING)
                check(!pending.exists() || pending.delete()) { "Could not finish desktop installation" }
            }
            FileUtils.writeString(marker(context, entry.id), entry.version)
            check(installed(context, entry.id) == entry.version) { "Could not finish package installation" }
            check(!journal.exists() || journal.delete()) { "Could not finish package installation" }
            keepDownload = false
            return null
        } catch (e: Exception) {
            Log.e(TAG, "install ${entry.id}", e)
            return e.message ?: context.getString(R.string.deskpkg_install_failed)
        } finally {
            if (!keepDownload) download.delete()
            release()
        }
    }

    private fun unzipAppImage(zip: File, target: File): Boolean {
        try {
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(zip))).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".AppImage", ignoreCase = true)) {
                        java.io.FileOutputStream(target).use { out -> FileUtils.copy(zin, out) }
                        return true
                    }
                    entry = zin.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "unzip ${zip.name}", e)
        }
        return false
    }

    fun remove(context: Context, entry: Entry) {
        val root = LinuxRuntime.rootDir(context)
        if (entry.kind == "appimage") {
            File(root, "opt/appimages/${entry.id}.AppImage").delete()
            File(root, "usr/share/applications/droiddeck-${entry.id}.desktop").delete()
        }
        marker(context, entry.id).delete()
    }
}
