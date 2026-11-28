package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.VkBridgeComponent
import java.io.File

enum class MaliGraphicsMode(val id: String, val label: Int) {
    AUTO("auto", R.string.common_auto),
    BRIDGE("bridge", R.string.mali_path_bridge),
    VENUS("venus", R.string.mali_path_venus);

    companion object {
        fun fromId(id: String?): MaliGraphicsMode? = entries.firstOrNull { it.id == id }
    }
}

object MaliGraphics {
    private val ROUTING_KEYS = setOf(
        "DD_MALI_GRAPHICS_MODE", "DD_INSIDE", "DD_VENUS", "DD_VKBRIDGE", "DD_VK_DRIVER", "DD_OPEN_DRIVER",
        "VK_ICD_FILENAMES", "VK_DRIVER_FILES", "VK_ADD_DRIVER_FILES", "VK_LOADER_DRIVERS_SELECT", "VK_LOADER_DRIVERS_DISABLE",
        "VKBRIDGE_SOCKET", "VTEST_SOCKET_NAME", "VN_DEBUG", "GALLIUM_DRIVER", "MESA_LOADER_DRIVER_OVERRIDE", "LIBGL_ALWAYS_SOFTWARE",
    )

    fun diagnosticOverrides(choice: MaliGraphicsMode, extra: List<String>): List<String> =
        if (choice == MaliGraphicsMode.AUTO) extra else
            extra.filter { it.substringBefore('=').removePrefix("DROIDDECK_GAME_") !in ROUTING_KEYS }

    internal fun migrate(bridge: Boolean?, venus: Boolean?, owner: String?): MaliGraphicsMode = when {
        venus == true && owner != "venus" -> MaliGraphicsMode.VENUS
        bridge == true && owner != "bridge" -> MaliGraphicsMode.BRIDGE
        else -> MaliGraphicsMode.AUTO
    }

    internal fun resolve(
        choice: MaliGraphicsMode,
        selectedDriver: Boolean,
        venus: Boolean,
        bridgeUsable: Boolean,
    ): MaliGraphicsMode = when {
        choice != MaliGraphicsMode.AUTO -> choice
        selectedDriver -> MaliGraphicsMode.AUTO
        venus -> MaliGraphicsMode.VENUS
        bridgeUsable -> MaliGraphicsMode.BRIDGE
        else -> MaliGraphicsMode.AUTO
    }

    fun resolve(context: Context, choice: MaliGraphicsMode = SessionPrefs.maliGraphicsMode(context)): MaliGraphicsMode {
        if (!DeviceSupport.mali()) return MaliGraphicsMode.AUTO
        val drivers = LinuxVulkanDriverManager(context)
        return resolve(
            choice,
            selectedDriver = drivers.isMaliCompatible(SessionPrefs.linuxDriver(context)),
            venus = VenusComponent.available(context) && VenusComponent.hostVulkanTooOld(context) == null,
            bridgeUsable = DeviceSupport.bridgeUsable() && bridgeBinaryPresent(context),
        )
    }

    private fun bridgeBinaryPresent(context: Context): Boolean =
        File(context.applicationInfo.nativeLibraryDir, VkBridgeComponent.BINARY).isFile

    fun problem(context: Context, route: MaliGraphicsMode): String? = when (route) {
        MaliGraphicsMode.AUTO -> null
        MaliGraphicsMode.BRIDGE -> when {
            !DeviceSupport.bridgeUsable() -> context.getString(R.string.mali_bridge_unsupported)
            !bridgeBinaryPresent(context) -> context.getString(R.string.mali_bridge_missing)
            else -> null
        }
        MaliGraphicsMode.VENUS -> when {
            !VenusComponent.available(context) -> context.getString(R.string.mali_venus_missing)
            VenusComponent.hostVulkanTooOld(context) != null -> context.getString(R.string.mali_venus_old_vulkan, VenusComponent.hostVulkanTooOld(context))
            else -> null
        }
    }
}
