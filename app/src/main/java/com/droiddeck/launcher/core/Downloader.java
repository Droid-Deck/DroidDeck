package com.droiddeck.launcher.core;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class Downloader {
    private static final String TAG = "Downloader";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int STALL_TIMEOUT_MS = 15_000;
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;
    private static final java.util.concurrent.ExecutorService CANCEL_EXECUTOR =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "download-cancel");
                thread.setDaemon(true);
                return thread;
            });

    private Downloader() {}

    public static final class Cancellation implements java.util.function.BooleanSupplier {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
        private final AtomicReference<InputStream> input = new AtomicReference<>();
        private final java.util.function.BooleanSupplier external;

        public Cancellation() { this(null); }
        public Cancellation(java.util.function.BooleanSupplier external) { this.external = external; }

        public void cancel() {
            cancelled.set(true);
            HttpURLConnection active = connection.get();
            if (active != null) disconnectAsync(active);
            InputStream activeInput = input.get();
            if (activeInput != null) closeAsync(activeInput);
        }

        private static void disconnectAsync(HttpURLConnection active) {
            CANCEL_EXECUTOR.execute(() -> {
                try { active.disconnect(); } catch (RuntimeException ignored) { }
            });
        }

        private static void closeAsync(InputStream active) {
            CANCEL_EXECUTOR.execute(() -> {
                try { active.close(); } catch (IOException ignored) { }
            });
        }

        @Override public boolean getAsBoolean() {
            return cancelled.get() || (external != null && external.getAsBoolean());
        }

        private void attach(HttpURLConnection active) {
            connection.set(active);
            if (getAsBoolean()) disconnectAsync(active);
        }

        private void detach(HttpURLConnection active) { connection.compareAndSet(active, null); }

        private void attach(InputStream active) {
            input.set(active);
            if (getAsBoolean()) closeAsync(active);
        }

        private void detach(InputStream active) { input.compareAndSet(active, null); }
    }

    public static String downloadString(String url) {
        return downloadString(url, null);
    }

    public static String downloadString(String url, java.util.function.BooleanSupplier cancelled) {
        List<String> sources = DownloadSource.INSTANCE.candidates(url);
        for (int i = 0; i < sources.size(); i++) {
            if (isCancelled(cancelled)) return null;
            if (i > 0) Log.i(TAG, "retrying " + url + " via " + sources.get(i));
            Fetch fetch = fetch(sources.get(i), cancelled);
            // A cancelled fetch says nothing about the source, so it must not count as a failure.
            if (isCancelled(cancelled)) return null;
            if (fetch.body != null) {
                DownloadSource.INSTANCE.succeeded(sources.get(i));
                return fetch.body;
            }
            if (fetch.fatal && sources.get(i).equals(url)) return null;
        }
        DownloadSource.INSTANCE.failed();
        return null;
    }

    /** Fetches without the mirror list, for a URL whose body is a digest the payload is checked against. */
    public static String downloadDirect(String url) {
        return fetch(url).body;
    }

    public static boolean downloadFile(String url, File destination, boolean resume,
                                       java.util.function.Consumer<Float> progress) {
        return downloadFile(url, destination, resume, null, progress);
    }

    /** Verify each candidate before accepting it, so a bad mirror can fall back. */
    public static boolean downloadFile(String url, File destination, boolean resume, String expectedSha256,
                                       java.util.function.Consumer<Float> progress) {
        return downloadFile(url, destination, resume, expectedSha256, null, progress);
    }

    /** {@code cancelled} is polled while bytes arrive; a cancel stops at once and leaves the partial file for the caller. */
    public static boolean downloadFile(String url, File destination, boolean resume, String expectedSha256,
                                       java.util.function.BooleanSupplier cancelled,
                                       java.util.function.Consumer<Float> progress) {
        if (expectedSha256 != null && !expectedSha256.matches("(?i)[0-9a-f]{64}"))
            throw new IllegalArgumentException("Expected SHA-256 must be 64 hex characters");
        return downloadCandidates(url, sources(url, expectedSha256), destination, resume, expectedSha256, cancelled, progress);
    }

    /** A third-party mirror may only serve bytes that can be checked against a digest from a direct source. */
    static List<String> sources(String url, String expectedSha256) {
        return expectedSha256 == null ? java.util.Collections.singletonList(url) : DownloadSource.INSTANCE.candidates(url);

    }

    // Package-private for the local multi-source HTTP test.
    static boolean downloadCandidates(String original, List<String> sources, File destination, boolean resume,
                                      String expectedSha256, java.util.function.Consumer<Float> progress) {
        return downloadCandidates(original, sources, destination, resume, expectedSha256, null, progress);
    }

    static boolean downloadCandidates(String original, List<String> sources, File destination, boolean resume,
                                      String expectedSha256, java.util.function.BooleanSupplier cancelled,
                                      java.util.function.Consumer<Float> progress) {
        Identity identity = loadIdentity(destination, resume, expectedSha256);
        for (int i = 0; i < sources.size(); i++) {
            String source = sources.get(i);
            if (isCancelled(cancelled)) return false;
            if (i > 0) Log.i(TAG, "retrying " + original + " via " + source);
            Outcome outcome = attempt(source, destination, resume, identity, expectedSha256, cancelled, progress);
            // A cancel is not a source failure: no other mirror, and no DownloadSource.failed().
            if (outcome == Outcome.CANCELLED) return false;
            if (outcome == Outcome.DONE) {
                if (expectedSha256 != null) {
                    String actualSha256 = Hashes.sha256(destination);
                    if (isCancelled(cancelled)) return false;
                    if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                        Log.w(TAG, "checksum mismatch from " + source + "; trying another source");
                        if (!discardPartial(destination, identity)) return false;
                        continue;
                    }
                }
                if (isCancelled(cancelled)) return false;
                resumeFile(destination).delete();
                DownloadSource.INSTANCE.succeeded(source);
                if (progress != null) progress.accept(1f);
                return true;
            }
            if (outcome == Outcome.FATAL && source.equals(original)) return false;
        }
        DownloadSource.INSTANCE.failed();
        return false;
    }

    private enum Outcome { DONE, RETRY, FATAL, CANCELLED }

    private static boolean isCancelled(java.util.function.BooleanSupplier cancelled) {
        return cancelled != null && cancelled.getAsBoolean();
    }

    private static final class Identity {
        private String source;
        private String etag;
    }

    private static File resumeFile(File dest) {
        return new File(dest.getPath() + ".resume.properties");
    }

    private static boolean strongETag(String value) {
        return value != null && !value.isEmpty() && !value.startsWith("W/");
    }

    /** Only reuse saved bytes when we persisted a strong validator and their source. */
    private static Identity loadIdentity(File dest, boolean resume, String expectedSha256) {
        Identity identity = new Identity();
        File marker = resumeFile(dest);
        if (!resume || !dest.isFile() || dest.length() == 0) {
            marker.delete();
            return identity;
        }
        try (FileInputStream in = new FileInputStream(marker)) {
            Properties p = new Properties();
            p.load(in);
            String source = p.getProperty("source");
            String etag = p.getProperty("etag");
            String expected = p.getProperty("sha256", "");
            if (source != null && source.startsWith("http") && strongETag(etag)
                    && expected.equals(expectedSha256 == null ? "" : expectedSha256.toLowerCase(java.util.Locale.ROOT))) {
                identity.source = source;
                identity.etag = etag;
                return identity;
            }
        } catch (IOException e) {
            // Legacy partials or interrupted metadata writes have no trustworthy identity.
        }
        if (!discardPartial(dest, identity)) Log.w(TAG, "could not discard unidentified partial " + dest);
        return identity;
    }

    private static boolean discardPartial(File dest, Identity identity) {
        identity.source = null;
        identity.etag = null;
        File marker = resumeFile(dest);
        return (!dest.exists() || dest.delete()) && (!marker.exists() || marker.delete());
    }

    private static void saveIdentity(File dest, Identity identity, String expectedSha256) {
        File marker = resumeFile(dest);
        if (!strongETag(identity.etag)) {
            marker.delete();
            return;
        }
        Properties p = new Properties();
        p.setProperty("source", identity.source);
        p.setProperty("etag", identity.etag);
        p.setProperty("sha256", expectedSha256 == null ? "" : expectedSha256.toLowerCase(java.util.Locale.ROOT));
        try (FileOutputStream out = new FileOutputStream(marker)) {
            p.store(out, "DroidDeck partial download validator");
        } catch (IOException e) {
            marker.delete(); // No valid metadata means no future resume.
            Log.w(TAG, "cannot persist download validator", e);
        }
    }

    private static Outcome attempt(String url, File destination, boolean resume, Identity identity,
                                   String expectedSha256, java.util.function.BooleanSupplier cancelled,
                                   java.util.function.Consumer<Float> progress) {
        if (isCancelled(cancelled)) return Outcome.CANCELLED;
        HttpURLConnection connection = null;
        long have = resume && destination.isFile() ? destination.length() : 0;
        try {
            if (have > 0 && (!url.equals(identity.source) || !strongETag(identity.etag))) {
                // A new source or unvalidated bytes must restart from byte zero.
                if (!discardPartial(destination, identity)) return Outcome.RETRY;
                have = 0;
            }
            connection = open(url, STALL_TIMEOUT_MS);
            if (cancelled instanceof Cancellation) ((Cancellation) cancelled).attach(connection);
            if (have > 0) {
                connection.setRequestProperty("Range", "bytes=" + have + "-");
                connection.setRequestProperty("If-Range", identity.etag);
            }
            int code = connection.getResponseCode();
            if (have > 0 && code == HTTP_RANGE_NOT_SATISFIABLE) {
                connection.disconnect();
                if (!discardPartial(destination, identity)) return Outcome.RETRY;
                return attempt(url, destination, false, identity, expectedSha256, cancelled, progress);
            }
            if (code / 100 != 2) {
                Log.w(TAG, url + " -> HTTP " + code);
                return code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE
                        ? Outcome.FATAL : Outcome.RETRY;
            }
            boolean appending = code == HttpURLConnection.HTTP_PARTIAL;
            if (appending && !rangeStartsAt(connection.getHeaderField("Content-Range"), have)) {
                connection.disconnect();
                if (!discardPartial(destination, identity)) return Outcome.RETRY;
                return attempt(url, destination, false, identity, expectedSha256, cancelled, progress);
            }
            String etag = connection.getHeaderField("ETag");
            if (have > 0 && appending && (!strongETag(etag) || !etag.equals(identity.etag))) {
                connection.disconnect();
                if (!discardPartial(destination, identity)) return Outcome.RETRY;
                return attempt(url, destination, false, identity, expectedSha256, cancelled, progress);
            }
            if (!appending && have > 0) {
                // If-Range may legitimately return full 200 when representation changed.
                if (!discardPartial(destination, identity)) return Outcome.RETRY;
                have = 0;
            }
            if (!appending) have = 0;
            long length = connection.getContentLengthLong();
            long total = appending ? rangeTotal(connection.getHeaderField("Content-Range")) : -1;
            if (total < 0) total = length < 0 ? -1 : length + have;
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            identity.source = url;
            identity.etag = strongETag(etag) ? etag : null;
            saveIdentity(destination, identity, expectedSha256);
            long written = have;
            InputStream input = connection.getInputStream();
            if (cancelled instanceof Cancellation) ((Cancellation) cancelled).attach(input);
            try (InputStream in = new BufferedInputStream(input, 1 << 16);
                 OutputStream out = new FileOutputStream(destination, appending)) {
                byte[] buffer = new byte[1 << 16];
                long lastReport = 0;
                for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                    written += read;
                    if (isCancelled(cancelled)) return Outcome.CANCELLED;
                    if (progress != null && written - lastReport > (1 << 20)) {
                        lastReport = written;
                        progress.accept(total > 0 ? written / (float) total : -1f);
                    }
                }
            } finally {
                if (cancelled instanceof Cancellation) ((Cancellation) cancelled).detach(input);
            }
            if (isCancelled(cancelled)) return Outcome.CANCELLED;
            if (total > 0 && written < total) return Outcome.RETRY;
            return Outcome.DONE;
        } catch (Exception e) {
            if (isCancelled(cancelled)) return Outcome.CANCELLED;
            Log.w(TAG, "download " + url, e);
            return Outcome.RETRY;
        } finally {
            if (connection != null) {
                if (cancelled instanceof Cancellation) ((Cancellation) cancelled).detach(connection);
                connection.disconnect();
            }
        }
    }

    /** True when a `Content-Range: bytes START-END/TOTAL` header starts exactly at {@code start}. */
    static boolean rangeStartsAt(String contentRange, long start) {
        if (contentRange == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*bytes\\s+(\\d+)-\\d+/(?:\\d+|\\*)\\s*$").matcher(contentRange);
        return m.matches() && Long.parseLong(m.group(1)) == start;
    }

    /** The complete length in a `Content-Range` header, or -1 when the header leaves it unknown. */
    static long rangeTotal(String contentRange) {
        if (contentRange == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*bytes\\s+\\d+-\\d+/(\\d+)\\s*$").matcher(contentRange);
        return m.matches() ? Long.parseLong(m.group(1)) : -1;
    }

    private static final class Fetch {
        final String body;
        final boolean fatal;

        Fetch(String body, boolean fatal) {
            this.body = body;
            this.fatal = fatal;
        }
    }

    private static Fetch fetch(String url) {
        return fetch(url, null);
    }

    private static Fetch fetch(String url, java.util.function.BooleanSupplier cancelled) {
        HttpURLConnection connection = null;
        Cancellation token = cancelled instanceof Cancellation ? (Cancellation) cancelled : null;
        try {
            connection = open(url, STALL_TIMEOUT_MS);
            if (token != null) token.attach(connection);
            int code = connection.getResponseCode();
            if (code / 100 != 2) {
                Log.w(TAG, url + " -> HTTP " + code);
                return new Fetch(null, code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE);
            }
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                FileUtils.copy(in, out);
                return new Fetch(new String(out.toByteArray(), StandardCharsets.UTF_8), false);
            }
        } catch (Exception e) {
            if (isCancelled(cancelled)) return new Fetch(null, false);
            Log.w(TAG, "GET " + url, e);
            return new Fetch(null, false);
        } finally {
            if (connection != null) {
                if (token != null) token.detach(connection);
                connection.disconnect();
            }
        }
    }

    private static HttpURLConnection open(String url, int readTimeoutMs) throws java.io.IOException {
        // An explicit Proxy routes the connection through the app setting instead of the system-wide proxy.
        Proxy proxy = DownloadSource.INSTANCE.httpProxy();
        HttpURLConnection connection = (HttpURLConnection) (proxy != null
                ? new URL(url).openConnection(proxy) : new URL(url).openConnection());
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "DroidDeck-Android");
        return connection;
    }
}
