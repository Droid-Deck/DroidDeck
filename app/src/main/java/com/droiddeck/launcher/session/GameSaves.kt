package com.droiddeck.launcher.session

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Game saves in and out of a game's Proton prefix, as zips other apps read: GameHub keeps a prefix's
 * drive_c rooted at "/drive_c/users/steamuser/…", Winlator / WinNative / Bannerlator at
 * "drive_c/users/xuser/…". Proton here runs as steamuser too, so on the way in any user folder but
 * Public becomes steamuser, and on the way out the chosen layout decides the user segment.
 *
 * Saves belong to the game's prefix (compatdata/<appid>/pfx), whichever Proton it runs with.
 * Ported from Bannerlator's GameSaveBackup and SaveLocator.
 */
object GameSaves {
    enum class Layout(val app: String, val rootPrefix: String, val user: String) {
        GAMEHUB("GameHub", "/drive_c/", "steamuser"),
        WINLATOR("Winlator", "drive_c/", "xuser");

        /** "GameHub zip", in the app's language. */
        fun label(context: Context): String = context.getString(R.string.saves_layout_zip, app)
    }

    /** A game, its prefix, and the save folders found in it. */
    data class Game(val game: Library.SteamGame, val prefix: File?, val saves: List<SaveDir>) {
        val launched get() = prefix?.let { File(it, "drive_c").isDirectory } == true
    }

    /** A folder of saves, relative to the steamuser profile. */
    data class SaveDir(val relPath: String, val files: Int, val bytes: Long)

    private const val USER = "steamuser"

    fun savesDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DroidDeck/Saves")
    private fun backupsDir(): File = File(savesDir(), "backups")

    /** [game] with its prefix and the saves found there; reads files, so not on the main thread. */
    fun game(game: Library.SteamGame): Game = Game(game, game.protonPrefix, game.protonPrefix?.let { locate(it, game) } ?: emptyList())

    // ------------------------------------------------------------------ finding saves

    /** Roots of the steamuser profile that hold most PC game saves, in the order they are trusted. */
    private val SAVE_ROOTS = listOf("Documents/My Games", "Saved Games", "AppData/Roaming", "Documents", "AppData/Local", "AppData/LocalLow")
    private const val KEEP_THRESHOLD = 50

    private fun profile(prefix: File) = File(prefix, "drive_c/users/$USER")

    /**
     * Folders under the save roots, one and two deep, whose names match the game's name or its folder's.
     * Nested matches keep the outer folder only.
     */
    fun locate(prefix: File, game: Library.SteamGame): List<SaveDir> {
        val profile = profile(prefix)
        if (!profile.isDirectory) return emptyList()
        val ids = listOfNotNull(game.name, game.gameFiles?.name).map(::ident).filter { it.norm.isNotEmpty() }
        if (ids.isEmpty()) return emptyList()
        val hits = LinkedHashMap<String, Int>()
        fun consider(dir: File) {
            val d = ident(dir.name)
            if (d.norm.isEmpty()) return
            val s = ids.maxOf { score(it, d) }
            if (s < KEEP_THRESHOLD) return
            val rel = dir.relativeTo(profile).path.replace(File.separatorChar, '/')
            if ((hits[rel] ?: -1) < s) hits[rel] = s
        }
        for (root in SAVE_ROOTS) {
            File(profile, root).listFiles()?.filter { it.isDirectory }?.forEach { d1 ->
                consider(d1)
                d1.listFiles()?.filter { it.isDirectory }?.forEach(::consider)
            }
        }
        val kept = ArrayList<String>()
        for (rel in hits.entries.sortedByDescending { it.value }.map { it.key }) {
            if (kept.none { rel == it || rel.startsWith("$it/") }) kept.add(rel)
        }
        return kept.map { rel ->
            var files = 0
            var bytes = 0L
            File(profile, rel).walkTopDown().filter { it.isFile }.forEach { files++; bytes += it.length() }
            SaveDir(rel, files, bytes)
        }.filter { it.files > 0 }
    }

    private data class Ident(val norm: String, val tokens: Set<String>)

    private fun ident(raw: String): Ident {
        val cleaned = raw.lowercase().replace("®", "").replace("™", "").replace("©", "")
        return Ident(cleaned.filter { it.isLetterOrDigit() }, cleaned.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet())
    }

