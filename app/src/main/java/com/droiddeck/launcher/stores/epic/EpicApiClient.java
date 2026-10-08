package com.droiddeck.launcher.stores.epic;

import android.util.Log;

import com.droiddeck.launcher.stores.StoreLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Epic's library, catalog and launcher-assets services, as the launcher calls them:
 *   library-service.live.use1a.on.epicgames.com/library/api/public/items   (owned items, paginated)
 *   catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace/{ns}/bulk/items
 *   launcher-public-service-prod06.ol.epicgames.com/launcher/api/public/assets/v2/platform/Windows/...
 * All with a bearer token.
 */
public final class EpicApiClient {

    private static final String TAG = "EpicApi";
    private static final String LIBRARY_URL = "https://library-service.live.use1a.on.epicgames.com/library/api/public/items?includeMetadata=true";
    private static final String CATALOG_BASE = "https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace";
    private static final String MANIFEST_BASE = "https://launcher-public-service-prod06.ol.epicgames.com/launcher/api/public/assets/v2/platform/Windows/namespace";
    private static final String LEGENDARY_UA = "Legendary/0.1.0 (GameNative)";

    private EpicApiClient() {}

    /** The owned games, every page; Unreal assets, private sandboxes and non-Windows items left out. */
    public static List<EpicGame> getLibraryItems(String accessToken) {
        List<EpicGame> result = new ArrayList<>();
        String cursor = null;
        do {
            try {
                String url = LIBRARY_URL + (cursor != null ? "&cursor=" + cursor : "");
                String resp = EpicAuthClient.getRequest(url, accessToken);
                if (resp == null) break;
                JSONObject json = new JSONObject(resp);
                JSONArray records = json.optJSONArray("records");
                if (records == null) break;
                for (int i = 0; i < records.length(); i++) {
                    JSONObject rec = records.getJSONObject(i);
                    String appName = rec.optString("appName", "");
                    if (appName.isEmpty() || appName.equals("1")) continue;
                    String namespace = rec.optString("namespace", "");
                    if ("ue".equals(namespace) || "89efe5924d3d467c839449ab6ab52e7f".equals(namespace)) continue;
                    if ("PRIVATE".equals(rec.optString("sandboxType", ""))) continue;
                    JSONArray platforms = rec.optJSONArray("platform");
                    if (platforms != null && platforms.length() > 0) {
                        boolean windows = false;
                        for (int j = 0; j < platforms.length(); j++) {
                            String p = platforms.optString(j, "");
                            if ("Windows".equals(p) || "Win32".equals(p)) { windows = true; break; }
                        }
                        if (!windows) continue;
                    }
                    EpicGame game = new EpicGame();
                    game.appName = appName;
                    game.namespace = namespace;
                    game.catalogItemId = rec.optString("catalogItemId", "");
                    result.add(game);
                }
                JSONObject meta = json.optJSONObject("responseMetadata");
                String next = meta != null ? meta.optString("nextCursor", null) : null;
                cursor = (next == null || next.isEmpty() || next.equals(cursor)) ? null : next;
            } catch (Exception e) {
                Log.e(TAG, "library page failed: " + e.getClass().getSimpleName());
                break;
            }
        } while (cursor != null);
        return result;
    }

