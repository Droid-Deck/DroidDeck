package com.droiddeck.launcher.runtime

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import java.io.File
import java.io.RandomAccessFile

/**
 * AppImages the user brings: picked from storage, checked, and extracted once into the runtime at
 * /opt/appimages/user/<id>/app. proot has no FUSE to mount an image, and extracting it at every
 * start would cost its whole size in /tmp each time. Each one gets a menu entry on the Linux
 * desktop, and the front end starts it under gamescope through bannerlator-appimage-run.
 *
 * <id>/name holds the name to show, <id>/icon.png the icon when the image has a PNG one.
 */
object AppImageManager {
    private const val TAG = "AppImageManager"
    const val GUEST_DIR = "/opt/appimages/user"
    const val LAUNCHER = "/usr/local/bin/bannerlator-appimage-run"
    private const val ELF_AARCH64 = 183
    private const val ELF_X86_64 = 62

    class Item(val id: String, val name: String, val icon: File?, val comment: String?) {
        val guestDir: String get() = "$GUEST_DIR/$id"
    }

    private fun root(context: Context) = File(LinuxRuntime.rootDir(context), GUEST_DIR.substring(1))

    fun list(context: Context): List<Item> =
        root(context).listFiles()?.filter { File(it, "app/AppRun").exists() || Files.isLink(File(it, "app/AppRun")) }
            ?.map { dir ->
                Item(
                    dir.name,
                    FileUtils.readString(File(dir, "name"))?.trim()?.takeIf { it.isNotEmpty() } ?: dir.name,
                    File(dir, "icon.png").takeIf { it.isFile },
                    FileUtils.readString(File(dir, "comment"))?.trim()?.takeIf { it.isNotEmpty() },
                )
            }?.sortedBy { it.name.lowercase() } ?: emptyList()

    /**
     * What is wrong with [file] as an AppImage for this device, or null when it can be imported.
     * An AppImage is an ELF program (its runtime) with "AI" and the format version at byte 8.
     */
    internal fun problem(file: File): String? {
        if (!file.isFile) return "The file is gone"
        val head = ByteArray(20)
        try {
            RandomAccessFile(file, "r").use { if (it.read(head) < head.size) return "The file is too small to be an AppImage" }
        } catch (e: Exception) {
            return "The file cannot be read: ${e.message}"
        }
        if (head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() || head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()) {
            return "This is not an AppImage (it is not a Linux program)"
        }
        val machine = (head[18].toInt() and 0xff) or ((head[19].toInt() and 0xff) shl 8)
        return when {
            machine == ELF_X86_64 -> "This AppImage is built for x86_64 PCs. DroidDeck runs ARM64 (aarch64) AppImages - look for an \"aarch64\" or \"arm64\" download."
            machine != ELF_AARCH64 -> "This AppImage is not built for ARM64 (aarch64)"
            head[8] != 'A'.code.toByte() || head[9] != 'I'.code.toByte() -> "This is an ARM64 program but not an AppImage"
            head[10].toInt() != 2 -> "Only type 2 AppImages can be imported (this one is type ${head[10].toInt()})"
            else -> null
        }
    }

