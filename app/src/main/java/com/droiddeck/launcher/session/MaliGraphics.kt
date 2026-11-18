package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.VkBridgeComponent
import java.io.File

enum class MaliGraphicsMode(val id: String, val label: Int) {
    AUTO("auto", R.string.common_auto),
    PANVK("panvk", R.string.mali_path_panvk),
    BRIDGE("bridge", R.string.mali_path_bridge),
    VENUS("venus", R.string.mali_path_venus);

    companion object {
        fun fromId(id: String?): MaliGraphicsMode? = entries.firstOrNull { it.id == id }
    }
}

object MaliGraphics {
    const val PATH_FILE = "mali-graphics-path"
    const val ERROR_FILE = "mali-graphics-error"

    internal fun migrate(mali: Boolean, bridge: Boolean?, venus: Boolean?, owner: String?): MaliGraphicsMode {
        if (!mali || (bridge == null && venus == null)) return MaliGraphicsMode.AUTO
        if (venus == true && owner != "venus") return MaliGraphicsMode.VENUS
        if (bridge == true && owner != "bridge") return MaliGraphicsMode.BRIDGE
        if ((owner == "venus" && venus == true) || (owner == "bridge" && bridge != false)) return MaliGraphicsMode.AUTO
        if (bridge == null) return MaliGraphicsMode.BRIDGE
        return MaliGraphicsMode.PANVK
    }

    internal fun resolve(choice: MaliGraphicsMode, mali: Boolean, panvk: Boolean, venus: Boolean, bridge: Boolean): MaliGraphicsMode? = when {
        choice != MaliGraphicsMode.AUTO -> choice
        !mali -> null
        panvk -> MaliGraphicsMode.PANVK
        venus -> MaliGraphicsMode.VENUS
        bridge -> MaliGraphicsMode.BRIDGE
        else -> null
    }

    fun resolve(context: Context): MaliGraphicsMode? = resolve(
        SessionPrefs.maliGraphicsMode(context), DeviceSupport.mali(), panvkIcd(context) != null,
        VenusComponent.available(context) && VenusComponent.hostVulkanTooOld(context) == null,
        DeviceSupport.bridgeUsable() && File(context.applicationInfo.nativeLibraryDir, VkBridgeComponent.BINARY).isFile,
    )

    fun panvkIcd(context: Context): String? {
        val id = SessionPrefs.linuxDriver(context)
        val drivers = LinuxVulkanDriverManager(context)
        if (!File(drivers.getDriverDir(id), "libvulkan_panfrost.so").isFile) return null
        return drivers.getIcdPath(id)
    }

    fun problem(context: Context): String? {
        val choice = SessionPrefs.maliGraphicsMode(context)
        if (choice == MaliGraphicsMode.AUTO && !DeviceSupport.mali()) return null
        val path = resolve(context) ?: return context.getString(R.string.mali_path_unavailable)
        if (!DeviceSupport.mali()) return context.getString(R.string.mali_path_unsupported, context.getString(path.label))
        return when (path) {
            MaliGraphicsMode.PANVK -> if (panvkIcd(context) == null) context.getString(R.string.mali_panvk_missing) else null
            MaliGraphicsMode.BRIDGE -> when {
                !DeviceSupport.bridgeUsable() -> context.getString(R.string.mali_bridge_unsupported)
                !File(context.applicationInfo.nativeLibraryDir, VkBridgeComponent.BINARY).isFile -> context.getString(R.string.mali_bridge_missing)
                else -> null
            }
            MaliGraphicsMode.VENUS -> when {
                !VenusComponent.available(context) -> context.getString(R.string.mali_venus_missing)
                VenusComponent.hostVulkanTooOld(context) != null -> context.getString(R.string.mali_venus_old_vulkan, VenusComponent.hostVulkanTooOld(context))
                else -> null
            }
            MaliGraphicsMode.AUTO -> null
        }
    }

    fun actual(dir: File?): MaliGraphicsMode? = dir?.let { File(it, PATH_FILE) }
        ?.let { MaliGraphicsMode.fromId(FileUtils.readString(it)?.trim()) }

    fun failure(dir: File?): String? = dir?.let { File(it, ERROR_FILE) }
        ?.let { FileUtils.readString(it) }?.trim()?.takeIf { it.isNotEmpty() }
}
