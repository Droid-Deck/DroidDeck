package com.droiddeck.launcher.stores.epic;

import android.content.Context;
import android.util.Log;

import com.droiddeck.launcher.stores.StoreLog;
import com.droiddeck.launcher.stores.download.StoreDownloadTier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Inflater;

/**
 * The Epic install pipeline, ported from Bannerlator's EpicDownloadManager: the manifest API's CDN
 * list, the manifest binary (ChunksV4, or the older JSON form), the files narrowed to the base
 * set plus the device's language, a delta pass that skips files already on disk with the right
 * size and SHA-1, the chunk fetch into `.chunks/<GUID>` (the native engine when it is there, the
 * Java pool otherwise), and the assembly of files from those chunks.
 *
 * Chunk URLs carry no auth tokens; the subfolder is the decimal group number; the Cloudflare
 * mirror is skipped (it refuses chunks). Blocking; the download queue runs it on its own thread.
 */
public final class EpicDownloadManager {

    private static final String TAG = "EpicDownload";
    private static final String UA = EpicAuthClient.USER_AGENT;

    public interface Callback {
        void onProgress(String message, int pct);
        default void onBytes(long done, long total, long speedBps) {}
        default void onLog(String line) {}
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

    /** The parsed manifest: its chunks, files, and from the meta block the exe the launcher starts. */
    public static final class Manifest {
        public String chunkDir = "ChunksV4";
        public List<ChunkInfo> uniqueChunks = new ArrayList<>();
        public List<FileInfo> files = new ArrayList<>();
        public String launchExe = "";
        public String launchCommand = "";
        public String buildVersion = "";
        public List<CdnUrl> cdnUrls = new ArrayList<>();
    }

    /** What an install leaves for the sidecar. */
    public static final class Result {
        public final String launchExe;
        public final String buildVersion;
        public final long bytes;
        Result(String launchExe, String buildVersion, long bytes) { this.launchExe = launchExe; this.buildVersion = buildVersion; this.bytes = bytes; }
    }

    public static final class InstallException extends Exception {
        InstallException(String message) { super(message); }
    }

