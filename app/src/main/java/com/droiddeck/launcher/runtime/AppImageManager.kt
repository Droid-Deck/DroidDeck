package com.droiddeck.launcher.runtime

import android.content.Context
import android.graphics.BitmapFactory
import android.os.StatFs
import android.system.Os
import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import java.io.File
import java.io.RandomAccessFile

/**
 * AppImages the user brings: picked from storage, checked, and extracted once into the runtime at
 * /opt/appimages/user/<id>/app. proot has no FUSE to mount an image, and extracting it at every
 * start would cost its whole size in /tmp each time. Each one gets a menu entry on the Linux
 * desktop, and the front end starts it under gamescope through droiddeck-appimage-run.
 *
 * <id>/name holds the name to show, <id>/icon.png the icon when the image has a PNG one.
 */
object AppImageManager {
    private const val TAG = "AppImageManager"
    const val GUEST_DIR = "/opt/appimages/user"
    const val LAUNCHER = "/usr/local/bin/droiddeck-appimage-run"
    private const val SHARP_ICON = 128

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
            machine != LinuxFex.ELF_AARCH64 && !LinuxFex.isX86(machine) -> "This AppImage is built for neither ARM64 (aarch64) nor x86 PCs"
            head[8] != 'A'.code.toByte() || head[9] != 'I'.code.toByte() -> "This is a Linux program but not an AppImage"
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

    fun dir(context: Context, id: String) = File(root(context), id)

