package com.droiddeck.launcher.stores.epic;

import android.content.Context;
import android.util.Log;

import com.droiddeck.launcher.stores.StoreLog;

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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Inflater;

public final class EpicDownloadManager {

    private static final String TAG = "EpicDownload";
    private static final String UA = EpicAuthClient.USER_AGENT;
    /** Keep reusable chunk data bounded even when a manifest shares chunks across distant files. */
    static final long MAX_RETAINED_CHUNK_CACHE_BYTES = 64L * 1024 * 1024;

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
            cb.onLog("epic: chunkDir=" + manifest.chunkDir + " chunks=" + manifest.uniqueChunks.size() + " files=" + manifest.files.size() + " exe=" + manifest.launchExe);
            return installManifest(manifest, installDirPath, chunkCacheDirPath, installTags, cancel, cb);
        } catch (InstallException e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "install failed", e);
            throw new InstallException(e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    static Result installManifest(Manifest manifest, String installDirPath, String chunkCacheDirPath,
                                  List<String> installTags, AtomicBoolean cancel, Callback cb) throws InstallException {
        try {
            List<CdnUrl> cdnUrls = manifest.cdnUrls;
            File installDir = new File(installDirPath);
            installDir.mkdirs();
            final String cachePath = chunkCacheDirPath == null ? "" : chunkCacheDirPath;
            final File chunkCacheDir = cachePath.isEmpty() ? new File(installDir, ".chunks") : new File(cachePath);
            chunkCacheDir.mkdirs();

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
            Map<String, Long> cachedChunkBytes = prepareBoundedCache(chunkCacheDir, needed);
            long pendingBytes = 0;
            long reclaimableBytes = 0;
            for (FileInfo f : pending) {
                pendingBytes += f.fileSize();
                File output = new File(installDir, f.filename.replace("\\", "/"));
                if (output.isFile()) reclaimableBytes += output.length();
            }
            boolean cacheOnInstallVolume = cachePath.isEmpty();
            long peakGameAdditional = cacheOnInstallVolume
                    ? estimatePeakAdditionalBytes(pending, chunksByGuid, cachedChunkBytes, true)
                    : pendingBytes;
            long peakCacheAdditional = cacheOnInstallVolume
                    ? 0 : estimatePeakAdditionalBytes(pending, chunksByGuid, cachedChunkBytes, false);
            peakGameAdditional = Math.max(0, peakGameAdditional - reclaimableBytes);
            long freeGame = installDir.getUsableSpace();
            if (cacheOnInstallVolume) {
                if (freeGame > 0 && peakGameAdditional > freeGame) throw new InstallException("Not enough free space for the bounded Epic install: need up to " + fmt(peakGameAdditional) + " additional, only " + fmt(freeGame) + " free");
            } else {
                long freeCache = chunkCacheDir.getUsableSpace();
                if (freeCache > 0 && peakCacheAdditional > freeCache) throw new InstallException("Not enough internal space for the bounded Epic download cache: need up to " + fmt(peakCacheAdditional) + " additional, only " + fmt(freeCache) + " free");
                if (freeGame > 0 && peakGameAdditional > freeGame) throw new InstallException("Not enough free space: need " + fmt(peakGameAdditional) + ", only " + fmt(freeGame) + " free");
            }
            if (cancel.get()) return null;
            // Reclaim invalid/old outputs only after both volume checks accept the update.
            // The peak estimate assumes these bytes are released before replacement writes.
            for (FileInfo f : pending) {
                File output = new File(installDir, f.filename.replace("\\", "/"));
                File parent = output.getParentFile();
                if (parent != null) parent.mkdirs();
                if (output.isFile() && !output.delete() && output.exists()) throw new InstallException("Could not replace incomplete file " + output.getName());
            }
            cb.onLog("epic: cache " + (cacheOnInstallVolume ? "beside the game" : "scratch") + ", peak game additional=" + fmt(peakGameAdditional) + (cacheOnInstallVolume ? "" : ", peak cache additional=" + fmt(peakCacheAdditional)) + " for " + fmt(pendingBytes) + " of files");
            final long fTotalBytes = pendingBytes;
            final int totalChunks = needed.size();
            cb.onSizes(fTotalBytes, planned);
            cb.onBytes(0, fTotalBytes, 0);
            cb.onLog("epic: " + fmt(fTotalBytes) + " to write using " + totalChunks + " unique chunks");
            cb.onLog("epic: engine=bounded-java (shared path; native availability does not change cache semantics)");
            Map<String, Integer> remainingRefs = chunkReferences(pending);
            long completedBytes = 0, writtenBytes = 0;
            long transferStarted = System.nanoTime();
            int completedCount = 0, doneFiles = 0, totalFiles = pending.size();
            for (FileInfo file : pending) {
                if (cancel.get()) return null;
                String relPath = file.filename.replace("\\", "/");
                File outFile = new File(installDir, relPath);
                cb.onProgress("Downloading chunks: " + (relPath.contains("/") ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath), (int) (completedBytes * 80L / Math.max(1, fTotalBytes)));
                // Like the native assembler, write to the final path and remove it on failure.
                // A sibling "<name>.part" can itself be a legitimate file in the manifest.
                File partFile = outFile;
                MessageDigest fileSha = expectedDigest(file.sha1);
                try (FileOutputStream fos = new FileOutputStream(partFile); BufferedOutputStream bos = new BufferedOutputStream(fos, 65536)) {
                    for (ChunkPart part : file.parts) {
                        if (cancel.get()) throw new IOException("cancelled");
                        String guid = part.guidStr();
                        ChunkInfo chunk = chunksByGuid.get(guid);
                        if (chunk == null) throw new InstallException("Missing chunk metadata " + guid + " for " + relPath);
                        File cachedChunk = new File(chunkCacheDir, guid);
                        if (!cachedChunk.isFile() || !isValidCachedChunk(chunk, cachedChunk)) {
                            if (cachedChunk.exists() && !cachedChunk.delete() && cachedChunk.exists()) {
                                throw new InstallException("Could not clear invalid Epic chunk " + guid);
                            }
                            cachedChunkBytes.remove(guid);
                            cachedChunk = new File(chunkCacheDir, guid);
                            if (!downloadChunkStreaming(chunk, manifest.chunkDir, cdnUrls, cachedChunk, cancel)) {
                                if (cancel.get()) throw new IOException("cancelled");
                                throw new InstallException("Failed to download Epic chunk " + guid);
                            }
                            cachedChunkBytes.put(guid, cachedChunk.length());
                        }
                        completedCount++;
                        cb.onProgress("Downloading chunks (part " + completedCount + ")", (int) (completedBytes * 80L / Math.max(1, fTotalBytes)));
                        copyChunkPart(cachedChunk, part, bos, fileSha, cancel, relPath);
                        completedBytes += part.size & 0xFFFFFFFFL;
                        long elapsedMs = Math.max(1, (System.nanoTime() - transferStarted) / 1_000_000);
                        cb.onBytes(completedBytes, fTotalBytes, completedBytes * 1000 / elapsedMs);
                        Integer refs = remainingRefs.get(guid);
                        if (refs != null) {
                            if (refs <= 1) {
                                remainingRefs.remove(guid);
                                if (cachedChunk.exists() && !cachedChunk.delete() && cachedChunk.exists()) {
                                    throw new InstallException("Could not release consumed Epic chunk " + guid);
                                }
                                cachedChunkBytes.remove(guid);
                            } else remainingRefs.put(guid, refs - 1);
                        }
                        trimCacheToBound(chunkCacheDir, cachedChunkBytes);
                    }
                    bos.flush();
                } catch (IOException e) {
                    partFile.delete();
                    if (cancel.get()) return null;
                    throw new InstallException("Could not assemble " + relPath + ": " + e.getMessage());
                } catch (InstallException e) {
                    partFile.delete();
                    throw e;
                }
                if (cancel.get()) { partFile.delete(); return null; }
                if (fileSha != null && !MessageDigest.isEqual(fileSha.digest(), file.sha1)) {
                    partFile.delete();
                    throw new InstallException("SHA-1 mismatch for " + relPath);
                }
                doneFiles++;
                writtenBytes += file.fileSize();
                cb.onStage("stream", writtenBytes, pendingBytes, doneFiles, totalFiles);
            }
            deleteDir(chunkCacheDir);
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
        long retained = 0;
        for (long bytes : cacheSizes.values()) retained += bytes;
        java.util.Iterator<Map.Entry<String, Long>> iterator = cacheSizes.entrySet().iterator();
        while (retained > MAX_RETAINED_CHUNK_CACHE_BYTES && iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            File chunk = new File(cacheDir, entry.getKey());
            long bytes = entry.getValue();
            if (chunk.exists() && !chunk.delete() && chunk.exists()) {
                throw new InstallException("Could not release Epic cache chunk " + entry.getKey());
            }
            retained -= bytes;
            iterator.remove();
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
     * Simulates the serial fetch → append part → last-reference eviction order. The returned
     * additional-space peak is measured against the current free-space reading: existing verified
     * chunks are already reflected in that reading and count as reclaimed only when actually freed.
     */
    static long estimatePeakAdditionalBytes(List<FileInfo> files, Map<String, ChunkInfo> chunksByGuid,
                                            Map<String, Long> cachedChunkBytes, boolean includeFileWrites) {
        Map<String, Integer> refs = chunkReferences(files);
        Map<String, Long> residentChunks = new LinkedHashMap<>(cachedChunkBytes);
        long residentDelta = 0, peak = 0;
        long cacheResident = 0;
        for (long bytes : residentChunks.values()) cacheResident += bytes;
        for (FileInfo file : files) {
            for (ChunkPart part : file.parts) {
                String guid = part.guidStr();
                ChunkInfo chunk = chunksByGuid.get(guid);
                if (chunk == null) throw new IllegalArgumentException("Missing chunk metadata " + guid);
                Long knownBytes = residentChunks.get(guid);
                long chunkBytes = knownBytes != null ? knownBytes : chunkWindowSize(chunk);
                if (knownBytes == null) {
                    residentChunks.put(guid, chunkBytes);
                    cacheResident += chunkBytes;
                    residentDelta += chunkBytes;
                    peak = Math.max(peak, residentDelta);
                }
                if (includeFileWrites) {
                    residentDelta += part.size & 0xFFFFFFFFL;
                    peak = Math.max(peak, residentDelta);
                }
                int count = refs.get(guid);
                if (count <= 1) {
                    refs.remove(guid);
                    Long removed = residentChunks.remove(guid);
                    if (removed != null) { cacheResident -= removed; residentDelta -= removed; }
                } else refs.put(guid, count - 1);
                java.util.Iterator<Map.Entry<String, Long>> iterator = residentChunks.entrySet().iterator();
                while (cacheResident > MAX_RETAINED_CHUNK_CACHE_BYTES && iterator.hasNext()) {
                    Map.Entry<String, Long> entry = iterator.next();
                    if (refs.containsKey(entry.getKey())) {
                        long removed = entry.getValue();
                        cacheResident -= removed;
                        residentDelta -= removed;
                        iterator.remove();
                    }
                }
            }
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

    private static boolean downloadChunkStreaming(ChunkInfo chunk, String chunkDir, List<CdnUrl> cdnUrls, File outFile, AtomicBoolean cancel) {
        String chunkPath = chunk.getPath(chunkDir);
        File tmp = new File(outFile.getPath() + ".part");
        tmp.delete();
        for (CdnUrl cdn : cdnUrls) {
            if (cancel.get()) return false;
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
                    long outputBytes = 0;
                    if ((storedAs & 1) != 0) {
                        Inflater inflater = new Inflater();
                        byte[] obuf = new byte[131072];
                        int remaining = compressedSize;
                        try {
                            while (remaining > 0 && !inflater.finished()) {
                                if (cancel.get()) break;
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
                                outputBytes = checkedChunkWriteSize(chunk, outputBytes, out);
                                fos.write(obuf, 0, out);
                                if (sha != null) sha.update(obuf, 0, out);
                            }
                            if (!cancel.get() && !inflater.finished()) throw new IOException("truncated compressed Epic chunk");
                        } finally {
                            inflater.end();
                        }
                    } else {
                        int remaining = compressedSize;
                        while (remaining > 0) {
                            if (cancel.get()) break;
                            int n = in.read(iobuf, 0, Math.min(iobuf.length, remaining));
                            if (n <= 0) break;
                            outputBytes = checkedChunkWriteSize(chunk, outputBytes, n);
                            fos.write(iobuf, 0, n);
                            if (sha != null) sha.update(iobuf, 0, n);
                            remaining -= n;
                        }
                        if (!cancel.get() && remaining != 0) throw new IOException("truncated Epic chunk");
                    }
                }
                if (cancel.get()) { tmp.delete(); conn.disconnect(); return false; }
                if (chunk.windowSize > 0 && tmp.length() != chunk.windowSize) { tmp.delete(); conn.disconnect(); continue; }
                if (sha != null && !MessageDigest.isEqual(sha.digest(), chunk.sha1)) { tmp.delete(); conn.disconnect(); continue; }
                if (!tmp.renameTo(outFile)) { tmp.delete(); conn.disconnect(); continue; }
                conn.disconnect();
                return true;
            } catch (Exception e) {
                Log.w(TAG, "chunk " + chunk.guidStr() + " failed: " + e.getMessage());
                tmp.delete();
                if (conn != null) conn.disconnect();
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
