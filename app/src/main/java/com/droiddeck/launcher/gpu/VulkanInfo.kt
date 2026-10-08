package com.droiddeck.launcher.gpu

import android.util.Log

/**
 * The phone's own Vulkan driver, as it describes itself (native/deviceinfo): device name, API and
 * driver versions, a few limits and the extensions DXVK/VKD3D lean on. Asked once per process and
 * kept; the first [query] creates a Vulkan instance, so it belongs off the main thread.
 */
object VulkanInfo {
    @Volatile private var cached: Map<String, String>? = null

    @JvmStatic private external fun nativeQuery(): String

    /** Blocking on first use. Empty-ish map with an "error" key when Vulkan cannot be asked. */
    @Synchronized
    fun query(): Map<String, String> {
        cached?.let { return it }
        val text = try {
            System.loadLibrary("deviceinfo")
            nativeQuery()
        } catch (t: Throwable) {
            Log.w("VulkanInfo", "query failed", t)
            "error=" + (t.message ?: t.javaClass.simpleName)
        }
        val map = text.lineSequence().mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
        }.toMap(LinkedHashMap())
        cached = map
        return map
    }

    /** What [query] found, if it has run in this process; never blocks. */
    fun cachedOrNull(): Map<String, String>? = cached
}