    /** exact 100, one contains the other 70, half the words shared 50, close spelling 40. */
    private fun score(a: Ident, b: Ident): Int {
        if (a.norm.isEmpty() || b.norm.isEmpty()) return 0
        if (a.norm == b.norm) return 100
        if (a.norm.contains(b.norm) || b.norm.contains(a.norm)) return 70
        if (a.tokens.isNotEmpty() && b.tokens.isNotEmpty() &&
            a.tokens.intersect(b.tokens).size.toDouble() / minOf(a.tokens.size, b.tokens.size) >= 0.5) return 50
        return if (1.0 - levenshtein(a.norm, b.norm).toDouble() / maxOf(a.norm.length, b.norm.length) >= 0.85) 40 else 0
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    // ------------------------------------------------------------------ export

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
    private fun safe(name: String) = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifEmpty { "game" }

    /**
     * Zips the game's saves into [dir] as "<Game>_<date>.zip" in [layout]. The found save folders when
     * there are any, else the whole steamuser profile minus Temp and crash dumps. Returns the zip.
     */
    fun export(context: Context, game: Game, layout: Layout, dir: File): Pair<File, Int> {
        val prefix = game.prefix ?: error(context.getString(R.string.saves_not_launched, game.game.name))
        dir.mkdirs()
        val out = File(dir, "${safe(game.game.name)}_${stamp()}.zip")
        val count = zipProfile(profile(prefix), game.saves.map { it.relPath }.ifEmpty { null }, layout, out)
        if (count == 0) { out.delete(); error(context.getString(R.string.saves_none_found, game.game.name)) }
        return out to count
    }

    private fun zipProfile(profile: File, roots: List<String>?, layout: Layout, out: File): Int {
        if (!profile.isDirectory) return 0
        val scope = roots?.map { File(profile, it) }?.filter { it.exists() } ?: listOf(profile)
        var count = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            for (top in scope) top.walkTopDown()
                .onEnter { d -> d.relativeTo(profile).path.replace(File.separatorChar, '/').let { !it.equals("AppData/Local/Temp", true) && !it.equals("AppData/Local/CrashDumps", true) } }
                .filter { it.isFile }
                .forEach { f ->
                    val rel = f.relativeTo(profile).path.replace(File.separatorChar, '/')
                    zos.putNextEntry(ZipEntry("${layout.rootPrefix}users/${layout.user}/$rel"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    count++
                }
        }
        return count
    }

    // ------------------------------------------------------------------ import

    /**
     * Unzips a GameHub or Winlator save zip into the game's drive_c, after zipping what its steamuser
     * profile holds now into Download/DroidDeck/Saves/backups. Entries outside drive_c, launcher
     * shortcuts and anything escaping drive_c are skipped. Returns (files written, backup or null).
     */
    fun import(context: Context, game: Game, zip: File): Pair<Int, File?> {
        val prefix = game.prefix ?: error(context.getString(R.string.saves_not_launched, game.game.name))
        val driveC = File(prefix, "drive_c")
        if (!driveC.isDirectory) error(context.getString(R.string.saves_not_launched, game.game.name))
        if (!looksLikeSaveZip(zip)) error(context.getString(R.string.saves_not_save_zip, zip.name))

        backupsDir().mkdirs()
        val backup = File(backupsDir(), "${safe(game.game.name)}_${stamp()}.zip")
        val backedUp = zipProfile(profile(prefix), null, Layout.GAMEHUB, backup)
        if (backedUp == 0) backup.delete()

        val base = driveC.canonicalFile
        var written = 0
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                remap(e.name)?.let { rel ->
                    val out = File(driveC, rel).canonicalFile
                    if (out.path.startsWith(base.path + File.separator)) {
                        if (e.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            Files.copy(zis, out.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            written++
                        }
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return written to backup.takeIf { it.exists() }
    }

    /** Which layout a zip was written in, from its first user folder; null when it has none. */
    fun layoutOf(zip: File): Layout? = runCatching {
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val segs = driveCSegments(e.name)
                if (segs != null && segs.size >= 2 && segs[0].equals("users", true) && !segs[1].equals("Public", true))
                    return@use if (segs[1].equals("xuser", true)) Layout.WINLATOR else Layout.GAMEHUB
                e = zis.nextEntry
            }
            null
        }
    }.getOrNull()

    private fun looksLikeSaveZip(zip: File): Boolean = runCatching {
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
            var e = zis.nextEntry
            while (e != null) { if (driveCSegments(e.name) != null) return@use true; e = zis.nextEntry }
            false
        }
    }.getOrDefault(false)

    /** An entry's path below drive_c, with any user folder but Public renamed to steamuser. */
    private fun remap(raw: String): String? {
        val segs = driveCSegments(raw)?.takeIf { it.isNotEmpty() } ?: return null
        if (segs.any { it == ".." }) return null
        // GameHub also packs its own launcher shortcuts; they are never saves.
        if (segs[0].equals("proton_shortcuts", true)) return null
        val last = segs.last().lowercase()
        if ((last.endsWith(".lnk") || last.endsWith(".desktop") || last.endsWith(".url")) && segs.any { it.equals("Desktop", true) }) return null
        if (segs.size >= 2 && segs[0].equals("users", true) && !segs[1].equals("Public", true)) segs[1] = USER
        return segs.joinToString("/")
    }

    private fun driveCSegments(raw: String): MutableList<String>? {
        val segs = raw.replace('\\', '/').trimStart('/').split("/").filter { it.isNotEmpty() && it != "." }
        if (segs.isEmpty() || !segs[0].equals("drive_c", true)) return null
        return segs.drop(1).toMutableList()
    }
}
