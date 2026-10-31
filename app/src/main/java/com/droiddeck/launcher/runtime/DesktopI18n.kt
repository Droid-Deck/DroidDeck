package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.AppLanguage
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
import java.io.File

/**
 * Installs the desktop's per-language translation packs and tells Plasma which language to use.
 *
 * The desktop build publishes `desktop-kde-i18n-<locale>.tar.zst` next to the desktop package with
 * the index `desktop-kde.i18n.txt`, one `<locale> <sha256> <size>` line per pack
 * (tools/desktop-kde/build.sh). The release tag is immutable and every line verifies its own pack,
 * so the index is read unpinned, straight from the release host without mirror candidates.
 */
object DesktopI18n {
    private const val TAG = "DesktopI18n"
    internal const val ID_PREFIX = "desktop-kde-i18n-"
    private const val INDEX_NAME = "desktop-kde.i18n.txt"
    private const val LOCALERC = "root/.config/plasma-localerc"
    private const val LANGUAGE_NOTE = "root/.config/.droiddeck-desktop-language"

    private val RELEASE_BASE = BuildConfig.DESKTOP_KDE_URL.substringBeforeLast('/')

    internal data class Pack(val sha256: String, val size: Long)

    /** gettext catalog directories under usr/share/locale; en* stays in the main package, which publishes no pack. */
    private val PACKS: Map<String, String> = mapOf(
        "en" to "en", "id" to "id", "ms" to "ms", "cs" to "cs", "da" to "da", "de" to "de",
        "es" to "es", "es-419" to "es", "fr" to "fr", "it" to "it", "hu" to "hu", "nl" to "nl",
        "nb" to "nb", "pl" to "pl", "pt-BR" to "pt_BR", "pt-PT" to "pt_PT", "ro" to "ro",
        "fi" to "fi", "sv" to "sv", "vi" to "vi", "tr" to "tr", "el" to "el", "bg" to "bg",
        "ru" to "ru", "uk" to "uk", "ar" to "ar", "hi" to "hi", "th" to "th", "ja" to "ja",
        "ko" to "ko", "zh-CN" to "zh_CN", "zh-TW" to "zh_TW", "zh-HK" to "zh_TW",
    )

    /** glibc locale names for plasma-localerc [Formats]; glibc has no es_419, so es-419 falls back to es_MX. */
    private val POSIX: Map<String, String> = mapOf(
        "en" to "en_US.UTF-8", "id" to "id_ID.UTF-8", "ms" to "ms_MY.UTF-8", "cs" to "cs_CZ.UTF-8",
        "da" to "da_DK.UTF-8", "de" to "de_DE.UTF-8", "es" to "es_ES.UTF-8", "es-419" to "es_MX.UTF-8",
        "fr" to "fr_FR.UTF-8", "it" to "it_IT.UTF-8", "hu" to "hu_HU.UTF-8", "nl" to "nl_NL.UTF-8",
        "nb" to "nb_NO.UTF-8", "pl" to "pl_PL.UTF-8", "pt-BR" to "pt_BR.UTF-8", "pt-PT" to "pt_PT.UTF-8",
        "ro" to "ro_RO.UTF-8", "fi" to "fi_FI.UTF-8", "sv" to "sv_SE.UTF-8", "vi" to "vi_VN.UTF-8",
        "tr" to "tr_TR.UTF-8", "el" to "el_GR.UTF-8", "bg" to "bg_BG.UTF-8", "ru" to "ru_RU.UTF-8",
        "uk" to "uk_UA.UTF-8", "ar" to "ar_SA.UTF-8", "hi" to "hi_IN.UTF-8", "th" to "th_TH.UTF-8",
        "ja" to "ja_JP.UTF-8", "ko" to "ko_KR.UTF-8", "zh-CN" to "zh_CN.UTF-8",
        "zh-TW" to "zh_TW.UTF-8", "zh-HK" to "zh_TW.UTF-8",
    )

    fun packLocale(tag: String): String? = PACKS[tag]

    fun posixLocale(tag: String): String? = POSIX[tag]

    /** Unsupported app languages resolve to English, so setup and the settings row agree. */
    internal fun effectiveTag(context: Context): String =
        AppLanguage.supportedTag(AppLanguage.effective(context)) ?: "en"

    fun indexUrl(): String = indexFor(RELEASE_BASE)

    fun packUrl(pack: String): String = packFor(RELEASE_BASE, pack)

    internal fun indexFor(baseUrl: String): String = "$baseUrl/$INDEX_NAME"

    internal fun packFor(baseUrl: String, pack: String): String = "$baseUrl/$ID_PREFIX$pack.tar.zst"

