package com.droiddeck.launcher.stores.amazon;

import android.util.Log;

import com.droiddeck.launcher.stores.StoreLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Amazon's device-auth endpoints, as the Amazon Games launcher registers itself:
 *   POST api.amazon.com/auth/register    (the PKCE code for bearer tokens, with a device registration)
 *   POST api.amazon.com/auth/token       (access token renewal; the refresh token stays)
 *   POST api.amazon.com/auth/deregister  (sign-out, best-effort)
 */
public final class AmazonAuthClient {

    private static final String TAG = "AmazonAuth";
    static final String REGISTER_URL = "https://api.amazon.com/auth/register";
    static final String REFRESH_URL = "https://api.amazon.com/auth/token";
    static final String DEREGISTER_URL = "https://api.amazon.com/auth/deregister";
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
    static final String APP_NAME = "AGSLauncher for Windows";
    static final String APP_VERSION = "1.0.0";
    static final String DEVICE_TYPE = "A2UMVHOX7UP4V7";
    static final String OS_VERSION = "10.0.19044.0";

    public static final class RegisterResult {
        public String accessToken;
        public String refreshToken;
        public long expiresIn;
        public String name;
    }

    private AmazonAuthClient() {}

    /** Exchanges the PKCE authorization code for bearer tokens; null on failure. */
    public static RegisterResult registerDevice(String authCode, String codeVerifier, String deviceSerial, String clientId) {
        try {
            JSONObject authData = new JSONObject();
            authData.put("authorization_code", authCode);
            authData.put("client_domain", "DeviceLegacy");
            authData.put("client_id", clientId);
            authData.put("code_algorithm", "SHA-256");
            authData.put("code_verifier", codeVerifier);
            authData.put("use_global_authentication", false);
            JSONObject regData = new JSONObject();
            regData.put("app_name", APP_NAME);
            regData.put("app_version", APP_VERSION);
            regData.put("device_model", "Windows");
            regData.put("device_name", JSONObject.NULL);
            regData.put("device_serial", deviceSerial);
            regData.put("device_type", DEVICE_TYPE);
            regData.put("domain", "Device");
            regData.put("os_version", OS_VERSION);
            JSONArray tokenTypes = new JSONArray().put("bearer").put("mac_dms");
            JSONArray extensions = new JSONArray().put("customer_info").put("device_info");
            JSONObject body = new JSONObject();
            body.put("auth_data", authData);
            body.put("registration_data", regData);
            body.put("requested_extensions", extensions);
            body.put("requested_token_type", tokenTypes);
            body.put("user_context_map", new JSONObject());
            String response = postJson(REGISTER_URL, body.toString(), null, null, null);
            if (response == null) return null;
            JSONObject success = new JSONObject(response).getJSONObject("response").getJSONObject("success");
            JSONObject bearer = success.getJSONObject("tokens").getJSONObject("bearer");
            RegisterResult result = new RegisterResult();
            result.accessToken = bearer.getString("access_token");
            result.refreshToken = bearer.getString("refresh_token");
            result.expiresIn = bearer.optLong("expires_in", 3600L);
            JSONObject customer = success.optJSONObject("extensions") != null ? success.getJSONObject("extensions").optJSONObject("customer_info") : null;
            result.name = customer != null ? customer.optString("name", "") : "";
            return result;
        } catch (Exception e) {
            Log.e(TAG, "registerDevice failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** A new access token; the refresh token is not returned and stays as it was. */
    public static RegisterResult refreshAccessToken(String refreshToken) {
        try {
            JSONObject body = new JSONObject();
            body.put("source_token", refreshToken);
            body.put("source_token_type", "refresh_token");
            body.put("requested_token_type", "access_token");
            body.put("app_name", APP_NAME);
            body.put("app_version", APP_VERSION);
            String response = postJson(REFRESH_URL, body.toString(), null, "x-amzn-identity-auth-domain", "api.amazon.com");
            if (response == null) return null;
            JSONObject json = new JSONObject(response);
            RegisterResult result = new RegisterResult();
            result.accessToken = json.getString("access_token");
            result.refreshToken = refreshToken;
            result.expiresIn = json.optLong("expires_in", 3600L);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "refresh failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    public static void deregisterDevice(String accessToken) {
        try {
            JSONObject body = new JSONObject().put("requested_extensions", new JSONArray().put("device_info").put("customer_info"));
            postJson(DEREGISTER_URL, body.toString(), accessToken, null, null);
        } catch (Exception e) {
            Log.w(TAG, "deregister failed (ignored): " + e.getClass().getSimpleName());
        }
    }

    static String postJson(String urlStr, String body, String bearerToken, String extraKey, String extraVal) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            if (bearerToken != null) conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            if (extraKey != null) conn.setRequestProperty(extraKey, extraVal);
            try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
            int code = conn.getResponseCode();
            String resp = readStream(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            conn.disconnect();
            if (code < 200 || code >= 300) { Log.e(TAG, "HTTP " + code + " from " + StoreLog.redactUrl(urlStr)); return null; }
            return resp;
        } catch (Exception e) {
            Log.e(TAG, "POST failed: " + StoreLog.redactUrl(urlStr) + " (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    static String getRequest(String urlStr, String token, String tokenHeader, String extraKey, String extraVal) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            if (token != null && tokenHeader != null) conn.setRequestProperty(tokenHeader, token);
            if (extraKey != null) conn.setRequestProperty(extraKey, extraVal);
            int code = conn.getResponseCode();
            String resp = readStream(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            conn.disconnect();
            if (code < 200 || code >= 300) { Log.e(TAG, "GET HTTP " + code + " from " + StoreLog.redactUrl(urlStr)); return null; }
            return resp;
        } catch (Exception e) {
            Log.e(TAG, "GET failed: " + StoreLog.redactUrl(urlStr) + " (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
