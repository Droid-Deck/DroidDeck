package com.droiddeck.launcher.stores.epic;

import android.util.Base64;
import android.util.Log;

import com.droiddeck.launcher.stores.StoreLog;

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
 * Epic's OAuth2 token endpoint, with the launcher's public client credentials (the ones every
 * third-party Epic client uses):
 *   POST account-public-service-prod03.ol.epicgames.com/account/api/oauth/token
 *     Basic base64(clientId:clientSecret), form grant_type=authorization_code|refresh_token, token_type=eg1
 *   GET  .../account/api/oauth/exchange with a bearer token: a short-lived exchange code for a launch.
 * Error bodies are never logged: they carry correlation ids and account context.
 */
public final class EpicAuthClient {

    private static final String TAG = "EpicAuth";

    static final String CLIENT_ID = "34a02cf8f4414e29b15921876da36f9a";
    static final String CLIENT_SECRET = "daafbccc737745039dffe53d94fc76cf";
    private static final String TOKEN_URL = "https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/token";
    private static final String EXCHANGE_URL = "https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/exchange";
    public static final String USER_AGENT = "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit";

    public static final class TokenResult {
        public String accessToken;
        public String refreshToken;
        public String accountId;
        public String displayName;
        public long expiresAt;
    }

    private EpicAuthClient() {}

    public static TokenResult exchangeCode(String authCode) {
        return postToken("grant_type=authorization_code&code=" + authCode + "&token_type=eg1");
    }

    public static TokenResult refreshToken(String refreshToken) {
        return postToken("grant_type=refresh_token&refresh_token=" + refreshToken + "&token_type=eg1");
    }

    private static TokenResult postToken(String formBody) {
        try {
            String creds64 = Base64.encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            HttpURLConnection conn = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Basic " + creds64);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] bodyBytes = formBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            try (OutputStream os = conn.getOutputStream()) { os.write(bodyBytes); }
            int code = conn.getResponseCode();
            InputStream is = (code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readStream(is);
            conn.disconnect();
            if (code < 200 || code >= 300) { Log.e(TAG, "token HTTP " + code); return null; }
            JSONObject json = new JSONObject(resp);
            TokenResult result = new TokenResult();
            result.accessToken = json.optString("access_token", null);
            result.refreshToken = json.optString("refresh_token", null);
            result.accountId = json.optString("account_id", "");
            result.displayName = json.optString("displayName", "");
            long expiresIn = json.optLong("expires_in", 7200L);
            String expiresAtStr = json.optString("expires_at", null);
            long expiresAtMs;
            if (expiresAtStr != null && !expiresAtStr.isEmpty()) {
                try { expiresAtMs = parseIso8601(expiresAtStr); } catch (Exception e) { expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L; }
            } else expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L;
            result.expiresAt = expiresAtMs;
            return result.accessToken != null ? result : null;
        } catch (Exception e) {
            Log.e(TAG, "token request failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** A short-lived exchange code for a game launch (-AUTH_PASSWORD); null on any failure. */
    public static String getExchangeCode(String accessToken) {
        try {
            String resp = getRequest(EXCHANGE_URL, accessToken);
            // getRequest has logged the HTTP status; the code itself is never logged.
            if (resp == null) { Log.i(TAG, "exchange code: no answer"); return null; }
            return new JSONObject(resp).optString("code", null);
        } catch (Exception e) {
            Log.e(TAG, "exchange code failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Authorized GET; the body or null. */
    public static String getRequest(String urlStr, String accessToken) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            int code = conn.getResponseCode();
            InputStream is = (code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readStream(is);
            if (code < 200 || code >= 300) { Log.e(TAG, "GET HTTP " + code + " from " + StoreLog.redactUrl(urlStr)); return null; }
            return resp;
        } catch (Exception e) {
            Log.e(TAG, "GET failed: " + StoreLog.redactUrl(urlStr) + " (" + e.getClass().getSimpleName() + ")");
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Authorized GET of raw bytes (the manifest binary). */
    public static byte[] getBytes(String urlStr, String accessToken) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            if (accessToken != null) conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) { Log.e(TAG, "bytes HTTP " + code + " from " + StoreLog.redactUrl(urlStr)); return null; }
            return readAllBytes(conn.getInputStream());
        } catch (Exception e) {
            Log.e(TAG, "bytes failed: " + StoreLog.redactUrl(urlStr) + " (" + e.getClass().getSimpleName() + ")");
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static byte[] readAllBytes(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[131072];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
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

    /** "2026-03-29T12:00:00.000Z" to epoch millis, without java.time. */
    private static long parseIso8601(String s) {
        int year = Integer.parseInt(s.substring(0, 4));
        int month = Integer.parseInt(s.substring(5, 7));
        int day = Integer.parseInt(s.substring(8, 10));
        int hour = Integer.parseInt(s.substring(11, 13));
        int minute = Integer.parseInt(s.substring(14, 16));
        int second = Integer.parseInt(s.substring(17, 19));
        long jd = 367L * year - (7 * (year + (month + 9) / 12)) / 4 + (275 * month) / 9 + day + 1721013L;
        long epochDays = jd - 2440588L;
        return epochDays * 86400000L + hour * 3600000L + minute * 60000L + second * 1000L;
    }
}
