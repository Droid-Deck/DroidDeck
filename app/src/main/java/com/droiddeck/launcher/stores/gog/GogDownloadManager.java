package com.droiddeck.launcher.stores.gog;

import android.content.Context;
import android.util.Log;

import com.droiddeck.launcher.frontend.AddedGames;
import com.droiddeck.launcher.runtime.GuestCommand;
import com.droiddeck.launcher.stores.StoreLanguage;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

public final class GogDownloadManager {

    private static final String TAG = "GogDownload";
    private static final int TIMEOUT = 30_000;
    private static final String MARKER = "_gog_manifest.json";

    public interface Callback {
        void onProgress(String message, int pct);
        default void onBytes(long done, long total, long speedBps) {}
        default void onLog(String line) {}
        default void onSizes(long downloadBytes, long diskBytes) {}
        default void onStage(String stage, long done, long total, int items, int itemsTotal) {}

    }

    public static final class Result {
        public final String exeRelative;
        public final String buildId;
        public final long bytes;
        public List<String> dependencies = new ArrayList<>();
        public boolean dependenciesRead = false;
        public String language;
        /** GOG's working directory for the exe: "" is the install root, null when no task declared it. */
        public String workDir;
        Result(String exeRelative, String buildId, long bytes) { this.exeRelative = exeRelative; this.buildId = buildId; this.bytes = bytes; }
    }

    public static class InstallException extends Exception {
        InstallException(String message) { super(message); }
    }

    public static final class InstallerException extends InstallException {
        InstallerException(String message) { super(message); }
    }

    private GogDownloadManager() {}

    public static Result install(Context ctx, GogGame game, File installPath, Callback cb, AtomicBoolean cancelled) throws InstallException {
        return install(ctx, game, installPath, cb, cancelled, null);
    }

