package com.droiddeck.launcher.session

import android.content.Context
import android.os.Process
import android.util.Log
import com.droiddeck.launcher.R
import java.io.File

/** A hidden USB drive mounted with root in the app's Android/data folder, below FUSE; ext4 needs [prepare] once. */
object UsbRootMount {
    private const val TAG = "UsbRootMount"

    class Drive(val device: String, val uuid: String, val type: String, val label: String, val size: Long) {
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

    // The app's files folder as /data/media holds it, below FUSE; 100000 is UserHandle.PER_USER_RANGE.
    private fun files(context: Context): String = "/data/media/${Process.myUid() / 100000}/Android/data/${context.packageName}/files"

    private fun lower(context: Context): String = "${files(context)}/usb"

    /** Without root: the mount propagates into this process. */
    fun mounted(context: Context): Boolean = lower(context) in GameStorage.mountPoints()

    /** The mount point, made through FUSE so it gets the app's owner and label; its /data/media path. */
    private fun mountPoint(context: Context): String {
        val dir = path(context)
        check(dir.isDirectory || dir.mkdirs()) { "cannot make $dir" }
        return lower(context)
    }

    private class Result(val code: Int, val out: List<String>)

    private const val ROOT = "__root__"

    /** Starts [script] as root in the global mount namespace (Magisk and KernelSU both take --mount-master). */
    private fun su(script: String): java.lang.Process =
        ProcessBuilder("su", "--mount-master", "-c", "[ \"\$(id -u)\" = 0 ] && echo $ROOT || exit 1\n$script").redirectErrorStream(true).start()