    /** Fills title, developer, art, description and the DLC flag from the catalog; true on success. */
    public static boolean enrichFromCatalog(String accessToken, EpicGame game) {
        if (game.namespace.isEmpty() || game.catalogItemId.isEmpty()) return false;
        try {
            String url = CATALOG_BASE + "/" + game.namespace + "/bulk/items?id=" + game.catalogItemId + "&includeDLCDetails=true&includeMainGameDetails=true&country=US";
            String resp = getWithLegendaryUA(url, accessToken);
            if (resp == null) return false;
            JSONObject root = new JSONObject(resp);
            JSONObject item = root.optJSONObject(game.catalogItemId);
            if (item == null && root.length() > 0) item = root.optJSONObject(root.keys().next());
            if (item == null) return false;
            game.title = item.optString("title", game.title.isEmpty() ? game.appName : game.title);
            game.developer = item.optString("developer", "");
            game.description = item.optString("description", "");
            game.isDLC = item.has("mainGameItem");
            if (game.isDLC) {
                JSONObject main = item.optJSONObject("mainGameItem");
                if (main != null) game.baseGameCatalogItemId = main.optString("id", "");
            }
            JSONArray keyImages = item.optJSONArray("keyImages");
            if (keyImages != null) for (int i = 0; i < keyImages.length(); i++) {
                JSONObject img = keyImages.getJSONObject(i);
                String type = img.optString("type", ""), imgUrl = img.optString("url", "");
                if (imgUrl.isEmpty()) continue;
                if ("DieselGameBoxTall".equals(type)) game.artCover = imgUrl;
                else if (("DieselGameBox".equals(type) || "Thumbnail".equals(type)) && game.artSquare.isEmpty()) game.artSquare = imgUrl;
            }
            JSONObject attrs = item.optJSONObject("customAttributes");
            if (attrs != null) {
                JSONObject offline = attrs.optJSONObject("CanRunOffline");
                if (offline != null) game.canRunOffline = !"false".equalsIgnoreCase(offline.optString("value", "true"));
            }
            String rd = item.optString("viewableDate", "");
            if (rd.isEmpty()) rd = item.optString("effectiveDate", "");
            if (!rd.isEmpty()) game.releaseDate = rd;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "catalog enrich failed for " + game.appName + ": " + e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * The manifest API answer for a game, flattened to {"manifests": [...], "versionId": "..."} as
     * the download manager reads it. {@code appName} is the library record's, not the catalog's.
     */
    public static String getManifestApiJson(String accessToken, String namespace, String catalogItemId, String appName) {
        try {
            String url = MANIFEST_BASE + "/" + namespace + "/catalogItem/" + catalogItemId + "/app/" + appName + "/label/Live";
            String resp = getWithLegendaryUA(url, accessToken);
            if (resp == null) return null;
            JSONObject root = new JSONObject(resp);
            JSONArray elements = root.optJSONArray("elements");
            if (elements == null || elements.length() == 0) return null;
            JSONObject first = elements.getJSONObject(0);
            JSONObject wrapper = new JSONObject();
            wrapper.put("manifests", first.optJSONArray("manifests"));
            String ver = first.optString("buildVersion", "");
            if (ver.isEmpty()) ver = first.optString("versionId", "");
            wrapper.put("versionId", ver);
            // The sidecar's EOS deployment id, when the app has one.
            JSONObject sidecar = first.optJSONObject("sidecar");
            if (sidecar != null) {
                String config = sidecar.optString("config", "");
                if (!config.isEmpty()) {
                    try { wrapper.put("deploymentId", new JSONObject(config).optString("deploymentId", "")); } catch (Exception ignored) {}
                }
            }
            return wrapper.toString();
        } catch (Exception e) {
            Log.e(TAG, "manifest API failed for " + appName + ": " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** `customAttributes.AdditionalCommandLine.value` for the game, "" when it has none, null when the call failed. */
    public static String getAdditionalCommandLine(String accessToken, String namespace, String catalogItemId) {
        try {
            String url = CATALOG_BASE + "/" + namespace + "/bulk/items?id=" + catalogItemId + "&country=US";
            String resp = getWithLegendaryUA(url, accessToken);
            if (resp == null) return null;
            JSONObject root = new JSONObject(resp);
            JSONObject item = root.optJSONObject(catalogItemId);
            if (item == null && root.length() > 0) item = root.optJSONObject(root.keys().next());
            if (item == null) return "";
            JSONObject attrs = item.optJSONObject("customAttributes");
            if (attrs == null) return "";
            JSONObject attr = attrs.optJSONObject("AdditionalCommandLine");
            return attr == null ? "" : attr.optString("value", "");
        } catch (Exception e) {
            Log.w(TAG, "additional command line: " + e.getClass().getSimpleName());
            return null;
        }
    }

    static String getWithLegendaryUA(String urlStr, String accessToken) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("User-Agent", LEGENDARY_UA);
            int code = conn.getResponseCode();
            java.io.InputStream is = (code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String body = EpicAuthClient.readStream(is);
            if (code < 200 || code >= 300) { Log.e(TAG, "GET HTTP " + code + " from " + StoreLog.redactUrl(urlStr)); return null; }
            return body;
        } catch (Exception e) {
            Log.e(TAG, "GET failed: " + StoreLog.redactUrl(urlStr) + " (" + e.getClass().getSimpleName() + ")");
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