    /**
     * Installs the app the manifest API JSON describes into {@code installDir}. {@code installTags}
     * null = every file. Returns null when cancelled; throws when it fails.
     */
    public static Result install(Context ctx, String manifestApiJson, String installDirPath, List<String> installTags, AtomicBoolean cancel, Callback cb) throws InstallException {
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
            cb.onLog("epic: chunkDir=" + manifest.chunkDir + " chunks=" + manifest.uniqueChunks.size() + " files=" + manifest.files.size() + " exe=" + manifest.launchExe);

            File installDir = new File(installDirPath);
            installDir.mkdirs();
            File chunkCacheDir = new File(installDir, ".chunks");
            chunkCacheDir.mkdirs();

            List<FileInfo> selected = resolveInstallFiles(manifest, installTags);
            cb.onLog("epic: " + selected.size() + "/" + manifest.files.size() + " files for tags " + (installTags == null ? "(all)" : installTags.toString()));
            long installBytes = 0;
            for (FileInfo f : selected) installBytes += f.fileSize();
            final long planned = installBytes;
            long usable = installDir.getUsableSpace();
            if (planned > 0 && usable > 0 && planned > usable) throw new InstallException("Not enough free space: need " + fmt(planned) + ", only " + fmt(usable) + " free");

            cb.onProgress("Verifying existing files…", 0);
            List<FileInfo> pending = new ArrayList<>(selected.size());
            int checked = 0, good = 0;
            for (FileInfo f : selected) {
                if (cancel.get()) return null;
                File out = new File(installDir, f.filename.replace("\\", "/"));
                if (fileExistsWithCorrectHash(out, f.fileSize(), f.sha1)) good++; else pending.add(f);
                checked++;
                if ((checked & 63) == 0) cb.onProgress("Verifying existing files… (" + checked + "/" + selected.size() + ")", 0);
            }
            cb.onLog("epic: delta " + good + " up to date, " + pending.size() + " to download");
            if (pending.isEmpty()) {
                deleteDir(chunkCacheDir);
                cb.onProgress("Complete", 100);
                return new Result(manifest.launchExe, manifest.buildVersion, planned);
            }
            List<ChunkInfo> needed = uniqueChunksForFiles(manifest, pending);
            long totalBytes = 0;
            for (ChunkInfo c : needed) totalBytes += Math.max(c.fileSize, 1);
            final long fTotalBytes = totalBytes;
            final int totalChunks = needed.size();
            final AtomicLong completedBytes = new AtomicLong(0);
            final AtomicInteger completedCount = new AtomicInteger(0);
            final AtomicInteger failCount = new AtomicInteger(0);
            final AtomicLong lastSpeedMs = new AtomicLong(System.currentTimeMillis());
            final AtomicLong lastSpeedBytes = new AtomicLong(0);
            final AtomicLong speedBps = new AtomicLong(0);
            cb.onBytes(0, fTotalBytes, 0);
            cb.onLog("epic: " + fmt(fTotalBytes) + " in " + totalChunks + " chunks");

            boolean javaPool = true;
            if (com.droiddeck.launcher.stores.StoresNative.INSTANCE.getAvailable()) {
                NativeOutcome r = runNativePool(ctx, manifestBytes, manifest, pending, needed, fTotalBytes, installDirPath, cdnUrls, cancel, cb,
                        completedBytes, completedCount, lastSpeedMs, lastSpeedBytes, speedBps);
                if (r.started) {
                    javaPool = false;
                    if (r.cancelled) return null;
                    if (!r.success) { cb.onLog("epic: engine failed: " + r.error); failCount.incrementAndGet(); }
                } else cb.onLog("epic: engine not started (" + r.error + "); using the built-in pool");
            }
            if (javaPool) {
                cb.onLog("epic: engine=built-in (8 threads)");
                ExecutorService pool = Executors.newFixedThreadPool(8);
                for (ChunkInfo chunk : needed) {
                    final ChunkInfo fc = chunk;
                    pool.submit(() -> {
                        if (cancel.get()) return;
                        File cached = new File(chunkCacheDir, fc.guidStr());
                        if (!cached.exists() && !downloadChunkStreaming(fc, manifest.chunkDir, cdnUrls, cached)) {
                            cb.onLog("FAIL chunk=" + fc.guidStr());
                            failCount.incrementAndGet();
                            return;
                        }
                        long done = completedBytes.addAndGet(Math.max(fc.fileSize, 1));
                        int cnt = completedCount.incrementAndGet();
                        sampleSpeed(done, lastSpeedMs, lastSpeedBytes, speedBps);
                        cb.onProgress("Downloading chunks (" + cnt + "/" + totalChunks + ")" + speedSuffix(speedBps.get()), (int) (done * 80L / Math.max(1, fTotalBytes)));
                        cb.onBytes(done, fTotalBytes, speedBps.get());
                    });
                }
                pool.shutdown();
                try {
                    while (!pool.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                        if (cancel.get()) { pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); return null; }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pool.shutdownNow();
                    throw new InstallException("interrupted");
                }
            }
            if (cancel.get()) return null;
            if (failCount.get() > 0) throw new InstallException(failCount.get() + " chunk(s) failed to download");

            int totalFiles = pending.size(), doneFiles = 0;
            for (FileInfo file : pending) {
                if (cancel.get()) return null;
                String relPath = file.filename.replace("\\", "/");
                File outFile = new File(installDir, relPath);
                File parent = outFile.getParentFile();
                if (parent != null) parent.mkdirs();
                cb.onProgress("Writing: " + (relPath.contains("/") ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath), 80 + (int) (doneFiles * 20L / totalFiles));
                try (FileOutputStream fos = new FileOutputStream(outFile); BufferedOutputStream bos = new BufferedOutputStream(fos, 65536)) {
                    for (ChunkPart part : file.parts) {
                        File cachedChunk = new File(chunkCacheDir, part.guidStr());
                        if (!cachedChunk.exists()) throw new InstallException("Missing chunk " + part.guidStr() + " for " + relPath);
                        byte[] data = readFile(cachedChunk);
                        bos.write(data, part.offset, part.size);
                    }
                }
                doneFiles++;
            }
            deleteDir(chunkCacheDir);
            cb.onProgress("Complete", 100);
            return new Result(manifest.launchExe, manifest.buildVersion, planned);
        } catch (InstallException e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "install failed", e);
            throw new InstallException(e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    private static final class NativeOutcome { boolean started, success, cancelled; String error = ""; }

    /** The native chunk pool on the same counters; never throws. */
    private static NativeOutcome runNativePool(Context ctx, byte[] manifestBytes, Manifest manifest, List<FileInfo> pending, List<ChunkInfo> needed, long totalBytes,
                                               String installDirPath, List<CdnUrl> cdnUrls, AtomicBoolean cancel, Callback cb,
                                               AtomicLong completedBytes, AtomicInteger completedCount, AtomicLong lastSpeedMs, AtomicLong lastSpeedBytes, AtomicLong speedBps) {
        NativeOutcome r = new NativeOutcome();
        try {
            java.util.IdentityHashMap<FileInfo, Integer> index = new java.util.IdentityHashMap<>();
            for (int i = 0; i < manifest.files.size(); i++) index.put(manifest.files.get(i), i);
            int[] pendingIdx = new int[pending.size()];
            for (int i = 0; i < pending.size(); i++) {
                Integer k = index.get(pending.get(i));
                if (k == null) { r.error = "pending file not in manifest"; return r; }
                pendingIdx[i] = k;
            }
            String[] prefixes = new String[cdnUrls.size()];
            for (int i = 0; i < cdnUrls.size(); i++) prefixes[i] = cdnUrls.get(i).baseUrl + cdnUrls.get(i).cloudDir;
            final int totalChunks = needed.size();
            StoreDownloadTier tier = StoreDownloadTier.Companion.current(ctx);
            int workers = Math.max(1, Math.min(128, tier.getNetworkWindow()));
            int process = Math.max(2, tier.getProcessWorkers());
            cb.onLog("epic: engine=native workers=" + workers + " process_workers=" + process + " tier=" + tier.getId());
            EpicNative.Result res = EpicNative.run(manifestBytes, installDirPath, prefixes, pendingIdx, totalChunks, totalBytes, "", workers, process, cancel,
                    new EpicNative.Listener() {
                        @Override public void onPlan(int chunksTotal, long bytesTotal, String chunkDir) { cb.onLog("epic: plan chunks=" + chunksTotal + " bytes=" + bytesTotal + " dir=" + chunkDir); }
                        @Override public void onProgress(long bytesDone, long bytesTotal, int chunksDone, int chunksTotal) {
                            completedBytes.set(bytesDone);
                            completedCount.set(chunksDone);
                            sampleSpeed(bytesDone, lastSpeedMs, lastSpeedBytes, speedBps);
                            cb.onProgress("Downloading chunks (" + chunksDone + "/" + totalChunks + ")" + speedSuffix(speedBps.get()), (int) (bytesDone * 80L / Math.max(1, totalBytes)));
                            cb.onBytes(bytesDone, totalBytes, speedBps.get());
                        }
                        @Override public void onLog(String line) { cb.onLog(line); }
                        @Override public void onComplete(boolean success, String error, long bytesCredited) {}
                    });
            r.started = res.started; r.success = res.success; r.cancelled = res.cancelled; r.error = res.error == null ? "" : res.error;
        } catch (Throwable t) {
            r.started = false;
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return r;
    }

    // ---- CDN list and manifest download ---------------------------------------------------------

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

    /** The manifest binary from the first CDN that serves it; the auth query goes on this URL only. */
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

    // ---- manifest parsing -----------------------------------------------------------------------

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
            // ManifestMeta: the launch exe and build version live here; the rest is skipped by size.
            int metaStart = b.position();
            int metaSize = b.getInt();
            try {
                b.get();            // data version
                b.getInt();         // feature level
                b.get();            // isFileData
                b.getInt();         // app id
                readFString(b);     // app name
                m.buildVersion = readFString(b);
                m.launchExe = readFString(b).replace('\\', '/');
                m.launchCommand = readFString(b);
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
            try { manifestVersion = Integer.parseInt(root.optString("ManifestFileVersion", "0")); } catch (NumberFormatException ignored) {}
            Manifest m = new Manifest();
            m.chunkDir = manifestVersion >= 15 ? "ChunksV4" : manifestVersion >= 6 ? "ChunksV3" : manifestVersion >= 3 ? "ChunksV2" : "ChunksV4";
            m.launchExe = root.optString("LaunchExe", "").replace('\\', '/');
            m.launchCommand = root.optString("LaunchCommand", "");
            m.buildVersion = root.optString("BuildVersionString", "");
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
                String hashHex = chunkHashList.getString(guidHex);
                if (hashHex != null && hashHex.length() >= 16) { try { c.hash = Long.parseUnsignedLong(hashHex.substring(0, 16), 16); } catch (Exception ignored) {} }
                if (dataGroupList != null) { try { c.groupNum = Integer.parseInt(dataGroupList.optString(guidHex, "0")); } catch (NumberFormatException ignored) {} }
                if (chunkFilesizeList != null) { try { c.fileSize = Long.parseLong(chunkFilesizeList.optString(guidHex, "0"), 16); } catch (NumberFormatException ignored) {} }
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
                    try { part.offset = Integer.parseInt(po.optString("Offset", "0")); } catch (NumberFormatException ignored) {}
                    try { part.size = Integer.parseInt(po.optString("Size", "0")); } catch (NumberFormatException ignored) {}
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

    // ---- chunk fetch (the built-in pool) --------------------------------------------------------

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

    /** One chunk streamed from the first CDN that serves it, SHA-1 checked, published by rename. */
    private static boolean downloadChunkStreaming(ChunkInfo chunk, String chunkDir, List<CdnUrl> cdnUrls, File outFile) {
        String chunkPath = chunk.getPath(chunkDir);
        File tmp = new File(outFile.getPath() + ".part");
        tmp.delete();
        for (CdnUrl cdn : cdnUrls) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(cdn.baseUrl + cdn.cloudDir + "/" + chunkPath).openConnection();
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
                    if ((storedAs & 1) != 0) {
                        Inflater inflater = new Inflater();
                        byte[] obuf = new byte[131072];
                        int remaining = compressedSize;
                        try {
                            while (remaining > 0 && !inflater.finished()) {
                                if (inflater.needsInput()) {
                                    int n = in.read(iobuf, 0, Math.min(iobuf.length, remaining));
                                    if (n <= 0) break;
                                    remaining -= n;
                                    inflater.setInput(iobuf, 0, n);
                                }
                                int out = inflater.inflate(obuf);
                                if (out > 0) { fos.write(obuf, 0, out); if (sha != null) sha.update(obuf, 0, out); }
                            }
                            int out;
                            while ((out = inflater.inflate(obuf)) > 0) { fos.write(obuf, 0, out); if (sha != null) sha.update(obuf, 0, out); }
                        } finally {
                            inflater.end();
                        }
                    } else {
                        int remaining = compressedSize;
                        while (remaining > 0) {
                            int n = in.read(iobuf, 0, Math.min(iobuf.length, remaining));
                            if (n <= 0) break;
                            fos.write(iobuf, 0, n);
                            if (sha != null) sha.update(iobuf, 0, n);
                            remaining -= n;
                        }
                    }
                }
                if (sha != null && !MessageDigest.isEqual(sha.digest(), chunk.sha1)) { tmp.delete(); conn.disconnect(); continue; }
                if (!tmp.renameTo(outFile)) { tmp.delete(); conn.disconnect(); continue; }
                conn.disconnect();
                return true;
            } catch (Exception e) {
                tmp.delete();
                if (conn != null) conn.disconnect();
            }
        }
        tmp.delete();
        Log.e(TAG, "every CDN failed for chunk " + chunk.guidStr());
        return false;
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

    // ---- selection and verification -------------------------------------------------------------

    public static List<FileInfo> getRequiredInstallFiles(Manifest manifest) {
        List<FileInfo> required = new ArrayList<>();
        for (FileInfo f : manifest.files) if (f.installTags.isEmpty()) required.add(f);
        return required.isEmpty() ? new ArrayList<>(manifest.files) : required;
    }

    /** Base files plus any file carrying one of the tags; null tags = every file; nothing matched = base only. */
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

    /** The install size for the tags (null = every file), from the manifest alone; -1 on failure. Blocking. */
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

    // ---- helpers --------------------------------------------------------------------------------

    static byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) { int r = fis.read(data, off, data.length - off); if (r < 0) break; off += r; }
            return data;
        }
    }

    static void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        dir.delete();
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
