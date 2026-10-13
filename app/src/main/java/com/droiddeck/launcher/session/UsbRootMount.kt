package com.droiddeck.launcher.session

import android.content.Context
import android.os.Process
import android.util.Log
import com.droiddeck.launcher.R
import java.io.File

/**
 * A USB drive as game storage, with root, on phones that do not show USB drives to apps.
 *
 * The block device is mounted (not bound) in the app's own Android/data folder, which Android
 * gives the app straight from disk rather than through FUSE: full speed, the drive's real free
 * space, nothing visible elsewhere, and no SELinux policy change. vold lets go of an exFAT/FAT
 * drive first and gets it back on unmount. ext4 needs [prepare] once.
 */
object UsbRootMount {
    private const val TAG = "UsbRootMount"

    class Drive(val device: String, val uuid: String, val type: String, val label: String, val size: Long = 0) {
        /** vold's volume id, e.g. public:8,97. */
        val volume: String get() = device.substringAfterLast('/')
        val fat: Boolean get() = type != "ext4"
    }

    private val supported = setOf("exfat", "vfat", "ext4")
    private val deviceName = Regex("/dev/block/vold/public:\\d+,\\d+")
    private val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/su/bin/su")

    /** A su binary exists; runs nothing, so no root prompt. */
    fun available(): Boolean = suPaths.any { File(it).exists() }

    /** Where the app and the session see the drive. */
    fun path(context: Context): File = File(context.getExternalFilesDir(null), "usb")

    // The same folder below FUSE; UserHandle.PER_USER_RANGE.
    private fun files(context: Context): String = "/data/media/${Process.myUid() / 100000}/Android/data/${context.packageName}/files"

    private fun lower(context: Context): String = "${files(context)}/usb"

    /** Without root: the mount propagates into this process. */
    fun mounted(context: Context): Boolean = try {
        val m = lower(context)
        File("/proc/self/mountinfo").readLines().any { it.split(' ').getOrNull(4) == m }
    } catch (e: Exception) {
        false
    }

    private class Result(val code: Int, val out: List<String>)

    private const val ROOT = "__root__"

    /** Starts [script] as root in the global mount namespace (Magisk and KernelSU both take --mount-master). */
    private fun su(script: String): java.lang.Process =
        ProcessBuilder("su", "--mount-master", "-c", "[ \"\$(id -u)\" = 0 ] && echo $ROOT || exit 1\n$script").redirectErrorStream(true).start()

    private fun run(context: Context, script: String): Result {
        val process = try { su(script) } catch (e: java.io.IOException) { null }
            ?: throw IllegalStateException(context.getString(R.string.usb_root_denied))
        val out = process.inputStream.bufferedReader().readLines()
        val code = process.waitFor()
        if (out.firstOrNull() != ROOT) throw IllegalStateException(context.getString(R.string.usb_root_denied))
        return Result(code, out.drop(1))
    }

    private fun fail(context: Context, result: Result): Nothing = throw IllegalStateException(
        context.getString(R.string.usb_root_mount_failed, result.code, result.out.joinToString(" ").trim().ifEmpty { "exit ${result.code}" }))

    /** USB partitions vold has nodes for, mounted by it or not. */
    fun drives(context: Context): List<Drive> =
        run(context, "for b in /dev/block/vold/public:*; do [ -b \"\$b\" ] && echo \"\$(blkid \"\$b\") SIZE=\\\"\$(blockdev --getsize64 \"\$b\")\\\"\"; done").out.mapNotNull(::parseBlkid)

    /** `/dev/block/vold/public:8,97: LABEL="x" UUID="y" TYPE="exfat" SIZE="123"` (SIZE is ours) */
    internal fun parseBlkid(line: String): Drive? {
        val device = line.substringBefore(": ")
        if (!deviceName.matches(device)) return null
        val fields = Regex("(\\w+)=\"([^\"]*)\"").findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
        val type = fields["TYPE"]?.takeIf { it in supported } ?: return null
        val uuid = fields["UUID"] ?: return null
        return Drive(device, uuid, type, fields["LABEL"].orEmpty(), fields["SIZE"]?.toLongOrNull() ?: 0)
    }

    /** The chosen drive's label, once root has read it; null = none known. */
    @Volatile var label: String? = null
        private set

    /** The chosen drive, if plugged in; none chosen yet = the first one found. */
    fun chosen(context: Context): Drive? {
        val uuid = SessionPrefs.usbRootDrive(context)
        val drives = drives(context)
        val drive = if (uuid.isEmpty()) drives.firstOrNull()?.also { SessionPrefs.setUsbRootDrive(context, it.uuid) }
            else drives.firstOrNull { it.uuid == uuid }
        drive?.let { label = it.label.ifEmpty { null } }
        return drive
    }

    private fun chosenOrFail(context: Context): Drive =
        chosen(context) ?: throw IllegalStateException(context.getString(R.string.usb_root_not_plugged))

