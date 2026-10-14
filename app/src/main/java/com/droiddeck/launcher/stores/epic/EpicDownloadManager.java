package com.droiddeck.launcher.stores.epic;

import android.content.Context;
import android.util.Log;

import com.droiddeck.launcher.stores.StoreLog;
import com.droiddeck.launcher.stores.StoreInstallRoot;
import com.droiddeck.launcher.stores.StoreInstalls;
import com.droiddeck.launcher.stores.StoresNative;
import com.droiddeck.launcher.stores.download.StoreDownloadTier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Inflater;

public final class EpicDownloadManager {

    private static final String TAG = "EpicDownload";
    private static final String UA = EpicAuthClient.USER_AGENT;
    /** Keep reusable chunk data bounded even when a manifest shares chunks across distant files. */
    static final long MAX_RETAINED_CHUNK_CACHE_BYTES = 64L * 1024 * 1024;
    static final int PREFETCH_PARTS = 8;
    static final String STAGING_DIRECTORY = ".droiddeck-epic-staging";
    private static final String STAGING_OWNER = "DroidDeck Epic staging v1\n";
    /** Most the adaptive engine caches of shared chunks; more only saves the occasional refetch. */
    static final long ADAPTIVE_CACHE_MAX_BYTES = 1024L * 1024 * 1024;
    /** Its budget when free space cannot be read. */
    static final long ADAPTIVE_CACHE_DEFAULT_BYTES = 256L * 1024 * 1024;
    /**
     * Below this, internal scratch is too small to be worth a cache of its own: the shared chunks
     * then go beside the game (still released at their last use) rather than failing the install.
     */
    static final long ADAPTIVE_CACHE_MIN_SCRATCH_BYTES = MAX_RETAINED_CHUNK_CACHE_BYTES;
    /** Chunks being decoded, written or released at any moment, beyond the budget itself. */
    static final long ADAPTIVE_TRANSIENT_BYTES = 64L * 1024 * 1024;

    static final String MODE_ADAPTIVE = "adaptive";
    static final String MODE_LOOKAHEAD = "lookahead";
    static final String MODE_BATCH = "batch";
    static final String MODE_JAVA = "java";

    /** Which writer {@link #install} asks for and how wide the native fetch may go. */
    static final class NativeOptions {
        final String mode;
        final int maxWorkers, processWorkers;
        final String tierId;
        NativeOptions(String mode, int maxWorkers, int processWorkers, String tierId) {
            this.mode = mode; this.maxWorkers = maxWorkers; this.processWorkers = processWorkers; this.tierId = tierId;
        }
        /** The default: the adaptive engine at the Fast tier's window. */
        static NativeOptions defaults() { return new NativeOptions(MODE_ADAPTIVE, 32, 2, "fast"); }
    }

    public interface Callback {
        void onProgress(String message, int pct);
        default void onBytes(long done, long total, long speedBps) {}
        default void onLog(String line) {}
        default void onStage(String stage, long done, long total, int items, int itemsTotal) {}
        default void onSizes(long downloadBytes, long diskBytes) {}
    }

    private EpicDownloadManager() {}

    public static final class CdnUrl {
        public final String baseUrl, cloudDir, authParams;
        CdnUrl(String baseUrl, String cloudDir, String authParams) { this.baseUrl = baseUrl; this.cloudDir = cloudDir; this.authParams = authParams; }
    }

    public static final class ChunkInfo {
        public int[] guid = new int[4];
        public long hash;
        public byte[] sha1;
        public int groupNum;
        public int windowSize;
        public long fileSize;
        public String guidStr() { return String.format("%08X%08X%08X%08X", guid[0], guid[1], guid[2], guid[3]); }
        public String getPath(String chunkDir) { return chunkDir + "/" + String.format("%02d", groupNum) + "/" + String.format("%016X", hash) + "_" + guidStr() + ".chunk"; }
    }

    public static final class ChunkPart {
        public int[] guid = new int[4];
        public int offset, size;
        public String guidStr() { return String.format("%08X%08X%08X%08X", guid[0], guid[1], guid[2], guid[3]); }
    }

    public static final class FileInfo {
        public String filename = "";
        public List<ChunkPart> parts = new ArrayList<>();
        public List<String> installTags = new ArrayList<>();
        public byte[] sha1 = null;
        public long fileSize() { long t = 0; for (ChunkPart p : parts) t += (p.size & 0xFFFFFFFFL); return t; }
    }

    public static final class Manifest {
        public String chunkDir = "ChunksV4";
        public List<ChunkInfo> uniqueChunks = new ArrayList<>();
        public List<FileInfo> files = new ArrayList<>();
        public String launchExe = "";
        public String launchCommand = "";
        public String buildVersion = "";
        public String prereqName = "";
        public String prereqPath = "";
        public boolean prereqRead = false;
        public List<CdnUrl> cdnUrls = new ArrayList<>();
        /** The manifest as downloaded, for the native engines (which parse it themselves); null = Java only. */
        byte[] rawBytes = null;
    }

    public static final class Result {
        public final String launchExe;
        public final String buildVersion;
        public final long bytes;
        public String prereqName = "";
        public String prereqPath = "";
        Result(String launchExe, String buildVersion, long bytes) { this.launchExe = launchExe; this.buildVersion = buildVersion; this.bytes = bytes; }
        public boolean prereqRead = false;
        Result withPrereq(Manifest m) { prereqName = m.prereqName; prereqPath = m.prereqPath; prereqRead = m.prereqRead; return this; }
    }

    public static final class InstallException extends Exception {
        InstallException(String message) { super(message); }
    }

