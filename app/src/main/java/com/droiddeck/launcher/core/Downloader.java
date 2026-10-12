package com.droiddeck.launcher.core;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class Downloader {
    private static final String TAG = "Downloader";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int STALL_TIMEOUT_MS = 15_000;
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;

    private Downloader() {}

    public static String downloadString(String url) {
        List<String> sources = DownloadSource.INSTANCE.candidates(url);
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) Log.i(TAG, "retrying " + url + " via " + sources.get(i));
            Fetch fetch = fetch(sources.get(i));
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
        List<String> sources = DownloadSource.INSTANCE.candidates(url);
        Identity identity = new Identity();
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) Log.i(TAG, "retrying " + url + " via " + sources.get(i));
            Outcome outcome = attempt(sources.get(i), destination, resume, identity, progress);
            if (outcome == Outcome.DONE) {
                DownloadSource.INSTANCE.succeeded(sources.get(i));
                return true;
            }
            if (outcome == Outcome.FATAL && sources.get(i).equals(url)) return false;
        }
        DownloadSource.INSTANCE.failed();
        return false;
    }

    private enum Outcome { DONE, RETRY, FATAL }

    private static final class Identity {
        private String source;
        private String etag;
    }

    private static Outcome attempt(String url, File destination, boolean resume, Identity identity,
                                   java.util.function.Consumer<Float> progress) {
        HttpURLConnection connection = null;
        long have = resume && destination.isFile() ? destination.length() : 0;
        try {
            connection = open(url, STALL_TIMEOUT_MS);
            if (have > 0) connection.setRequestProperty("Range", "bytes=" + have + "-");
            int code = connection.getResponseCode();
            if (have > 0 && code == HTTP_RANGE_NOT_SATISFIABLE) {
                connection.disconnect();
                destination.delete();
                return attempt(url, destination, false, identity, progress);
            }
            if (code / 100 != 2) {
                Log.w(TAG, url + " -> HTTP " + code);
                return code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE
                        ? Outcome.FATAL : Outcome.RETRY;
            }
            boolean appending = code == HttpURLConnection.HTTP_PARTIAL;
            if (appending && !rangeStartsAt(connection.getHeaderField("Content-Range"), have)) {
                // A 206 that does not begin where the kept bytes end would corrupt the file if appended.
                connection.disconnect();
                destination.delete();
                return attempt(url, destination, false, identity, progress);
            }
            String etag = connection.getHeaderField("ETag");
            boolean identified = etag != null && etag.equals(identity.etag);
            if (appending && identity.source == null) {
                // Kept bytes from an earlier run: nothing identifies them, so this attempt adopts them.
                identity.source = url;
                identity.etag = etag;
            } else if (appending && !url.equals(identity.source) && !identified) {
                // A source that cannot be identified is not trusted to append to kept bytes.
                connection.disconnect();
                destination.delete();
                return attempt(url, destination, false, identity, progress);
            } else if (appending && identity.etag != null && etag != null && !identified) {
                // The same offset from another revision would mix two files into one.
                connection.disconnect();
                destination.delete();
                return attempt(url, destination, false, identity, progress);
            } else if (!appending) {
                identity.source = url;
                identity.etag = etag;
            }
            if (!appending && have > 0) {
                connection.disconnect();
                destination.delete();
                return attempt(url, destination, false, identity, progress);
            }
            if (!appending) have = 0;
            long length = connection.getContentLengthLong();
            long total = appending ? rangeTotal(connection.getHeaderField("Content-Range")) : -1;
            if (total < 0) total = length < 0 ? -1 : length + have;
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory())
                parent.mkdirs();
            long written = have;
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), 1 << 16);
                 OutputStream out = new FileOutputStream(destination, appending)) {
                byte[] buffer = new byte[1 << 16];
                long lastReport = 0;
                for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                    written += read;
                    if (progress != null && written - lastReport > (1 << 20)) {
                        lastReport = written;
                        progress.accept(total > 0 ? written / (float) total : -1f);
                    }
                }
            }
            if (total > 0 && written < total) return Outcome.RETRY;
            if (progress != null) progress.accept(1f);
            return Outcome.DONE;
        } catch (Exception e) {
            Log.w(TAG, "download " + url, e);
            return Outcome.RETRY;
        } finally {
            if (connection != null) connection.disconnect();
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
        HttpURLConnection connection = null;
        try {
            connection = open(url, STALL_TIMEOUT_MS);
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
            Log.w(TAG, "GET " + url, e);
            return new Fetch(null, false);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, int readTimeoutMs) throws java.io.IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "DroidDeck-Android");
        return connection;
    }
}
