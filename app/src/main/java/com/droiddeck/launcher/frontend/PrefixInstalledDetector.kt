package com.droiddeck.launcher.frontend

import org.json.JSONObject
import java.io.File

/**
 * Which catalog components a game's prefix already has from elsewhere - a real installer, the game,
 * the user - so the list can say so instead of recommending them again. Bannerlator's detector,
 * on DroidDeck's compatdata/<id>: only markers absent from a fresh Proton prefix count (OpenAL32,
 * Mono, PhysX, Games for Windows Live); msvcp140, d3dx9 and the like ship with Proton and cannot
 * tell. Files DroidDeck put there itself (droiddeck-wincomponents' state) are not counted.
 * Pure filesystem reads; never throws.
 */
object PrefixInstalledDetector {
    private const val STATE = ".droiddeck-wincomponents.json"

    /** Detector names (oalinst, mono, physx, XLiveRedist) present in [compat]/pfx. */
    fun detect(compat: File?): Set<String> = try {
        val pfx = compat?.let { File(it, "pfx") }
        if (pfx == null || !pfx.isDirectory) emptySet() else {
            val ours = ours(compat)
            val out = HashSet<String>()
            val openal = listOf("system32", "syswow64").map { "drive_c/windows/$it/openal32.dll" }
            if (openal.any { it !in ours && fileIgnoringCase(pfx, it) }) out += "oalinst"
            if (File(pfx, "drive_c/windows/mono").isDirectory) out += "mono"
            if (hasPhysX(pfx)) out += "physx"
            if (registryContains(pfx, "Games for Windows")) out += "XLiveRedist"
            out
        }
    } catch (_: Throwable) {
        emptySet()
    }

    private fun ours(compat: File): Set<String> = try {
        val files = JSONObject(File(compat, STATE).readText()).optJSONObject("files")
        files?.keys()?.asSequence()?.map { it.lowercase() }?.toSet().orEmpty()
    } catch (_: Exception) {
        emptySet()
    }

    /** Windows names ignore case; the prefix's folder may spell them either way. */
    private fun fileIgnoringCase(pfx: File, rel: String): Boolean {
        val target = File(pfx, rel)
        return target.parentFile?.listFiles()?.any { it.isFile && it.name.equals(target.name, ignoreCase = true) } == true
    }

    private fun hasPhysX(pfx: File): Boolean {
        for (pf in arrayOf("drive_c/Program Files", "drive_c/Program Files (x86)")) {
            val base = File(pfx, pf)
            if (File(base, "NVIDIA Corporation/PhysX").isDirectory) return true
            if (base.listFiles()?.any { it.isDirectory && it.name.contains("PhysX", ignoreCase = true) } == true) return true
        }
        return false
    }

    private fun registryContains(pfx: File, needle: String): Boolean =
        arrayOf("system.reg", "user.reg").any { name ->
            val f = File(pfx, name)
            try {
                f.isFile && f.length() < 32L * 1024 * 1024 &&
                    f.bufferedReader().use { r -> r.lineSequence().any { it.contains(needle, ignoreCase = true) } }
            } catch (_: Throwable) {
                false
            }
        }
}
