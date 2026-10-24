package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.core.FileUtils
import org.json.JSONObject
import java.io.File

object QualcommDriverPackage {
    private const val PREFIX = "qualcomm-adrenotools:"

    @JvmStatic fun runtimeId(importedId: String): String {
        require(validName(importedId)) { "Invalid imported driver ID" }
        return PREFIX + importedId
    }

    @JvmStatic fun importedId(runtimeId: String?): String? = runtimeId
        ?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf(::validName)

    @JvmStatic fun isRuntimeId(id: String?): Boolean = id?.startsWith(PREFIX) == true

    private fun validName(name: String): Boolean = name.isNotBlank() && name != "." && !name.contains("..") &&
        name.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }

    private fun metadata(context: Context, id: String): JSONObject? {
        if (!validName(id)) return null
        return runCatching { JSONObject(FileUtils.readString(File(TurnipDriver(context).driverPath(id), "meta.json"))) }.getOrNull()
    }

    @JvmStatic fun available(context: Context): List<String> = TurnipDriver(context).enumerateImported()
        .filter { isInstalled(context, runtimeId(it)) }.map(::runtimeId)

    @JvmStatic fun isInstalled(context: Context, runtimeId: String): Boolean {
        val id = importedId(runtimeId) ?: return false
        val meta = metadata(context, id) ?: return false
        val vendor = meta.optString("vendor")
        val description = listOf(meta.optString("name"), meta.optString("description"), meta.optString("libraryName")).joinToString(" ")
        if (!vendor.equals("Qualcomm", true) || description.contains("turnip", true) || description.contains("mesa", true)) return false
        if (meta.optInt("minApi", 0) > Build.VERSION.SDK_INT) return false
        val library = meta.optString("libraryName")
        return validName(library) && library.endsWith(".so") && TurnipDriver(context).isInstalled(id) &&
            LinuxVulkanDriverManager.isAarch64Elf(File(TurnipDriver(context).driverPath(id), library))
    }

    @JvmStatic fun label(context: Context, runtimeId: String): String {
        val id = importedId(runtimeId) ?: return "Invalid Qualcomm package"
        val driver = TurnipDriver(context)
        return "${driver.displayName(id)} · Qualcomm (AdrenoTools, experimental)" +
            if (isInstalled(context, runtimeId)) "" else " [missing or incompatible]"
    }

    @JvmStatic fun version(context: Context, runtimeId: String): String = importedId(runtimeId)
        ?.let { TurnipDriver(context).driverVersion(it) }.orEmpty()

    @JvmStatic fun mounts(context: Context, runtimeId: String): List<String> {
        ensureAvailable(isInstalled(context, runtimeId)) { "The selected Qualcomm AdrenoTools package is missing or incompatible" }
        val id = importedId(runtimeId)!!
        val driver = TurnipDriver(context)
        val directory = File(driver.driverPath(id))
        val primary = driver.libraryName(id)!!
        val mounts = arrayListOf("${File(directory, primary).path}:${QualcommSystemDriver.HAL}")
        for (library in directory.listFiles().orEmpty().sortedBy { it.name }) {
            val name = library.name
            if (name == primary || !name.endsWith(".so") || !validName(name) ||
                name in setOf("libc.so", "libdl.so", "libm.so", "liblog.so", "libvulkan.so") ||
                !LinuxVulkanDriverManager.isAarch64Elf(library)) continue
            mounts.add("${library.path}:/vendor/lib64/$name")
            // The SP-HAL namespace resolves libc++ from system before vendor.
            if (name == "libc++.so") mounts.add("${library.path}:/system/lib64/$name")
        }
        return mounts
    }
}
