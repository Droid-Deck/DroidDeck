package com.droiddeck.launcher.stores.ubisoft

import android.content.Context
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONArray
import org.json.JSONObject

/** What the launcher service hands a starting Ubisoft game: the account, its products and a fresh game ticket. */
object UbisoftLaunch {
    private const val ENTITLEMENTS = "https://public-ubiservices.ubi.com/v1/profiles/me/global/ubiconnect/entitlement/api/entitlements"
    private const val ME = "https://public-ubiservices.ubi.com/v3/users/me"

    fun prepare(app: Context, id: String): JSONObject {
        val folder = StoreInstallRoot.gameFolders(app).firstOrNull { f ->
            StoreGameSidecar.read(f)?.let { it.store == Store.UBISOFT && it.id == id } == true
        } ?: return failure("not-installed")
        val sidecar = StoreGameSidecar.read(folder) ?: return failure("not-installed")
        val product = sidecar.extra["productId"]?.toLongOrNull() ?: return failure("no-product")
        val s = UbisoftAuth.current(app) ?: return failure("signed-out")
        val native = UbisoftNative.call("launch", JSONObject().put("ticket", s.ticket).put("sessionId", s.sessionId).put("productId", product))
            ?: return failure("engine-missing")
        native.optString("error").takeIf { it.isNotEmpty() }?.let {
            StoresState.logLine("Ubisoft: launch ticket for $product: $it")
            return failure("ticket: $it")
        }
        val auth = "Ubi_v1 t=${s.ticket}"
        val me = runCatching { UbisoftAuth.request(ME, "GET", auth, s.sessionId) }.getOrNull()?.takeIf { it.ok }
            ?.let { runCatching { JSONObject(it.body) }.getOrNull() }
        val account = JSONObject()
            .put("userId", s.userId)
            .put("username", me?.optString("username")?.takeIf { it.isNotEmpty() } ?: s.name)
            .put("nameOnPlatform", me?.optString("nameOnPlatform")?.takeIf { it.isNotEmpty() } ?: s.name)
            .put("email", me?.optString("email").orEmpty())
            .put("country", me?.optString("country")?.takeIf { it.isNotEmpty() } ?: "US")
        val appId = runCatching { UbisoftAuth.request(ENTITLEMENTS, "GET", auth, s.sessionId) }.getOrNull()?.takeIf { it.ok }
            ?.let { applicationId(it.body, product) } ?: sidecar.extra["appId"].orEmpty()
        StoresState.logLine("Ubisoft: launch ticket for $product ready (${native.optJSONArray("owned")?.length() ?: 0} products, appId ${if (appId.isEmpty()) "unknown" else "found"})")
        val language = com.droiddeck.launcher.stores.StoreLanguage.localeFor(null, app).let { "${it.language}-${it.country}" }
        val cached = sidecar.extra[CHUNKS]?.let { runCatching { JSONArray(it) }.getOrNull() }
        var setup = sidecar.extra[SETUP]?.let { runCatching { JSONObject(it) }.getOrNull() }
        val chunks = if (cached != null && setup != null) cached else
            UbisoftNative.call("chunks", JSONObject().put("ticket", s.ticket).put("sessionId", s.sessionId).put("productId", product)
                .put("manifest", sidecar.extra["manifest"] ?: sidecar.installVersion).put("folder", folder.path).put("language", language))
                ?.let { r ->
                    r.optString("error").takeIf { it.isNotEmpty() }?.let { StoresState.logLine("Ubisoft: manifest for $product: $it") }
                    setup = r.optJSONObject("setup")
                    r.optJSONArray("chunks")
                }?.also { c ->
                    runCatching {
                        StoreGameSidecar.read(folder)?.let { sc ->
                            sc.copy(extra = sc.extra + (CHUNKS to c.toString()) + listOfNotNull(setup?.let { SETUP to it.toString() })).write(folder)
                        }
                        com.droiddeck.launcher.stores.StoreLaunch.installedGame(app, Store.UBISOFT, id)
                            ?.let { com.droiddeck.launcher.frontend.AutoComponents.refreshLater(app, it) }
                    }
                } ?: cached
        setup?.let { StoresState.logLine("Ubisoft: setup for $product: ${it.toString().take(1500)}") }
        UbisoftActivation.watch(app, product)
        return JSONObject().put("ok", true)
            .put("productId", product)
            .put("ticket", native.optString("ticket"))
            .put("owned", native.optJSONArray("owned") ?: JSONArray())
            .put("products", native.optJSONArray("products") ?: JSONArray())
            .put("account", account)
            .put("appId", appId)
            .put("language", language)
            .put("chunks", chunks ?: JSONArray())
            .put("registry", setup?.optJSONArray("registry") ?: JSONArray())
    }

    const val CHUNKS = "chunks"
    const val SETUP = "setup"

    /** The entitlement's `applicationId` for the product; the list sits under one of a few keys depending on the API version. */
    internal fun applicationId(body: String, product: Long): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val list = sequenceOf("items", "entitlements", "products", "games", "results").firstNotNullOfOrNull { root.optJSONArray(it) } ?: return null
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .firstOrNull { it.optLong("productId") == product }?.optString("applicationId")?.takeIf { it.isNotEmpty() }
    }

    private fun failure(reason: String) = JSONObject().put("ok", false).put("reason", reason)
}
