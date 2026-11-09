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
        val sidecar = StoreInstallRoot.gameFolders(app).firstNotNullOfOrNull { f ->
            StoreGameSidecar.read(f)?.takeIf { it.store == Store.UBISOFT && it.id == id }
        } ?: return failure("not-installed")
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
            ?.let { applicationId(it.body, product) }.orEmpty()
        StoresState.logLine("Ubisoft: launch ticket for $product ready (${native.optJSONArray("owned")?.length() ?: 0} products, appId ${if (appId.isEmpty()) "unknown" else "found"})")
        return JSONObject().put("ok", true)
            .put("productId", product)
            .put("ticket", native.optString("ticket"))
            .put("owned", native.optJSONArray("owned") ?: JSONArray())
            .put("account", account)
            .put("appId", appId)
    }

    /** The entitlement's `applicationId` for the product; the list sits under one of a few keys depending on the API version. */
    internal fun applicationId(body: String, product: Long): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val list = sequenceOf("items", "entitlements", "products", "games", "results").firstNotNullOfOrNull { root.optJSONArray(it) } ?: return null
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .firstOrNull { it.optLong("productId") == product }?.optString("applicationId")?.takeIf { it.isNotEmpty() }
    }

    private fun failure(reason: String) = JSONObject().put("ok", false).put("reason", reason)
}
