package com.droiddeck.launcher.session

import android.util.Log
import com.droiddeck.launcher.stores.StoreInstalls
import java.io.File

object CpuCacheSysfs {
    private const val TAG = "SessionService"
    private val FEX_CPUID_SIZE_KB = mapOf(1 to 32, 2 to 512, 3 to 8192)

    fun binds(dir: File, host: File = File("/sys/devices/system/cpu")): List<String> = try {
        StoreInstalls.deleteTree(dir)
        val cpus = host.list { _, name -> name.matches(Regex("cpu\\d+")) }?.sorted().orEmpty()
        val binds = cpus.mapNotNull { cpu ->
            if (stage(File(host, "$cpu/cache"), File(dir, cpu))) File(dir, cpu).path + ":" + File(host, "$cpu/cache").path else null
        }
        Log.i(TAG, "cpu cache: ${binds.size} of ${cpus.size} cores report the sizes the x86 CPUID reports")
        binds
    } catch (e: Exception) {
        Log.w(TAG, "cpu cache: could not stage the core caches: $e")
        emptyList()
    }

    private fun stage(cache: File, out: File): Boolean {
        val indexes = cache.listFiles { f -> f.isDirectory && f.name.matches(Regex("index\\d+")) } ?: return false
        var staged = false
        for (index in indexes) {
            val target = File(out, index.name).apply { mkdirs() }
            index.listFiles { f -> f.isFile }?.forEach { f ->
                runCatching { File(target, f.name).writeText(f.readText()) }
            }
            val level = runCatching { File(index, "level").readText().trim().toInt() }.getOrNull() ?: continue
            val kb = FEX_CPUID_SIZE_KB[level] ?: continue
            File(target, "size").writeText("${kb}K\n")
            staged = true
        }
        return staged
    }
}
