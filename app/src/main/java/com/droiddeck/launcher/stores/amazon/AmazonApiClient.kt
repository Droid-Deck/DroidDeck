package com.droiddeck.launcher.stores.amazon

import org.json.JSONObject

class GameDownloadSpec(@JvmField val downloadUrl: String, @JvmField val versionId: String)

object AmazonApiClient {
    @JvmStatic
    fun getGameDownload(accessToken: String, entitlementId: String): GameDownloadSpec? {
        val answer = AmazonNative.call(
            "download.lookup",
            JSONObject().put("accessToken", accessToken).put("entitlementId", entitlementId),
        ) ?: return null
        if (answer.has("error")) return null
        val url = answer.optString("downloadUrl").ifEmpty { return null }
        return GameDownloadSpec(url, answer.optString("versionId"))
    }

    @JvmStatic
    fun getBytes(url: String, accessToken: String?): ByteArray? {
        val request = JSONObject().put("url", url)
        if (accessToken != null) request.put("accessToken", accessToken)
        return AmazonNative.fetch("cdn.get", request)
    }

    @JvmStatic
    fun appendPath(baseUrl: String, segment: String): String {
        val q = baseUrl.indexOf('?')
        return if (q < 0) "$baseUrl/$segment" else baseUrl.substring(0, q) + "/" + segment + baseUrl.substring(q)
    }
}