    internal fun parseIndex(body: String): Map<String, Pack> {
        val packs = LinkedHashMap<String, Pack>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val fields = line.split(Regex("\\s+"))
            val size = fields.getOrNull(2)?.toLongOrNull()
            if (fields.size != 3 || size == null || size <= 0 || !fields[1].matches(Regex("[0-9a-fA-F]{64}"))) {
                Log.w(TAG, "index: skipping $line")
                continue
            }
            packs[fields[0]] = Pack(fields[1], size)
        }
        return packs
    }

    fun ensure(context: Context, listener: LinuxRuntimeInstaller.ProgressListener?): String? =
        ensure(context, listener, RELEASE_BASE)

    fun reinstall(context: Context, listener: LinuxRuntimeInstaller.ProgressListener?): String? =
        reinstall(context, listener, RELEASE_BASE)

    /** Drops the marker first, so ensure() downloads the pack again and re-applies the language. */
    internal fun reinstall(
        context: Context,
        listener: LinuxRuntimeInstaller.ProgressListener?,
        baseUrl: String,
    ): String? = synchronized(DesktopCatalog.installLock) {
        val pack = packLocale(effectiveTag(context))
        if (pack != null && !pack.startsWith("en")) marker(LinuxRuntime.rootDir(context), pack).delete()
        ensure(context, listener, baseUrl)
    }

    /** Returns null on success and a reason otherwise; callers log it and start the desktop anyway. */
    internal fun ensure(
        context: Context,
        listener: LinuxRuntimeInstaller.ProgressListener?,
        baseUrl: String,
    ): String? = synchronized(DesktopCatalog.installLock) {
        runCatching { ensureLocked(context, listener, baseUrl) }.getOrElse { e ->
            Log.e(TAG, "ensure", e)
            e.message ?: "the language pack failed"
        }
    }

    /**
     * Applies the language when its pack is already present, without the network. Deliberately
     * unlocked: callers run on the session-start thread while installs can hold installLock for
     * minutes, and this only writes plasma-localerc, never the pack.
     */
    internal fun applyLocal(context: Context): String? =
        runCatching { ensureLocked(context, null, RELEASE_BASE, download = false) }.getOrElse { e ->
            Log.e(TAG, "applyLocal", e)
            e.message ?: "the desktop language could not be applied"
        }

    private fun ensureLocked(
        context: Context,
        listener: LinuxRuntimeInstaller.ProgressListener?,
        baseUrl: String,
        download: Boolean = true,
    ): String? {
        val root = LinuxRuntime.rootDir(context)
        if (!root.isDirectory) return "the runtime is not installed"
        val tag = effectiveTag(context)
        val pack = packLocale(tag) ?: return "the app language has no desktop pack"
        val posix = posixLocale(tag) ?: return "the app language has no locale"
        if (!pack.startsWith("en") && !installed(root, pack)) {
            if (!download) return "the language pack is not installed"
            installPack(context, root, tag, pack, listener, baseUrl)?.let { return it }
        }
        applyLanguage(root, pack, posix)?.let { return it }
        return null
    }

    private fun installPack(
        context: Context,
        root: File,
        tag: String,
        pack: String,
        listener: LinuxRuntimeInstaller.ProgressListener?,
        baseUrl: String,
    ): String? {
        val body = Downloader.downloadDirect(indexFor(baseUrl)) ?: return "the language index is unreachable"
        val entry = parseIndex(body)[pack] ?: return "no language pack for $pack"
        val download = File(context.cacheDir, "pkg-$ID_PREFIX$pack-${entry.sha256.take(12)}.download")
        if (!verified(download, entry)) {
            val downloading = context.getString(R.string.user_apps_downloading, AppLanguage.nativeName(tag))
            listener?.onProgress(LinuxRuntimeInstaller.Step.DOWNLOADING, downloading, 0)
            val ok = Downloader.downloadFile(packFor(baseUrl, pack), download, true, entry.sha256) { f ->
                listener?.onProgress(
                    LinuxRuntimeInstaller.Step.DOWNLOADING, downloading,
                    if (f < 0) -1 else Math.round(f * 100f),
                )
            }
            if (!ok) return "the language pack download failed"
            listener?.onProgress(LinuxRuntimeInstaller.Step.VERIFYING, context.getString(R.string.rtinst_verifying), -1)
            if (!entry.sha256.equals(Hashes.sha256(download), ignoreCase = true)) return "the language pack checksum mismatch"
        }
        marker(root, pack).delete()
        if (!LinuxRuntimeInstaller.extract(context, download, root, listener)) return "the language pack could not be extracted"
        if (!File(root, "usr/share/locale/$pack").isDirectory) return "the language pack has no $pack locale"
        val recorded = FileUtils.writeString(marker(root, pack), "${BuildConfig.DESKTOP_KDE_TAG} $pack")
        if (!recorded) return "the language pack could not be recorded"
        download.delete()
        return null
    }

    internal fun installed(root: File, pack: String): Boolean =
        FileUtils.readString(marker(root, pack))?.trim() == "${BuildConfig.DESKTOP_KDE_TAG} $pack" &&
            File(root, "usr/share/locale/$pack").isDirectory

    private val FORMATS = listOf(
        "LC_ADDRESS", "LC_MEASUREMENT", "LC_MONETARY", "LC_NAME",
        "LC_NUMERIC", "LC_PAPER", "LC_TELEPHONE", "LC_TIME",
    )

    /**
     * plasma-localerc also belongs to the user: it is rewritten only while its [Translations]
     * LANGUAGE still equals the note of our last write, so a language picked in KDE settings is
     * never overwritten. A localerc that already carries a LANGUAGE value but no note predates
     * any write of ours and is left untouched; one without a LANGUAGE value is ours to write.
     * Writes merge: only the keys we manage change, the rest of the file stays as it is.
     * Returns null on success and a reason otherwise.
     */
    internal fun applyLanguage(root: File, language: String, posix: String): String? {
        val localerc = File(root, LOCALERC)
        val note = FileUtils.readString(File(root, LANGUAGE_NOTE))?.trim()?.takeIf { it.isNotEmpty() }
        val existing = FileUtils.readString(localerc)
        val current = readLanguage(existing)
        val write = when {
            current == null -> true
            note == null -> false
            current != note -> false
            else -> current != language
        }
        if (!write) return null
        val text = existing?.let { merged(it, language, posix) } ?: buildString {
            append("[Formats]\nLANG=$posix\n")
            for (field in FORMATS) append("$field=$posix\n")
            append("\n[Translations]\nLANGUAGE=$language\n")
        }
        if (!FileUtils.writeString(localerc, text)) {
            Log.w(TAG, "could not write $localerc")
            return "could not write $localerc"
        }
        if (!FileUtils.writeString(File(root, LANGUAGE_NOTE), "$language\n")) Log.w(TAG, "could not record the desktop language")
        return null
    }

    private fun merged(existing: String, language: String, posix: String): String {
        val managed = mapOf(
            "Formats" to (listOf("LANG") + FORMATS).associateWith { posix },
            "Translations" to mapOf("LANGUAGE" to language),
        )
        val order = mutableListOf("")
        val blocks = mutableMapOf("" to mutableListOf<String>())
        val headers = mutableMapOf<String, String>()
        var section = ""
        for (line in existing.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed.trim('[', ']')
                if (section !in blocks) {
                    order += section
                    blocks[section] = mutableListOf()
                    headers[section] = line
                }
            } else blocks.getValue(section) += line
        }
        for ((name, keys) in managed) {
            val block = blocks[name]
            if (block == null) {
                order += name
                blocks[name] = keys.map { (key, value) -> "$key=$value" }.toMutableList()
                headers[name] = "[$name]"
                continue
            }
            val pending = keys.toMutableMap()
            for (i in block.indices) {
                val trimmed = block[i].trim()
                if (trimmed.isEmpty() || '=' !in trimmed) continue
                val key = trimmed.substringBefore('=').trim()
                if (key in keys) {
                    block[i] = "$key=${keys.getValue(key)}"
                    pending.remove(key)
                }
            }
            var at = block.size
            while (at > 0 && block[at - 1].isBlank()) at--
            pending.forEach { (key, value) -> block.add(at, "$key=$value"); at++ }
        }
        val out = mutableListOf<String>()
        for (name in order) {
            if (name.isNotEmpty()) {
                if (out.isNotEmpty() && out.last().isNotBlank()) out += ""
                out += headers.getValue(name)
            }
            out += blocks.getValue(name)
        }
        return out.joinToString("\n")
    }

    private fun readLanguage(text: String?): String? {
        if (text == null) return null
        var section = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) section = line.trim('[', ']')
            else if (section == "Translations" && line.contains('=') && line.substringBefore('=').trim() == "LANGUAGE") {
                return line.substringAfter('=').trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun marker(root: File, pack: String) = File(root, ".droiddeck-pkg-$ID_PREFIX$pack")

    private fun verified(file: File, pack: Pack): Boolean =
        file.isFile && file.length() > 0 && !File(file.path + ".resume.properties").exists() &&
            file.length() == pack.size && pack.sha256.equals(Hashes.sha256(file), ignoreCase = true)
}
