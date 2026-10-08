package com.droiddeck.launcher.stores.amazon;

import android.content.Context;
import android.util.Log;

import com.droiddeck.launcher.stores.StoreLog;
import com.droiddeck.launcher.stores.download.StoreDownloadTier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Amazon Games install pipeline, ported from Bannerlator: GetGameDownload for the download
 * root and version, `manifest.proto`, then every file fetched (the native engine when it is
 * there, eight Java threads otherwise), SHA-256 checked, written as `.tmp` and renamed. A
 * complete file on disk is skipped by size, so a rerun resumes. Blocking; the queue runs it.
 */
public final class AmazonDownloadManager {

    private static final String TAG = "AmazonDownload";
    private static final int MAX_PARALLEL = 8;
    private static final int MAX_RETRIES = 3;
    private static final long PROGRESS_INTERVAL = 512L * 1024L;
    private static final String DOWNLOAD_USER_AGENT = "nile/0.1 Amazon";
    private static final String IN_PROGRESS_MARKER = ".amazon_download_in_progress";
    private static final String COMPLETE_MARKER = ".amazon_download_complete";

    public interface Callback {
        void onProgress(String message, int pct);
        default void onBytes(long done, long total, long speedBps) {}
        default void onLog(String line) {}
    }

    public static final class Result {
        public final String versionId;
        public final long bytes;
        Result(String versionId, long bytes) { this.versionId = versionId; this.bytes = bytes; }
    }

    public static final class InstallException extends Exception {
        InstallException(String message) { super(message); }
    }

    private AmazonDownloadManager() {}