    public static Result install(Context ctx, String manifestApiJson, String installDirPath, String chunkCacheDirPath, List<String> installTags, AtomicBoolean cancel, Callback cb) throws InstallException {
        try {
            if (cancel.get()) return null;
            cb.onProgress("Reading CDN list…", 0);
            List<CdnUrl> cdnUrls = parseCdnUrls(manifestApiJson);
            if (cdnUrls.isEmpty()) throw new InstallException("No CDN in the manifest answer");
            for (CdnUrl c : cdnUrls) cb.onLog("epic: CDN " + c.baseUrl + " auth=" + (c.authParams.isEmpty() ? "no" : "yes"));
            cb.onProgress("Downloading manifest…", 0);
            byte[] manifestBytes = downloadManifest(manifestApiJson, cdnUrls);
            if (manifestBytes == null) throw new InstallException("The manifest could not be downloaded");
            if (cancel.get()) return null;
            cb.onProgress("Parsing manifest…", 0);
            Manifest manifest = parseManifest(manifestBytes);
            if (manifest == null) throw new InstallException("The manifest could not be parsed");
            manifest.cdnUrls = cdnUrls;
            manifest.rawBytes = manifestBytes;
            cb.onLog("epic: chunkDir=" + manifest.chunkDir + " chunks=" + manifest.uniqueChunks.size() + " files=" + manifest.files.size() + " exe=" + manifest.launchExe);
            StoreDownloadTier tier = StoreDownloadTier.Companion.current(ctx);
            String mode = com.droiddeck.launcher.session.SessionPrefs.INSTANCE.storesEpicMode(ctx);
            NativeOptions options = new NativeOptions(mode, Math.max(1, Math.min(128, tier.getNetworkWindow())), Math.max(2, tier.getProcessWorkers()), tier.getId());
            return installManifest(manifest, installDirPath, chunkCacheDirPath, installTags, cancel, cb, options);
        } catch (InstallException e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "install failed", e);
            throw new InstallException(e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    static Result installManifest(Manifest manifest, String installDirPath, String chunkCacheDirPath,
                                  List<String> installTags, AtomicBoolean cancel, Callback cb) throws InstallException {
        return installManifest(manifest, installDirPath, chunkCacheDirPath, installTags, cancel, cb, NativeOptions.defaults());
    }

    static Result installManifest(Manifest manifest, String installDirPath, String chunkCacheDirPath,
                                  List<String> installTags, AtomicBoolean cancel, Callback cb,
                                  NativeOptions nativeOptions) throws InstallException {
        Result result = installSelected(manifest, installDirPath, chunkCacheDirPath, installTags, cancel, cb, nativeOptions);
        // A finished install leaves no staging; the owned namespace is recreated by the next run.
        if (result != null) releaseOwnedStaging(new File(installDirPath));
        return result;
    }

    private static Result installSelected(Manifest manifest, String installDirPath, String chunkCacheDirPath,
                                          List<String> installTags, AtomicBoolean cancel, Callback cb,
                                          NativeOptions nativeOptions) throws InstallException {
        try {
            List<CdnUrl> cdnUrls = manifest.cdnUrls;
            File installDir = new File(installDirPath);
            installDir.mkdirs();
            final String cachePath = chunkCacheDirPath == null ? "" : chunkCacheDirPath;
            final File chunkCacheDir = cachePath.isEmpty() ? new File(installDir, ".chunks") : new File(cachePath);
            chunkCacheDir.mkdirs();
            File stagingDir = recoverOwnedStaging(installDir, manifest.files);

            List<FileInfo> selected = resolveInstallFiles(manifest, installTags);
            cb.onLog("epic: " + selected.size() + "/" + manifest.files.size() + " files for tags " + (installTags == null ? "(all)" : installTags.toString()));
            long installBytes = 0;
            for (FileInfo f : selected) installBytes += f.fileSize();
            final long planned = installBytes;

            cb.onProgress("Checking files…", 0);
            List<FileInfo> pending = new ArrayList<>(selected.size());
            int checked = 0, good = 0;
            long checkedBytes = 0;
            cb.onStage("check", 0, installBytes, 0, selected.size());
            for (FileInfo f : selected) {
                if (cancel.get()) return null;
                File out = new File(installDir, f.filename.replace("\\", "/"));
                if (fileExistsWithCorrectHash(out, f.fileSize(), f.sha1)) good++; else pending.add(f);
                checked++;
                checkedBytes += f.fileSize();
                if ((checked & 15) == 0 || checked == selected.size()) cb.onStage("check", checkedBytes, installBytes, checked, selected.size());
                if ((checked & 63) == 0) cb.onProgress("Checking files… (" + checked + "/" + selected.size() + ")", 0);
            }
            cb.onLog("epic: delta " + good + " up to date, " + pending.size() + " to download");
            if (pending.isEmpty()) {
                deleteDir(chunkCacheDir);
                cb.onProgress("Complete", 100);
                return new Result(manifest.launchExe, manifest.buildVersion, planned).withPrereq(manifest);
            }
            List<ChunkInfo> needed = uniqueChunksForFiles(manifest, pending);
            Map<String, ChunkInfo> chunksByGuid = new LinkedHashMap<>();
            for (ChunkInfo c : needed) chunksByGuid.put(c.guidStr(), c);
            if (!chunkCacheDir.isDirectory() && !chunkCacheDir.mkdirs()) throw new InstallException("Could not create Epic chunk cache");
            long pendingBytes = 0;
            Map<String, Long> oldFileBytes = new LinkedHashMap<>();
            for (FileInfo f : pending) {
                pendingBytes += f.fileSize();
                File output = new File(installDir, f.filename.replace("\\", "/"));
                if (output.isFile()) oldFileBytes.put(f.filename, output.length());
            }
            NativeOptions options = nativeOptions != null ? nativeOptions : NativeOptions.defaults();
            String mode = options.mode;
            if (!MODE_LOOKAHEAD.equals(mode) && !MODE_BATCH.equals(mode) && !MODE_JAVA.equals(mode)) mode = MODE_ADAPTIVE;
            boolean nativeAvailable = StoresNative.INSTANCE.ensureLoaded();
            if (!nativeAvailable && !MODE_JAVA.equals(mode)) {
                cb.onLog("epic: native engine unavailable; mode=java-bounded");
                mode = MODE_JAVA;
            } else if (MODE_JAVA.equals(mode)) {
                cb.onLog("epic: mode=java-bounded chosen by storesEpicMode");
            }
            if (MODE_ADAPTIVE.equals(mode)) {
                NativeAttempt attempt = installAdaptive(manifest, pending, pendingBytes, oldFileBytes, planned, installDir, cachePath, chunkCacheDir, stagingDir, options, cancel, cb);
                if (attempt.handled) return attempt.result;
                // Its space plan did not fit: the lookahead engine sizes its window to what is free.
                mode = MODE_LOOKAHEAD;
            } else if (MODE_BATCH.equals(mode)) {
                return installBatch(manifest, pending, needed, pendingBytes, planned, installDir, cachePath, chunkCacheDir, options, cancel, cb);
            }
            boolean lookahead = MODE_LOOKAHEAD.equals(mode);
            Map<String, Long> cachedChunkBytes = prepareBoundedCache(chunkCacheDir, needed);
            boolean cacheOnInstallVolume = cachePath.isEmpty();
            long peakGameAdditional = lookahead ? 0 : cacheOnInstallVolume
                    ? estimatePeakAdditionalBytes(pending, chunksByGuid, cachedChunkBytes, true, oldFileBytes)
                    : estimateStagedFilePeak(pending, oldFileBytes);
            long peakCacheAdditional = lookahead || cacheOnInstallVolume
                    ? 0 : estimatePeakAdditionalBytes(pending, chunksByGuid, cachedChunkBytes, false);
            long freeGame = StoreInstallRoot.availableBytes(installDir);
            // The lookahead engine plans its own window against both free-space readings.
            if (!lookahead && cacheOnInstallVolume) {
                if (StoreInstallRoot.insufficientSpace(peakGameAdditional, freeGame)) throw new InstallException("Not enough free space for safe staged Epic replacement: need up to " + fmt(peakGameAdditional) + " additional, only " + fmt(freeGame) + " free");
            } else if (!lookahead) {
                long freeCache = StoreInstallRoot.availableBytes(chunkCacheDir);
                if (StoreInstallRoot.insufficientSpace(peakCacheAdditional, freeCache)) throw new InstallException("Not enough internal space for the bounded Epic download cache: need up to " + fmt(peakCacheAdditional) + " additional, only " + fmt(freeCache) + " free");
                if (StoreInstallRoot.insufficientSpace(peakGameAdditional, freeGame)) throw new InstallException("Not enough free space for safe staged Epic replacement: need " + fmt(peakGameAdditional) + ", only " + fmt(freeGame) + " free");
            }
            if (cancel.get()) return null;
            if (lookahead) {
                JSONObject request = nativeRequest(manifest, pending, chunksByGuid, cachedChunkBytes,
                        oldFileBytes, installDir, chunkCacheDir, stagingDir, cacheOnInstallVolume);
                request.put("free_game", StoreInstallRoot.availableBytes(installDir));
                request.put("free_cache", StoreInstallRoot.availableBytes(chunkCacheDir));
                final long nativeTotal = pendingBytes;
                final long nativeStarted = System.nanoTime();
                cb.onSizes(nativeTotal, planned);
                cb.onBytes(0, nativeTotal, 0);
                EpicNative.Result outcome = EpicNative.installAdaptive(request.toString(), cancel, new EpicNative.InstallListener() {
                    @Override public void onPlan(int chunks, long bytes, String plan) {
                        cb.onLog("epic: mode=lookahead engine=lookahead-rust " + plan);
                    }
                    @Override public void onProgress(long done, long total, int files, int filesTotal) {
                        long elapsedMs = Math.max(1, (System.nanoTime() - nativeStarted) / 1_000_000);
                        cb.onBytes(done, total, done * 1000 / elapsedMs);
                        cb.onProgress("Downloading chunks…", (int) (done * 80L / Math.max(1, total)));
                        cb.onStage("stream", done, total, files, filesTotal);
                    }
                    @Override public void onLog(String line) { cb.onLog(line); }
                    @Override public void onComplete(boolean success, String error, long bytes) {}
                    @Override public boolean beforeSwap() {
                        try {
                            if (cancel.get()) return false;
                            StoreInstalls.markUpdating(installDir);
                            return !cancel.get();
                        } catch (Exception e) {
                            cb.onLog("epic: cannot mark updating: " + e.getMessage());
                            return false;
                        }
                    }
                });
                if (outcome.cancelled || cancel.get()) return null;
                if (!outcome.success) throw new InstallException("Native Epic install failed: " + outcome.error);
                long lookaheadMs = Math.max(1, (System.nanoTime() - nativeStarted) / 1_000_000);
                cb.onLog("epic: summary mode=lookahead files=" + pending.size() + " bytes_written=" + nativeTotal + " elapsed_ms=" + lookaheadMs
                        + " avg_MBps=" + String.format(java.util.Locale.ROOT, "%.2f", nativeTotal * 1000.0 / lookaheadMs / 1048576.0));
                cb.onStage("install", nativeTotal, nativeTotal, pending.size(), pending.size());
                cb.onProgress("Complete", 100);
                return new Result(manifest.launchExe, manifest.buildVersion, planned).withPrereq(manifest);
            }
            cb.onLog("epic: cache " + (cacheOnInstallVolume ? "beside the game" : "scratch") + ", peak game additional=" + fmt(peakGameAdditional) + (cacheOnInstallVolume ? "" : ", peak cache additional=" + fmt(peakCacheAdditional)) + " for " + fmt(pendingBytes) + " of files");
            final long fTotalBytes = pendingBytes;
            final int totalChunks = needed.size();
            cb.onSizes(fTotalBytes, planned);
            cb.onBytes(0, fTotalBytes, 0);
            cb.onLog("epic: " + fmt(fTotalBytes) + " to write using " + totalChunks + " unique chunks");
            cb.onLog("epic: engine=bounded-java (8-worker prefetch; native availability does not change cache semantics)");
            long completedBytes = 0, writtenBytes = 0;
            long transferStarted = System.nanoTime();
            int completedCount = 0, doneFiles = 0, totalFiles = pending.size();
            Set<String> reservedPaths = new LinkedHashSet<>();
            for (FileInfo file : manifest.files) reservedPaths.add(new File(installDir, file.filename.replace("\\", "/")).getCanonicalPath());
            boolean updateRecorded = false;
            try (ChunkPrefetch prefetch = new ChunkPrefetch(pending, chunksByGuid, cachedChunkBytes,
                    chunkCacheDir, manifest.chunkDir, cdnUrls, cancel)) {
                for (FileInfo file : pending) {
                    if (cancel.get()) return null;
                    String relPath = file.filename.replace("\\", "/");
                    File outFile = new File(installDir, relPath);
                    cb.onProgress("Downloading chunks: " + (relPath.contains("/") ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath), (int) (completedBytes * 80L / Math.max(1, fTotalBytes)));
                    MessageDigest fileSha = expectedDigest(file.sha1);
                    File partFile = createStagingFile(outFile, reservedPaths, stagingDir);
                    try {
                        try (FileOutputStream fos = new FileOutputStream(partFile); BufferedOutputStream bos = new BufferedOutputStream(fos, 65536)) {
                            for (ChunkPart part : file.parts) {
                                if (cancel.get()) throw new IOException("cancelled");
                                String guid = part.guidStr();
                                File cachedChunk = prefetch.await(guid);
                                completedCount++;
                                cb.onProgress("Downloading chunks (part " + completedCount + ")", (int) (completedBytes * 80L / Math.max(1, fTotalBytes)));
                                copyChunkPart(cachedChunk, part, bos, fileSha, cancel, relPath);
                                completedBytes += part.size & 0xFFFFFFFFL;
                                long elapsedMs = Math.max(1, (System.nanoTime() - transferStarted) / 1_000_000);
                                cb.onBytes(completedBytes, fTotalBytes, completedBytes * 1000 / elapsedMs);
                                prefetch.consume();
                            }
                            bos.flush();
                            fos.getFD().sync();
                        }
                        if (cancel.get()) return null;
                        if (partFile.length() != file.fileSize()) throw new InstallException("Size mismatch for " + relPath);
                        if (fileSha != null && !MessageDigest.isEqual(fileSha.digest(), file.sha1)) {
                            throw new InstallException("SHA-1 mismatch for " + relPath);
                        }
                        // Once a swap can mix versions, the old installed record is no longer truthful.
                        if (!updateRecorded) {
                            StoreInstalls.markUpdating(installDir);
                            updateRecorded = true;
                        }
                        if (cancel.get()) return null;
                        Files.move(partFile.toPath(), outFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        if (cancel.get()) return null;
                        throw new InstallException("Could not assemble or atomically replace " + relPath + ": " + e.getMessage());
                    } finally {
                        partFile.delete();
                    }
                    doneFiles++;
                    writtenBytes += file.fileSize();
                    cb.onStage("stream", writtenBytes, pendingBytes, doneFiles, totalFiles);
                }
            }
            deleteDir(chunkCacheDir);
            long boundedMs = Math.max(1, (System.nanoTime() - transferStarted) / 1_000_000);
            cb.onLog("epic: summary mode=java-bounded files=" + doneFiles + " bytes_written=" + writtenBytes + " elapsed_ms=" + boundedMs + " avg_MBps=" + String.format(java.util.Locale.ROOT, "%.2f", writtenBytes * 1000.0 / boundedMs / 1048576.0));
            cb.onStage("install", writtenBytes, pendingBytes, doneFiles, totalFiles);
            cb.onProgress("Complete", 100);
            return new Result(manifest.launchExe, manifest.buildVersion, planned).withPrereq(manifest);
        } catch (InstallException e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "install failed", e);
            throw new InstallException(e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    /** What a native attempt did: {@code handled} = the install ended there ({@code result} null when cancelled). */
    private static final class NativeAttempt {
        final boolean handled;
        final Result result;
        private NativeAttempt(boolean handled, Result result) { this.handled = handled; this.result = result; }
        static final NativeAttempt FALLBACK = new NativeAttempt(false, null);
        static NativeAttempt done(Result result) { return new NativeAttempt(true, result); }
    }

    /** The pending files as indices into the manifest's file list, what the native runs take; null when one is not in it. */
    static int[] pendingIndices(Manifest manifest, List<FileInfo> pending) {
        java.util.IdentityHashMap<FileInfo, Integer> index = new java.util.IdentityHashMap<>();
        for (int i = 0; i < manifest.files.size(); i++) index.put(manifest.files.get(i), i);
        int[] pendingIdx = new int[pending.size()];
        for (int i = 0; i < pending.size(); i++) {
            Integer k = index.get(pending.get(i));
            if (k == null) return null;
            pendingIdx[i] = k;
        }
        return pendingIdx;
    }

    private static String[] cdnPrefixes(List<CdnUrl> cdnUrls) {
        String[] prefixes = new String[cdnUrls.size()];
        for (int i = 0; i < cdnUrls.size(); i++) prefixes[i] = cdnUrls.get(i).baseUrl + cdnUrls.get(i).cloudDir;
        return prefixes;
    }

    /**
     * Where the adaptive engine's shared-chunk cache goes and how big it may get, and what each
     * volume must hold. A card install keeps the cache on internal scratch (each byte hits the
     * card once); when internal storage cannot hold a useful cache, the cache goes beside the game
     * instead of failing. {@code error} names the storage that is short, and by how much.
     */
    static final class AdaptiveSpacePlan {
        boolean cacheBesideGame;
        long cacheBudget;
        /**
         * Most planned bytes of replaced files the engine may stage at once (each sits beside its
         * old copy until its swap); -1 = nothing is replaced, no limit.
         */
        long overlapBudget = -1;
        long gameNeed;
        long scratchNeed;
        String error;
    }

    /** The part of free space the shared cache may use, after {@code need}: half the headroom, capped. */
    static long adaptiveCacheBudget(long free, long need) {
        if (free < 0) return ADAPTIVE_CACHE_DEFAULT_BYTES;
        long headroom = free - need - ADAPTIVE_TRANSIENT_BYTES;
        if (headroom <= 0) return 0;
        return Math.min(ADAPTIVE_CACHE_MAX_BYTES, headroom / 2);
    }

    /**
     * The game volume's net growth at its worst point when files are swapped in order and each
     * replaced file's old copy is released at its swap: the running Σ(new − old) after each file.
     * What a staged file costs while it sits beside its old copy is the overlap budget's part.
     */
    static long settledNetPeak(List<FileInfo> files, Map<String, Long> oldFileBytes) {
        long running = 0, peak = 0;
        for (FileInfo file : files) {
            Long old = oldFileBytes.get(file.filename);
            running += file.fileSize() - (old == null ? 0 : old);
            peak = Math.max(peak, running);
        }
        return peak;
    }

    /** The largest pending file whose destination has data (the engine's smallest overlap budget). */
    static long largestReplacedFile(List<FileInfo> files, Map<String, Long> oldFileBytes) {
        long largest = 0;
        for (FileInfo file : files) {
            Long old = oldFileBytes.get(file.filename);
            if (old != null && old > 0) largest = Math.max(largest, file.fileSize());
        }
        return largest;
    }

    /**
     * The cache placement and budget as before; for an update, the replacement-overlap budget is
     * what the game volume has left after the net growth and the cache, never below the largest
     * replaced file (one at a time is the minimum that still progresses). The check is the
     * lookahead planner's: the need must not exceed the free reading, no extra margin.
     */
    static AdaptiveSpacePlan planAdaptiveSpace(long netPeak, long largestReplaced, long freeGame, boolean scratchRequested, long freeScratch) {
        AdaptiveSpacePlan plan = new AdaptiveSpacePlan();
        long minimum = netPeak + largestReplaced;
        long scratchBudget = scratchRequested ? adaptiveCacheBudget(freeScratch, 0) : 0;
        plan.cacheBesideGame = !scratchRequested || (freeScratch >= 0 && scratchBudget < ADAPTIVE_CACHE_MIN_SCRATCH_BYTES);
        long cacheOnGame;
        if (plan.cacheBesideGame) {
            plan.cacheBudget = adaptiveCacheBudget(freeGame, minimum);
            cacheOnGame = plan.cacheBudget + ADAPTIVE_TRANSIENT_BYTES;
            plan.scratchNeed = 0;
        } else {
            plan.cacheBudget = scratchBudget;
            cacheOnGame = 0;
            plan.scratchNeed = plan.cacheBudget + ADAPTIVE_TRANSIENT_BYTES;
        }
        long overlap = 0;
        if (largestReplaced > 0) {
            overlap = freeGame < 0 ? largestReplaced : Math.max(largestReplaced, freeGame - netPeak - cacheOnGame);
            plan.overlapBudget = overlap;
        }
        plan.gameNeed = netPeak + overlap + cacheOnGame;
        if (StoreInstallRoot.insufficientSpace(plan.gameNeed, freeGame)) {
            plan.error = "Not enough free space on the game's storage: need " + fmt(plan.gameNeed)
                    + (plan.cacheBesideGame && scratchRequested ? " (download cache moved beside the game: internal storage is full)" : "")
                    + ", only " + fmt(freeGame) + " free (short by " + fmt(plan.gameNeed - freeGame) + ")";
        } else if (!plan.cacheBesideGame && StoreInstallRoot.insufficientSpace(plan.scratchNeed, freeScratch)) {
            plan.error = "Not enough internal space for the download cache: need " + fmt(plan.scratchNeed) + ", only " + fmt(freeScratch)
                    + " free (short by " + fmt(plan.scratchNeed - freeScratch) + ")";
        }
        return plan;
    }

    /**
     * The adaptive native install; the library is loaded. Hands back {@code FALLBACK} (nothing
     * fetched or written) only when its space plan does not fit, so the caller can run the
     * lookahead engine, which narrows its window to the free space. Any other failure is final.
     */
    private static NativeAttempt installAdaptive(Manifest manifest, List<FileInfo> pending, long pendingBytes, Map<String, Long> oldFileBytes,
                                                 long planned, File installDir, String cachePath, File chunkCacheDir, File stagingDir,
                                                 NativeOptions options, AtomicBoolean cancel, Callback cb) throws Exception {
        int[] pendingIdx = pendingIndices(manifest, pending);
        if (manifest.rawBytes == null || pendingIdx == null) {
            throw new InstallException("Native Epic install failed: the manifest is not available to the native engine");
        }
        long netPeak = settledNetPeak(pending, oldFileBytes);
        long largestReplaced = largestReplacedFile(pending, oldFileBytes);
        long freeGame = StoreInstallRoot.availableBytes(installDir);
        boolean scratchRequested = !cachePath.isEmpty();
        long freeScratch = scratchRequested ? StoreInstallRoot.availableBytes(chunkCacheDir) : -1;
        AdaptiveSpacePlan space = planAdaptiveSpace(netPeak, largestReplaced, freeGame, scratchRequested, freeScratch);
        if (space.error != null) {
            cb.onLog("epic: adaptive space plan does not fit (" + space.error + "); mode=lookahead");
            return NativeAttempt.FALLBACK;
        }
        String engineCache = space.cacheBesideGame ? "" : cachePath;
        File engineCacheDir = engineCache.isEmpty() ? new File(installDir, ".chunks") : chunkCacheDir;
        if (space.cacheBesideGame && scratchRequested) {
            cb.onLog("epic: internal storage cannot hold the shared-chunk cache (" + fmt(freeScratch) + " free); cache beside the game");
        }
        if (cancel.get()) return NativeAttempt.done(null);
        cb.onLog("epic: mode=adaptive tier=" + options.tierId + " workers=" + options.maxWorkers + " process_workers=" + options.processWorkers
                + " cache=" + (engineCache.isEmpty() ? "beside the game" : "scratch") + " budget=" + fmt(space.cacheBudget)
                + " overlap_budget=" + (space.overlapBudget < 0 ? "none" : fmt(space.overlapBudget))
                + " game need=" + fmt(space.gameNeed) + " of " + fmt(freeGame) + (space.cacheBesideGame ? "" : ", scratch need=" + fmt(space.scratchNeed) + " of " + fmt(freeScratch)));
        cb.onSizes(pendingBytes, planned);
        cb.onBytes(0, pendingBytes, 0);
        final int totalFiles = pending.size();
        final File fInstallDir = installDir;
        final Object lock = new Object();
        final long started = System.nanoTime();
        final long[] state = new long[] {0, 0, System.currentTimeMillis(), 0, 0}; // bytes, files, speed ms, speed bytes, speed bps
        EpicNative.Result res = EpicNative.stream(manifest.rawBytes, installDir.getPath(), engineCache, stagingDir.getCanonicalPath(), cdnPrefixes(manifest.cdnUrls), pendingIdx, pendingBytes,
                space.cacheBudget, space.overlapBudget, "", options.maxWorkers, options.processWorkers, cancel, new EpicNative.StreamListener() {
                    @Override public void onStreamProgress(long bytesWritten, long bytesTotal, int filesDone, int filesTotal, long filesBytes) {
                        synchronized (lock) {
                            if (bytesWritten < state[0] && filesDone <= state[1]) return; // a late, older sample
                            state[0] = Math.max(state[0], bytesWritten);
                            long nowMs = System.currentTimeMillis();
                            if (nowMs - state[2] >= 500) {
                                state[4] = (state[0] - state[3]) * 1000L / Math.max(1, nowMs - state[2]);
                                state[2] = nowMs;
                                state[3] = state[0];
                            }
                            cb.onBytes(state[0], pendingBytes, state[4]);
                            cb.onProgress("Downloading chunks (" + filesDone + "/" + totalFiles + " files)" + speedSuffix(state[4]),
                                    (int) (state[0] * 80L / Math.max(1, pendingBytes)));
                            if (filesDone > state[1]) {
                                state[1] = filesDone;
                                cb.onStage("stream", filesBytes, pendingBytes, filesDone, totalFiles);
                            }
                        }
                    }
                    @Override public void onLog(String line) { cb.onLog(line); }
                    @Override public boolean onBeforeReplace() {
                        // Once a swap can mix versions, the old installed record is no longer truthful.
                        try {
                            StoreInstalls.markUpdating(fInstallDir);
                            return true;
                        } catch (Exception e) {
                            cb.onLog("epic: could not mark the install as updating: " + e.getMessage());
                            return false;
                        }
                    }
                    @Override public void onComplete(boolean success, String error, long bytes) {}
                });
        if (!res.started) throw new InstallException("Native Epic install failed: " + res.error);
        long elapsedMs = Math.max(1, (System.nanoTime() - started) / 1_000_000);
        if (res.cancelled) return NativeAttempt.done(null);
        if (!res.success) throw new InstallException("Native Epic install failed: " + res.error);
        deleteDir(engineCacheDir);
        deleteDir(chunkCacheDir);
        cb.onLog("epic: summary mode=adaptive files=" + totalFiles + " bytes_written=" + pendingBytes + " elapsed_ms=" + elapsedMs
                + " avg_MBps=" + String.format(java.util.Locale.ROOT, "%.2f", pendingBytes * 1000.0 / elapsedMs / 1048576.0));
        cb.onStage("install", pendingBytes, pendingBytes, totalFiles, totalFiles);
        cb.onProgress("Complete", 100);
        return NativeAttempt.done(new Result(manifest.launchExe, manifest.buildVersion, planned).withPrereq(manifest));
    }

    /**
     * The older native batch install, kept for A/B benchmarks: every chunk fetched into the cache
     * in parallel, then the files assembled from it. It needs the whole cache and the files at
     * once (about twice the game), and it writes files in place rather than staged, so an
     * existing install is marked updating before anything is fetched.
     */
    private static Result installBatch(Manifest manifest, List<FileInfo> pending, List<ChunkInfo> needed, long pendingBytes,
                                       long planned, File installDir, String cachePath, File chunkCacheDir, NativeOptions options,
                                       AtomicBoolean cancel, Callback cb) throws InstallException {
        int[] pendingIdx = pendingIndices(manifest, pending);
        if (manifest.rawBytes == null || pendingIdx == null) {
            throw new InstallException("Native Epic install failed: the manifest is not available to the native engine");
        }
        long cacheMissing = 0, totalBytes = 0;
        for (ChunkInfo c : needed) {
            if (!new File(chunkCacheDir, c.guidStr()).isFile()) cacheMissing += chunkWindowSize(c);
            totalBytes += Math.max(c.fileSize, 1);
        }
        long freeGame = StoreInstallRoot.availableBytes(installDir);
        if (cachePath.isEmpty()) {
            long need = cacheMissing + pendingBytes;
            if (StoreInstallRoot.insufficientSpace(need, freeGame)) throw new InstallException("Not enough free space for the batch Epic download: need " + fmt(need) + " (" + fmt(pendingBytes) + " of game files + " + fmt(cacheMissing) + " of download cache), only " + fmt(freeGame) + " free (short by " + fmt(need - freeGame) + ")");
        } else {
            long freeCache = StoreInstallRoot.availableBytes(chunkCacheDir);
            if (StoreInstallRoot.insufficientSpace(cacheMissing, freeCache)) throw new InstallException("Not enough internal space for the batch download cache: need " + fmt(cacheMissing) + ", only " + fmt(freeCache) + " free (short by " + fmt(cacheMissing - freeCache) + ")");
            if (StoreInstallRoot.insufficientSpace(pendingBytes, freeGame)) throw new InstallException("Not enough free space on the game's storage: need " + fmt(pendingBytes) + ", only " + fmt(freeGame) + " free (short by " + fmt(pendingBytes - freeGame) + ")");
        }
        if (cancel.get()) return null;
        final long fTotal = totalBytes;
        final int totalChunks = needed.size();
        final int totalFiles = pending.size();
        cb.onLog("epic: mode=batch tier=" + options.tierId + " workers=" + options.maxWorkers + " process_workers=" + options.processWorkers + (cachePath.isEmpty() ? "" : " cache=scratch") + " cache_missing=" + fmt(cacheMissing));
        cb.onSizes(pendingBytes, planned);
        final AtomicLong lastMs = new AtomicLong(System.currentTimeMillis()), lastBytes = new AtomicLong(), speed = new AtomicLong();
        final long started = System.nanoTime();
        EpicNative.Result fetch = EpicNative.run(manifest.rawBytes, installDir.getPath(), cachePath, cdnPrefixes(manifest.cdnUrls), pendingIdx, totalChunks, fTotal, "",
                options.maxWorkers, options.processWorkers, cancel, new EpicNative.Listener() {
                    @Override public void onPlan(int chunksTotal, long bytesTotal, String chunkDir) { cb.onLog("epic: plan chunks=" + chunksTotal + " bytes=" + bytesTotal + " dir=" + chunkDir); }
                    @Override public void onProgress(long bytesDone, long bytesTotal, int chunksDone, int chunksTotal) {
                        sampleSpeed(bytesDone, lastMs, lastBytes, speed);
                        cb.onProgress("Downloading chunks (" + chunksDone + "/" + totalChunks + ")" + speedSuffix(speed.get()), (int) (bytesDone * 80L / Math.max(1, fTotal)));
                        cb.onBytes(bytesDone, fTotal, speed.get());
                    }
                    @Override public void onLog(String line) { cb.onLog(line); }
                    @Override public void onComplete(boolean success, String error, long bytes) {}
                });
        if (!fetch.started) throw new InstallException("Native Epic install failed: " + fetch.error);
        if (fetch.cancelled) return null;
        if (!fetch.success) throw new InstallException("Epic batch download failed: " + fetch.error);
        // Assembly overwrites files in place: the old installed record stops being truthful now.
        try { StoreInstalls.markUpdating(installDir); } catch (Exception e) { throw new InstallException("Could not mark the install as updating: " + e.getMessage()); }
        EpicNative.Result asm = EpicNative.assemble(manifest.rawBytes, installDir.getPath(), cachePath, pendingIdx, cancel, new EpicNative.Listener() {
            @Override public void onPlan(int chunksTotal, long bytesTotal, String chunkDir) {}
            @Override public void onProgress(long bytesDone, long bytesTotal, int done, int total) {
                cb.onStage("stream", bytesDone, bytesTotal, done, Math.max(total, totalFiles));
                cb.onProgress("Writing files (" + done + "/" + Math.max(total, totalFiles) + ")", 80 + (int) (done * 20L / Math.max(1, Math.max(total, totalFiles))));
            }
            @Override public void onLog(String line) { cb.onLog(line); }
            @Override public void onComplete(boolean success, String error, long bytes) {}
        });
        if (asm.cancelled) return null;
        if (!asm.started || !asm.success) throw new InstallException(asm.error == null || asm.error.isEmpty() ? "Epic batch assembly failed" : asm.error);
        long elapsedMs = Math.max(1, (System.nanoTime() - started) / 1_000_000);
        cb.onLog("epic: summary mode=batch files=" + totalFiles + " bytes_written=" + pendingBytes + " elapsed_ms=" + elapsedMs
                + " avg_MBps=" + String.format(java.util.Locale.ROOT, "%.2f", pendingBytes * 1000.0 / elapsedMs / 1048576.0));
        deleteDir(chunkCacheDir);
        cb.onStage("install", pendingBytes, pendingBytes, totalFiles, totalFiles);
        cb.onProgress("Complete", 100);
        return new Result(manifest.launchExe, manifest.buildVersion, planned).withPrereq(manifest);
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
        return "  " + (bps >= 1048576 ? String.format(java.util.Locale.ROOT, "%.1f MB/s", bps / 1048576.0) : (bps / 1024) + " KB/s");
    }

    static File recoverOwnedStaging(File installDir, List<FileInfo> manifestFiles) throws IOException {
        File staging = new File(installDir, STAGING_DIRECTORY);
        Path expected = installDir.getCanonicalFile().toPath().resolve(STAGING_DIRECTORY);
        if (!staging.getCanonicalFile().toPath().equals(expected) ||
                (Files.exists(staging.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isDirectory(staging.toPath(), LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Unsafe Epic staging namespace; refusing to traverse it");
        }
        String namespace = expected.toString();
        for (FileInfo file : manifestFiles) {
            String path = new File(installDir, file.filename.replace("\\", "/")).getCanonicalPath();
            if (path.equals(namespace) || path.startsWith(namespace + File.separator)) {
                throw new IOException("Manifest conflicts with Epic staging namespace");
            }
        }
        File owner = new File(staging, "owner");
        if (Files.exists(staging.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(owner.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    !owner.getCanonicalFile().toPath().equals(expected.resolve("owner")) ||
                    owner.length() != STAGING_OWNER.length() ||
                    !STAGING_OWNER.equals(new String(Files.readAllBytes(owner.toPath()), StandardCharsets.UTF_8))) {
                throw new IOException("Unowned Epic staging namespace; refusing to delete it");
            }
            File[] files = staging.listFiles();
            if (files == null) throw new IOException("Could not inspect Epic staging");
            for (File file : files) {
                if (file.getName().equals("owner")) continue;
                if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                        !file.getCanonicalFile().toPath().equals(expected.resolve(file.getName())) ||
                        !file.getName().endsWith(".stage")) throw new IOException("Unexpected Epic staging entry");
            }
            for (File file : files) {
                if (file.getName().equals("owner")) continue;
                if (!file.delete()) throw new IOException("Could not recover orphan Epic staging");
            }
        } else {
            if (!staging.mkdir()) throw new IOException("Could not create Epic staging namespace");
            try (FileOutputStream out = new FileOutputStream(owner)) {
                out.write(STAGING_OWNER.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
        }
        return staging;
    }

    /**
     * Remove the owned staging namespace when it holds nothing but its own marker. Same no-follow
     * and canonical-path checks as {@link #recoverOwnedStaging}; anything unexpected is left alone.
     */
    static void releaseOwnedStaging(File installDir) {
        try {
            File staging = new File(installDir, STAGING_DIRECTORY);
            if (!Files.isDirectory(staging.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
            Path expected = installDir.getCanonicalFile().toPath().resolve(STAGING_DIRECTORY);
            if (!staging.getCanonicalFile().toPath().equals(expected)) return;
            String[] names = staging.list();
            if (names == null || names.length != 1 || !names[0].equals("owner")) return;
            File owner = new File(staging, "owner");
            if (!Files.isRegularFile(owner.toPath(), LinkOption.NOFOLLOW_LINKS)
                    || owner.length() != STAGING_OWNER.length()
                    || !STAGING_OWNER.equals(new String(Files.readAllBytes(owner.toPath()), StandardCharsets.UTF_8))) return;
            if (owner.delete()) staging.delete();
        } catch (IOException e) {
            Log.w(TAG, "staging namespace kept: " + e.getMessage());
        }
    }

    private static JSONObject nativeRequest(Manifest manifest, List<FileInfo> files,
            Map<String, ChunkInfo> chunks, Map<String, Long> cached, Map<String, Long> old,
            File install, File cache, File staging, boolean sameVolume) throws Exception {
        JSONObject request = new JSONObject();
        request.put("schema_version", 1);
        request.put("install_dir", install.getCanonicalPath());
        request.put("cache_dir", cache.getCanonicalPath());
        request.put("staging_dir", staging.getCanonicalPath());
        request.put("same_volume", sameVolume);
        request.put("chunk_dir", manifest.chunkDir);
        request.put("max_workers", 32);
        JSONArray hosts = new JSONArray();
        for (CdnUrl cdn : manifest.cdnUrls) hosts.put(cdn.baseUrl + cdn.cloudDir);
        request.put("hosts", hosts);
        JSONArray chunkList = new JSONArray();
        for (ChunkInfo chunk : chunks.values()) {
            JSONObject item = new JSONObject();
            item.put("guid", chunk.guidStr());
            item.put("path", chunk.getPath(manifest.chunkDir));
            item.put("size", chunkWindowSize(chunk));
            item.put("exact_size", chunk.windowSize > 0);
            item.put("wire_size", Math.max(1, chunk.fileSize));
            item.put("sha1", hexDigest(chunk.sha1));
            item.put("cached", cached.containsKey(chunk.guidStr()) ? cached.get(chunk.guidStr()) : -1);
            chunkList.put(item);
        }
        request.put("chunks", chunkList);
        JSONArray fileList = new JSONArray();
        for (FileInfo file : files) {
            JSONObject item = new JSONObject();
            item.put("path", file.filename.replace("\\", "/"));
            item.put("sha1", hexDigest(file.sha1));
            item.put("old_size", old.containsKey(file.filename) ? old.get(file.filename) : 0);
            JSONArray parts = new JSONArray();
            for (ChunkPart part : file.parts) {
                parts.put(new JSONObject().put("guid", part.guidStr()).put("offset", part.offset & 0xFFFFFFFFL).put("size", part.size & 0xFFFFFFFFL));
            }
            item.put("parts", parts);
            fileList.put(item);
        }
        request.put("files", fileList);
        return request;
    }

    private static String hexDigest(byte[] digest) {
        if (digest == null || digest.length != 20) return "";
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format("%02x", b & 255));
        return out.toString();
    }

    private static File createStagingFile(File output, Set<String> reservedPaths, File staging) throws IOException {
        File parent = output.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create output directory");
        // Owned staging is on the output volume, never a manifest's literal "<name>.part".
        while (true) {
            File stage = File.createTempFile("file-", ".stage", staging);
            if (!reservedPaths.contains(stage.getCanonicalPath())) return stage;
            if (!stage.delete()) throw new IOException("Could not clear staging collision");
        }
    }

    static long estimateStagedFilePeak(List<FileInfo> files, Map<String, Long> oldFileBytes) {
        long delta = 0, peak = 0;
        for (FileInfo file : files) {
            delta += file.fileSize();
            peak = Math.max(peak, delta);
            delta -= oldFileBytes.containsKey(file.filename) ? oldFileBytes.get(file.filename) : 0;
        }
        return peak;
    }

    /**
     * Discard abandoned/orphaned cache entries and retain only verified chunks this install uses.
     * A bounded run consumes each chunk at its last part reference, so stale chunks from an older
     * all-at-once run must not silently occupy the space needed by the game files.
     */
    static Map<String, Long> prepareBoundedCache(File cacheDir, List<ChunkInfo> needed) throws InstallException {
        Map<String, ChunkInfo> byGuid = new LinkedHashMap<>();
        for (ChunkInfo chunk : needed) byGuid.put(chunk.guidStr(), chunk);
        Map<String, Long> valid = new LinkedHashMap<>();
        File[] entries = cacheDir.listFiles();
        if (entries == null) throw new InstallException("Could not inspect Epic chunk cache");
        for (File entry : entries) {
            ChunkInfo chunk = byGuid.get(entry.getName());
            if (chunk == null || !isValidCachedChunk(chunk, entry)) {
                deleteDir(entry);
                if (entry.exists()) throw new InstallException("Could not clear stale Epic cache entry " + entry.getName());
                continue;
            }
            valid.put(entry.getName(), entry.length());
        }
        trimCacheToBound(cacheDir, valid);
        return valid;
    }

    /** Evict oldest reusable chunks until retained cache data is under the fixed disk ceiling. */
    private static void trimCacheToBound(File cacheDir, Map<String, Long> cacheSizes) throws InstallException {
        for (String guid : evictOldestReusableChunks(cacheSizes).keySet()) {
            File chunk = new File(cacheDir, guid);
            if (chunk.exists() && !chunk.delete() && chunk.exists()) {
                throw new InstallException("Could not release Epic cache chunk " + guid);
            }
        }
    }

    /**
     * The writer and space planner use the same oldest-first retained-cache policy. Entries leave
     * this map as soon as their last reference is consumed; an oversized incoming chunk is
     * therefore allowed to coexist with the retained cache until its current part is written.
     */
    private static Map<String, Long> evictOldestReusableChunks(Map<String, Long> cacheSizes) {
        long retained = 0;
        for (long bytes : cacheSizes.values()) retained += bytes;
        Map<String, Long> evicted = new LinkedHashMap<>();
        java.util.Iterator<Map.Entry<String, Long>> iterator = cacheSizes.entrySet().iterator();
        while (retained > MAX_RETAINED_CHUNK_CACHE_BYTES && iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            retained -= entry.getValue();
            evicted.put(entry.getKey(), entry.getValue());
            iterator.remove();
        }
        return evicted;
    }

    private static boolean consumeChunkReference(Map<String, Integer> refs, String guid) {
        Integer count = refs.get(guid);
        if (count == null) return false;
        if (count <= 1) {
            refs.remove(guid);
            return true;
        }
        refs.put(guid, count - 1);
        return false;
    }

    /**
     * Pure disk schedule shared by execution and preflight. The next eight part references pin
     * at most eight GUIDs (including across file boundaries); other reusable data is oldest-first
     * capped at 64 MiB. Incoming downloads reserve their entire decoded size before submission.
     */
    static final class PrefetchSchedule {
        final List<String> parts = new ArrayList<>();
        final Map<String, Long> resident;
        final Map<String, Integer> refs;
        final Map<String, ChunkInfo> chunks;
        int cursor;

        PrefetchSchedule(List<FileInfo> files, Map<String, ChunkInfo> chunks, Map<String, Long> cached) {
            for (FileInfo file : files) for (ChunkPart part : file.parts) parts.add(part.guidStr());
            this.chunks = chunks;
            resident = new LinkedHashMap<>(cached);
            refs = chunkReferences(files);
        }

        Set<String> window() {
            return new LinkedHashSet<>(parts.subList(cursor, Math.min(parts.size(), cursor + PREFETCH_PARTS)));
        }

        List<String> fill() {
            List<String> fetch = new ArrayList<>();
            for (String guid : window()) if (!resident.containsKey(guid)) {
                ChunkInfo chunk = chunks.get(guid);
                if (chunk == null) throw new IllegalArgumentException("Missing chunk metadata " + guid);
                resident.put(guid, chunkWindowSize(chunk));
                fetch.add(guid);
            }
            return fetch;
        }

        List<String> consume() {
            List<String> release = new ArrayList<>();
            String guid = parts.get(cursor++);
            if (consumeChunkReference(refs, guid)) {
                resident.remove(guid);
                release.add(guid);
            }
            Set<String> pinned = window();
            long reusable = 0;
            for (Map.Entry<String, Long> entry : resident.entrySet()) {
                if (!pinned.contains(entry.getKey())) reusable += entry.getValue();
            }
            Iterator<Map.Entry<String, Long>> it = resident.entrySet().iterator();
            while (reusable > MAX_RETAINED_CHUNK_CACHE_BYTES && it.hasNext()) {
                Map.Entry<String, Long> entry = it.next();
                if (pinned.contains(entry.getKey())) continue;
                reusable -= entry.getValue();
                release.add(entry.getKey());
                it.remove();
            }
            return release;
        }

        long bytes() {
            long bytes = 0;
            for (long size : resident.values()) bytes += size;
            return bytes;
        }
    }

    private static final class ChunkPrefetch implements AutoCloseable {
        final PrefetchSchedule schedule;
        final File cacheDir;
        final AtomicBoolean cancel;
        final AtomicBoolean stopped = new AtomicBoolean();
        final ExecutorService workers = Executors.newFixedThreadPool(PREFETCH_PARTS);
        final Map<String, Future<Boolean>> jobs = new LinkedHashMap<>();
        final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
        final String chunkDir;
        final List<CdnUrl> cdns;

        ChunkPrefetch(List<FileInfo> files, Map<String, ChunkInfo> chunks, Map<String, Long> cached,
                      File cacheDir, String chunkDir, List<CdnUrl> cdns, AtomicBoolean cancel) {
            schedule = new PrefetchSchedule(files, chunks, cached);
            this.cacheDir = cacheDir;
            this.chunkDir = chunkDir;
            this.cdns = cdns;
            this.cancel = cancel;
        }

        File await(String guid) throws Exception {
            for (String fetch : schedule.fill()) {
                jobs.put(fetch, workers.submit(() -> downloadChunkStreaming(schedule.chunks.get(fetch),
                        chunkDir, cdns, new File(cacheDir, fetch), cancel, stopped, connections)));
            }
            Future<Boolean> job = jobs.get(guid);
            if (job != null) while (true) {
                if (cancel.get()) throw new IOException("cancelled");
                try {
                    if (!job.get(100, TimeUnit.MILLISECONDS)) {
                        if (cancel.get()) throw new IOException("cancelled");
                        throw new InstallException("Failed to download Epic chunk " + guid);
                    }
                    break;
                } catch (TimeoutException ignored) { }
            }
            return new File(cacheDir, guid);
        }

        void consume() throws InstallException {
            for (String guid : schedule.consume()) {
                // Only the writer advances the schedule, after closing its chunk reader.
                jobs.remove(guid);
                File chunk = new File(cacheDir, guid);
                if (chunk.exists() && !chunk.delete() && chunk.exists()) {
                    throw new InstallException("Could not release Epic cache chunk " + guid);
                }
            }
        }

        @Override public void close() {
            stopped.set(true);
            for (HttpURLConnection connection : connections) connection.disconnect();
            workers.shutdownNow();
            boolean interrupted = false;
            while (true) {
                try {
                    if (workers.awaitTermination(100, TimeUnit.MILLISECONDS)) break;
                    for (HttpURLConnection connection : connections) connection.disconnect();
                } catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static boolean isValidCachedChunk(ChunkInfo chunk, File file) {
        if (!file.isFile()) return false;
        if (chunk.windowSize > 0 && file.length() != chunk.windowSize) return false;
        byte[] expected = chunk.sha1;
        if (expected == null || expected.length != 20) return true;
        boolean allZero = true;
        for (byte b : expected) if (b != 0) { allZero = false; break; }
        if (allZero) return true;
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) sha.update(buffer, 0, count);
            return MessageDigest.isEqual(sha.digest(), expected);
        } catch (Exception e) {
            return false;
        }
    }

    private static MessageDigest expectedDigest(byte[] expected) throws InstallException {
        if (expected == null || expected.length != 20) return null;
        for (byte b : expected) if (b != 0) {
            try { return MessageDigest.getInstance("SHA-1"); }
            catch (java.security.NoSuchAlgorithmException e) { throw new InstallException("SHA-1 verification unavailable"); }
        }
        return null;
    }

    static Map<String, Integer> chunkReferences(List<FileInfo> files) {
        Map<String, Integer> refs = new LinkedHashMap<>();
        for (FileInfo file : files) for (ChunkPart part : file.parts) {
            String guid = part.guidStr();
            refs.put(guid, refs.containsKey(guid) ? refs.get(guid) + 1 : 1);
        }
        return refs;
    }

    /**
     * Simulates reserved parallel prefetch → append part → last-reference eviction order. The returned
     * additional-space peak is measured against the current free-space reading: existing verified
     * chunks are already reflected in that reading and count as reclaimed only when actually freed.
     * Old output bytes are reclaimed after the complete new file is staged and verified, never
     * before its parts are written. The same schedule is used for scratch-cache game-volume checks.
     */
    static long estimatePeakAdditionalBytes(List<FileInfo> files, Map<String, ChunkInfo> chunksByGuid,
                                            Map<String, Long> cachedChunkBytes, boolean includeFileWrites) {
        return estimatePeakAdditionalBytes(files, chunksByGuid, cachedChunkBytes, includeFileWrites, new LinkedHashMap<>());
    }

    static long estimatePeakAdditionalBytes(List<FileInfo> files, Map<String, ChunkInfo> chunksByGuid,
                                            Map<String, Long> cachedChunkBytes, boolean includeFileWrites,
                                            Map<String, Long> oldFileBytes) {
        PrefetchSchedule schedule = new PrefetchSchedule(files, chunksByGuid, cachedChunkBytes);
        long initialCache = schedule.bytes();
        long fileDelta = 0, peak = 0;
        for (FileInfo file : files) {
            for (ChunkPart part : file.parts) {
                schedule.fill();
                peak = Math.max(peak, fileDelta + schedule.bytes() - initialCache);
                if (includeFileWrites) {
                    fileDelta += part.size & 0xFFFFFFFFL;
                    peak = Math.max(peak, fileDelta + schedule.bytes() - initialCache);
                }
                schedule.consume();
            }
            // The verified staging file coexists with the old output until its atomic swap.
            if (includeFileWrites) fileDelta -= oldFileBytes.containsKey(file.filename) ? oldFileBytes.get(file.filename) : 0;
        }
        return Math.max(0, peak);
    }

    private static void copyChunkPart(File chunkFile, ChunkPart part, BufferedOutputStream out,
                                      MessageDigest fileSha, AtomicBoolean cancel, String relPath) throws IOException {
        long offset = part.offset & 0xFFFFFFFFL;
        long remaining = part.size & 0xFFFFFFFFL;
        long length = chunkFile.length();
        if (offset > length || remaining > length - offset) {
            throw new IOException("Chunk " + part.guidStr() + " is too short for " + relPath);
        }
        try (FileInputStream in = new FileInputStream(chunkFile)) {
            in.getChannel().position(offset);
            byte[] buffer = new byte[64 * 1024];
            while (remaining > 0) {
                if (cancel.get()) throw new IOException("cancelled");
                int count = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new IOException("Unexpected end of chunk " + part.guidStr());
                out.write(buffer, 0, count);
                if (fileSha != null) fileSha.update(buffer, 0, count);
                remaining -= count;
            }
        }
    }


    public static List<CdnUrl> parseCdnUrls(String json) {
        List<CdnUrl> result = new ArrayList<>();
        try {
            JSONArray manifests = new JSONObject(json).optJSONArray("manifests");
            if (manifests == null) return result;
            for (int i = 0; i < manifests.length(); i++) {
                JSONObject m = manifests.optJSONObject(i);
                if (m == null) continue;
                String uri = m.optString("uri", "");
                int buildsIdx = uri.indexOf("/Builds");
                if (buildsIdx < 0) continue;
                String baseUrl = uri.substring(0, buildsIdx);
                if (!baseUrl.startsWith("http") || baseUrl.contains("cloudflare.epicgamescdn.com")) continue;
                String afterBase = uri.substring(buildsIdx);
                int q = afterBase.indexOf('?');
                if (q >= 0) afterBase = afterBase.substring(0, q);
                int lastSlash = afterBase.lastIndexOf('/');
                if (lastSlash < 0) continue;
                String cloudDir = afterBase.substring(0, lastSlash);
                StringBuilder auth = new StringBuilder();
                JSONArray params = m.optJSONArray("queryParams");
                if (params != null) for (int j = 0; j < params.length(); j++) {
                    JSONObject p = params.optJSONObject(j);
                    if (p == null) continue;
                    auth.append(auth.length() == 0 ? "?" : "&").append(p.optString("name")).append('=').append(p.optString("value"));
                }
                result.add(new CdnUrl(baseUrl, cloudDir, auth.toString()));
            }
        } catch (Exception e) {
            Log.e(TAG, "CDN parse: " + e.getClass().getSimpleName());
        }
        return result;
    }

    public static byte[] downloadManifest(String json, List<CdnUrl> cdnUrls) {
        try {
            JSONArray manifests = new JSONObject(json).optJSONArray("manifests");
            if (manifests == null || manifests.length() == 0) return null;
            String firstUri = manifests.getJSONObject(0).optString("uri", "");
            String uriPath = firstUri.contains("?") ? firstUri.substring(0, firstUri.indexOf('?')) : firstUri;
            int lastSlash = uriPath.lastIndexOf('/');
            if (lastSlash < 0) return null;
            String manifestFilename = uriPath.substring(lastSlash + 1);
            for (CdnUrl cdn : cdnUrls) {
                byte[] bytes = downloadBytes(cdn.baseUrl + cdn.cloudDir + "/" + manifestFilename + cdn.authParams);
                if (bytes != null && bytes.length > 4) return bytes;
            }
        } catch (Exception e) {
            Log.e(TAG, "manifest download: " + e.getClass().getSimpleName());
        }
        return null;
    }


    public static Manifest parseManifest(byte[] bytes) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int magic = buf.getInt();
            if (magic != 0x44BEC00C) return parseJsonManifest(bytes);
            int headerSize = buf.getInt();
            int sizeUncompressed = buf.getInt();
            buf.getInt();
            buf.position(buf.position() + 20);
            int storedAs = buf.get() & 0xFF;
            int version = buf.getInt();
            String chunkDir = version >= 15 ? "ChunksV4" : version >= 6 ? "ChunksV3" : version >= 3 ? "ChunksV2" : "Chunks";
            buf.position(headerSize);
            byte[] body = new byte[buf.remaining()];
            buf.get(body);
            if ((storedAs & 1) != 0) {
                Inflater inflater = new Inflater();
                inflater.setInput(body);
                byte[] decomp = new byte[sizeUncompressed];
                int got = inflater.inflate(decomp);
                inflater.end();
                if (got != sizeUncompressed) { Log.e(TAG, "manifest inflate size mismatch"); return null; }
                body = decomp;
            }
            ByteBuffer b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
            Manifest m = new Manifest();
            m.chunkDir = chunkDir;
            int metaStart = b.position();
            int metaSize = b.getInt();
            try {
                b.get();
                b.getInt();
                b.get();
                b.getInt();
                readFString(b);
                m.buildVersion = readFString(b);
                m.launchExe = readFString(b).replace('\\', '/');
                m.launchCommand = readFString(b);
                int prereqIds = b.getInt();
                for (int i = 0; i < prereqIds && i < 64; i++) readFString(b);
                m.prereqName = readFString(b);
                m.prereqPath = readFString(b).replace('\\', '/');
                readFString(b);
                m.prereqRead = true;
            } catch (Exception e) {
                Log.w(TAG, "manifest meta: " + e.getClass().getSimpleName());
            }
            b.position(metaStart + metaSize);

            int cdlStart = b.position();
            int cdlSize = b.getInt();
            b.get();
            int chunkCount = b.getInt();
            List<ChunkInfo> chunks = new ArrayList<>(chunkCount);
            for (int i = 0; i < chunkCount; i++) chunks.add(new ChunkInfo());
            for (ChunkInfo c : chunks) { c.guid[0] = b.getInt(); c.guid[1] = b.getInt(); c.guid[2] = b.getInt(); c.guid[3] = b.getInt(); }
            for (ChunkInfo c : chunks) c.hash = b.getLong();
            for (ChunkInfo c : chunks) { c.sha1 = new byte[20]; b.get(c.sha1); }
            for (ChunkInfo c : chunks) c.groupNum = b.get() & 0xFF;
            for (ChunkInfo c : chunks) c.windowSize = b.getInt();
            for (ChunkInfo c : chunks) c.fileSize = b.getLong();
            b.position(cdlStart + cdlSize);

            int fmlStart = b.position();
            int fmlSize = b.getInt();
            b.get();
            int fileCount = b.getInt();
            List<FileInfo> files = new ArrayList<>(fileCount);
            for (int i = 0; i < fileCount; i++) files.add(new FileInfo());
            for (FileInfo f : files) f.filename = readFString(b);
            for (int i = 0; i < fileCount; i++) readFString(b);
            for (FileInfo f : files) { f.sha1 = new byte[20]; b.get(f.sha1); }
            b.position(b.position() + fileCount);
            for (int i = 0; i < fileCount; i++) {
                int tagCount = b.getInt();
                for (int j = 0; j < tagCount; j++) { String tag = readFString(b); if (tag != null && !tag.isEmpty()) files.get(i).installTags.add(tag); }
            }
            for (FileInfo f : files) {
                int partCount = b.getInt();
                for (int j = 0; j < partCount; j++) {
                    int partStart = b.position();
                    int partStructSize = b.getInt();
                    ChunkPart part = new ChunkPart();
                    part.guid[0] = b.getInt(); part.guid[1] = b.getInt(); part.guid[2] = b.getInt(); part.guid[3] = b.getInt();
                    part.offset = b.getInt(); part.size = b.getInt();
                    f.parts.add(part);
                    b.position(partStart + partStructSize);
                }
            }
            b.position(fmlStart + fmlSize);
            Map<String, ChunkInfo> seen = new LinkedHashMap<>(chunkCount * 2);
            for (ChunkInfo c : chunks) seen.put(c.guidStr(), c);
            m.uniqueChunks = new ArrayList<>(seen.values());
            m.files = files;
            return m;
        } catch (Exception e) {
            Log.e(TAG, "manifest parse: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static Manifest parseJsonManifest(byte[] bytes) {
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            int manifestVersion = 0;
            try { manifestVersion = (int) blobToNum(root.optString("ManifestFileVersion", "0")); } catch (NumberFormatException ignored) {}
            Manifest m = new Manifest();
            m.chunkDir = manifestVersion >= 15 ? "ChunksV4" : manifestVersion >= 6 ? "ChunksV3" : manifestVersion >= 3 ? "ChunksV2" : "ChunksV4";
            m.launchExe = root.optString("LaunchExeString", root.optString("LaunchExe", "")).replace('\\', '/');
            m.launchCommand = root.optString("LaunchCommand", "");
            m.buildVersion = root.optString("BuildVersionString", "");
            m.prereqName = root.optString("PrereqName", "");
            m.prereqPath = root.optString("PrereqPath", "").replace('\\', '/');
            m.prereqRead = true;
            JSONObject chunkHashList = root.optJSONObject("ChunkHashList");
            JSONObject dataGroupList = root.optJSONObject("DataGroupList");
            JSONObject chunkFilesizeList = root.optJSONObject("ChunkFilesizeList");
            if (chunkHashList == null) return null;
            Map<String, ChunkInfo> chunkMap = new LinkedHashMap<>();
            Iterator<String> keys = chunkHashList.keys();
            while (keys.hasNext()) {
                String guidHex = keys.next();
                if (guidHex.length() < 32) continue;
                ChunkInfo c = new ChunkInfo();
                for (int k = 0; k < 4; k++) c.guid[k] = (int) Long.parseLong(guidHex.substring(k * 8, k * 8 + 8), 16);
                try { c.hash = blobToNum(chunkHashList.getString(guidHex)); } catch (NumberFormatException ignored) {}
                if (dataGroupList != null) { try { c.groupNum = (int) blobToNum(dataGroupList.optString(guidHex, "0")); } catch (NumberFormatException ignored) {} }
                if (chunkFilesizeList != null) { try { c.fileSize = blobToNum(chunkFilesizeList.optString(guidHex, "0")); } catch (NumberFormatException ignored) {} }
                chunkMap.put(guidHex, c);
            }
            JSONArray fileList = root.optJSONArray("FileManifestList");
            if (fileList == null) return null;
            List<FileInfo> files = new ArrayList<>(fileList.length());
            for (int i = 0; i < fileList.length(); i++) {
                JSONObject fo = fileList.getJSONObject(i);
                FileInfo fi = new FileInfo();
                fi.filename = fo.optString("Filename", "");
                JSONArray tags = fo.optJSONArray("InstallTags");
                if (tags != null) for (int t = 0; t < tags.length(); t++) { String tag = tags.optString(t, ""); if (!tag.isEmpty()) fi.installTags.add(tag); }
                JSONArray parts = fo.optJSONArray("FileChunkParts");
                if (parts != null) for (int j = 0; j < parts.length(); j++) {
                    JSONObject po = parts.getJSONObject(j);
                    ChunkPart part = new ChunkPart();
                    String g = po.optString("Guid", "");
                    if (g.length() >= 32) for (int k = 0; k < 4; k++) part.guid[k] = (int) Long.parseLong(g.substring(k * 8, k * 8 + 8), 16);
                    try { part.offset = (int) blobToNum(po.optString("Offset", "0")); } catch (NumberFormatException ignored) {}
                    try { part.size = (int) blobToNum(po.optString("Size", "0")); } catch (NumberFormatException ignored) {}
                    fi.parts.add(part);
                }
                files.add(fi);
            }
            m.uniqueChunks = new ArrayList<>(chunkMap.values());
            m.files = files;
            return m;
        } catch (Exception e) {
            Log.e(TAG, "json manifest parse: " + e.getClass().getSimpleName());
            return null;
        }
    }

    static long blobToNum(String blob) {
        long num = 0;
        for (int i = 0, shift = 0; i < blob.length(); i += 3, shift += 8) {
            String group = blob.substring(i, Math.min(i + 3, blob.length()));
            for (int k = 0; k < group.length(); k++) {
                char ch = group.charAt(k);
                if (ch < '0' || ch > '9') throw new NumberFormatException("not a blob: " + blob);
            }
            if (shift < 64) num += Long.parseLong(group) << shift;
        }
        return num;
    }

    public static String readFString(ByteBuffer buf) {
        int length = buf.getInt();
        if (length == 0) return "";
        if (length < 0) {
            int chars = (-length) - 1;
            byte[] bytes = new byte[chars * 2];
            buf.get(bytes);
            buf.getShort();
            return new String(bytes, StandardCharsets.UTF_16LE);
        }
        byte[] bytes = new byte[length - 1];
        buf.get(bytes);
        buf.get();
        return new String(bytes, StandardCharsets.US_ASCII);
    }


    public static byte[] downloadBytes(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", UA);
            if (conn.getResponseCode() != 200) { Log.w(TAG, "HTTP " + conn.getResponseCode() + " for " + StoreLog.redactUrl(urlStr)); return null; }
            return EpicAuthClient.readAllBytes(conn.getInputStream());
        } catch (Exception e) {
            Log.w(TAG, "download error [" + StoreLog.redactUrl(urlStr) + "]: " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean downloadChunkStreaming(ChunkInfo chunk, String chunkDir, List<CdnUrl> cdnUrls,
                                                  File outFile, AtomicBoolean cancel, AtomicBoolean stopped,
                                                  Set<HttpURLConnection> connections) {
        String chunkPath = chunk.getPath(chunkDir);
        File tmp = new File(outFile.getPath() + ".part");
        tmp.delete();
        for (CdnUrl cdn : cdnUrls) {
            if (cancel.get() || stopped.get()) return false;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(cdn.baseUrl + cdn.cloudDir + "/" + chunkPath).openConnection();
                connections.add(conn);
                if (cancel.get() || stopped.get()) return false;
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", UA);
                if (conn.getResponseCode() != 200) { conn.disconnect(); continue; }
                MessageDigest sha = null;
                if (chunk.sha1 != null && chunk.sha1.length == 20) {
                    boolean allZero = true;
                    for (byte x : chunk.sha1) if (x != 0) { allZero = false; break; }
                    if (!allZero) { try { sha = MessageDigest.getInstance("SHA-1"); } catch (Exception ignored) {} }
                }
                try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] hdrBuf = new byte[41];
                    readFully(in, hdrBuf);
                    ByteBuffer hdr = ByteBuffer.wrap(hdrBuf).order(ByteOrder.LITTLE_ENDIAN);
                    if (hdr.getInt() != 0xB1FE3AA2) { Log.w(TAG, "bad chunk magic"); continue; }
                    hdr.getInt();
                    int headerSize = hdr.getInt();
                    int compressedSize = hdr.getInt();
                    hdr.position(hdr.position() + 24);
                    int storedAs = hdr.get() & 0xFF;
                    if (headerSize > 41) skipFully(in, headerSize - 41);
                    byte[] iobuf = new byte[131072];
                    long outputBytes = 0;
                    if ((storedAs & 1) != 0) {
                        Inflater inflater = new Inflater();
                        byte[] obuf = new byte[131072];
                        int remaining = compressedSize;
                        try {
                            while (remaining > 0 && !inflater.finished()) {
                                if (cancel.get() || stopped.get()) break;
                                if (inflater.needsInput()) {
                                    int n = in.read(iobuf, 0, Math.min(iobuf.length, remaining));
                                    if (n <= 0) break;
                                    remaining -= n;
                                    inflater.setInput(iobuf, 0, n);
                                }
                                int out = inflater.inflate(obuf);
                                if (out > 0) {
                                    outputBytes = checkedChunkWriteSize(chunk, outputBytes, out);
                                    fos.write(obuf, 0, out);
                                    if (sha != null) sha.update(obuf, 0, out);
                                }
                                if (out == 0 && !inflater.needsInput() && !inflater.finished()) {
                                    throw new IOException("invalid compressed Epic chunk");
                                }
                            }
                            int out;
                            while ((out = inflater.inflate(obuf)) > 0) {
                                if (cancel.get() || stopped.get()) break;
                                outputBytes = checkedChunkWriteSize(chunk, outputBytes, out);
                                fos.write(obuf, 0, out);
                                if (sha != null) sha.update(obuf, 0, out);
                            }
                            if (!cancel.get() && !stopped.get() && !inflater.finished()) throw new IOException("truncated compressed Epic chunk");
                        } finally {
                            inflater.end();
                        }
                    } else {
                        int remaining = compressedSize;
                        while (remaining > 0) {
                            if (cancel.get() || stopped.get()) break;
                            int n = in.read(iobuf, 0, Math.min(iobuf.length, remaining));
                            if (n <= 0) break;
                            outputBytes = checkedChunkWriteSize(chunk, outputBytes, n);
                            fos.write(iobuf, 0, n);
                            if (sha != null) sha.update(iobuf, 0, n);
                            remaining -= n;
                        }
                        if (!cancel.get() && !stopped.get() && remaining != 0) throw new IOException("truncated Epic chunk");
                    }
                }
                if (cancel.get() || stopped.get()) { tmp.delete(); conn.disconnect(); return false; }
                if (chunk.windowSize > 0 && tmp.length() != chunk.windowSize) { tmp.delete(); conn.disconnect(); continue; }
                if (sha != null && !MessageDigest.isEqual(sha.digest(), chunk.sha1)) { tmp.delete(); conn.disconnect(); continue; }
                if (!tmp.renameTo(outFile)) { tmp.delete(); conn.disconnect(); continue; }
                conn.disconnect();
                return true;
            } catch (Exception e) {
                Log.w(TAG, "chunk " + chunk.guidStr() + " failed: " + e.getMessage());
                tmp.delete();
                if (conn != null) conn.disconnect();
            } finally {
                if (conn != null) {
                    connections.remove(conn);
                    conn.disconnect();
                }
                tmp.delete();
            }
        }
        tmp.delete();
        Log.e(TAG, "every CDN failed for chunk " + chunk.guidStr());
        return false;
    }

    private static long checkedChunkWriteSize(ChunkInfo chunk, long written, int count) throws IOException {
        long next = written + count;
        if (next > chunkWindowSize(chunk)) throw new IOException("chunk exceeds expected window size");
        return next;
    }

    private static long chunkWindowSize(ChunkInfo chunk) {
        // Legacy JSON manifests omit window sizes; their chunks use 1 MiB windows.
        return chunk.windowSize > 0 ? chunk.windowSize : 1024L * 1024;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) { int n = in.read(buf, off, buf.length - off); if (n < 0) throw new IOException("stream ended"); off += n; }
    }

    private static void skipFully(InputStream in, int count) throws IOException {
        byte[] skip = new byte[Math.min(count, 4096)];
        int remaining = count;
        while (remaining > 0) { int n = in.read(skip, 0, Math.min(skip.length, remaining)); if (n < 0) throw new IOException("stream ended"); remaining -= n; }
    }


    public static List<FileInfo> getRequiredInstallFiles(Manifest manifest) {
        List<FileInfo> required = new ArrayList<>();
        for (FileInfo f : manifest.files) if (f.installTags.isEmpty()) required.add(f);
        return required.isEmpty() ? new ArrayList<>(manifest.files) : required;
    }

    public static List<FileInfo> resolveInstallFiles(Manifest manifest, List<String> selectedTags) {
        if (selectedTags == null) return new ArrayList<>(manifest.files);
        if (selectedTags.isEmpty()) return getRequiredInstallFiles(manifest);
        Set<String> want = new LinkedHashSet<>(selectedTags);
        List<FileInfo> out = new ArrayList<>();
        for (FileInfo f : manifest.files) {
            if (f.installTags.isEmpty()) { out.add(f); continue; }
            for (String t : f.installTags) if (want.contains(t)) { out.add(f); break; }
        }
        return out.isEmpty() ? getRequiredInstallFiles(manifest) : out;
    }

    public static List<ChunkInfo> uniqueChunksForFiles(Manifest manifest, List<FileInfo> files) {
        Map<String, ChunkInfo> byGuid = new LinkedHashMap<>();
        for (ChunkInfo c : manifest.uniqueChunks) byGuid.put(c.guidStr(), c);
        Set<String> seen = new LinkedHashSet<>();
        List<ChunkInfo> chunks = new ArrayList<>();
        for (FileInfo f : files) for (ChunkPart p : f.parts) { String g = p.guidStr(); if (seen.add(g)) { ChunkInfo c = byGuid.get(g); if (c != null) chunks.add(c); } }
        return chunks;
    }

    public static boolean fileExistsWithCorrectHash(File outputFile, long expectedSize, byte[] expectedHash) {
        if (outputFile == null || !outputFile.exists() || outputFile.length() != expectedSize) return false;
        if (expectedHash == null || expectedHash.length != 20) return false;
        boolean allZero = true;
        for (byte x : expectedHash) if (x != 0) { allZero = false; break; }
        if (allZero) return false;
        try (FileInputStream fis = new FileInputStream(outputFile)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) != -1) digest.update(buf, 0, n);
            return MessageDigest.isEqual(digest.digest(), expectedHash);
        } catch (Exception e) {
            return false;
        }
    }

    public static String[] fetchPrereq(String manifestApiJson) {
        try {
            List<CdnUrl> cdnUrls = parseCdnUrls(manifestApiJson);
            if (cdnUrls.isEmpty()) return null;
            byte[] bytes = downloadManifest(manifestApiJson, cdnUrls);
            if (bytes == null) return null;
            Manifest m = parseManifest(bytes);
            if (m == null || !m.prereqRead) { Log.w(TAG, "manifest prerequisite not read"); return null; }
            Log.i(TAG, "manifest " + m.buildVersion + ": " + m.files.size() + " files, prerequisite " + (m.prereqName.isEmpty() && m.prereqPath.isEmpty() ? "none" : m.prereqName + " (" + m.prereqPath + ")"));
            return new String[] { m.prereqName, m.prereqPath };
        } catch (Exception e) {
            return null;
        }
    }

    public static long fetchInstallSizeBytes(String manifestApiJson, List<String> installTags) {
        try {
            List<CdnUrl> cdnUrls = parseCdnUrls(manifestApiJson);
            if (cdnUrls.isEmpty()) return -1;
            byte[] bytes = downloadManifest(manifestApiJson, cdnUrls);
            if (bytes == null) return -1;
            Manifest m = parseManifest(bytes);
            if (m == null) return -1;
            long total = 0;
            for (FileInfo f : resolveInstallFiles(m, installTags)) total += Math.max(f.fileSize(), 0);
            return total > 0 ? total : -1;
        } catch (Exception e) {
            return -1;
        }
    }


    static void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        dir.delete();
    }

    private static String fmt(long bytes) {
        if (bytes >= 1073741824L) return String.format("%.1f GB", bytes / 1073741824.0);
        if (bytes >= 1048576L) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.0f KB", bytes / 1024.0);
    }
}
