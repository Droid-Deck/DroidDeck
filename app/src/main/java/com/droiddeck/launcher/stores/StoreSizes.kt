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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object StoreSizes {
    private const val TAG = "StoreSizes"

    val known = mutableStateMapOf<String, Long>()

    private val sizeRequests = ConcurrentHashMap<String, CompletableFuture<Long?>>()
    private val diskRequests = ConcurrentHashMap<String, CompletableFuture<Long?>>()
    private val pool = Executors.newFixedThreadPool(3, com.droiddeck.launcher.stores.download.DownloadQueue.workerFactory("sizes"))

    fun size(item: CatalogItem): Long = known[item.key] ?: item.sizeBytes

    private val caches = ConcurrentHashMap<Store, SizeCache>()
    private fun cache(context: Context, store: Store) = caches.getOrPut(store) { SizeCache.forStore(context.applicationContext.filesDir, store) }

    fun request(context: Context, item: CatalogItem) {
        if (!item.owned || item.sizeBytes > 0 || item.store == Store.GOG || known.containsKey(item.key)) return
        resolve(context, item).thenAccept { bytes ->
            if (bytes != null) StoresState.post { known[item.key] = bytes }
        }
    }

    fun resolve(context: Context, item: CatalogItem): CompletableFuture<Long?> {
        val available = known[item.key] ?: item.sizeBytes.takeIf { it > 0 }
        if (!item.owned || item.store == Store.GOG || item.sizeBytes > 0) return CompletableFuture.completedFuture(available)
        if (available != null) return CompletableFuture.completedFuture(available)
        val app = context.applicationContext
        val version = item.extra["version"].orEmpty()
        return sizeRequests.computeIfAbsent(item.key) {
            CompletableFuture.supplyAsync({
                val cached = cache(app, item.store).get(item.id, version)
                val bytes = cached ?: runCatching { fetch(app, item) }
                    .onFailure { Log.w(TAG, "${item.key}: ${it.javaClass.simpleName}") }
                    .getOrNull()
                bytes?.takeIf { it > 0 }?.also {
                    cache(app, item.store).put(item.id, version, it)
                    when (item.store) {
                        Store.EPIC -> EpicPrefs.get(app).edit().putLong("size_${item.id}", it).apply()
                        Store.AMAZON -> AmazonPrefs.get(app).edit().putLong("size_${item.id}", it).apply()
                        Store.ITCH -> {}
                        Store.GOG -> {}
                    }
                }
            }, pool).exceptionally { error ->
                Log.w(TAG, "${item.key}: size resolution failed", error)
                null
            }
        }
    }

    val onDisk = mutableStateMapOf<String, Long>()

    fun requestDisk(context: Context, key: String, game: InstalledStoreGame) {
        resolveDisk(context, key, game).thenAccept { bytes ->
            if (bytes != null) StoresState.post { onDisk[key] = bytes }
        }
    }

    fun resolveDisk(context: Context, key: String, game: InstalledStoreGame): CompletableFuture<Long?> {
        onDisk[key]?.let { return CompletableFuture.completedFuture(it) }
        val app = context.applicationContext
        val version = "disk:${game.sidecar.installedAt}:${game.sidecar.installVersion}"
        return diskRequests.computeIfAbsent(key) {
            CompletableFuture.supplyAsync({
                val c = cache(app, game.sidecar.store)
                c.get("disk:${game.sidecar.id}", version)
                    ?: game.folder.walkTopDown().filter { it.isFile && !it.name.startsWith(".droiddeck") }
                        .sumOf { it.length() }.also { c.put("disk:${game.sidecar.id}", version, it) }
            }, pool).thenApply { it.takeIf { bytes -> bytes > 0 } }.exceptionally { error ->
                Log.w(TAG, "$key: disk size resolution failed", error)
                null
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
        Store.ITCH -> com.droiddeck.launcher.stores.itch.ItchLibrary.size(context, item)
    } }
}