    /** Mounts the chosen drive; null on success, else the reason. */
    @Synchronized
    fun mount(context: Context): String? = attempt("mount") {
        val drive = chosenOrFail(context)
        if (mounted(context)) return@attempt
        val m = lower(context)
        val u = Process.myUid()
        path(context).parentFile?.mkdirs()
        val script = buildString {
            appendLine("[ -d $m ] || { mkdir $m && chown $u:$u $m; } || exit 10")
            if (drive.fat) {
                // vold's fs type (sdfat on Samsung), then make vold let go.
                appendLine("t=\$(grep '^${drive.device} ' /proc/mounts | head -n 1 | cut -d' ' -f3)")
                appendLine("if [ -n \"\$t\" ]; then sm unmount ${drive.volume} || exit 11")
                appendLine("  n=0; while grep -q '^${drive.device} ' /proc/mounts; do [ \$n -lt 20 ] || exit 12; sleep 0.5; n=\$((n+1)); done; fi")
                appendLine("for f in \$t ${drive.type} sdfat; do mount -t \$f -o nosuid,nodev,noexec,noatime,uid=$u,gid=$u,fmask=0077,dmask=0077,iocharset=utf8 ${drive.device} $m && exit 0; done")
                appendLine("sm mount ${drive.volume}; exit 13")
            } else {
                appendLine("mount -t ext4 -o nosuid,nodev,noatime ${drive.device} $m || exit 13")
                appendLine("[ \"\$(ls -Zd $m | cut -d' ' -f1)\" = \"\$(ls -Zd ${files(context)} | cut -d' ' -f1)\" ] && exit 0")
                appendLine("umount $m; exit 20")
            }
        }
        val result = run(context, script)
        when (result.code) {
            0 -> Log.i(TAG, "mounted ${drive.type} ${drive.uuid} (${drive.device}) at $m")
            20 -> throw IllegalStateException(context.getString(R.string.usb_root_needs_prepare))
            else -> fail(context, result)
        }
    }

    /** ext4 only, on user confirm: gives the drive's files this app's owner and SELinux label. */
    @Synchronized
    fun prepare(context: Context): String? = attempt("prepare") {
        val drive = chosenOrFail(context)
        check(drive.type == "ext4") { "not an ext4 drive" }
        val m = lower(context)
        val u = Process.myUid()
        path(context).parentFile?.mkdirs()
        val result = run(context, """
            [ -d $m ] || { mkdir $m && chown $u:$u $m; } || exit 10
            grep -q ' $m ' /proc/self/mountinfo || mount -t ext4 -o nosuid,nodev,noatime ${drive.device} $m || exit 13
            chown -hR $u:$u $m || exit 21
            chcon -hR ${'$'}(ls -Zd ${files(context)} | cut -d' ' -f1) $m || exit 23
        """.trimIndent())
        if (result.code != 0) fail(context, result)
        Log.i(TAG, "prepared ext4 ${drive.uuid}, mounted at $m")
    }

    /** Syncs, unmounts, hands exFAT/FAT back to vold. Refuses while files are open. */
    @Synchronized
    fun unmount(context: Context): String? = attempt("unmount") {
        val m = lower(context)
        val drive = runCatching { chosen(context) }.getOrNull()
        val result = run(context, buildString {
            appendLine("grep -q ' $m ' /proc/self/mountinfo || exit 0")
            appendLine("sync; umount $m || exit 30")
            if (drive?.fat == true) appendLine("sm mount ${drive.volume}")
        })
        if (result.code == 30) throw IllegalStateException(context.getString(R.string.usb_root_busy))
        if (result.code != 0) fail(context, result)
        Log.i(TAG, "unmounted $m")
    }

    private inline fun attempt(what: String, block: () -> Unit): String? = try {
        block()
        null
    } catch (e: Exception) {
        Log.w(TAG, "$what: ${e.message}")
        e.message ?: e.javaClass.simpleName
    }

    private var watching = false

    /** Called (off the main thread) after a plug-in or pull-out was handled, so the UI can refresh. */
    @Volatile var onChange: (() -> Unit)? = null

    /**
     * Mounts on plug-in and detaches on pull-out. Android sends no media broadcasts for a USB drive
     * it hides from apps, so vold's block nodes are watched instead, from a root shell of their own.
     */
    @Synchronized
    fun watch(context: Context) {
        if (watching) return
        watching = true
        val app = context.applicationContext
        Thread({
            val lines = try { su("exec inotifyd - /dev/block/vold:nd").inputStream.bufferedReader() } catch (e: java.io.IOException) { null }
            if (lines == null || lines.readLine() != ROOT) { watching = false; return@Thread }
            // inotifyd: "n<TAB>/dev/block/vold<TAB>public:8,97" on create, "d..." on delete.
            lines.forEachLine { line ->
                val fields = line.split('\t')
                if (fields.size < 3 || !fields[2].startsWith("public:") || !SessionPrefs.usbRootEnabled(app)) return@forEachLine
                if (fields[0] == "n") {
                    // shortcut: fixed wait for vold to finish checking the new volume; poll its state if 5 s proves short.
                    Thread.sleep(5000)
                    mount(app)
                } else dropIfGone(app)
                onChange?.invoke()
            }
            watching = false
        }, "usb-root-watch").start()
    }

    @Synchronized
    private fun dropIfGone(context: Context) {
        val m = lower(context)
        attempt("removed") {
            val result = run(context, "s=\$(grep ' $m ' /proc/mounts | cut -d' ' -f1); [ -z \"\$s\" ] || [ -b \"\$s\" ] && exit 1; umount -l $m")
            if (result.code == 0) Log.w(TAG, "drive pulled while mounted; detached $m")
        }
    }
}
