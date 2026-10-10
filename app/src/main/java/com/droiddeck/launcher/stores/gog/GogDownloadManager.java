package com.droiddeck.launcher.stores.gog;

import android.content.Context;
import android.util.Log;

import com.droiddeck.launcher.stores.StoreLog;
import com.droiddeck.launcher.stores.download.StoreDownloadTier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * The GOG install pipeline, ported from Bannerlator's GogDownloadManager and made blocking: the
 * download queue runs {@link #install} on its own thread and reads progress through {@link Callback}.
 *
 * Gen 2: builds → build manifest → depot manifests (base product, matching language) → secure
 * link → per file: chunks fetched, inflated, MD5-checked, assembled (the native engine when it is
 * there, else the Java pool here). Gen 1: build manifest → per-file Range GET. Neither available:
 * the product's Windows installer is downloaded as a file to run once.
 *
 * Resume and repair come from the per-file size + MD5 check: a complete file is skipped, a damaged
 * one re-fetched. Nothing here writes outside the install folder; URLs are only logged redacted.
 */
public final class GogDownloadManager {

    private static final String TAG = "GogDownload";
    private static final int TIMEOUT = 30_000;
    private static final String MARKER = "_gog_manifest.json";

    /** Progress out of a running install. Every method is called on the install's thread. */
    public interface Callback {
        /** A new phase or file, as text, with the install's overall percentage. */
        void onProgress(String message, int pct);
        /** Bytes landed against the planned total, and the current speed (0 = unknown). */
        default void onBytes(long done, long total, long speedBps) {}
        /** A diagnostic line for the engine log. */
        default void onLog(String line) {}
        /** What this run fetches and what the game takes on disk. */
        default void onSizes(long downloadBytes, long diskBytes) {}
        /**
         * The active stage's own count: {@code stage} is "verify" or "install", {@code done} of
         * {@code total} an amount (bytes, or items when there are no bytes), {@code items} of
         * {@code itemsTotal} the files, when it counts files.
         */
        default void onStage(String stage, long done, long total, int items, int itemsTotal) {}

    }

    /** What an install leaves behind for the sidecar. */
    public static final class Result {
        /** The chosen exe, relative to the install folder (forward slashes), or "" when none was found. */
        public final String exeRelative;
        public final String buildId;
        public final long bytes;
        /** The build manifest's "dependencies" (GOG's redistributable ids: MSVC2017_x64, DirectX...), for Windows components. */
        public List<String> dependencies = new ArrayList<>();
        Result(String exeRelative, String buildId, long bytes) { this.exeRelative = exeRelative; this.buildId = buildId; this.bytes = bytes; }
    }

    /** Thrown for every way an install can fail; the message is what the Downloads page shows. */
    public static final class InstallException extends Exception {
        InstallException(String message) { super(message); }
    }

    private GogDownloadManager() {}

    /**
     * Installs {@code game} into {@code installPath}. Blocks until done; {@code cancelled} set from
     * another thread stops it at the next file boundary (what was written stays for a resume).
     * Returns null when cancelled.
     */
    public static Result install(Context ctx, GogGame game, File installPath, Callback cb, AtomicBoolean cancelled) throws InstallException {
        cb.onProgress("Checking sign-in…", 0);
        String token = GogAuth.validToken(ctx);
        if (token == null) throw new InstallException("Not signed in to GOG");
        if (cancelled.get()) return null;
        cb.onProgress("Fetching builds…", 2);

        String buildsUrl = "https://content-system.gog.com/products/" + game.gameId + "/os/windows/builds?generation=2";
        String buildsJson = httpGet(buildsUrl, null);
        if (buildsJson == null) buildsJson = httpGet(buildsUrl, token);
        if (buildsJson != null) {
            Gen2Outcome out = runGen2(ctx, game, token, buildsJson, installPath, cb, cancelled);
            if (out.result != null || cancelled.get()) return out.result;
            if (out.hardStop) throw new InstallException(out.error);
            cb.onLog("gen2 unavailable: " + out.error);
        }
        if (cancelled.get()) return null;
        cb.onProgress("Trying the older build format…", 10);
        String builds1Url = "https://content-system.gog.com/products/" + game.gameId + "/os/windows/builds?generation=1";
        String builds1Json = httpGet(builds1Url, null);
        if (builds1Json == null) builds1Json = httpGet(builds1Url, token);
        if (builds1Json == null) throw new InstallException("No builds available for this game");
        Gen1Outcome one = runGen1(ctx, game, token, builds1Json, installPath, cb, cancelled);
        if (one.result != null || cancelled.get()) return one.result;
        if (!"NO_CS_BUILDS".equals(one.error)) throw new InstallException("Download failed: " + one.error);
        cb.onProgress("No Galaxy builds; fetching the installer…", 12);
        return runInstaller(ctx, game, token, installPath, cb, cancelled);
    }

    // ---- gen2 -----------------------------------------------------------------------------------

    private static final class Gen2Outcome {
        Result result; String error; boolean hardStop;
    }

    private static Gen2Outcome runGen2(Context ctx, GogGame game, String token, String buildsJson, File installPath, Callback cb, AtomicBoolean cancelled) {
        Gen2Outcome out = new Gen2Outcome();
        try {
            cb.onProgress("Fetching manifest…", 5);
            Gen2Manifest gm = resolveGen2Manifest(buildsJson, token, cb);
            if (gm == null) { out.error = "gen2 manifest unavailable"; return out; }
            JSONObject manifest = gm.manifest;
            JSONArray depots = manifest.optJSONArray("depots");
            if (depots == null) { out.error = "no depots in manifest"; return out; }
            JSONArray products = manifest.optJSONArray("products");
            String tempExe = null;
            if (products != null && products.length() > 0) {
                tempExe = products.getJSONObject(0).optString("temp_executable", null);
                if (tempExe != null && tempExe.isEmpty()) tempExe = null;
            }
            // The build manifest lists every product's depots (base + DLC), each tagged by productId;
            // the base install takes the base product's only, under a secure link scoped to it.
            String baseProductId = manifest.optString("baseProductId", null);
            if ((baseProductId == null || baseProductId.isEmpty()) && products != null && products.length() > 0)
                baseProductId = products.getJSONObject(0).optString("productId", null);
            if (baseProductId == null || baseProductId.isEmpty()) baseProductId = game.gameId;
            // The build's required redistributables (MSVC, DirectX, ...): logged, and kept in the
            // sidecar, where Windows components take them as this game's list (WinCompSources).
            JSONArray deps = manifest.optJSONArray("dependencies");
            List<String> dependencies = new ArrayList<>();
            if (deps != null) for (int i = 0; i < deps.length(); i++) { String d = deps.optString(i, ""); if (!d.isEmpty()) dependencies.add(d); }
            if (!dependencies.isEmpty()) cb.onLog("gog: build lists dependencies " + deps);

            cb.onProgress("Reading depot manifests…", 10);
            List<DepotFile> files = new ArrayList<>();
            List<String> depotJsons = new ArrayList<>();
            for (int i = 0; i < depots.length(); i++) {
                if (cancelled.get()) return out;
                JSONObject depot = depots.getJSONObject(i);
                String depotPid = depot.optString("productId", "");
                if (!depotPid.isEmpty() && !depotPid.equals(baseProductId)) continue;
                if (!languageCompatible(depot)) continue;
                String manifestHash = depot.optString("manifest");
                if (manifestHash == null || manifestHash.isEmpty()) continue;
                byte[] dmRaw = fetchBytes("https://gog-cdn-fastly.gog.com/content-system/v2/meta/" + buildCdnPath(manifestHash), null);
                if (dmRaw == null) { cb.onLog("gog: depot " + i + " meta fetch failed"); continue; }
                String dmStr = decompressBytes(dmRaw);
                if (dmStr == null) { cb.onLog("gog: depot " + i + " decompress failed"); continue; }
                parseDepotManifest(dmStr, files);
                depotJsons.add(dmStr);
            }
            if (files.isEmpty()) { out.error = "no depot files collected"; return out; }

            cb.onProgress("Fetching CDN link…", 15);
            String secureLinkUrl = "https://content-system.gog.com/products/" + baseProductId + "/secure_link?_version=2&generation=2&path=/";
            String cdnBase = parseCdnUrl(httpGet(secureLinkUrl, token));
            if (cdnBase == null) { out.error = "secure link refused"; return out; }

            installPath.mkdirs();
            long plannedBytes = 0;
            for (DepotFile df : files) plannedBytes += df.totalSize;
            if (plannedBytes > 0) {
                long usable = installPath.getUsableSpace();
                if (usable > 0 && plannedBytes > usable) {
                    out.hardStop = true;
                    out.error = "Not enough free space: need " + formatBytes(plannedBytes) + ", only " + formatBytes(usable) + " free";
                    return out;
                }
            }
            // Largest first: the biggest archives start first and small files fill the tail.
            Collections.sort(files, (a, b) -> Long.compare(b.totalSize, a.totalSize));

            final int total = files.size();
            final long planned = plannedBytes;
            final AtomicInteger doneCount = new AtomicInteger(0);
            final AtomicLong totalBytes = new AtomicLong(0);
            final AtomicLong lastSpeedMs = new AtomicLong(System.currentTimeMillis());
            final AtomicLong lastSpeedB = new AtomicLong(0);
            final AtomicLong speedBps = new AtomicLong(0);
            final AtomicBoolean anyFailed = new AtomicBoolean(false);
            final AtomicReference<String> cdnBaseRef = new AtomicReference<>(cdnBase);
            final AtomicInteger cdnRefreshCount = new AtomicInteger(0);
            final int MAX_CDN_REFRESH = 5;
            cb.onSizes(planned, planned);
            cb.onBytes(0, planned, 0);
            cb.onLog("gog: " + total + " files, " + formatBytes(planned) + ", base=" + baseProductId);

            int threads = downloadThreads();
            if (GogNative.isAvailable()) {
                runNativeEngine(ctx, GogNative.KIND_GEN2_CHUNKS, "Verified…", depotJsons, installPath, cdnBaseRef, secureLinkUrl, token,
                        cdnRefreshCount, MAX_CDN_REFRESH, threads, threads, true, "gog base=" + baseProductId,
                        cancelled, anyFailed, doneCount, total, planned, totalBytes, lastSpeedMs, lastSpeedB, speedBps, cb);
            } else {
                cb.onLog("gog: engine=built-in (" + threads + " threads)");
                ExecutorService pool = Executors.newFixedThreadPool(threads, com.droiddeck.launcher.stores.download.DownloadQueue.workerFactory("gog-dl"));
                List<Future<Void>> futures = new ArrayList<>();
                for (DepotFile df : files) {
                    futures.add(pool.submit((Callable<Void>) () -> {
                        if (cancelled.get() || anyFailed.get()) return null;
                        File outFile = new File(installPath, df.relativePath);
                        outFile.getParentFile().mkdirs();
                        if (fileVerified(outFile, df.totalSize, df.md5)) {
                            int done = doneCount.incrementAndGet();
                            long tb = totalBytes.addAndGet(df.totalSize);
                            cb.onProgress("Verified…", 15 + (int) ((done / (float) total) * 80));
                            cb.onBytes(tb, planned, speedBps.get());
                            return null;
                        }
                        boolean ok = assembleDepotFile(df, outFile, cdnBaseRef, secureLinkUrl, token, cdnRefreshCount, MAX_CDN_REFRESH, cancelled, cb);
                        if (cancelled.get()) return null;
                        if (!ok) { cb.onLog("FAIL file=" + df.relativePath); anyFailed.set(true); return null; }
                        int done = doneCount.incrementAndGet();
                        long tb = totalBytes.addAndGet(df.totalSize);
                        sampleSpeed(tb, lastSpeedMs, lastSpeedB, speedBps);
                        cb.onProgress("Downloading: " + baseName(df.relativePath) + speedSuffix(speedBps.get()), 15 + (int) ((done / (float) total) * 80));
                        cb.onBytes(tb, planned, speedBps.get());
                        return null;
                    }));
                }
                pool.shutdown();
                try {
                    for (Future<Void> f : futures) f.get();
                } catch (Exception e) {
                    pool.shutdownNow();
                    out.error = "download pool error: " + e.getClass().getSimpleName();
                    return out;
                }
            }
            if (cancelled.get()) return out;
            if (anyFailed.get()) { out.error = "one or more chunks failed to download"; return out; }

            cb.onProgress("Finishing…", 96);
            writeFile(new File(installPath, MARKER), ("{\"gameId\":\"" + game.gameId + "\",\"buildId\":\"" + gm.buildId + "\"}").getBytes("UTF-8"));
            deleteCounted(new File(installPath, ".gog_chunks"), cb);
            String clientId = manifest.optString("clientId", null);
            if (clientId != null && !clientId.isEmpty()) GogPrefs.get(ctx).edit().putString("client_id_" + game.gameId, clientId).apply();
            // The game's own client secret: cloud saves need a token issued to the game's client.
            String clientSecret = manifest.optString("clientSecret", null);
            if (clientSecret != null && !clientSecret.isEmpty()) GogPrefs.get(ctx).edit().putString("client_secret_" + game.gameId, clientSecret).apply();
            out.result = new Result(pickExe(installPath, tempExe, game.title), gm.buildId, planned);
            out.result.dependencies = dependencies;
            return out;
        } catch (Exception e) {
            out.error = "exception: " + e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return out;
        }
    }

    private static final class Gen2Manifest {
        final JSONObject manifest; final String buildId;
        Gen2Manifest(JSONObject manifest, String buildId) { this.manifest = manifest; this.buildId = buildId; }
    }

    /** The first Windows build's manifest, inflated and parsed; null on any failure. */
    private static Gen2Manifest resolveGen2Manifest(String buildsJson, String token, Callback cb) {
        try {
            JSONArray items = new JSONObject(buildsJson).optJSONArray("items");
            if (items == null || items.length() == 0) { cb.onLog("gog: no gen2 builds"); return null; }
            String buildId = null, manifestUrl = null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if ("windows".equals(item.optString("os"))) {
                    buildId = item.optString("build_id");
                    manifestUrl = item.optString("link");
                    if (manifestUrl == null || manifestUrl.isEmpty()) manifestUrl = item.optString("meta_url");
                    break;
                }
            }
            if (buildId == null || manifestUrl == null || manifestUrl.isEmpty()) { cb.onLog("gog: no windows build"); return null; }
            byte[] raw = fetchBytes(manifestUrl, token);
            if (raw == null) { cb.onLog("gog: manifest fetch failed"); return null; }
            String str = decompressBytes(raw);
            if (str == null) { cb.onLog("gog: manifest decompress failed"); return null; }
            return new Gen2Manifest(new JSONObject(str), buildId);
        } catch (Exception e) {
            cb.onLog("gog: manifest " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** English or every-language depots; GOG tags the rest by language code. */
    private static boolean languageCompatible(JSONObject depot) {
        JSONArray languages = depot.optJSONArray("languages");
        if (languages == null || languages.length() == 0) return true;
        String s = languages.toString();
        return s.contains("*") || s.contains("en-US") || s.contains("\"en\"") || s.contains("english");
    }

    // ---- gen1 -----------------------------------------------------------------------------------

    private static final class Gen1Outcome { Result result; String error; }

    private static Gen1Outcome runGen1(Context ctx, GogGame game, String token, String buildsJson, File installPath, Callback cb, AtomicBoolean cancelled) {
        Gen1Outcome out = new Gen1Outcome();
        try {
            JSONObject builds = new JSONObject(buildsJson);
            JSONArray items = builds.optJSONArray("items");
            if (items == null || items.length() == 0) { out.error = builds.optInt("total_count", -1) == 0 ? "NO_CS_BUILDS" : "no items"; return out; }
            String manifestUrl = null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if ("windows".equals(item.optString("os"))) { manifestUrl = item.optString("link"); break; }
            }
            if (manifestUrl == null || manifestUrl.isEmpty()) { out.error = "no windows manifest"; return out; }
            cb.onProgress("Fetching manifest…", 12);
            byte[] raw = fetchBytes(manifestUrl, token);
            if (raw == null) { out.error = "manifest fetch failed"; return out; }
            String manifestStr = decompressBytes(raw);
            if (manifestStr == null) { out.error = "manifest decompress failed"; return out; }
            JSONObject manifest = new JSONObject(manifestStr);
            JSONArray depots = manifest.optJSONArray("depot");
            if (depots == null) { out.error = "no depot array"; return out; }
            List<Gen1File> files = new ArrayList<>();
            for (int i = 0; i < depots.length(); i++) {
                JSONObject depot = depots.getJSONObject(i);
                if (depot.optBoolean("support", false)) continue;
                JSONArray jFiles = depot.optJSONArray("files");
                if (jFiles == null) continue;
                for (int j = 0; j < jFiles.length(); j++) {
                    JSONObject f = jFiles.getJSONObject(j);
                    String path = f.optString("path"), url = f.optString("url");
                    long size = f.optLong("size", 0);
                    if (path == null || url == null || size == 0) continue;
                    files.add(new Gen1File(path, url, f.optLong("offset", 0), size));
                }
            }
            if (files.isEmpty()) { out.error = "no files in manifest"; return out; }
            installPath.mkdirs();
            long planned = 0;
            for (Gen1File gf : files) planned += gf.size;
            final int total = files.size();
            final long plannedBytes = planned;
            final AtomicInteger done = new AtomicInteger(0);
            final AtomicLong totalBytes = new AtomicLong(0);
            final AtomicLong lastSpeedMs = new AtomicLong(System.currentTimeMillis());
            final AtomicLong lastSpeedB = new AtomicLong(0);
            final AtomicLong speedBps = new AtomicLong(0);
            final AtomicBoolean anyFailed = new AtomicBoolean(false);
            cb.onSizes(planned, planned);
            cb.onBytes(0, planned, 0);
            int threads = downloadThreads();
            if (GogNative.isAvailable()) {
                runNativeEngine(ctx, GogNative.KIND_GEN1_RANGES, "Resuming…", Collections.singletonList(manifestStr), installPath,
                        new AtomicReference<>(""), null, null, new AtomicInteger(0), 0, threads, threads, false, "gog gen1=" + game.gameId,
                        cancelled, anyFailed, done, total, planned, totalBytes, lastSpeedMs, lastSpeedB, speedBps, cb);
            } else {
                ExecutorService pool = Executors.newFixedThreadPool(threads, com.droiddeck.launcher.stores.download.DownloadQueue.workerFactory("gog-dl"));
                List<Future<Void>> futures = new ArrayList<>();
                for (Gen1File gf : files) {
                    futures.add(pool.submit((Callable<Void>) () -> {
                        if (cancelled.get() || anyFailed.get()) return null;
                        File outFile = new File(installPath, gf.path);
                        outFile.getParentFile().mkdirs();
                        if (outFile.exists() && outFile.length() == gf.size) {
                            cb.onProgress("Resuming…", 15 + (int) ((done.incrementAndGet() / (float) total) * 80));
                            cb.onBytes(totalBytes.addAndGet(gf.size), plannedBytes, speedBps.get());
                            return null;
                        }
                        for (int attempt = 1; attempt <= 3; attempt++) {
                            if (cancelled.get()) return null;
                            outFile.delete();
                            if (downloadRange(gf.url, gf.offset, gf.size, outFile)) {
                                int d = done.incrementAndGet();
                                long tb = totalBytes.addAndGet(gf.size);
                                sampleSpeed(tb, lastSpeedMs, lastSpeedB, speedBps);
                                cb.onProgress("Downloading: " + baseName(gf.path) + speedSuffix(speedBps.get()), 15 + (int) ((d / (float) total) * 80));
                                cb.onBytes(tb, plannedBytes, speedBps.get());
                                return null;
                            }
                            if (attempt < 3) Thread.sleep(1000L << (attempt - 1));
                        }
                        cb.onLog("FAIL file=" + gf.path);
                        anyFailed.set(true);
                        return null;
                    }));
                }
                pool.shutdown();
                try { for (Future<Void> f : futures) f.get(); } catch (Exception e) { pool.shutdownNow(); out.error = "gen1 pool error"; return out; }
            }
            if (cancelled.get()) return out;
            if (anyFailed.get()) { out.error = "one or more files failed to download"; return out; }
            cb.onProgress("Finishing…", 96);
            writeFile(new File(installPath, MARKER), ("{\"gameId\":\"" + game.gameId + "\",\"generation\":1}").getBytes("UTF-8"));
            out.result = new Result(pickExe(installPath, null, game.title), "", planned);
            return out;
        } catch (Exception e) {
            out.error = "exception: " + e.getClass().getSimpleName();
            return out;
        }
    }

    // ---- installer fallback ---------------------------------------------------------------------

    /** Games with no content-system builds: the Windows installer, as one file, to run once in Proton. */
    private static Result runInstaller(Context ctx, GogGame game, String token, File installPath, Callback cb, AtomicBoolean cancelled) throws InstallException {
        try {
            String productJson = httpGet("https://api.gog.com/products/" + game.gameId + "?expand=downloads", token);
            if (productJson == null) throw new InstallException("No downloadable builds for this game");
            JSONObject downloads = new JSONObject(productJson).optJSONObject("downloads");
            JSONArray installers = downloads == null ? null : downloads.optJSONArray("installers");
            if (installers == null || installers.length() == 0) throw new InstallException("No downloadable builds for this game");
            String manualUrl = null, fileName = null;
            for (int i = 0; i < installers.length(); i++) {
                JSONObject inst = installers.getJSONObject(i);
                if (!"windows".equals(inst.optString("os"))) continue;
                JSONArray files = inst.optJSONArray("files");
                if (files != null && files.length() > 0) {
                    JSONObject f = files.getJSONObject(0);
                    manualUrl = f.optString("downlink", null);
                    if (manualUrl == null) manualUrl = f.optString("manualUrl", null);
                    fileName = f.optString("filename", null);
                }
                if (manualUrl == null) manualUrl = inst.optString("manualUrl", null);
                if (fileName == null || fileName.isEmpty()) fileName = "setup_" + game.gameId + ".exe";
                break;
            }
            if (manualUrl == null) throw new InstallException("No Windows installer for this game");
            String downloadUrl = resolveRedirect(manualUrl, token);
            if (downloadUrl == null) throw new InstallException("The installer link could not be resolved");
            installPath.mkdirs();
            File outFile = new File(installPath, fileName);
            cb.onProgress("Downloading installer: " + fileName, 15);
            long size = downloadWithProgress(downloadUrl, outFile, cb, cancelled);
            if (cancelled.get()) return null;
            if (size <= 0) throw new InstallException("The installer download failed");
            cb.onLog("gog: installer downloaded; the game installs itself on first launch");
            return new Result(fileName, "", size);
        } catch (InstallException e) {
            throw e;
        } catch (Exception e) {
            throw new InstallException("Installer download error: " + e.getClass().getSimpleName());
        }
    }

    private static String resolveRedirect(String url, String token) {
        try {
            if (url.startsWith("/")) url = "https://www.gog.com" + url;
            for (int hop = 0; hop < 5; hop++) {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(TIMEOUT); conn.setReadTimeout(TIMEOUT); conn.setInstanceFollowRedirects(false);
                if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
                int code = conn.getResponseCode();
                String location = conn.getHeaderField("Location");
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    conn.disconnect();
                    if (location == null) return null;
                    url = location.startsWith("/") ? "https://www.gog.com" + location : location;
                    continue;
                }
                if (code == 200) {
                    String ct = conn.getContentType();
                    if (ct != null && ct.contains("application/json")) {
                        String body = readAll(conn.getInputStream());
                        conn.disconnect();
                        String inner = new JSONObject(body).optString("downlink", null);
                        if (inner != null && !inner.isEmpty()) { url = inner; continue; }
                    } else conn.disconnect();
                    return url;
                }
                conn.disconnect();
                return null;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static long downloadWithProgress(String url, File out, Callback cb, AtomicBoolean cancelled) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT); conn.setReadTimeout(60_000);
            long total = conn.getContentLengthLong();
            long downloaded = 0;
            try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[131072];
                int n;
                long windowStart = System.currentTimeMillis(), windowBytes = 0, speed = 0;
                while ((n = is.read(buf)) != -1) {
                    if (cancelled.get()) return -1;
                    fos.write(buf, 0, n);
                    downloaded += n; windowBytes += n;
                    long elapsed = System.currentTimeMillis() - windowStart;
                    if (elapsed >= 500) { speed = windowBytes * 1000L / elapsed; windowStart = System.currentTimeMillis(); windowBytes = 0; }
                    if (total > 0) {
                        cb.onProgress("Downloading: " + out.getName() + speedSuffix(speed), 15 + (int) ((downloaded / (float) total) * 80));
                        cb.onBytes(downloaded, total, speed);
                    }
                }
            } finally {
                conn.disconnect();
            }
            return downloaded;
        } catch (Exception e) {
            Log.w(TAG, "installer download failed: " + e.getClass().getSimpleName());
            return -1;
        }
    }

    // ---- native engine --------------------------------------------------------------------------

    /**
     * Drives the native engine for one fetch loop, producing what the Java pool produces: the same
     * counters, progress strings and percentages. A secure-link expiry (the run dying on
     * 401/403/404/500) refreshes the link and re-runs for the files not yet done.
     */
    private static void runNativeEngine(Context ctx, int kind, String verifiedMsg, List<String> depotJsons, File installPath,
                                        AtomicReference<String> cdnBaseRef, String secureLinkUrl, String token,
                                        AtomicInteger cdnRefreshCount, int maxCdnRefresh, int maxWorkers, int processWorkers,
                                        boolean sortLargestFirst, String label, AtomicBoolean cancelled, AtomicBoolean anyFailed,
                                        AtomicInteger doneCount, int total, long planned, AtomicLong totalBytes,
                                        AtomicLong lastSpeedMs, AtomicLong lastSpeedB, AtomicLong speedBps, Callback cb) {
        StoreDownloadTier tier = StoreDownloadTier.Companion.current(ctx);
        int workers = Math.max(1, Math.min(128, tier.getNetworkWindow()));
        int process = Math.max(16, Math.max(processWorkers, tier.getProcessWorkers()));
        // The tier's window goes to the engine as its ceiling (Max = 96); the per-host spread is the engine's.
        cb.onLog("gog: plan kind=" + kind + " files=" + total + " bytes=" + planned + " window=" + workers + " process_workers=" + process + " tier=" + tier.getId() + " engine=native");
        final String[] manifests = depotJsons.toArray(new String[0]);
        final java.util.Set<String> donePaths = java.util.concurrent.ConcurrentHashMap.newKeySet();
        int run = 0;
        while (!cancelled.get()) {
            run++;
            final String base = cdnBaseRef.get();
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean okRef = new AtomicBoolean(false);
            final AtomicBoolean linkExpiryRef = new AtomicBoolean(false);
            final AtomicReference<String> errRef = new AtomicReference<>("");
            // The bar between file completions: what earlier runs counted, plus the files this run
            // skipped as already verified (the engine credits those no bytes), plus the engine's own
            // byte count, which includes the files still in flight. Never shown going back.
            final long runStart = totalBytes.get();
            final AtomicLong verifiedInRun = new AtomicLong(0);
            final AtomicLong shownBytes = new AtomicLong(runStart);
            GogNative.Listener listener = new GogNative.Listener() {
                @Override public void onProgress(long bytesDone, long bytesTotal, int filesDone, int filesTotal, String file, long fileBytes, boolean verified) {
                    donePaths.add(file);
                    int done = doneCount.incrementAndGet();
                    int pct = 15 + (int) ((done / (float) total) * 80);
                    long tb = totalBytes.addAndGet(fileBytes);
                    if (verified) verifiedInRun.addAndGet(fileBytes);
                    long shown = shownBytes.accumulateAndGet(tb, Math::max);
                    if (verified) { cb.onProgress(verifiedMsg, pct); cb.onBytes(shown, planned, speedBps.get()); return; }
                    sampleSpeed(shown, lastSpeedMs, lastSpeedB, speedBps);
                    cb.onProgress("Downloading: " + baseName(file) + speedSuffix(speedBps.get()), pct);
                    cb.onBytes(shown, planned, speedBps.get());
                }
                @Override public void onBytes(long bytesDone, long bytesTotal) {
                    long shown = shownBytes.accumulateAndGet(Math.min(planned, runStart + verifiedInRun.get() + bytesDone), Math::max);
                    sampleSpeed(shown, lastSpeedMs, lastSpeedB, speedBps);
                    cb.onBytes(shown, planned, speedBps.get());
                }
                @Override public void onLog(String line) { cb.onLog(line); }
                @Override public void onComplete(boolean success, boolean wasCancelled, boolean linkExpiry, String error, long bytesWritten, int filesDone) {
                    okRef.set(success); linkExpiryRef.set(linkExpiry); errRef.set(error == null ? "" : error); latch.countDown();
                }
            };
            // Every CDN of the set: the engine spreads its window across the hosts (JNI.md).
            String[] bases = (base == null || base.isEmpty()) ? new String[0] : base.split("\n");
            if (run == 1) cb.onLog("gog: " + bases.length + " CDN host(s) offered");
            long handle = GogNative.start(kind, manifests, bases, installPath.getAbsolutePath(), donePaths.toArray(new String[0]), "",
                    workers, process, sortLargestFirst, label + " run=" + run, listener);
            if (handle == 0L) { cb.onLog("gog: native start failed"); anyFailed.set(true); return; }
            boolean interrupted = false;
            try {
                while (true) {
                    try { if (latch.await(250, TimeUnit.MILLISECONDS)) break; } catch (InterruptedException ie) { interrupted = true; }
                    if (cancelled.get() || interrupted) GogNative.cancel(handle);
                }
            } finally {
                GogNative.release(handle);
            }
            if (interrupted) { Thread.currentThread().interrupt(); anyFailed.set(true); return; }
            if (cancelled.get() || okRef.get()) return;
            String err = errRef.get();
            if (linkExpiryRef.get() && tryRefreshCdn(cdnBaseRef, base, secureLinkUrl, token, cdnRefreshCount, maxCdnRefresh, cb)) {
                cb.onLog("gog: secure link expired (" + err + "); refreshed, running again for the remaining files");
                continue;
            }
            cb.onLog("gog: engine failed: " + err);
            anyFailed.set(true);
            return;
        }
    }

    // ---- gen2 helpers (shared with the Java pool) -----------------------------------------------

    private static void parseDepotManifest(String json, List<DepotFile> out) {
        try {
            JSONObject depotObj = new JSONObject(json).optJSONObject("depot");
            if (depotObj == null) return;
            JSONArray depot = depotObj.optJSONArray("items");
            if (depot == null) return;
            for (int i = 0; i < depot.length(); i++) {
                JSONObject entry = depot.getJSONObject(i);
                String path = entry.optString("path", "").replace("\\", "/");
                if (path.startsWith("/")) path = path.substring(1);
                JSONArray chunks = entry.optJSONArray("chunks");
                if (path.isEmpty() || chunks == null || chunks.length() == 0) continue;
                DepotFile df = new DepotFile(path);
                String fileMd5 = entry.optString("md5", null);
                if (fileMd5 != null && !fileMd5.isEmpty()) df.md5 = fileMd5;
                long fileTotal = 0;
                for (int c = 0; c < chunks.length(); c++) {
                    JSONObject chunk = chunks.getJSONObject(c);
                    String compressedMd5 = chunk.optString("compressedMd5"), decMd5 = chunk.optString("md5");
                    String hash = (compressedMd5 != null && !compressedMd5.isEmpty()) ? compressedMd5 : decMd5;
                    if (hash == null || hash.isEmpty()) continue;
                    long decSize = chunk.optLong("size", 0);
                    df.chunks.add(new DepotFile.ChunkRef(hash, compressedMd5, decMd5, chunk.optLong("compressedSize", 0), decSize));
                    fileTotal += decSize;
                }
                df.totalSize = fileTotal;
                if (!df.chunks.isEmpty()) out.add(df);
            }
        } catch (Exception e) {
            Log.w(TAG, "depot manifest parse: " + e.getClass().getSimpleName());
        }
    }

    static String buildCdnPath(String hash) { return hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/" + hash; }

    private static boolean assembleDepotFile(DepotFile df, File outFile, AtomicReference<String> cdnBaseRef, String secureLinkUrl, String token,
                                             AtomicInteger cdnRefreshCount, int maxCdnRefresh, AtomicBoolean cancelled, Callback cb) {
        File tmpFile = new File(outFile.getAbsolutePath() + ".bhtmp");
        File parent = outFile.getParentFile();
        if (parent != null) parent.mkdirs();
        tmpFile.delete();
        boolean ok = true;
        try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
            for (DepotFile.ChunkRef chunk : df.chunks) {
                if (cancelled.get()) { ok = false; break; }
                byte[] inflated = fetchChunkVerified(cdnBaseRef, buildCdnPath(chunk.hash), chunk, secureLinkUrl, token, cdnRefreshCount, maxCdnRefresh, cancelled, cb);
                if (inflated == null) { ok = false; break; }
                fos.write(inflated);
            }
        } catch (Exception e) {
            ok = false;
        }
        if (!ok || cancelled.get()) { tmpFile.delete(); return false; }
        if (df.totalSize > 0 && tmpFile.length() != df.totalSize) { cb.onLog("size mismatch file=" + df.relativePath); tmpFile.delete(); return false; }
        if (df.md5 != null && !df.md5.isEmpty()) {
            String actual = md5HexFile(tmpFile);
            if (actual == null || !actual.equalsIgnoreCase(df.md5)) { cb.onLog("md5 mismatch file=" + df.relativePath); tmpFile.delete(); return false; }
        }
        if (outFile.exists()) outFile.delete();
        return tmpFile.renameTo(outFile);
    }

    /**
     * Every CDN the secure link offers, as one value - their bases, one per line - so the set is
     * refreshed (and compared on expiry) as a whole. GOG answers with several hosts in {@code urls[]};
     * taking only the first left every request on one host.
     */
    private static String parseCdnUrl(String json) {
        java.util.List<String> all = parseCdnUrls(json);
        return all.isEmpty() ? null : String.join("\n", all);
    }

    /** The CDN bases of a secure_link answer, in the order GOG gives them. */
    static java.util.List<String> parseCdnUrls(String json) {
        java.util.List<String> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray urls = new JSONObject(json).optJSONArray("urls");
            if (urls == null) return out;
            for (int i = 0; i < urls.length(); i++) {
                JSONObject u = urls.optJSONObject(i);
                if (u == null) continue;
                String urlFormat = u.optString("url_format");
                JSONObject params = u.optJSONObject("parameters");
                if (urlFormat == null || urlFormat.isEmpty() || params == null) continue;
                java.util.Iterator<String> keys = params.keys();
                while (keys.hasNext()) { String k = keys.next(); urlFormat = urlFormat.replace("{" + k + "}", params.optString(k)); }
                urlFormat = urlFormat.replace("\\/", "/");
                int idx = urlFormat.indexOf("/{path}");
                if (idx >= 0) urlFormat = urlFormat.substring(0, idx);
                if (!out.contains(urlFormat)) out.add(urlFormat);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static final AtomicInteger CDN_TURN = new AtomicInteger();

    /** One of the set's CDNs, in turn, so the Java pool spreads its requests over all of them. */
    static String pickCdn(String set) {
        String[] all = set.split("\n");
        return all[Math.floorMod(CDN_TURN.getAndIncrement(), all.length)];
    }

    /** The first CDN of the set - what a caller that takes a single base gets. */
    static String firstCdn(String set) {
        int nl = set.indexOf('\n');
        return nl < 0 ? set : set.substring(0, nl);
    }

    private static boolean downloadRange(String url, long offset, long size, File out) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT); conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + size - 1));
            try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[131072];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            }
            conn.disconnect();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "range download failed: " + out.getName() + " (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    static String httpGet(String url, String token) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT); conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("User-Agent", "GOG Galaxy");
            if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
            if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }
            String body = readAll(conn.getInputStream());
            conn.disconnect();
            return body;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    static byte[] fetchBytes(String url, String token) { return fetchBytesEx(url, token).body; }

    /** gzip or zlib detected by magic, else the bytes as UTF-8. */
    static String decompressBytes(byte[] data) {
        if (data == null || data.length < 2) return null;
        try {
            int b0 = data[0] & 0xFF, b1 = data[1] & 0xFF;
            if (b0 == 0x1F && b1 == 0x8B) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = gzip.read(buf)) != -1) bos.write(buf, 0, n);
                }
                return bos.toString("UTF-8");
            }
            if (b0 == 0x78) {
                byte[] out = inflateZlib(data);
                return out == null ? null : new String(out, "UTF-8");
            }
            return new String(data, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] inflateZlib(byte[] data) {
        try {
            if (data == null || data.length < 2 || (data[0] & 0xFF) != 0x78) return null;
            Inflater inf = new Inflater();
            inf.setInput(data);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) { if (inf.needsInput() || inf.needsDictionary()) break; }
                bos.write(buf, 0, n);
            }
            inf.end();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static final class HttpResult {
        final byte[] body; final int status;
        HttpResult(byte[] body, int status) { this.body = body; this.status = status; }
    }

    private static HttpResult fetchBytesEx(String url, String token) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT); conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("User-Agent", "GOG Galaxy");
            if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
            int code = conn.getResponseCode();
            if (code != 200) { conn.disconnect(); return new HttpResult(null, code); }
            int len = conn.getContentLength();
            ByteArrayOutputStream bos = len > 0 ? new ByteArrayOutputStream(len) : new ByteArrayOutputStream();
            byte[] buf = new byte[131072];
            try (InputStream is = conn.getInputStream()) {
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            }
            conn.disconnect();
            return new HttpResult(bos.toByteArray(), code);
        } catch (Exception e) {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
            return new HttpResult(null, -1);
        }
    }

    /** The chunk path goes before the query string so the secure link's token survives. */
    private static String buildChunkUrl(String base, String chunkPath) {
        int q = base.indexOf('?');
        return q >= 0 ? base.substring(0, q) + "/" + chunkPath + base.substring(q) : base + "/" + chunkPath;
    }

    private static String md5Hex(byte[] data) {
        try { return toHex(java.security.MessageDigest.getInstance("MD5").digest(data)); } catch (Exception e) { return null; }
    }

    private static String md5HexFile(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[131072];
            int n;
            while ((n = fis.read(buf)) != -1) md.update(buf, 0, n);
            return toHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static String toHex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) { int v = b & 0xFF; if (v < 16) sb.append('0'); sb.append(Integer.toHexString(v)); }
        return sb.toString();
    }

    private static boolean fileVerified(File f, long expectedSize, String expectedMd5) {
        if (f == null || !f.exists() || f.length() == 0) return false;
        if (expectedSize > 0 && f.length() != expectedSize) return false;
        if (expectedMd5 != null && !expectedMd5.isEmpty()) {
            String actual = md5HexFile(f);
            return actual != null && actual.equalsIgnoreCase(expectedMd5);
        }
        return true;
    }

    private static void sleepBackoff(int attempt) {
        try { Thread.sleep(Math.min(1000L << Math.max(0, attempt - 1), 8000L)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static boolean tryRefreshCdn(AtomicReference<String> cdnBaseRef, String staleBase, String secureLinkUrl, String token,
                                         AtomicInteger refreshCount, int cap, Callback cb) {
        if (secureLinkUrl == null) return false;
        synchronized (cdnBaseRef) {
            String current = cdnBaseRef.get();
            if (staleBase == null || !staleBase.equals(current)) return true;
            if (refreshCount.get() >= cap) { cb.onLog("gog: CDN refresh cap reached (" + cap + ")"); return false; }
            String neu = parseCdnUrl(httpGet(secureLinkUrl, token));
            if (neu == null || neu.isEmpty()) { cb.onLog("gog: CDN refresh failed"); return false; }
            cdnBaseRef.set(neu);
            cb.onLog("gog: secure link refreshed #" + refreshCount.incrementAndGet());
            return true;
        }
    }

    private static byte[] fetchChunkVerified(AtomicReference<String> cdnBaseRef, String chunkPath, DepotFile.ChunkRef chunk, String secureLinkUrl, String token,
                                             AtomicInteger cdnRefreshCount, int maxCdnRefresh, AtomicBoolean cancelled, Callback cb) {
        int hardFail = 0, guard = 0;
        while (guard++ < 8) {
            if (cancelled.get()) return null;
            String base = cdnBaseRef.get();
            HttpResult res = fetchBytesEx(buildChunkUrl(pickCdn(base), chunkPath), null);
            if (res.body == null) {
                int code = res.status;
                if ((code == 401 || code == 403 || code == 404 || code == 500) && tryRefreshCdn(cdnBaseRef, base, secureLinkUrl, token, cdnRefreshCount, maxCdnRefresh, cb)) continue;
                cb.onLog("chunk http fail code=" + code);
                if (++hardFail >= 3) return null;
                sleepBackoff(hardFail);
                continue;
            }
            byte[] raw = res.body;
            if (chunk.compressedSize > 0 && raw.length != chunk.compressedSize) { if (++hardFail >= 3) return null; sleepBackoff(hardFail); continue; }
            if (chunk.compressedMd5 != null && !chunk.compressedMd5.isEmpty()) {
                String actual = md5Hex(raw);
                if (actual == null || !actual.equalsIgnoreCase(chunk.compressedMd5)) { if (++hardFail >= 3) return null; sleepBackoff(hardFail); continue; }
            }
            byte[] inflated = inflateZlib(raw);
            if (inflated == null) inflated = raw;
            if (chunk.size > 0 && inflated.length != chunk.size) { if (++hardFail >= 3) return null; sleepBackoff(hardFail); continue; }
            if (chunk.md5 != null && !chunk.md5.isEmpty()) {
                String actual = md5Hex(inflated);
                if (actual == null || !actual.equalsIgnoreCase(chunk.md5)) { if (++hardFail >= 3) return null; sleepBackoff(hardFail); continue; }
            }
            return inflated;
        }
        return null;
    }

    private static void sampleSpeed(long totalBytes, AtomicLong lastSpeedMs, AtomicLong lastSpeedB, AtomicLong speedBps) {
        long nowMs = System.currentTimeMillis(), prevMs = lastSpeedMs.get();
        if (nowMs - prevMs >= 500 && lastSpeedMs.compareAndSet(prevMs, nowMs)) {
            long prevB = lastSpeedB.getAndSet(totalBytes);
            long dt = nowMs - prevMs;
            if (dt > 0) speedBps.set((totalBytes - prevB) * 1000L / dt);
        }
    }

    private static String speedSuffix(long bps) {
        if (bps <= 0) return "";
        return "  " + (bps >= 1048576 ? String.format("%.1f MB/s", bps / 1048576.0) : (bps / 1024) + " KB/s");
    }

    private static String baseName(String path) { return path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path; }

    /** Download-pool threads for the built-in loop: cores times two, between 6 and 16. */
    private static int downloadThreads() {
        int threads = Runtime.getRuntime().availableProcessors() * 2;
        return Math.max(6, Math.min(16, threads));
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); }
    }

    /** Removes the chunk cache, counting its files as the Install stage's progress (a large cache takes a while). */
    private static void deleteCounted(File dir, Callback cb) {
        File[] files = dir.listFiles();
        if (files == null) { dir.delete(); return; }
        int total = files.length, done = 0;
        for (File f : files) {
            deleteDir(f);
            done++;
            if ((done & 31) == 0 || done == total) cb.onStage("cleanup", done, total, done, total);
        }
        dir.delete();
    }

    static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) for (File c : children) deleteDir(c);
        dir.delete();
    }

    /**
     * The exe to run, relative to the install folder: the manifest's hint when present, else the
     * candidate named most like the title, else the shallowest. "" when the folder has none.
     */
    static String pickExe(File installPath, String tempExe, String title) {
        if (tempExe != null) {
            File hinted = new File(installPath, tempExe);
            if (hinted.isFile()) return relative(installPath, hinted);
        }
        List<File> candidates = new ArrayList<>();
        collectExeRecursive(installPath, candidates);
        if (candidates.isEmpty()) return "";
        String key = title.toLowerCase().replaceAll("[^a-z0-9]", "");
        File best = candidates.get(0);
        for (File f : candidates) {
            String n = f.getName().toLowerCase().replaceAll("\\.exe$", "").replaceAll("[^a-z0-9]", "");
            if (!n.isEmpty() && (n.equals(key) || key.startsWith(n) || n.startsWith(key))) { best = f; break; }
        }
        return relative(installPath, best);
    }

    private static String relative(File root, File f) {
        String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length());
        if (rel.startsWith("/")) rel = rel.substring(1);
        return rel.replace('\\', '/');
    }

    private static void collectExeRecursive(File dir, List<File> out) {
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".exe")) {
                String path = f.getAbsolutePath().toLowerCase();
                if (!path.contains("redist") && !path.contains("unins") && !path.contains("setup") && !path.contains("crash")
                        && !path.contains("report") && !path.contains("helper") && !path.contains("dotnet") && !path.contains("vcredist")
                        && !path.contains("directx")) out.add(f);
            }
        }
        for (File f : files) if (f.isDirectory()) collectExeRecursive(f, out);
    }

    private static final class DepotFile {
        final String relativePath;
        final List<ChunkRef> chunks = new ArrayList<>();
        String md5;
        long totalSize;
        DepotFile(String relativePath) { this.relativePath = relativePath; }
        static final class ChunkRef {
            final String hash, compressedMd5, md5;
            final long compressedSize, size;
            ChunkRef(String hash, String compressedMd5, String md5, long compressedSize, long size) {
                this.hash = hash; this.compressedMd5 = compressedMd5; this.md5 = md5; this.compressedSize = compressedSize; this.size = size;
            }
        }
    }

    private static final class Gen1File {
        final String path, url; final long offset, size;
        Gen1File(String path, String url, long offset, long size) { this.path = path; this.url = url; this.offset = offset; this.size = size; }
    }

    /** The install size of the Windows build (English / all-language depots), or -1. Blocking. */
    /**
     * The "dependencies" of the build [buildId] of [gameId] (else the newest Windows build), from
     * its build manifest alone: no game file is fetched. Null when the builds or the manifest
     * could not be had; empty when the build lists none. Blocking.
     */
    public static List<String> fetchDependencies(String gameId, String buildId, String token) {
        try {
            String buildsUrl = "https://content-system.gog.com/products/" + gameId + "/os/windows/builds?generation=2";
            String buildsJson = httpGet(buildsUrl, null);
            if (buildsJson == null) buildsJson = httpGet(buildsUrl, token);
            if (buildsJson == null) return null;
            JSONArray items = new JSONObject(buildsJson).optJSONArray("items");
            if (items == null || items.length() == 0) return null;
            String manifestUrl = null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (!"windows".equals(item.optString("os"))) continue;
                String link = item.optString("link");
                if (link == null || link.isEmpty()) link = item.optString("meta_url");
                if (manifestUrl == null) manifestUrl = link;
                if (buildId != null && !buildId.isEmpty() && buildId.equals(item.optString("build_id"))) { manifestUrl = link; break; }
            }
            if (manifestUrl == null || manifestUrl.isEmpty()) return null;
            byte[] raw = fetchBytes(manifestUrl, token);
            if (raw == null) return null;
            String manifestStr = decompressBytes(raw);
            if (manifestStr == null) return null;
            JSONArray deps = new JSONObject(manifestStr).optJSONArray("dependencies");
            List<String> out = new ArrayList<>();
            if (deps != null) for (int i = 0; i < deps.length(); i++) { String d = deps.optString(i, ""); if (!d.isEmpty()) out.add(d); }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static long fetchInstallSizeBytes(String gameId, String token) {
        try {
            String buildsUrl = "https://content-system.gog.com/products/" + gameId + "/os/windows/builds?generation=2";
            String buildsJson = httpGet(buildsUrl, null);
            if (buildsJson == null) buildsJson = httpGet(buildsUrl, token);
            if (buildsJson == null) return -1;
            JSONArray items = new JSONObject(buildsJson).optJSONArray("items");
            if (items == null || items.length() == 0) return -1;
            String manifestUrl = null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if ("windows".equals(item.optString("os"))) {
                    manifestUrl = item.optString("link");
                    if (manifestUrl == null || manifestUrl.isEmpty()) manifestUrl = item.optString("meta_url");
                    break;
                }
            }
            if (manifestUrl == null || manifestUrl.isEmpty()) return -1;
            byte[] raw = fetchBytes(manifestUrl, token);
            if (raw == null) return -1;
            String manifestStr = decompressBytes(raw);
            if (manifestStr == null) return -1;
            JSONArray depots = new JSONObject(manifestStr).optJSONArray("depots");
            if (depots == null) return -1;
            long total = 0;
            for (int i = 0; i < depots.length(); i++) {
                JSONObject depot = depots.getJSONObject(i);
                if (languageCompatible(depot)) total += depot.optLong("size", 0);
            }
            return total > 0 ? total : -1;
        } catch (Exception e) {
            Log.w(TAG, "install size " + gameId + ": " + e.getClass().getSimpleName());
            return -1;
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "Unknown";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** Keeps the import used: every URL a log line names goes through here. */
    static String safe(String url) { return StoreLog.redactUrl(url); }
}
