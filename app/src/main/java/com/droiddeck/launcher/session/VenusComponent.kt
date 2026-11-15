package com.droiddeck.launcher.session

import android.content.Context
import android.os.Process
import android.util.Log
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.core.SessionPart
import java.io.File
import java.io.PrintWriter

/**
 * Vulkan for the session on a Mali, through Venus (the "Mali (Venus)" route; by David Cimo,
 * DavidCimo/droiddeck-mali, ported to this branch).
 *
 * The guest's processes are glibc and cannot load the vendor's bionic driver. Instead they load
 * Mesa's Venus ICD (libvulkan_virtio.so), which serializes every Vulkan call over a unix socket to
 * virglrenderer's vtest server. The server runs here, on the Android side, and replays the calls
 * on the device's own Vulkan driver. Both ends of the socket are under the app's files directory,
 * which the session binds at its own path, so the same path works inside and out.
 */
class VenusComponent(private val logFile: File?) : SessionPart() {
    @Volatile private var pid = -1

    override fun start() {
        val context = app()
        stageIcd(context)
        val libDir = context.applicationInfo.nativeLibraryDir
        val socket = socket(context)
        socket.delete()
        val env = arrayOf(
            "LD_LIBRARY_PATH=$libDir",
            // vtest forks a render server per client; this is where its executable is.
            "RENDER_SERVER_EXEC_PATH=$libDir/libvirgl_render_server.so",
            "TMPDIR=" + context.cacheDir.path,
        )
        val command = "$libDir/libvirgl_test_server.so --venus --no-virgl --socket-path " + socket.path
        val out = logFile?.let { runCatching { PrintWriter(java.io.FileWriter(it, true)) }.getOrNull() }
        pid = HostProcess.start(command, env, context.filesDir, { status ->
            Log.w(TAG, "vtest server exited: $status")
            out?.let { synchronized(it) { it.println("vtest server exited: $status"); it.flush() } }
        }, { line ->
            Log.i(TAG, line)
            out?.let { synchronized(it) { it.println(line); it.flush() } }
        })
        Log.i(TAG, "vtest server pid $pid on $socket")
    }

    override fun stop() {
        if (pid > 1) Process.killProcess(pid)
        pid = -1
    }

    companion object {
        private const val TAG = "Venus"
        private const val ASSET = "venus/libvulkan_virtio.so"
        private const val DIR = "venus"
        private const val LIB = "libvulkan_virtio.so"
        private const val ICD = "virtio_icd.json"

        /**
         * The selection rule: Venus when Setup's "Mali (Venus)" is on, on a GPU that is neither an
         * Adreno nor one the system-driver route covers (PowerVR, Xclipse), and the apk carries it.
         * It then replaces the Mali bridge and any open driver pair for the session.
         */
        @JvmStatic
        fun wanted(context: Context): Boolean =
            !DeviceSupport.adreno() && !com.droiddeck.launcher.gpu.SystemVulkanDriver.isAvailable() &&
                SessionPrefs.venus(context) && available(context) && hostVulkanTooOld(context) == null

        /** The phone driver's Vulkan version when it is below 1.2: gamescope skips such a device, so Venus cannot run. */
        @JvmStatic
        fun hostVulkanTooOld(context: Context): String? {
            val api = com.droiddeck.launcher.gpu.VulkanInfo.cachedOrNull()?.get("api") ?: SessionPrefs.hostVulkanApi(context)
            val parts = api.split('.').mapNotNull { it.trim().toIntOrNull() }
            if (parts.size < 2) return null
            return api.takeIf { parts[0] < 1 || (parts[0] == 1 && parts[1] < 2) }
        }

        /** Whether this apk carries the Venus component (tools/venus, venus.tzst). */
        @JvmStatic
        fun available(context: Context): Boolean = runCatching {
            context.assets.list(DIR)?.contains(LIB) == true &&
                File(context.applicationInfo.nativeLibraryDir, "libvirgl_test_server.so").isFile
        }.getOrDefault(false)

        @JvmStatic
        fun socket(context: Context): File = File(File(context.filesDir, DIR), "vtest.sock")

        /** What the guest needs to find the ICD and the server. */
        @JvmStatic
        fun guestEnv(context: Context): List<String> {
            val dir = File(context.filesDir, DIR)
            return listOf(
                "VK_ICD_FILENAMES=" + File(dir, ICD).path,
                "VN_DEBUG=vtest",
                "VTEST_SOCKET_NAME=" + socket(context).path,
                // Xwayland has no DRI3 without a DRM node, so swapchains present through shared memory.
                "MESA_VK_WSI_DEBUG=sw",
                // The Mali compatibility layer in every process but gamescope, not only Wine: Zink
                // (Steam's GL, Xwayland's glamor) requires robustness2's nullDescriptor, which the
                // layer stands in for. The session script checks it and falls back to llvmpipe.
                "DROIDDECK_MALI_COMPAT=1",
                "BL_VENUS=1",
                // gamescope's composite layout without immutable YCbCr samplers from the start
                // (patch 0930): a Mali-G615 refused every composite pipeline with them, and testing
                // that through Venus took the render server down.
                "GAMESCOPE_NO_YCBCR_SAMPLERS=1",
            )
        }

        /** Copies the ICD out of the APK when it changed, and writes its manifest with an absolute path. */
        private fun stageIcd(context: Context) {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val lib = File(dir, LIB)
            // Copied again after every app update: the asset is compressed, so its size is unknown up front.
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            if (!lib.isFile || lib.lastModified() < installed) {
                val tmp = File(dir, "$LIB.tmp")
                context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(lib)) Log.e(TAG, "could not move the Venus ICD into place")
                Log.i(TAG, "staged $lib (${lib.length()} bytes)")
            }
            FileUtils.writeString(
                File(dir, ICD),
                """{"file_format_version":"1.0.1","ICD":{"library_path":"${lib.path}","api_version":"1.4.354"}}""",
            )
        }
    }
}
