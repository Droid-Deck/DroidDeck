package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.epic.EpicApiClient
import com.droiddeck.launcher.stores.epic.EpicCredentialStore
import com.droiddeck.launcher.stores.epic.EpicDownloadManager
import com.droiddeck.launcher.stores.gog.GogAuth
import com.droiddeck.launcher.stores.gog.GogDownloadManager
import org.json.JSONObject
import java.io.File

/**
 * For a GOG or Epic game installed before its sidecar kept the store's list (GOG's build
 * `dependencies`, Epic's manifest prerequisite): the list read once from the store's build
 * manifest with the saved sign-in - the manifest alone, never a game file - and kept in the sidecar
 * the way an install keeps it. Runs on the components' worker ([AutoComponents]). Without a
 * sign-in or a network it is skipped quietly and tried again a week later.
 */
object StoreListBackfill {
    private const val TAG = "StoreListBackfill"
    private const val FILE = "store-list-backfill.json"
    private const val RETRY_AFTER_MS = 7L * 24 * 3600 * 1000

    /** The store's list for the game in a sidecar: GOG's dependency ids, or Epic's prerequisite {name, path}; null when it could not be had. */
    fun interface Fetcher {
        fun fetch(context: Context, sidecar: StoreGameSidecar): Map<String, String>?
    }

    /** The store itself, with the saved sign-in. */
    internal val storeFetcher = Fetcher { context, sidecar -> fromStore(context, sidecar) }

    @VisibleForTesting internal var fetcher: Fetcher = storeFetcher

    /** Whether [sidecar] was written before the list was kept. */
    /**
     * Whether [sidecar] has no list read whole: written before the list was kept, or by a build
     * that could have written an empty one for a manifest it did not read right.
     */
    fun needs(sidecar: StoreGameSidecar): Boolean =
        (sidecar.store == Store.GOG || sidecar.store == Store.EPIC) && WinCompSources.LIST_READ !in sidecar.extra

    /**
     * The list for the game in [folder], fetched and kept when its sidecar lacks one and it was not
     * tried in the past week. True when the sidecar changed. Blocking (network).
     */
    @Synchronized
    fun fill(context: Context, folder: File): Boolean {
        val sidecar = StoreGameSidecar.read(folder)?.takeIf { it.isInstalled && needs(it) } ?: return false
        val tries = runCatching { JSONObject(AtomicFile(File(context.filesDir, FILE)).readFully().toString(Charsets.UTF_8)) }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        if (now - tries.optLong(folder.path, 0L) < RETRY_AFTER_MS) return false
        // Offline is no try: it waits for a network rather than a week.
        if (!AutoComponents.online(context)) return false
        val list = runCatching { fetcher.fetch(context, sidecar) }.getOrNull()
        if (list == null) {
            tries.put(folder.path, now)
            val atomic = AtomicFile(File(context.filesDir, FILE))
            val out = atomic.startWrite()
            try { out.write(tries.toString().toByteArray()); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out) }
            Log.i(TAG, "${sidecar.title}: the ${sidecar.store.label} list could not be had; again in a week")
            return false
        }
        // Read fresh and written whole, as an install writes it: only the list is added.
        val current = StoreGameSidecar.read(folder) ?: return false
        current.copy(extra = current.extra + list + (WinCompSources.LIST_READ to "1")).write(folder)
        Log.i(TAG, "backfill: ${sidecar.title} " + when (sidecar.store) {
            Store.GOG -> "gog deps=[${list[WinCompSources.GOG_DEPENDENCIES].orEmpty()}]"
            else -> "epic prereq=" + (list[WinCompSources.EPIC_PREREQ_NAME].orEmpty().ifEmpty { list[WinCompSources.EPIC_PREREQ_PATH].orEmpty() }.ifEmpty { "none" })
        })
        return true
    }

    private fun fromStore(context: Context, sidecar: StoreGameSidecar): Map<String, String>? {
        return when (sidecar.store) {
            Store.GOG -> {
                val token = GogAuth.validToken(context) ?: return null
                GogDownloadManager.fetchDependencies(sidecar.id, sidecar.installVersion, token)
                    ?.let { mapOf(WinCompSources.GOG_DEPENDENCIES to it.joinToString(",")) }
            }
            Store.EPIC -> {
                val token = EpicCredentialStore.getValidAccessToken(context) ?: return null
                val namespace = sidecar.extra["namespace"].orEmpty()
                val catalogItemId = sidecar.extra["catalogItemId"].orEmpty()
                if (namespace.isEmpty() || catalogItemId.isEmpty()) return null
                val json = EpicApiClient.getManifestApiJson(token, namespace, catalogItemId, sidecar.extra["appName"] ?: sidecar.id) ?: return null
                EpicDownloadManager.fetchPrereq(json)?.let { (name, path) ->
                    mapOf(WinCompSources.EPIC_PREREQ_NAME to name, WinCompSources.EPIC_PREREQ_PATH to path)
                }
            }
            else -> null
        }
    }
}