    /** A directory name from the image's file name: "Some_App-1.2-aarch64.AppImage" -> "some-app-1-2-aarch64". */
    internal fun idFor(fileName: String, taken: Set<String>): String {
        val base = fileName.removeSuffix(".AppImage").removeSuffix(".appimage").lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).ifEmpty { "appimage" }
        var id = base
        var n = 2
        while (id in taken) id = "$base-${n++}"
        return id
    }

    /** Imports [file]; null on success, else what went wrong. [onProgress] gets a stage line. */
    fun import(context: Context, file: File, onProgress: (String) -> Unit): String? {
        if (!LinuxRuntime.isInstalled(context)) return "Install the Linux runtime first"
        problem(file)?.let { return it }
        val base = root(context).apply { mkdirs() }
        // Room for the image while it is extracted, and the extracted tree (about the same again).
        if (StatFs(base.path).availableBytes < file.length() * 3) {
            return "Not enough free space: importing needs about ${FileUtils.sizeToString(file.length() * 3)}"
        }
        val id = idFor(file.name, base.list()?.toSet() ?: emptySet())
        val dir = File(base, id)
        val image = File(dir, "image.AppImage")
        try {
            dir.mkdirs()
            // Copied in, not run where it lies: shared storage is mounted noexec, and proot's loader
            // maps a program executable.
            onProgress("Copying ${file.name}")
            file.inputStream().use { input -> image.outputStream().use { FileUtils.copy(input, it) } }
            image.setExecutable(true, false)
            onProgress("Extracting ${file.name}")
            val guestDir = "$GUEST_DIR/$id"
            val out = StringBuilder()
            val status = GuestCommand.run(context, listOf(
                "/bin/bash", "-c",
                "cd \"$1\" && ./image.AppImage --appimage-extract >/dev/null && mv squashfs-root app",
                "extract", guestDir,
            ), logName = "appimage-import") { line -> if (out.length < 2000) out.appendLine(line) }
            if (status != 0 || !File(dir, "app").isDirectory) {
                FileUtils.delete(dir)
                return "The AppImage could not be extracted" + (out.lines().lastOrNull { it.isNotBlank() }?.let { ": $it" } ?: "")
            }
            image.delete()
            describe(dir, file)
            writeMenuEntry(context, dir)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "import ${file.name}", e)
            FileUtils.delete(dir)
            return e.message ?: "Import failed"
        } finally {
            image.delete()
        }
    }

    /** Name, comment and icon from the image's own desktop entry; the file name when it has none. */
    private fun describe(dir: File, source: File) {
        val app = File(dir, "app")
        val entry = app.listFiles { f -> f.isFile && f.name.endsWith(".desktop") }?.firstOrNull()
        val text = entry?.let { FileUtils.readString(it) }
        fun key(k: String) = text?.let { desktopKey(it, k) }
        val name = key("Name") ?: source.name.substringBeforeLast('.')
        FileUtils.writeString(File(dir, "name"), name)
        key("Comment")?.let { FileUtils.writeString(File(dir, "comment"), it) }
        key("Categories")?.let { FileUtils.writeString(File(dir, "categories"), it) }
        // The icon the entry names, beside it or anywhere in the image's icon theme, else .DirIcon.
        val iconName = key("Icon")
        val candidates = listOfNotNull(
            iconName?.let { File(app, "$it.png") },
            iconName?.let { n -> app.walkTopDown().maxDepth(8).filter { it.isFile && it.name == "$n.png" }.maxByOrNull { it.length() } },
            File(app, ".DirIcon"),
        )
        candidates.firstOrNull { it.isFile && isPng(it) }?.copyTo(File(dir, "icon.png"), overwrite = true)
    }

    private fun isPng(f: File): Boolean = try {
        f.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() }
    } catch (e: Exception) { false }

    /** A key of a desktop entry's main group, unlocalised. */
    internal fun desktopKey(desktop: String, key: String): String? {
        var inMain = false
        for (line in desktop.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("[")) { inMain = t == "[Desktop Entry]"; continue }
            if (inMain && t.startsWith("$key=")) return t.substringAfter('=').trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun menuEntry(context: Context, id: String) =
        // A system directory, not ~/.local/share/applications: the desktop copies game entries from
        // here into the user's with a GPU launcher (droiddeck-desktop-gpu) while it runs on pixman.
        File(LinuxRuntime.rootDir(context), "usr/local/share/applications/droiddeck-appimage-$id.desktop")

    /** The Linux desktop's menu entry: the desktop's own Exec rules (and droiddeck-gpu) apply to it. */
    private fun writeMenuEntry(context: Context, dir: File) {
        val id = dir.name
        val name = FileUtils.readString(File(dir, "name"))?.trim() ?: id
        val categories = FileUtils.readString(File(dir, "categories"))?.trim()?.takeIf { it.isNotEmpty() } ?: "Utility;"
        val icon = if (File(dir, "icon.png").isFile) "$GUEST_DIR/$id/icon.png" else "application-x-executable"
        FileUtils.writeString(menuEntry(context, id),
            "[Desktop Entry]\nType=Application\nName=$name\nExec=$LAUNCHER $GUEST_DIR/$id %U\nIcon=$icon\n" +
            "Terminal=false\nCategories=$categories\nX-DroidDeck-AppImage=$id\n")
    }

    fun remove(context: Context, id: String) {
        if (id.isEmpty() || '/' in id || id.startsWith(".")) return
        FileUtils.delete(File(root(context), id))
        menuEntry(context, id).delete()
    }

    private object Files {
        fun isLink(f: File) = java.nio.file.Files.isSymbolicLink(f.toPath())
    }
}
