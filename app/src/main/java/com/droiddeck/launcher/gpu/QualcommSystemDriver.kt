package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.TarZst
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import java.io.File

internal inline fun ensureAvailable(condition: Boolean, message: () -> String) {
    if (!condition) throw QualcommSystemDriver.Unavailable(message())
}

object QualcommSystemDriver {
    class Unavailable(message: String) : IllegalStateException(message)

    const val ID = "system-qualcomm"
    const val HAL = "/vendor/lib64/hw/vulkan.adreno.so"
    private val required = listOf("lib/libhybris-vulkan-icd.so.0", "lib/libhybris-common.so.1", "lib/libhybris/linker/q.so",
        "bin/gamescope", "shaders/qualcomm-shaders.so", "wsi/libVkLayer_window_system_integration.so",
        "mesa/libgallium-26.2.3.so", "mesa/dri/zink_dri.so", "mesa/libGLX_droiddeck_qualcomm.so.0",
        "mesa/libEGL_droiddeck_qualcomm.so.0", "mesa/libgbm.so.1", "mesa/gbm/dri_gbm.so",
        "mesa/egl-vendor.json", "mesa/qualcomm-gl.sh")

    @JvmStatic fun detected(): Boolean {
        if (Build.VERSION.SDK_INT < 29 || !File(HAL).canRead()) return false
        val vk = VulkanInfo.query()
        return isQualcomm(vk)
    }

    internal fun isQualcomm(vk: Map<String, String>): Boolean =
        vk["vendor_id"] == "0x5143" && (vk["driver_id"] == "8" ||
            (vk["driver_id"] == null && vk["driver_name"]?.contains("Qualcomm", ignoreCase = true) == true))

    @JvmStatic fun handles(id: String?) = id == ID || QualcommDriverPackage.isRuntimeId(id)

    @JvmStatic fun selected(context: Context) = handles(SessionPrefs.linuxDriver(context))

    @JvmStatic fun mounts(context: Context, id: String): List<String> = androidPaths() +
        if (QualcommDriverPackage.isRuntimeId(id)) QualcommDriverPackage.mounts(context, id) else emptyList()

    @JvmStatic fun label(): String {
        val vk = VulkanInfo.query()
        val version = vk["driver_version_raw"]?.toULongOrNull()?.let { "${(it shr 22) and 0x7fu}.${(it shr 12) and 0x3ffu}.${it and 0xfffu}" }
            ?: vk["driver_version"] ?: "unknown version"
        return "System Qualcomm · ${vk["device"] ?: "Adreno"} · $version (experimental)"
    }

    @JvmStatic @Synchronized fun prepare(context: Context): File {
        ensureAvailable(detected()) { "The installed Vulkan driver is not a supported Qualcomm Android driver" }
        val sha = BuildConfig.QUALCOMM_BRIDGE_SHA256 + "-" + BuildConfig.QUALCOMM_FIXES_REVISION.take(16)
        val root = File(context.filesDir, "qualcomm-system")
        val dir = File(root, sha)
        if (!File(dir, ".complete").isFile || required.any { !File(dir, it).isFile }) {
            val staging = File(root, "$sha.partial")
            FileUtils.delete(staging)
            ensureAvailable(TarZst.extractAsset(context, "qualcomm/runtime.tzst", staging)) { "Cannot extract the Qualcomm Vulkan bridge" }
            val gamescope = File(staging, "bin/gamescope")
            gamescope.parentFile!!.mkdirs()
            ensureAvailable(FileUtils.copyAsset(context, "qualcomm/gamescope", gamescope)) { "Cannot prepare Qualcomm Gamescope" }
            ensureAvailable(gamescope.setExecutable(true, false)) { "Cannot prepare Qualcomm Gamescope" }
            copyAssets(context, "qualcomm/wsi", File(staging, "wsi"))
            copyAssets(context, "qualcomm/shaders", File(staging, "shaders"))
            copyAssets(context, "qualcomm/licenses", File(staging, "licenses"))
            copyAssets(context, "qualcomm/mesa", File(staging, "mesa"))
            ensureAvailable(required.all { File(staging, it).isFile }) { "The Qualcomm Vulkan bridge is incomplete" }
            File(staging, ".complete").writeText(sha)
            FileUtils.delete(dir)
            ensureAvailable(staging.renameTo(dir)) { "Cannot install the Qualcomm Vulkan bridge" }
        }
        val manifest = JSONObject().put("file_format_version", "1.0.0").put("ICD", JSONObject()
            .put("library_path", File(dir, "lib/libhybris-vulkan-icd.so.0").path)
            .put("api_version", VulkanInfo.query()["api"] ?: "1.1.0"))
        val icd = File(dir, "icd.json")
        if (!icd.isFile || icd.readText() != manifest.toString()) icd.writeText(manifest.toString())
        return icd
    }

