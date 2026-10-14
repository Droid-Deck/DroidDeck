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

    fun fetch(url: String = CATALOG_URL): List<Entry>? {
        val body = Downloader.downloadString(url) ?: return null
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
        return File(root, DesktopRemoval.PENDING).exists() || marker(context, DESKTOP_ID).exists()
    }

    fun desktopSize(context: Context): Long = DesktopRemoval.installedSize(context)

    fun cleanLegacy(context: Context, listener: LinuxRuntimeInstaller.ProgressListener? = null) =
        DesktopRemoval.cleanLegacy(context, listener)

    fun removeDesktop(context: Context, listener: LinuxRuntimeInstaller.ProgressListener): String? =
        DesktopRemoval.remove(context, listener)

    fun install(context: Context, entry: Entry, listener: LinuxRuntimeInstaller.ProgressListener?): String? {
        val root = LinuxRuntime.rootDir(context)
        if (!root.isDirectory) return context.getString(R.string.deskpkg_runtime_missing)
        if (entry.sha256.isEmpty()) return context.getString(R.string.deskpkg_no_checksum, entry.name)
        val download = File(context.cacheDir, "pkg-${entry.id}.download")
        try {
            val downloading = context.getString(R.string.user_apps_downloading, entry.name)
            listener?.onProgress(LinuxRuntimeInstaller.Step.DOWNLOADING, downloading, 0)
            val ok = Downloader.downloadFile(entry.url, download, true) { f ->
                listener?.onProgress(LinuxRuntimeInstaller.Step.DOWNLOADING, downloading, if (f < 0) -1 else Math.round(f * 100f))
            }
            if (!ok) return context.getString(R.string.user_apps_download_failed)
            listener?.onProgress(LinuxRuntimeInstaller.Step.VERIFYING, context.getString(R.string.rtinst_verifying), -1)
            val actual = Hashes.sha256(download)
            if (!entry.sha256.equals(actual, ignoreCase = true)) return context.getString(R.string.deskpkg_checksum_mismatch)
            listener?.onProgress(context.getString(R.string.deskpkg_installing, entry.name), -1)
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
                    marker(context, entry.id).delete()
                    if (!LinuxRuntimeInstaller.extract(context, download, root, listener)) return context.getString(R.string.deskpkg_extract_failed)
                }
            }
            if (entry.id == DESKTOP_ID) {
                cleanLegacy(context, listener)
                val pending = File(root, DesktopRemoval.PENDING)
                check(!pending.exists() || pending.delete()) { "Could not finish desktop installation" }
            }
            FileUtils.writeString(marker(context, entry.id), entry.version)
            check(installed(context, entry.id) == entry.version) { "Could not finish package installation" }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "install ${entry.id}", e)
            return e.message ?: context.getString(R.string.deskpkg_install_failed)
        } finally {
            download.delete()
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