    private val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "usb-root-deadline").apply { isDaemon = true } }

    // A root prompt nobody answers or a stuck mount must not hold the lock forever; 60 s is well above the 10 s eject wait.
    private fun run(context: Context, script: String, timeoutS: Long = 60): Result {
        val process = try { su(script) } catch (e: java.io.IOException) { throw IllegalStateException(context.getString(R.string.usb_root_denied)) }
        // Closing the pipe too: the root shell under su can keep it open after su dies.
        val deadline = deadlines.schedule({ process.destroy(); process.inputStream.close() }, timeoutS, java.util.concurrent.TimeUnit.SECONDS)
        val out = try { process.inputStream.bufferedReader().readLines() } finally { deadline.cancel(false) }
        val code = process.waitFor()
        if (out.firstOrNull() != ROOT) throw IllegalStateException(context.getString(R.string.usb_root_denied))
        return Result(code, out.drop(1))
    }

    private fun fail(context: Context, result: Result): Nothing = throw IllegalStateException(
        context.getString(R.string.usb_root_mount_failed, result.code, result.out.joinToString(" ").trim()))

    /** USB partitions vold has nodes for, mounted by it or not; not volumes apps already see (SD cards). */
    fun drives(context: Context): List<Drive> {
        val visible = GameStorage.visibleVolumes(context)
        return run(context, "for b in /dev/block/vold/public:*; do [ -b \"\$b\" ] && echo \"\$(blkid \"\$b\") SIZE=\\\"\$(blockdev --getsize64 \"\$b\")\\\"\"; done")
            .out.mapNotNull(::parseBlkid).filter { it.uuid !in visible }
    }

    /** `/dev/block/vold/public:8,97: LABEL="x" UUID="y" TYPE="exfat" SIZE="123"` (SIZE is ours) */
    internal fun parseBlkid(line: String): Drive? {
        val device = line.substringBefore(": ")
        if (!deviceName.matches(device)) return null
        val fields = Regex("(\\w+)=\"([^\"]*)\"").findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
        val type = fields["TYPE"]?.takeIf { it in supported } ?: return null
        val uuid = fields["UUID"] ?: return null
        return Drive(device, uuid, type, fields["LABEL"].orEmpty(), fields["SIZE"]?.toLongOrNull() ?: 0)
    }

    /** The chosen drive, saved with its label; none chosen yet = the first one found. */
    private fun chosen(context: Context): Drive {
        val uuid = SessionPrefs.usbRootDrive(context)
        val drive = drives(context).firstOrNull { uuid.isEmpty() || it.uuid == uuid }
            ?: throw IllegalStateException(context.getString(R.string.usb_root_not_plugged))
        SessionPrefs.setUsbRootDrive(context, drive.uuid, drive.label)
        return drive
    }

    /** Mounts the chosen drive; null on success, else the reason. */
    @Synchronized
    fun mount(context: Context): String? = attempt("mount") {
        if (mounted(context)) return@attempt
        val drive = chosen(context)
        val m = mountPoint(context)
        val u = Process.myUid()
        val script = buildString {
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
        val drive = chosen(context)
        check(!drive.fat) { "not an ext4 drive" }
        val m = mountPoint(context)
        val u = Process.myUid()
        val result = run(context, """
            grep -q ' $m ' /proc/self/mountinfo || mount -t ext4 -o nosuid,nodev,noatime ${drive.device} $m || exit 13
            chown -hR $u:$u $m || exit 21
            chcon -hR ${'$'}(ls -Zd ${files(context)} | cut -d' ' -f1) $m || exit 23
        """.trimIndent(), timeoutS = 30 * 60) // relabels every file on the drive
        if (result.code != 0) fail(context, result)
        Log.i(TAG, "prepared ext4 ${drive.uuid}, mounted at $m")
    }

    /** Syncs, unmounts, hands exFAT/FAT back to vold. Refuses while files are open. */
    @Synchronized
    fun unmount(context: Context): String? = attempt("unmount") {
        if (!mounted(context)) return@attempt
        if (SessionState.running) throw IllegalStateException(context.getString(R.string.usb_root_eject_running))
        // A queued or paused store install would later write to the bare folder under the mount.
        if (com.droiddeck.launcher.stores.download.SavedQueue.installsTo(path(context)))
            throw IllegalStateException(context.getString(R.string.usb_root_busy))
        val m = lower(context)
        // The device and type come from the mount itself; an exFAT/FAT drive goes back to vold.
        val result = run(context, """
            set -- ${'$'}(grep ' $m ' /proc/mounts | head -n 1)
            [ -n "${'$'}1" ] || exit 0
            sync; umount $m || exit 30
            [ "${'$'}3" = ext4 ] || sm mount "${'$'}{1##*/}" || true
        """.trimIndent())
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

    @Volatile private var watcher: Thread? = null

    // The watcher runs as root, so only root can stop it: by its pid.
    @Volatile private var watcherPid = 0

    /** Called (off the main thread) after a plug-in or pull-out was handled, so the UI can refresh. */
    @Volatile var onChange: (() -> Unit)? = null

    /** Mounts on plug-in, detaches on pull-out: no broadcasts for hidden drives, so vold's nodes are watched as root. */
    @Synchronized
    private fun watch(context: Context) {
        if (watcher?.isAlive == true) return
        val app = context.applicationContext
        watcher = Thread({
            // An earlier app process's watcher outlives it as an orphan of init; stop those first.
            val lines = try { su("pkill -P 1 -f 'inotifyd - /dev/block/vold'; echo \$\$; exec inotifyd - /dev/block/vold:nd").inputStream.bufferedReader() } catch (e: java.io.IOException) { null }
            if (lines == null || lines.readLine() != ROOT) return@Thread
            try {
                watcherPid = lines.readLine()?.toIntOrNull() ?: 0
                // Turned off while su was starting, before [unwatch] had a pid to kill.
                if (!SessionPrefs.usbRootEnabled(app)) { attempt("unwatch") { run(app, "kill $watcherPid") }; return@Thread }
                // inotifyd: "n<TAB>/dev/block/vold<TAB>public:8,97" on create, "d..." on delete.
                lines.forEachLine { line ->
                    val fields = line.split('\t')
                    if (fields.size < 3 || !fields[2].startsWith("public:") || !SessionPrefs.usbRootEnabled(app)) return@forEachLine
                    if (fields[0] == "n") {
                        // vold checks a new volume before mounting it; 5 s covers that on the drives tried.
                        Thread.sleep(5000)
                        mount(app)
                    } else dropIfGone(app)
                    onChange?.invoke()
                }
            } finally {
                watcherPid = 0
            }
        }, "usb-root-watch").apply { start() }
    }

    /** [watch] and [mount], off the main thread: the setting is on. */
    fun enable(context: Context): String? {
        // Pulled while no app process was watching: the old mount is still there.
        dropIfGone(context)
        watch(context)
        return mount(context)
    }

    /** Stops [watch]'s root process; the next [watch] starts a new one. */
    fun unwatch(context: Context) {
        val pid = watcherPid.takeIf { it > 0 } ?: return
        attempt("unwatch") { run(context, "kill $pid") }
        // Gone before returning, so a quick [watch] after this starts a new one.
        watcher?.join(5000)
    }

    @Synchronized
    private fun dropIfGone(context: Context) {
        if (!mounted(context)) return
        val m = lower(context)
        attempt("removed") {
            val result = run(context, "s=\$(grep ' $m ' /proc/mounts | cut -d' ' -f1); { [ -z \"\$s\" ] || [ -b \"\$s\" ]; } && exit 1; umount -l $m")
            if (result.code == 0) Log.w(TAG, "drive pulled while mounted; detached $m")
        }
    }
}