    public static Result install(Context ctx, GogGame game, File installPath, Callback cb, AtomicBoolean cancelled, String language) throws InstallException {
        cb.onProgress("Checking sign-in…", 0);
        String token = GogAuth.validToken(ctx);
        if (token == null) throw new InstallException("Not signed in to GOG");
        if (cancelled.get()) return null;
        String lang = (language == null || language.isEmpty()) ? StoreLanguage.preferredCode(ctx) : language;
        cb.onLog("gog: installing language " + lang);
        cb.onProgress("Fetching builds…", 2);

        String buildsUrl = "https://content-system.gog.com/products/" + game.gameId + "/os/windows/builds?generation=2";
        String buildsJson = httpGet(buildsUrl, null);
        if (buildsJson == null) buildsJson = httpGet(buildsUrl, token);
        if (buildsJson != null) {
            Gen2Outcome out = runGen2(ctx, game, token, buildsJson, installPath, cb, cancelled, lang);
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
        Gen1Outcome one = runGen1(ctx, game, token, builds1Json, installPath, cb, cancelled, lang);
        if (one.result != null || cancelled.get()) return one.result;
        if (!"NO_CS_BUILDS".equals(one.error)) throw new InstallException("Download failed: " + one.error);
        cb.onProgress("No Galaxy builds; fetching the installer…", 12);
        return runInstaller(ctx, game, token, installPath, cb, cancelled);
    }


    private static final class Gen2Outcome {
        Result result; String error; boolean hardStop;
    }

    private static Gen2Outcome runGen2(Context ctx, GogGame game, String token, String buildsJson, File installPath, Callback cb, AtomicBoolean cancelled, String language) {
        Gen2Outcome out = new Gen2Outcome();
        try {
            out.result = GogMaintenance.installContent(ctx, game, installPath, new JSONObject(buildsJson), language, cb, cancelled);
            if (out.result == null && !cancelled.get()) out.error = "no usable public Windows build";
        } catch (Exception e) {
            out.error = "exception: " + e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return out;
        }
        return out;
    }

    public static List<String> listLanguages(Context ctx, String gameId) {
        try {
            String token = GogAuth.validToken(ctx);
            JSONObject builds = GogContent.builds(gameId, token);
            GogBuild build = GogBuild.Companion.select(builds, "", "");
            if (build == null || build.getGeneration() != 2) return Collections.emptyList();
            return StoreLanguage.codesFromDepots(GogContent.json(build.getLink(), token).optJSONArray("depots"));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }


    private static final class Gen1Outcome { Result result; String error; }

    private static Gen1Outcome runGen1(Context ctx, GogGame game, String token, String buildsJson, File installPath, Callback cb, AtomicBoolean cancelled, String language) {
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
            if (manifest.has("product")) {
                try {
                    out.result = GogMaintenance.installContent(ctx, game, installPath, builds, language, cb, cancelled);
                    if (out.result == null && !cancelled.get()) out.error = "no usable public Windows build";
                } catch (Exception e) {
                    out.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                return out;
            }
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
                        threads, threads, false, "gog gen1=" + game.gameId,
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
            GameTask task = pickTask(installPath, game.gameId, null, game.title);
            out.result = new Result(task == null ? "" : task.path, "", planned);
            if (task != null) out.result.workDir = task.workDir;
            return out;
        } catch (Exception e) {
            out.error = "exception: " + e.getClass().getSimpleName();
            return out;
        }
    }


    private static Result runInstaller(Context ctx, GogGame game, String token, File installPath, Callback cb, AtomicBoolean cancelled) throws InstallException {
        try {
            String productJson = httpGet("https://api.gog.com/products/" + game.gameId + "?expand=downloads", token);
            if (productJson == null) throw new InstallException("No downloadable builds for this game");
            JSONObject downloads = new JSONObject(productJson).optJSONObject("downloads");
            List<InstallerPart> parts = installerParts(downloads, game.gameId);
            if (parts.isEmpty()) throw new InstallException("No downloadable builds for this game");
            installPath.mkdirs();
            long planned = 0;
            boolean sized = true;
            for (InstallerPart part : parts) { planned += part.size; if (part.size <= 0) sized = false; }
            if (sized) {
                long usable = com.droiddeck.launcher.stores.StoreInstallRoot.availableBytes(installPath);
                if (com.droiddeck.launcher.stores.StoreInstallRoot.insufficientSpace(planned, usable)) {
                    throw new InstallException("Not enough free space: need " + formatBytes(planned) + ", only " + formatBytes(usable) + " free");
                }
            }
            long total = 0;
            for (InstallerPart part : parts) {
                if (cancelled.get()) return null;
                String downloadUrl = resolveRedirect(part.url, token);
                if (downloadUrl == null) throw new InstallException("The installer link could not be resolved: " + part.filename);
                File outFile;
                try {
                    outFile = installerDestination(installPath, part.filename);
                } catch (IOException e) {
                    throw new InstallException("The installer part is not inside the game folder: " + part.filename);
                }
                cb.onProgress("Downloading installer: " + part.filename, 15 + (int) ((total / (float) Math.max(1, planned)) * 80));
                long size = downloadWithProgress(downloadUrl, outFile, cb, cancelled, total, sized ? planned : 0);
                if (cancelled.get()) return null;
                if (size <= 0) throw new InstallException("The installer download failed: " + part.filename);
                total += size;
            }
            if (cancelled.get()) return null;
            cb.onLog("gog: installer downloaded (" + parts.size() + " file(s)); extracting natively");
            extractInstaller(ctx, installPath, parts.get(0).filename, cb, cancelled);
            if (cancelled.get()) return null;
            for (InstallerPart part : parts) new File(installPath, part.filename).delete();
            deleteDir(new File(installPath, "tmp"));
            deleteDir(new File(installPath, "commonappdata"));
            GameTask task = pickTask(installPath, game.gameId, null, game.title);
            String exe = task == null ? "" : task.path;
            if (exe.isEmpty()) throw new InstallException("The installer was extracted but no game executable was found");
            cb.onLog("gog: installer extracted natively; game exe " + exe);
            GogMaintenance.captureOffline(game.gameId, installPath, downloads);
            Result result = new Result(exe, "", total);
            result.workDir = task.workDir;
            return result;
        } catch (InstallerException e) {
            throw e;
        } catch (InstallException e) {
            throw new InstallerException(e.getMessage());
        } catch (Exception e) {
            throw new InstallerException("Installer download error: " + e.getClass().getSimpleName());
        }
    }

    private static void extractInstaller(Context ctx, File installPath, String setupName, Callback cb, AtomicBoolean cancelled) throws InstallException {
        String guestDir = AddedGames.INSTANCE.guestPath(ctx, installPath);
        if (guestDir == null || guestDir.isEmpty()) {
            throw new InstallException("The game folder is not visible to the Linux session");
        }
        String guestSetup = guestDir + "/" + setupName;
        cb.onProgress("Extracting installer…", 95);
        int rc;
        try {
            rc = GuestCommand.INSTANCE.run(ctx, Arrays.asList(
                    "/usr/local/bin/droiddeck-innoextract", "--gog", "--silent",
                    "-d", guestDir, guestSetup),
                    false, "gog-innoextract", null, null, false, cancelled,
                    line -> { cb.onLog("gog innoextract: " + line); return kotlin.Unit.INSTANCE; });
        } catch (Exception e) {
            throw new InstallException("The installer could not be extracted: " + e.getClass().getSimpleName());
        }
        if (cancelled.get()) return;
        if (rc != 0) throw new InstallException("The installer could not be extracted (innoextract exit " + rc + ")");
    }

    static final class InstallerPart {
        final String url;
        final String filename;
        final long size;
        InstallerPart(String url, String filename, long size) { this.url = url; this.filename = filename; this.size = size; }
    }

    static List<InstallerPart> installerParts(JSONObject downloads, String gameId) {
        List<InstallerPart> out = new ArrayList<>();
        if (downloads == null) return out;
        JSONArray installers = downloads.optJSONArray("installers");
        if (installers == null) return out;
        for (int i = 0; i < installers.length(); i++) {
            JSONObject inst = installers.optJSONObject(i);
            if (inst == null || !"windows".equals(inst.optString("os"))) continue;
            JSONArray files = inst.optJSONArray("files");
            String base = null;
            if (files != null) {
                for (int j = 0; j < files.length(); j++) {
                    JSONObject f = files.optJSONObject(j);
                    if (f == null) continue;
                    String url = f.optString("downlink", "");
                    if (url.isEmpty()) url = f.optString("manualUrl", "");
                    if (url.isEmpty()) continue;
                    String name = partName(f.optString("filename", ""));
                    if (name.isEmpty()) {
                        if (base == null) base = "setup_" + safeName(gameId);
                        name = out.isEmpty() ? base + ".exe" : base + "-" + out.size() + ".bin";
                    } else if (base == null) {
                        base = name.toLowerCase(java.util.Locale.US).endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
                    }
                    out.add(new InstallerPart(url, name, f.optLong("size", 0)));
                }
            }
            if (out.isEmpty()) {
                String url = inst.optString("manualUrl", "");
                if (url.isEmpty()) url = inst.optString("downlink", "");
                if (!url.isEmpty()) out.add(new InstallerPart(url, "setup_" + safeName(gameId) + ".exe", inst.optLong("total_size", 0)));
            }
            if (!out.isEmpty()) return out;
        }
        return out;
    }

    static File installerDestination(File installPath, String filename) throws IOException {
        File root = installPath.getCanonicalFile();
        File candidate = new File(root, filename);
        if (Files.isSymbolicLink(candidate.toPath())) throw new IOException("installer part is a link");
        File out = candidate.getCanonicalFile();
        if (out.equals(root) || !out.toPath().startsWith(root.toPath())) throw new IOException("installer part leaves the game folder");
        return out;
    }

    private static String partName(String raw) {
        return raw == null ? "" : new File(raw.replace('\\', '/')).getName();
    }

    private static String safeName(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? "game" : cleaned;
    }

    static String resolveRedirect(String url, String token) {
        try {
            if (url.startsWith("/")) url = "https://www.gog.com" + url;
            for (int hop = 0; hop < 5; hop++) {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(TIMEOUT); conn.setReadTimeout(TIMEOUT); conn.setInstanceFollowRedirects(false);
                if (token != null && (new URL(url).getHost().equals("gog.com") || new URL(url).getHost().endsWith(".gog.com"))) conn.setRequestProperty("Authorization", "Bearer " + token);
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

    private static long downloadWithProgress(String url, File out, Callback cb, AtomicBoolean cancelled, long doneBefore, long planned) {
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
                    if (planned > 0) {
                        long shown = doneBefore + downloaded;
                        cb.onProgress("Downloading: " + out.getName() + speedSuffix(speed), 15 + (int) ((shown / (float) planned) * 80));
                        cb.onBytes(shown, planned, speed);
                    } else if (total > 0) {
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


    private static void runNativeEngine(Context ctx, int kind, String verifiedMsg, List<String> depotJsons, File installPath,
                                        int maxWorkers, int processWorkers,
                                        boolean sortLargestFirst, String label, AtomicBoolean cancelled, AtomicBoolean anyFailed,
                                        AtomicInteger doneCount, int total, long planned, AtomicLong totalBytes,
                                        AtomicLong lastSpeedMs, AtomicLong lastSpeedB, AtomicLong speedBps, Callback cb) {
        StoreDownloadTier tier = StoreDownloadTier.Companion.current(ctx);
        int workers = Math.max(1, Math.min(128, tier.getNetworkWindow()));
        int process = Math.max(16, Math.max(processWorkers, tier.getProcessWorkers()));
        cb.onLog("gog: plan kind=" + kind + " files=" + total + " bytes=" + planned + " window=" + workers + " process_workers=" + process + " tier=" + tier.getId() + " engine=native");
        final String[] manifests = depotJsons.toArray(new String[0]);
        final java.util.Set<String> donePaths = java.util.concurrent.ConcurrentHashMap.newKeySet();
        int run = 0;
        while (!cancelled.get()) {
            run++;
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean okRef = new AtomicBoolean(false);
            final AtomicReference<String> errRef = new AtomicReference<>("");
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
                @Override public void onMaintenance(long networkBytes, long reusedBytes, long appliedBytes) {}
                @Override public void onComplete(boolean success, boolean wasCancelled, boolean linkExpiry, String error, long bytesWritten, int filesDone) {
                    okRef.set(success); errRef.set(error == null ? "" : error); latch.countDown();
                }
            };
            long handle = GogNative.start(kind, manifests, new String[0], installPath.getAbsolutePath(), donePaths.toArray(new String[0]), "",
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
            cb.onLog("gog: engine failed: " + errRef.get());
            anyFailed.set(true);
            return;
        }
    }


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

    private static int downloadThreads() {
        int threads = Runtime.getRuntime().availableProcessors() * 2;
        return Math.max(6, Math.min(16, threads));
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); }
    }

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
     * GOG's own manifest names the game's play task: Blood Omen's is kain.exe while dxconfig.exe is
     * its separate tool task (#680). The product's own {@code goggame-<id>.info} decides when it has
     * one; any other {@code goggame-*.info} in the folder is only the fallback. Builds without a
     * manifest fall through to the hint and search below.
     */
    private static GameTask declaredGameTask(File installPath, String gameId) {
        File own = gameId == null || gameId.isEmpty() ? null : new File(installPath, "goggame-" + gameId + ".info");
        if (own != null && !own.isFile()) own = null;
        List<File> others = new ArrayList<>();
        File[] files = installPath.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                if (f.equals(own) || !f.isFile() || !f.getName().startsWith("goggame-") || !f.getName().endsWith(".info")) continue;
                others.add(f);
            }
        }
        if (own != null) {
            GameTask task = bestGameTask(installPath, own);
            if (task != null) return task;
        }
        GameTask best = null;
        for (File manifest : others) {
            GameTask task = bestGameTask(installPath, manifest);
            if (task != null && (best == null || task.rank > best.rank)) best = task;
        }
        return best;
    }

    static final class GameTask {
        final String path;
        /** GOG's working directory for the task: "" is the install root, null when no task declared it. */
        final String workDir;
        final int rank;
        GameTask(String path, String workDir, int rank) { this.path = path; this.workDir = workDir; this.rank = rank; }
    }

    /** The task this manifest would be played from, or null when it names none that exists. */
    private static GameTask bestGameTask(File installPath, File manifest) {
        JSONArray tasks;
        try {
            tasks = new JSONObject(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8))
                    .optJSONArray("playTasks");
        } catch (Exception ignored) {
            return null; // A truncated manifest is not a reason to fail an install that downloaded fine.
        }
        if (tasks == null) return null;
        GameTask best = null;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task == null) continue;
            int rank = taskRank(task);
            if (rank == 0 || (best != null && rank <= best.rank)) continue;
            String path = task.optString("path", "").replace('\\', '/').replaceAll("/+", "/").replaceFirst("^(\\.?/)+", "");
            if (path.isEmpty()) continue;
            File exe = new File(installPath, path);
            if (!exe.isFile() || gogSupportExe(exe.getName()) || !inside(installPath, exe)) continue;
            best = new GameTask(path, taskWorkDir(installPath, task), rank);
        }
        return best;
    }

    /**
     * GOG runs a task from its declared working directory, and from the installation root when the
     * task declares none. The Witcher's play task is {@code System\witcher.exe}; started from
     * {@code System} hand-built shortcuts cannot find the game's {@code Data} folder, so the root is
     * recorded as "" and reaches the shortcut's Start In. A directory that leaves the folder, or is
     * not there, falls back to the root rather than to a guess.
     */
    private static String taskWorkDir(File installPath, JSONObject task) {
        String dir = task.optString("workingDir", "").replace('\\', '/').replaceAll("/+", "/").trim();
        while (dir.startsWith("./")) dir = dir.substring(2);
        dir = dir.replaceAll("^/+", "").replaceAll("/+$", "");
        if (dir.isEmpty() || dir.equals(".")) return "";
        File folder = new File(installPath, dir);
        return folder.isDirectory() && inside(installPath, folder) ? dir : "";
    }

    /**
     * GOG's categories that launch the game: a primary task wins, and a primary {@code launcher} is
     * how GOG ships games that start through one. Only a primary {@code launcher} counts: GOG files
     * the configuration tool beside a game as a non-primary {@code launcher}, so that one falls to
     * the search rather than becoming the game. {@code tool} and {@code document} name no game.
     */
    private static int taskRank(JSONObject task) {
        String category = task.optString("category").toLowerCase(Locale.ROOT);
        boolean primary = task.optBoolean("isPrimary", false);
        if ("game".equals(category)) return primary ? 4 : 2;
        if ("launcher".equals(category)) return primary ? 3 : 0;
        return 0;
    }

    /**
     * GOG's Galaxy support executables sit in the game folder but install or patch its Galaxy files,
     * never play the game. The Witcher: Enhanced Edition ships
     * {@code galaxy_the_witcher_enhanced_edition_2.1.0.15.exe}, and its build metadata even names
     * that file as the temp executable: launching it is the same 32-bit Inno Setup stub that stops
     * at "Path not found" under ARM64 FEX (#694). Play tasks and the name search both step over it.
     */
    private static boolean gogSupportExe(String path) {
        return new File(path.replace('\\', '/')).getName().toLowerCase(Locale.ROOT).startsWith("galaxy_");
    }

    /** Whether the canonical path of {@code file} stays below {@code root}. */
    private static boolean inside(File root, File file) {
        try {
            return file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    /** Exe names that belong to the config or tool shims around the game, not to the game. */
    private static final String[] TOOL_STEM_PARTS = {
            "config", "setting", "setup", "install", "unins", "touchup", "crashreport",
            "report", "editor", "launcher", "diagnostic",
    };

    static GameTask pickTask(File installPath, String gameId, String tempExe, String title) {
        GameTask declared = declaredGameTask(installPath, gameId);
        if (declared != null) return declared;
        if (tempExe != null) {
            String hint = tempExe.replace('\\', '/');
            File hinted = new File(installPath, hint);
            if (!gogSupportExe(hint) && hinted.isFile() && inside(installPath, hinted)) return new GameTask(relative(installPath, hinted), null, 0);
        }
        List<File> candidates = new ArrayList<>();
        collectExeRecursive(installPath, candidates);
        if (candidates.isEmpty()) return null;
        String key = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        List<String> words = new ArrayList<>();
        for (String word : title.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() >= 4 && !words.contains(word)) words.add(word);
        }
        Map<File, Integer> scores = new HashMap<>();
        for (File f : candidates) scores.put(f, exeScore(f, key, words));
        Collections.sort(candidates, (a, b) -> {
            int byScore = Integer.compare(scores.get(b), scores.get(a));
            if (byScore != 0) return byScore;
            int byDepth = Integer.compare(depth(installPath, a), depth(installPath, b));
            if (byDepth != 0) return byDepth;
            int bySize = Long.compare(b.length(), a.length());
            if (bySize != 0) return bySize;
            return relative(installPath, a).compareTo(relative(installPath, b));
        });
        return new GameTask(relative(installPath, candidates.get(0)), null, 0);
    }

    static String pickExe(File installPath, String gameId, String tempExe, String title) {
        GameTask task = pickTask(installPath, gameId, tempExe, title);
        return task == null ? "" : task.path;
    }

    /**
     * A word of the game's title in the exe name is the strongest hint: Blood Omen's kain.exe beats
     * the dxconfig.exe beside it (#680). Config and tool exes lose even when their name is the only
     * one resembling the title, and the depth penalty keeps a bundled tool below the game's own
     * folder. Before this, whatever the folder listing returned first was kept.
     */
    private static int exeScore(File f, String key, List<String> words) {
        String stem = f.getName().toLowerCase(Locale.ROOT).replaceAll("\\.exe$", "").replaceAll("[^a-z0-9]", "");
        int score = 0;
        if (!stem.isEmpty() && !key.isEmpty()) {
            if (stem.equals(key)) score += 120;
            else if (key.startsWith(stem) || stem.startsWith(key)) score += 80;
        }
        for (String word : words) {
            if (stem.equals(word)) { score += 100; break; }
            if (stem.contains(word)) { score += 40; break; }
        }
        for (String part : TOOL_STEM_PARTS) {
            if (stem.contains(part)) { score -= 110; break; }
        }
        return score;
    }

    private static int depth(File root, File f) {
        String path = relative(root, f);
        int depth = 0;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') depth++;
        return depth;
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
            if (f.isFile() && f.getName().toLowerCase().endsWith(".exe") && !gogSupportExe(f.getName())) {
                String path = f.getAbsolutePath().toLowerCase();
                if (!path.contains("redist") && !path.contains("unins") && !path.contains("setup") && !path.contains("crash")
                        && !path.contains("report") && !path.contains("helper") && !path.contains("dotnet") && !path.contains("vcredist")
                        && !path.contains("directx")) out.add(f);
            }
        }
        for (File f : files) if (f.isDirectory()) collectExeRecursive(f, out);
    }

    private static final class Gen1File {
        final String path, url; final long offset, size;
        Gen1File(String path, String url, long offset, long size) { this.path = path; this.url = url; this.offset = offset; this.size = size; }
    }

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
            JSONObject manifest = new JSONObject(manifestStr);
            JSONArray depots = manifest.optJSONArray("depots");
            if (depots == null) { Log.w(TAG, "gog: build manifest for " + gameId + " has no depots; keys " + manifest.names()); return null; }
            JSONArray deps = manifest.optJSONArray("dependencies");
            List<String> out = new ArrayList<>();
            if (deps != null) for (int i = 0; i < deps.length(); i++) { String d = deps.optString(i, ""); if (!d.isEmpty()) out.add(d); }
            Log.i(TAG, "gog: build " + manifest.optString("buildId", buildId) + " of " + gameId + ": " + depots.length() + " depots, dependencies "
                + (manifest.has("dependencies") ? String.valueOf(deps) : "(none listed)"));
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
            String resolved = StoreLanguage.pick(StoreLanguage.codesFromDepots(depots), StoreLanguage.preferredCode());
            for (int i = 0; i < depots.length(); i++) {
                JSONObject depot = depots.getJSONObject(i);
                JSONArray languages = depot.optJSONArray("languages");
                String[] listed = new String[languages == null ? 0 : languages.length()];
                for (int j = 0; j < listed.length; j++) listed[j] = languages.optString(j, "");
                if (listed.length == 0 || StoreLanguage.depotMatchesResolved(listed, resolved))
                    total += depot.optLong("size", 0);
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

    static String safe(String url) { return StoreLog.redactUrl(url); }
}
