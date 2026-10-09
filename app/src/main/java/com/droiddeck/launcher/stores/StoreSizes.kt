package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import com.droiddeck.launcher.stores.amazon.AmazonApiClient
import com.droiddeck.launcher.stores.amazon.AmazonCredentialStore
import com.droiddeck.launcher.stores.amazon.AmazonManifest
import com.droiddeck.launcher.stores.amazon.AmazonPrefs
import com.droiddeck.launcher.stores.epic.EpicApiClient
import com.droiddeck.launcher.stores.epic.EpicCredentialStore
import com.droiddeck.launcher.stores.epic.EpicDownloadManager
import com.droiddeck.launcher.stores.epic.EpicInstallTags
import com.droiddeck.launcher.stores.epic.EpicPrefs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Install sizes for owned Epic and Amazon games, whose library answers carry none: Epic's from the
 * game's build manifest (the files this device installs), Amazon's from its download manifest.
 * Asked for a card as it comes on screen, three at a time in the background, once per game - the
 * answer is kept in the store's prefs (`size_<id>`, where the library reads it too). Until it is
 * known the card shows nothing.
 */
object StoreSizes {
    private const val TAG = "StoreSizes"

    /** Sizes found this run, by card key; the cards read them. */
    val known = mutableStateMapOf<String, Long>()

    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newFixedThreadPool(3, com.droiddeck.launcher.stores.download.DownloadQueue.workerFactory("sizes"))

    fun size(item: CatalogItem): Long = known[item.key] ?: item.sizeBytes

    /** A card for [item] is on screen (or about to be): find its size if nothing has yet. */
    fun request(context: Context, item: CatalogItem) {
        if (!item.owned || item.sizeBytes > 0 || item.store == Store.GOG) return
        if (known.containsKey(item.key) || !asked.add(item.key)) return
        val app = context.applicationContext
        pool.execute {
            val bytes = runCatching { fetch(app, item) }.onFailure { Log.w(TAG, "${item.key}: ${it.javaClass.simpleName}") }.getOrNull() ?: 0L
            if (bytes > 0) {
                when (item.store) {
                    Store.EPIC -> EpicPrefs.get(app).edit().putLong("size_${item.id}", bytes).apply()
                    Store.AMAZON -> AmazonPrefs.get(app).edit().putLong("size_${item.id}", bytes).apply()
                    Store.GOG -> {}
                }
                StoresState.post { known[item.key] = bytes }
            }
        }
    }

    private fun fetch(context: Context, item: CatalogItem): Long { return when (item.store) {
        Store.EPIC -> {
            val token = EpicCredentialStore.getValidAccessToken(context) ?: return 0L
            val ns = item.extra["namespace"].orEmpty()
            val catalogItemId = item.extra["catalogItemId"].orEmpty()
            if (ns.isEmpty() || catalogItemId.isEmpty()) return 0L
            val json = EpicApiClient.getManifestApiJson(token, ns, catalogItemId, item.id) ?: return 0L
            val bytes = EpicDownloadManager.downloadManifest(json, EpicDownloadManager.parseCdnUrls(json)) ?: return 0L
            val manifest = EpicDownloadManager.parseManifest(bytes) ?: return 0L
            EpicDownloadManager.resolveInstallFiles(manifest, EpicInstallTags.tagsForDevice()).sumOf { it.fileSize() }
        }
        Store.AMAZON -> {
            val token = AmazonCredentialStore.getValidAccessToken(context) ?: return 0L
            val entitlement = item.extra["entitlementId"].orEmpty().ifEmpty { return 0L }
            val spec = AmazonApiClient.getGameDownload(token, entitlement) ?: return 0L
            val bytes = AmazonApiClient.getBytes(AmazonApiClient.appendPath(spec.downloadUrl, "manifest.proto"), token) ?: return 0L
            AmazonManifest.parse(bytes).totalInstallSize
        }
        Store.GOG -> 0L
    } }
}
