package com.droiddeck.launcher.gpu

import android.content.Context
import android.net.Uri
import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * An all-in-one driver package: one zip carrying both halves of a matched pair, the display
 * (Android, bionic) driver in one directory and the runtime (Linux, glibc) driver in another,
 * indexed by a root `manifest.json` - DroidDeck's own DD-Turnip releases (Droid-Deck/Drivers):
 *
 * ```
 * manifest.json   {"package", "version", "drivers": {"android": {"path"}, "linux": {"path"}}}
 * android/        meta.json (AdrenoTools) + libvulkan_freedreno.so
 * linux/          meta.json + libvulkan_freedreno.so
 * ```
 *
 * Installing one puts each half through its own importer, with that importer's checks, and
 * remembers the two as one bundle: picking either half sets both, and deleting either deletes both,
 * so a bundle is always used whole. Zips of the older kind, one driver each, never reach this.
 */
object DriverBundle {
    private const val TAG = "DriverBundle"
    private const val PREFS = "driver_bundles"
    private const val MANIFEST = "manifest.json"
    /** Refuses a zip that unpacks to more than this: both halves together are about 30 MB. */
    private const val MAX_BYTES = 512L shl 20

    class Manifest(val name: String, val version: String, val tag: String, val androidPath: String, val linuxPath: String)

    /** An installed bundle: [androidId] in TurnipDriver's list, [linuxId] in LinuxVulkanDriverManager's. */
    class Bundle(val id: String, val name: String, val version: String, val androidId: String, val linuxId: String) {
        /** "DD-Turnip 0.1.0". */
        val label: String get() = if (version.isEmpty()) name else "$name $version"
    }

    /** The manifest of a bundle, or null when [json] is not one (both halves, in two directories). */
    fun parseManifest(json: JSONObject): Manifest? {
        val drivers = json.optJSONObject("drivers") ?: return null
        val android = cleanPath(drivers.optJSONObject("android")?.optString("path").orEmpty()) ?: return null
        val linux = cleanPath(drivers.optJSONObject("linux")?.optString("path").orEmpty()) ?: return null
        if (android == linux) return null
        return Manifest(
            json.optString("package").ifEmpty { "Driver bundle" }, json.optString("version"),
            json.optString("tag"), android, linux,
        )
    }

    private fun cleanPath(path: String): String? {
        val p = path.trim().trim('/')
        return p.takeIf { it.isNotEmpty() && it.split('/').none { part -> part.isEmpty() || part == "." || part == ".." } }
    }

