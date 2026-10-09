/*
 * Epic Online Services (EOS) overlay provisioning for DroidDeck.
 *
 * Credits: the provisioning MECHANISM - download Epic's own overlay component
 * ("EpicOnlineServicesOverlay") and point a game's bundled EOS SDK at it through a single HKCU
 * registry value - comes from Legendary's legendary/lfs/eos.py, by way of The GameNative Team's
 * work (https://github.com/utkarshdalal/GameNative) and Bannerlator's EpicOverlayManager. The Epic
 * app identifiers, the registry key and the EOSOVH-Win64-Shipping.dll name are public facts from
 * Epic / Legendary. Original implementation.
 *
 * Reference: https://github.com/derrod/legendary - legendary/lfs/eos.py
 * Reference: https://github.com/utkarshdalal/GameNative
 *
 * Provision only: Epic's signed component is placed once, shared by every prefix, and each Epic
 * game's prefix gets the one pointer (written by droiddeck-store-launch before Proton starts, where
 * the prefix is known). The game's EOS SDK loads it itself - which is what lets a "corrective
 * action" (a consent Epic wants once per product) show in game instead of waiting on a browser.
 */
package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.stores.StoresState
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object EpicOverlay {
    private const val TAG = "EpicOverlay"

    // Epic's "EpicOnlineServicesOverlay" app (public, from Legendary).
    private const val OVERLAY_APP = "98bc04bc842e4906993fd6d6644ffb8d"
    private const val OVERLAY_NAMESPACE = "302e5ede476149b1bc3e4fe6ae45e50e"
    private const val OVERLAY_CATALOG_ITEM = "cc15684f44d849e89e9bf4cec0508b68"
    const val OVERLAY_DLL = "EOSOVH-Win64-Shipping.dll"

    /** Inside the session: `/root/.local/share/droiddeck/epic-overlay`, which every prefix reaches as Z:. droiddeck-store-launch names the same path. */
    const val GUEST_DIR = "/root/.local/share/droiddeck/epic-overlay"
    private const val VERSION_FILE = "droiddeck-version"

    private val busy = AtomicBoolean(false)

    fun dir(context: Context): File = File(LinuxRuntime.rootDir(context), GUEST_DIR.removePrefix("/"))

    /** The installed overlay's build, or null when it is not there. */
    fun installedVersion(context: Context): String? {
        val d = dir(context)
        if (!File(d, OVERLAY_DLL).let { it.isFile && it.length() > 0 }) return null
        return runCatching { File(d, VERSION_FILE).readText().trim() }.getOrNull()?.ifEmpty { null } ?: "unknown"
    }

    /** Starts the download in the background when the overlay is missing; nothing when it is there or under way. */
    fun ensureAsync(context: Context) {
        val app = context.applicationContext
        if (installedVersion(app) != null || !busy.compareAndSet(false, true)) return
        Thread({
            try { ensure(app) } finally { busy.set(false) }
        }, "epic-overlay").start()
    }

    /** Blocking: downloads Epic's overlay component when it is missing. True when it is in place afterwards. */
    fun ensure(context: Context): Boolean {
        val app = context.applicationContext
        if (installedVersion(app) != null) return true
        return try {
            val token = EpicCredentialStore.getValidAccessToken(app) ?: return false.also { Log.i(TAG, "overlay: not signed in") }
            val manifest = EpicApiClient.getManifestApiJson(token, OVERLAY_NAMESPACE, OVERLAY_CATALOG_ITEM, OVERLAY_APP)
                ?: return false.also { Log.w(TAG, "overlay: manifest not fetched") }
            val target = dir(app).apply { mkdirs() }
            StoresState.logLine("epic: fetching the EOS overlay")
            val result = EpicDownloadManager.install(app, manifest, target.path, "", null, AtomicBoolean(false), object : EpicDownloadManager.Callback {
                override fun onProgress(message: String, pct: Int) {}
                override fun onLog(line: String) { StoresState.logLine(line) }
            })
            val ok = result != null && File(target, OVERLAY_DLL).isFile
            if (ok) File(target, VERSION_FILE).writeText(result!!.buildVersion.ifEmpty { "unknown" })
            Log.i(TAG, "overlay: " + if (ok) "installed ${result!!.buildVersion}" else "install did not produce $OVERLAY_DLL")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "overlay: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}
