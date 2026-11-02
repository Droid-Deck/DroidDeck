package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.session.DesktopLocale
import java.io.File
import java.util.Locale

object DesktopLanguage {
    private const val TAG = "DesktopLanguage"
    private const val INDEX = "desktop-kde.i18n.txt"
    private const val ABSENT = "absent"

    class Pack(val locale: String, val sha256: String, val size: Long)

    internal fun parseIndex(text: String): Map<String, Pack> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(' ')
        if (parts.size != 3 || !parts[0].matches(Regex("[A-Za-z_]+")) || !parts[1].matches(Regex("[0-9a-fA-F]{64}"))) return@mapNotNull null
        Pack(parts[0], parts[1], parts[2].toLongOrNull() ?: return@mapNotNull null)
    }.associateBy { it.locale }

    /** KDE ships region packs (pt_BR, zh_TW) beside bare-language ones (pt, es). */
    internal fun candidates(locale: Locale): List<String> {
        val region = DesktopLocale.lang(locale).removeSuffix(".UTF-8")
        if (region == DesktopLocale.DEFAULT.removeSuffix(".UTF-8") || region.startsWith("en")) return emptyList()
        return listOfNotNull(region, "zh_TW".takeIf { region == "zh_HK" }, region.substringBefore('_')).distinct()
    }

    internal fun baseUrl(desktopUrl: String) = desktopUrl.substringBeforeLast('/') + "/"

    private fun marker(root: File, tag: String, name: String) = File(root, ".droiddeck-i18n-$tag-$name")

    /** Installs the desktop's translations for [locale]; false when a download failed and is worth retrying. */
    @Synchronized
    fun ensure(context: Context, locale: Locale): Boolean {
        val root = LinuxRuntime.rootDir(context)
        if (!DesktopCatalog.desktopInstalled(root)) return true
        val entry = DesktopCatalog.desktopEntry(context)
        val pending = candidates(locale).filterNot { marker(root, entry.version, it).isFile }
        if (pending.isEmpty()) return true
        val base = baseUrl(entry.url)
        val index = parseIndex(Downloader.downloadString(base + INDEX) ?: return false)
        var complete = true
        for (name in pending) {
            val pack = index[name]
            if (pack == null) {
                FileUtils.writeString(marker(root, entry.version, name), ABSENT)
                continue
            }
            val download = File(context.cacheDir, "i18n-$name-${pack.sha256.take(12)}.download")
            try {
                val ok = Downloader.downloadFile(
                    base + "desktop-kde-i18n-$name.tar.zst", download, true, pack.sha256, java.util.function.Consumer<Float> { },
                ) && pack.sha256.equals(Hashes.sha256(download), ignoreCase = true) &&
                    LinuxRuntimeInstaller.extract(context, download, root, null)
                if (ok) FileUtils.writeString(marker(root, entry.version, name), pack.sha256) else complete = false
            } catch (e: Exception) {
                Log.w(TAG, "language pack $name: $e")
                complete = false
            } finally {
                download.delete()
            }
        }
        return complete
    }
}
