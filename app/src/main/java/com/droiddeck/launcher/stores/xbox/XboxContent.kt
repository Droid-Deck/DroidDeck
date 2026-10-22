package com.droiddeck.launcher.stores.xbox

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.stores.*
import org.json.JSONObject
import java.io.File

internal object XboxContent {
    data class Offer(val item: CatalogItem, val installed: Boolean)
    data class Plan(val base: InstalledStoreGame, val offers: List<Offer>, val error: String? = null)
    var plan by mutableStateOf<Plan?>(null)
        private set
    fun dismiss() { plan = null }

    fun manage(context: Context, base: InstalledStoreGame) {
        val app = context.applicationContext
        Thread({
            try {
                val manifest = XboxManifest.read(base.folder)
                check(manifest.storeId.matches(Regex("[A-Z0-9]{12}"))) { "The package has no valid Store ID." }
                val account = StoreAccounts.signedInAs(app, Store.XBOX) ?: error("Sign in to Xbox first.")
                val market = base.sidecar.extra["market"] ?: Xodus.market()
                val licenses = XboxCatalog.access(Xodus.run(app, listOf("library", "--json", "--market", market)), account, market)
                val products = XboxCatalog.rawProducts(listOf(manifest.storeId) + manifest.related, market)
                val baseProduct = products.singleOrNull { it.optString("ProductId") == manifest.storeId }
                val families = packages(baseProduct).map { it.optString("PackageFamilyName") }.filter { it.startsWith(manifest.name + "_") }.toSet()
                val offers = products.filter { it.optString("ProductId") in manifest.related && it.optString("ProductType") == "Durable" }.mapNotNull { product ->
                    val id = product.getString("ProductId")
                    val access = licenses[id] ?: return@mapNotNull null
                    val packages = packages(product).filter { it.optString("MainPackageFamilyNameForDlc") in families }
                        .distinctBy { it.optString("ContentId") }
                    if (packages.size != 1) return@mapNotNull null
                    val pkg = packages.single()
                    val cid = pkg.optString("ContentId")
                    if (!cid.matches(Regex("[0-9a-fA-F-]{36}"))) return@mapNotNull null
                    val localized = XboxCatalog.objects(product.optJSONArray("LocalizedProperties")).firstOrNull()
                    val item = CatalogItem(Store.XBOX, id, localized?.optString("ProductTitle")?.ifBlank { id } ?: id,
                        imageUrl = base.sidecar.cover, owned = access == "owned", subscription = access == "gamepass",
                        sizeBytes = pkg.optLong("MaxDownloadSizeInBytes"),
                        extra = mapOf("baseId" to base.sidecar.id, "contentId" to cid, "market" to market, "family" to pkg.getString("PackageFamilyName")))
                    Offer(item, File(base.folder, ".xbox-content/$id/.droiddeck-content.json").isFile)
                }
                StoresState.post { plan = Plan(base, offers) }
            } catch (e: Exception) { StoresState.post { plan = Plan(base, emptyList(), e.message ?: "Xbox content could not be loaded.") } }
        }, "xbox-content").start()
    }

    private fun packages(product: JSONObject?): List<JSONObject> = XboxCatalog.objects(product?.optJSONArray("DisplaySkuAvailabilities"))
        .filter { it.optJSONObject("Sku")?.optJSONObject("Properties")?.optBoolean("IsTrial", true) == false }
        .flatMap { XboxCatalog.objects(it.optJSONObject("Sku")?.optJSONObject("Properties")?.optJSONArray("Packages")) }
        .filter { pkg -> pkg.optString("PackageFormat") == "MSIXVC" &&
            XboxCatalog.objects(pkg.optJSONArray("PlatformDependencies")).any { it.optString("PlatformName") == "Windows.Desktop" } }

    private val parents = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun busy(base: String): Boolean = StoresState.latestDownloads.any { it.isActive && parents[it.id] == base }

    fun install(context: Context, item: CatalogItem) {
        plan = null
        val base = runCatching { base(context, item) }.getOrElse { error ->
            StoresState.post { StoresState.problems[Store.XBOX] = error.message ?: "The base game is not available." }
            return
        }
        if (XboxRuntime.isActive(base.sidecar.id)) {
            StoresState.post { StoresState.problems[Store.XBOX] = "Close the Xbox game before changing its content." }
            return
        }
        parents[item.id] = base.sidecar.id
        val folder = File(base.folder, ".xbox-content/${item.id}")
        if (folder.canonicalPath != base.folder.canonicalPath + "/.xbox-content/${item.id}") {
            StoresState.post { StoresState.problems[Store.XBOX] = "The Xbox content folder contains a link outside this game." }
            return
        }
        com.droiddeck.launcher.stores.download.SavedQueue.remember(context, item, base.folder.parentFile!!.parentFile!!)
        com.droiddeck.launcher.stores.download.DownloadQueue.enqueue(context,
            com.droiddeck.launcher.stores.download.DownloadEntry(Store.XBOX, item.id, item.title, cover = item.imageUrl, bytesTotal = item.sizeBytes)) {
            XboxInstaller(context.applicationContext, item, folder)
        }
    }

    fun base(context: Context, item: CatalogItem): InstalledStoreGame {
        require(item.id.matches(Regex("[A-Z0-9]{12}")))
        val id = item.extra["baseId"] ?: error("Missing Xbox base game")
        val folder = StoreInstallRoot.gameFolders(context).firstOrNull { StoreGameSidecar.read(it)?.let { s -> s.store == Store.XBOX && s.id == id && s.isInstalled } == true }
            ?: error("The Xbox base game is not installed.")
        return InstalledStoreGame(StoreGameSidecar.read(folder)!!, folder)
    }

    fun complete(context: Context, item: CatalogItem, folder: File) {
        val base = base(context, item)
        val parent = XboxManifest.read(base.folder)
        val content = XboxManifest.read(folder)
        check(item.id in parent.related && content.storeId == item.id && content.dependency == parent.name && content.publisher == parent.publisher && item.extra["family"].orEmpty().startsWith(content.name + "_")) {
            "The downloaded content does not belong to this Xbox game."
        }
        require(folder.canonicalPath == base.folder.canonicalPath + "/.xbox-content/${item.id}")
        val marker = File(folder, ".droiddeck-content.json")
        val temporary = File(folder, marker.name + ".tmp")
        temporary.writeText(JSONObject().put("productId", item.id).put("parent", base.sidecar.id)
            .put("contentId", item.extra["contentId"]).put("version", content.version).toString())
        check(temporary.renameTo(marker))
    }
}