    /** True when the zip at [uri] is a bundle rather than a single driver. */
    fun isBundle(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { !it.isDirectory && it.name == MANIFEST }
                    ?.let { parseManifest(JSONObject(zip.readBytes().toString(Charsets.UTF_8))) } != null
            }
        } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "reading $uri as a bundle", e)
        false
    }

    /**
     * Install both halves of the bundle at [uri]. Both or neither: a half that is refused removes
     * the one already in. Throws [IllegalArgumentException] with a user-facing reason when the zip
     * is not a usable bundle, [IOException] on read failures.
     */
    fun install(context: Context, uri: Uri): Bundle {
        val work = File(context.cacheDir, "bundle-${System.nanoTime()}")
        val td = TurnipDriver(context)
        val lm = LinuxVulkanDriverManager(context)
        var androidId: String? = null
        try {
            unzip(context, uri, work)
            val manifest = File(work, MANIFEST).takeIf { it.isFile }
                ?.let { runCatching { parseManifest(JSONObject(FileUtils.readString(it))) }.getOrNull() }
                ?: throw IllegalArgumentException("Not a driver bundle: no manifest.json naming an android and a linux driver")
            val androidDir = File(work, manifest.androidPath)
            val linuxDir = File(work, manifest.linuxPath)

            val androidStage = td.newStagingDir()
            androidId = try {
                androidDir.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(androidStage, it.name)) }
                td.adopt(androidStage, manifest.name)
            } catch (e: Exception) {
                FileUtils.delete(androidStage)
                throw e
            }

            val linuxStage = lm.newStagingDir()
            val linuxId = try {
                val library = linuxDir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("libvulkan_freedreno") && it.name.endsWith(".so") }
                library?.copyTo(File(linuxStage, LinuxVulkanDriverManager.LIB_NAME))
                val meta = File(linuxDir, LinuxVulkanDriverManager.META_NAME).takeIf { it.isFile }
                    ?.let { runCatching { JSONObject(FileUtils.readString(it)) }.getOrNull() }
                lm.adopt(linuxStage, library?.name, meta, manifest.name)
            } catch (e: Exception) {
                FileUtils.delete(linuxStage)
                throw e
            }

            val base = LinuxVulkanDriverManager.sanitizeId(manifest.tag.ifEmpty { "${manifest.name}-${manifest.version}" })
            val saved = registry(context)
            var id = base
            var n = 2
            while (saved.contains(id)) id = "$base-${n++}"
            val bundle = Bundle(id, manifest.name, manifest.version, androidId, linuxId)
            saved.edit().putString(id, JSONObject().put("name", bundle.name).put("version", bundle.version)
                .put("android", androidId).put("linux", linuxId).toString()).apply()
            Log.i(TAG, "installed bundle $id: display $androidId, runtime $linuxId")
            return bundle
        } catch (e: Exception) {
            androidId?.let { td.remove(it) }
            throw e
        } finally {
            FileUtils.delete(work)
        }
    }

    private fun unzip(context: Context, uri: Uri, dest: File) {
        val root = dest.canonicalFile
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                for (entry in generateSequence { zip.nextEntry }) {
                    if (entry.isDirectory) continue
                    val out = File(root, entry.name).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) throw IllegalArgumentException("Not a driver bundle: ${entry.name} points outside it")
                    out.parentFile?.mkdirs()
                    out.outputStream().use { sink ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val r = zip.read(buf)
                            if (r <= 0) break
                            total += r
                            if (total > MAX_BYTES) throw IllegalArgumentException("Not a driver bundle: it unpacks to more than ${MAX_BYTES shr 20} MB")
                            sink.write(buf, 0, r)
                        }
                    }
                }
            }
        } ?: throw IOException("cannot open $uri")
    }

    /** Every installed bundle whose two halves are both still there. */
    fun all(context: Context): List<Bundle> {
        val td = TurnipDriver(context)
        val lm = LinuxVulkanDriverManager(context)
        return registry(context).all.mapNotNull { (id, raw) ->
            runCatching {
                val j = JSONObject(raw as String)
                Bundle(id, j.getString("name"), j.optString("version"), j.getString("android"), j.getString("linux"))
            }.getOrNull()
        }.filter { td.isInstalled(it.androidId) && lm.isInstalled(it.linuxId) }.sortedBy { it.id }
    }

    fun get(context: Context, id: String): Bundle? = all(context).firstOrNull { it.id == id }

    /** The bundle [driverId] is a half of, in the runtime list when [linux], else the display list. */
    fun containing(context: Context, driverId: String, linux: Boolean): Bundle? =
        all(context).firstOrNull { (if (linux) it.linuxId else it.androidId) == driverId }

    /** The bundle both drivers are set to right now, if they are one. */
    fun active(context: Context): Bundle? {
        val android = SessionPrefs.androidDriver(context)
        val linux = SessionPrefs.linuxDriver(context)
        return all(context).firstOrNull { it.androidId == android && it.linuxId == linux }
    }

    /** Set both halves as the drivers in use. */
    fun select(context: Context, bundle: Bundle) {
        SessionPrefs.setAndroidDriver(context, bundle.androidId)
        SessionPrefs.setLinuxDriver(context, bundle.linuxId)
    }

    /** Delete both halves; a driver still set to either goes back to its default. */
    fun remove(context: Context, bundle: Bundle) {
        TurnipDriver(context).remove(bundle.androidId)
        LinuxVulkanDriverManager(context).removeDriver(bundle.linuxId)
        if (SessionPrefs.linuxDriver(context) == bundle.linuxId) SessionPrefs.setLinuxDriver(context, "")
        registry(context).edit().remove(bundle.id).apply()
        Log.i(TAG, "removed bundle ${bundle.id}")
    }

    private fun registry(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