    /** Installs the game into {@code installDir}; null when cancelled, an exception when it fails. */
    public static Result install(Context ctx, AmazonGame game, String accessToken, File installDir, AtomicBoolean cancel, Callback cb) throws InstallException {
        if (game.entitlementId == null || game.entitlementId.isEmpty()) throw new InstallException("This game has no entitlement to download with");
        try {
            installDir.mkdirs();
            new File(installDir, IN_PROGRESS_MARKER).createNewFile();
            cb.onProgress("Asking Amazon for the download…", 0);
            AmazonApiClient.GameDownloadSpec spec = AmazonApiClient.getGameDownload(accessToken, game.entitlementId);
            if (spec == null) throw new InstallException("Amazon gave no download for this game");
            cb.onLog("amazon: version " + spec.versionId);
            cb.onProgress("Downloading manifest…", 1);
            byte[] manifestBytes = AmazonApiClient.getBytes(AmazonApiClient.appendPath(spec.downloadUrl, "manifest.proto"), accessToken);
            if (manifestBytes == null) throw new InstallException("The manifest could not be downloaded");
            AmazonManifest.ParsedManifest manifest = AmazonManifest.parse(manifestBytes);
            cb.onLog("amazon: " + manifest.allFiles.size() + " files, " + fmt(manifest.totalInstallSize));
            long usable = installDir.getUsableSpace();
            if (manifest.totalInstallSize > 0 && usable > 0 && manifest.totalInstallSize > usable)
                throw new InstallException("Not enough free space: need " + fmt(manifest.totalInstallSize) + ", only " + fmt(usable) + " free");
            cb.onBytes(0, manifest.totalInstallSize, 0);
            if (cancel.get()) return null;

            AtomicLong downloaded = new AtomicLong(0);
            AtomicLong lastEmit = new AtomicLong(0);
            AtomicLong lastSpeedMs = new AtomicLong(System.currentTimeMillis());
            AtomicLong lastSpeedBytes = new AtomicLong(0);
            AtomicLong speedBps = new AtomicLong(0);
            List<AmazonManifest.ManifestFile> files = manifest.allFiles;
            final long total = manifest.totalInstallSize;

            if (AmazonNative.isAvailable()) {
                if (!downloadAllNative(ctx, spec.downloadUrl, files, total, installDir, downloaded, lastEmit, lastSpeedMs, lastSpeedBytes, speedBps, cancel, cb)) {
                    if (cancel.get()) return null;
                    throw new InstallException("A file failed to download");
                }
            } else {
                cb.onLog("amazon: engine=built-in (" + MAX_PARALLEL + " threads)");
                ExecutorService pool = Executors.newFixedThreadPool(MAX_PARALLEL);
                List<Future<Boolean>> futures = new ArrayList<>();
                for (AmazonManifest.ManifestFile file : files) {
                    final String dlUrl = spec.downloadUrl;
                    futures.add(pool.submit(() -> downloadFileWithRetry(file, dlUrl, installDir, downloaded, lastEmit, total, lastSpeedMs, lastSpeedBytes, speedBps, cb, cancel)));
                }
                pool.shutdown();
                try {
                    for (Future<Boolean> f : futures) {
                        if (!f.get()) {
                            pool.shutdownNow();
                            if (cancel.get()) return null;
                            throw new InstallException("A file failed to download");
                        }
                    }
                } catch (InstallException e) {
                    throw e;
                } catch (Exception e) {
                    pool.shutdownNow();
                    throw new InstallException("download pool error: " + e.getClass().getSimpleName());
                }
            }
            if (cancel.get()) return null;
            cb.onProgress("Finishing…", 98);
            new File(installDir, IN_PROGRESS_MARKER).delete();
            new File(installDir, COMPLETE_MARKER).createNewFile();
            return new Result(spec.versionId, total);
        } catch (InstallException e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "install failed", e);
            throw new InstallException(e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    /** One entry per manifest file with the exact URL the Java loop would open and the SHA-256 when it would check it. */
    static String buildPlan(List<AmazonManifest.ManifestFile> files, String baseUrl) {
        JSONArray arr = new JSONArray();
        try {
            for (AmazonManifest.ManifestFile file : files) {
                String hashHex = file.hashHex();
                arr.put(new JSONObject().put("relPath", file.unixPath()).put("url", AmazonApiClient.appendPath(baseUrl, "files/" + hashHex))
                        .put("size", file.size).put("sha256hex", (file.hashAlgorithm == 0 && file.hashBytes.length > 0) ? hashHex : ""));
            }
        } catch (Exception e) {
            Log.e(TAG, "plan failed", e);
            return "";
        }
        return arr.toString();
    }

    private static boolean downloadAllNative(Context ctx, String baseUrl, List<AmazonManifest.ManifestFile> files, long totalSize, File installDir,
                                             AtomicLong totalDownloaded, AtomicLong lastEmit, AtomicLong lastSpeedMs, AtomicLong lastSpeedBytes, AtomicLong speedBps,
                                             AtomicBoolean cancel, Callback cb) {
        String plan = buildPlan(files, baseUrl);
        if (plan.isEmpty()) return false;
        StoreDownloadTier tier = StoreDownloadTier.Companion.current(ctx);
        int maxWorkers = Math.max(1, Math.min(128, tier.getNetworkWindow()));
        int processWorkers = Math.max(Math.max(1, Runtime.getRuntime().availableProcessors() / 2), tier.getProcessWorkers());
        cb.onLog("amazon: engine=native workers=" + maxWorkers + " process_workers=" + processWorkers + " tier=" + tier.getId());
        AmazonNative.Listener listener = new AmazonNative.Listener() {
            @Override public void onProgress(long bytesDone, long bytesTotal, long filesDone, long filesTotal) {
                totalDownloaded.set(bytesDone);
                long emit = lastEmit.get();
                if (bytesDone - emit >= PROGRESS_INTERVAL && lastEmit.compareAndSet(emit, bytesDone)) {
                    sampleSpeed(bytesDone, lastSpeedMs, lastSpeedBytes, speedBps);
                    cb.onProgress("Downloading (" + filesDone + "/" + filesTotal + " files)" + speedSuffix(speedBps.get()), (int) (bytesDone * 95L / Math.max(1, totalSize)));
                    cb.onBytes(bytesDone, totalSize, speedBps.get());
                }
            }
            @Override public void onLog(String line) { cb.onLog(line); }
            @Override public void onComplete(boolean success, String error, long bytesWritten) {
                cb.onLog("amazon: complete success=" + success + " bytes=" + bytesWritten + (error.isEmpty() ? "" : " error=" + error));
            }
        };
        AmazonNative.RunResult result = AmazonNative.runBlocking(plan, installDir.getAbsolutePath(), "", maxWorkers, processWorkers, cancel::get, listener);
        if (result.cancelled) return false;
        if (!result.success) { cb.onLog("amazon: engine failed: " + result.error); return false; }
        return true;
    }

    private static boolean downloadFileWithRetry(AmazonManifest.ManifestFile file, String baseUrl, File installDir, AtomicLong totalDownloaded, AtomicLong lastEmit, long totalSize,
                                                 AtomicLong lastSpeedMs, AtomicLong lastSpeedBytes, AtomicLong speedBps, Callback cb, AtomicBoolean cancel) {
        File destFile = new File(installDir, file.unixPath());
        File tmpFile = new File(installDir, file.unixPath() + ".tmp");
        if (destFile.exists() && destFile.length() == file.size) { totalDownloaded.addAndGet(file.size); return true; }
        String fileUrl = AmazonApiClient.appendPath(baseUrl, "files/" + file.hashHex());
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            destFile.getParentFile().mkdirs();
            try {
                if (!downloadFile(fileUrl, tmpFile, totalDownloaded, lastEmit, totalSize, lastSpeedMs, lastSpeedBytes, speedBps, cb, cancel)) { tmpFile.delete(); return false; }
                if (file.hashAlgorithm == 0 && file.hashBytes.length > 0 && !Arrays.equals(sha256(tmpFile), file.hashBytes)) {
                    cb.onLog("amazon: SHA-256 mismatch for " + file.unixPath());
                    tmpFile.delete();
                    if (attempt < MAX_RETRIES) { Thread.sleep(1000L << (attempt - 1)); continue; }
                    return false;
                }
                if (destFile.exists()) destFile.delete();
                return tmpFile.renameTo(destFile);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                tmpFile.delete();
                return false;
            } catch (Exception e) {
                cb.onLog("amazon: attempt " + attempt + " failed for " + file.unixPath() + ": " + e.getClass().getSimpleName());
                tmpFile.delete();
                if (attempt < MAX_RETRIES) { try { Thread.sleep(1000L << (attempt - 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; } }
            }
        }
        return false;
    }

    /** Streams one file; false = cancelled. */
    private static boolean downloadFile(String urlStr, File tmpFile, AtomicLong totalDownloaded, AtomicLong lastEmit, long totalSize,
                                        AtomicLong lastSpeedMs, AtomicLong lastSpeedBytes, AtomicLong speedBps, Callback cb, AtomicBoolean cancel) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("User-Agent", DOWNLOAD_USER_AGENT);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) { conn.disconnect(); throw new IOException("HTTP " + code + " " + StoreLog.redactUrl(urlStr)); }
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmpFile)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (cancel.get()) { conn.disconnect(); return false; }
                out.write(buf, 0, n);
                long dl = totalDownloaded.addAndGet(n);
                long emit = lastEmit.get();
                if (dl - emit >= PROGRESS_INTERVAL && lastEmit.compareAndSet(emit, dl)) {
                    sampleSpeed(dl, lastSpeedMs, lastSpeedBytes, speedBps);
                    cb.onProgress("Downloading: " + tmpFile.getName().replace(".tmp", "") + speedSuffix(speedBps.get()), (int) (dl * 95L / Math.max(1, totalSize)));
                    cb.onBytes(dl, totalSize, speedBps.get());
                }
            }
        } finally {
            conn.disconnect();
        }
        return true;
    }

    /** The install size from the manifest alone, or -1. Blocking. */
    public static long fetchInstallSizeBytes(String accessToken, String entitlementId) {
        try {
            AmazonApiClient.GameDownloadSpec spec = AmazonApiClient.getGameDownload(accessToken, entitlementId);
            if (spec == null) return -1;
            byte[] bytes = AmazonApiClient.getBytes(AmazonApiClient.appendPath(spec.downloadUrl, "manifest.proto"), accessToken);
            if (bytes == null) return -1;
            AmazonManifest.ParsedManifest m = AmazonManifest.parse(bytes);
            return m.totalInstallSize > 0 ? m.totalInstallSize : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean isInstalled(File installDir) { return new File(installDir, COMPLETE_MARKER).exists(); }

    private static byte[] sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) >= 0) digest.update(buf, 0, n);
        }
        return digest.digest();
    }

    private static void sampleSpeed(long done, AtomicLong lastSpeedMs, AtomicLong lastSpeedBytes, AtomicLong speedBps) {
        long nowMs = System.currentTimeMillis(), prevMs = lastSpeedMs.get();
        if (nowMs - prevMs >= 500 && lastSpeedMs.compareAndSet(prevMs, nowMs)) {
            long prevB = lastSpeedBytes.getAndSet(done);
            long dt = nowMs - prevMs;
            if (dt > 0) speedBps.set((done - prevB) * 1000L / dt);
        }
    }

    private static String speedSuffix(long bps) {
        if (bps <= 0) return "";
        return "  " + (bps >= 1048576 ? String.format("%.1f MB/s", bps / 1048576.0) : (bps / 1024) + " KB/s");
    }

    private static String fmt(long bytes) {
        if (bytes >= 1073741824L) return String.format("%.1f GB", bytes / 1073741824.0);
        if (bytes >= 1048576L) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.0f KB", bytes / 1024.0);
    }
}
