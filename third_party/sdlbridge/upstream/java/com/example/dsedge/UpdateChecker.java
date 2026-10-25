package com.example.dsedge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks the GitHub Releases of the repo this APK was built in (CI writes assets/build.txt).
 * The repo must be public for the unauthenticated GitHub API to see its releases.
 */
final class UpdateChecker {
    interface Callback {
        /** url is non-null only when a newer build exists. Called on the main thread. */
        void onResult(String message, String url);
    }

    static final class BuildInfo {
        String repo = "UNKNOWN", tag = "dev", sdl = "unknown";
    }

    private UpdateChecker() {}

    static BuildInfo readBuildInfo(Context ctx) {
        BuildInfo b = new BuildInfo();
        // Built into another app (SDL3DroidDeck), the bridge's build info has its own name next to that app's assets.
        try (InputStream in = ctx.getAssets().open(Host.embedded(ctx) ? "sdl3bridge-build.txt" : "build.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                if (k.equals("repo")) b.repo = v;
                else if (k.equals("tag")) b.tag = v;
                else if (k.equals("sdl")) b.sdl = v;
            }
        } catch (Exception ignored) { }
        return b;
    }

    static void check(final Context ctx, final Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        final BuildInfo cur = readBuildInfo(ctx);
        new Thread(() -> {
            String msg;
            String url = null;
            if (Host.embedded(ctx)) {
                msg = "Built into " + Host.appName(ctx) + ": it updates together with it.";
            } else if (cur.repo.equals("UNKNOWN")) {
                msg = "Updates are off: this APK was not built by the GitHub workflow.";
            } else {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(
                            "https://api.github.com/repos/" + cur.repo + "/releases/latest").openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    int code = c.getResponseCode();
                    if (code == 404) {
                        msg = "No release found. The repo must be public and have a finished build.";
                    } else if (code != 200) {
                        msg = "Update check failed (HTTP " + code + ").";
                    } else {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                            String line;
                            while ((line = r.readLine()) != null) sb.append(line);
                        }
                        JSONObject j = new JSONObject(sb.toString());
                        String latest = j.optString("tag_name", "");
                        String notes = j.optString("body", "");
                        String sdl = "";
                        int i = notes.indexOf("sdl=");
                        if (i >= 0) sdl = notes.substring(i + 4).split("\\s")[0];
                        String apk = null;
                        JSONArray assets = j.optJSONArray("assets");
                        for (int k = 0; assets != null && k < assets.length(); k++) {
                            JSONObject a = assets.getJSONObject(k);
                            if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break; }
                        }
                        if (apk != null && newer(latest, cur.tag)) {
                            msg = "Update available: " + latest + (sdl.isEmpty() ? "" : " (SDL " + sdl + ")") + ". Tap to download.";
                            url = apk;
                        } else {
                            msg = "Up to date (" + cur.tag + ", SDL " + cur.sdl + ").";
                        }
                    }
                } catch (Exception e) {
                    msg = "Update check failed: " + e.getClass().getSimpleName();
                }
            }
            final String m = msg;
            final String u = url;
            main.post(() -> cb.onResult(m, u));
        }, "update-check").start();
    }

    /** Tags look like "build-12". Anything unparsable on our side ("dev") counts as older. */
    private static boolean newer(String latest, String current) {
        long a = number(latest), b = number(current);
        return a > b;
    }

    private static long number(String tag) {
        try { return Long.parseLong(tag.replaceAll("[^0-9]", "")); } catch (NumberFormatException e) { return -1; }
    }
}