    /**
     * Imports [file]; null on success, else what went wrong. [name] and [icon] replace the image's own;
     * each of [meta] is written as a file beside them.
     */
    fun import(
        context: Context, file: File, name: String?, icon: File?, meta: Map<String, String> = emptyMap(), onProgress: (String) -> Unit,
    ): String? {
        if (!LinuxRuntime.isInstalled(context)) return "Install the Linux runtime first"
        problem(file)?.let { return it }
        val base = root(context).apply { mkdirs() }
        // Room for the image while it is extracted, and the extracted tree (about the same again).
        if (StatFs(base.path).availableBytes < file.length() * 3) {
            return "Not enough free space: importing needs about ${FileUtils.sizeToString(file.length() * 3)}"
        }
        val dir = File(base, idFor(name ?: file.name, base.list()?.toSet() ?: emptySet()))
        try {
            extract(context, dir, file, onProgress)?.let { FileUtils.delete(dir); return it }
            LinuxFex.writeArch(dir, LinuxFex.elfMachine(file))
            describe(dir, file)
            if (name != null) FileUtils.writeString(File(dir, "name"), name)
            if (icon != null && !UserApps.saveIcon(icon, File(dir, "icon.png"))) Log.w(TAG, "icon ${icon.path} could not be read")
            meta.forEach { (k, v) -> FileUtils.writeString(File(dir, k), v) }
            writeMenuEntry(context, dir)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "import ${file.name}", e)
            FileUtils.delete(dir)
            return e.message ?: "Import failed"
        }
    }

    /** Swaps the program of imported [id] for [file], keeping its name and icon; the old one stays if this fails. */
    fun replace(context: Context, id: String, file: File, meta: Map<String, String>, onProgress: (String) -> Unit): String? {
        problem(file)?.let { return it }
        val dir = dir(context, id)
        if (!File(dir, "app").isDirectory) return "That AppImage is gone"
        if (StatFs(dir.path).availableBytes < file.length() * 3) {
            return "Not enough free space: updating needs about ${FileUtils.sizeToString(file.length() * 3)}"
        }
        return try {
            extract(context, dir, file, onProgress) ?: run {
                LinuxFex.writeArch(dir, LinuxFex.elfMachine(file))
                meta.forEach { (k, v) -> FileUtils.writeString(File(dir, k), v) }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "replace $id", e)
            e.message ?: "Update failed"
        }
    }

    /** Extracts [file] to [dir]/app, replacing what is there only once the extraction has worked. */
    private fun extract(context: Context, dir: File, file: File, onProgress: (String) -> Unit): String? {
        val image = File(dir, "image.AppImage")
        val links = File(dir, ".links").apply { mkdirs() }
        try {
            dir.mkdirs()
            // Copied in, not run where it lies: shared storage is mounted noexec, and proot's loader
            // maps a program executable.
            onProgress("Copying ${file.name}")
            file.inputStream().use { input -> image.outputStream().use { FileUtils.copy(input, it) } }
            image.setExecutable(true, false)
            onProgress("Extracting ${file.name}")
            val unpack = if (LinuxFex.isX86(LinuxFex.elfMachine(image))) "${LinuxFex.TOOL} extract ./image.AppImage"
                else "./image.AppImage --appimage-extract || { rm -rf squashfs-root AppDir; ${LinuxFex.TOOL} extract ./image.AppImage; }"
            val out = StringBuilder()
            // uruntime's DwarFS images unpack into AppDir with squashfs-root a link to it, and keep
            // one program under several names as hard links (sharun), which Android denies apps.
            val status = GuestCommand.run(context, listOf(
                "/bin/bash", "-c",
                "cd \"$1\" && rm -rf squashfs-root AppDir && { $unpack; } >/dev/null && " +
                    "rm -rf app && mv \"$(readlink -f squashfs-root)\" app && rm -f squashfs-root",
                "extract", "$GUEST_DIR/${dir.name}",
            ), logName = "appimage-import", linkDir = links) { line -> if (out.length < 2000) out.appendLine(line) }
            if (status != 0 || !File(dir, "app").isDirectory) {
                listOf("squashfs-root", "AppDir").forEach { FileUtils.delete(File(dir, it)) }
                return "The AppImage could not be extracted" + (out.lines().lastOrNull { it.isNotBlank() }?.let { ": $it" } ?: "")
            }
            copyLinkedFiles(File(dir, "app"), links)
            return null
        } finally {
            image.delete()
            FileUtils.delete(links)
        }
    }

    /**
     * Each name proot's link2symlink left for a hard link becomes a file of its own: a program run
     * through a link would see the shared file's name, and sharun picks what to start by its own.
     */
    private fun copyLinkedFiles(app: File, links: File) {
        val roots = setOf(links.path + "/", links.canonicalPath + "/")
        app.walkTopDown().onEnter { !Files.isLink(it) }.forEach { f ->
            if (!Files.isLink(f)) return@forEach
            val target = runCatching { Os.readlink(f.path) }.getOrNull() ?: return@forEach
            if (roots.none { target.startsWith(it) }) return@forEach
            val data = f.toPath().toRealPath()
            java.nio.file.Files.delete(f.toPath())
            java.nio.file.Files.copy(data, f.toPath())
            if (java.nio.file.Files.isExecutable(data)) f.setExecutable(true, false)
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
        restoreIcon(dir)
    }

    /**
     * The image's own icon as [dir]'s: of the files its entry names, beside it or anywhere in its
     * icon theme, a PNG of at least [SHARP_ICON] px, else the SVG, else the largest PNG; .DirIcon
     * without any.
     */
    internal fun restoreIcon(dir: File) {
        val app = File(dir, "app")
        val entry = app.listFiles { f -> f.isFile && f.name.endsWith(".desktop") }?.firstOrNull()
        val iconName = entry?.let { FileUtils.readString(it) }?.let { desktopKey(it, "Icon") }
        val named = iconName?.let { n ->
            app.walkTopDown().maxDepth(8).filter { it.isFile && (it.name == "$n.png" || it.name == "$n.svg") }.toList()
        }.orEmpty()
        val png = named.filter(::isPng).maxByOrNull(::pngSide)
        val svg = named.firstOrNull { it.name.endsWith(".svg") }
        val pick = png?.takeIf { pngSide(it) >= SHARP_ICON } ?: svg ?: png ?: File(app, ".DirIcon").takeIf { it.isFile }
        val icon = File(dir, "icon.png")
        if (pick == null || !UserApps.saveIcon(pick, icon)) icon.delete()
    }

    private fun pngSide(f: File): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        return minOf(bounds.outWidth, bounds.outHeight)
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
    internal fun writeMenuEntry(context: Context, dir: File) {
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