    @JvmStatic @JvmOverloads fun addEnvironment(context: Context, environment: MutableList<String>, id: String = SessionPrefs.linuxDriver(context)) {
        if (QualcommDriverPackage.isRuntimeId(id)) ensureAvailable(QualcommDriverPackage.isInstalled(context, id)) {
            "The selected Qualcomm AdrenoTools package is missing or incompatible"
        }
        val icd = prepare(context)
        val source = if (id == ID) label() else QualcommDriverPackage.label(context, id)
        android.util.Log.i("QualcommSystemDriver", "$source; Android mounts: ${mounts(context, id).joinToString()}")
        environment.addAll(sessionEnvironment(icd, id))
    }

    internal fun sessionEnvironment(icd: File, id: String): List<String> {
        val dir = icd.parentFile!!
        return listOf(
            "VK_DRIVER_FILES=${icd.path}", "VK_ICD_FILENAMES=${icd.path}",
            "HYBRIS_ANDROID_SDK_VERSION=${Build.VERSION.SDK_INT}", "HYBRIS_VULKAN_HAL=$HAL",
            "HYBRIS_LINKER_DIR=${File(dir, "lib/libhybris/linker").path}",
            "HYBRIS_LD_LIBRARY_PATH=/system/lib64:/system_ext/lib64:/vendor/lib64/egl:/vendor/lib64/hw:/vendor/lib64",
            // The compat layer's Mali-only image-bounds pass rewrites unrelated OpSelects; DXVK comparisons become constants on Adreno.
            "HYBRIS_VULKAN_COMPAT_IMAGE_BOUNDS=0",
            "DROIDDECK_SYSTEM_QUALCOMM=1", "BL_GAMESCOPE_BACKEND=sdl",
            "DROIDDECK_QUALCOMM_DRIVER=$id",
            "BL_GAMESCOPE_BINARY=${File(dir, "bin/gamescope").path}",
            "DROIDDECK_QUALCOMM_ROOT=${dir.path}",
            "DROIDDECK_QUALCOMM_SHADERS=${File(dir, "shaders").path}",
            "VK_LAYER_PATH=${File(dir, "shaders").path}",
            "VK_INSTANCE_LAYERS=VK_LAYER_DROIDDECK_qualcomm_shaders",
            "PATH=${File(dir, "bin").path}:/usr/local/bin:/usr/bin:/bin",
        )
    }

    private fun copyAssets(context: Context, path: String, destination: File) {
        val children = context.assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            ensureAvailable(FileUtils.copyAsset(context, path, destination)) { "Cannot prepare $path" }
        } else {
            ensureAvailable(destination.isDirectory || destination.mkdirs()) { "Cannot prepare $path" }
            for (child in children) copyAssets(context, "$path/$child", File(destination, child))
        }
    }

    @JvmStatic fun androidPaths(): List<String> = listOf(
        "/system", "/vendor", "/odm", "/system_ext", "/product", "/apex", "/linkerconfig",
        "/dev/__properties__"
    ).filter { File(it).exists() }
}
