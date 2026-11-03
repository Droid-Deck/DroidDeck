package com.droiddeck.launcher.core;

import android.content.Context;
import android.util.Log;
import com.droiddeck.launcher.BuildConfig;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ComponentDownloads {
    private static volatile ComponentPayloadStore store;

    private ComponentDownloads() {}

    public static void init(Context context) {
        if (BuildConfig.PLAY_DISTRIBUTION) {
            Context application = context.getApplicationContext();
            store = new ComponentPayloadStore(path -> {
                try {
                    return application.getAssets().open(path);
                } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
        }
    }

    private static ComponentPayloadStore bundled() throws IOException {
        ComponentPayloadStore current = store;
        if (current == null) throw new IOException("Bundled component delivery is unavailable");
        return current;
    }

    public static String catalogName(String name) {
        return BuildConfig.PLAY_DISTRIBUTION ? "play-" + BuildConfig.SOURCE_COMMIT + "-" + name : name;
    }

    public static String downloadString(String url) {
        return downloadString(url, null);
    }

    public static String downloadString(String url, BooleanSupplier cancelled) {
        if (!BuildConfig.PLAY_DISTRIBUTION) return Downloader.downloadString(url, cancelled);
        try {
            return bundled().read(url, cancelled);
        } catch (Exception error) {
            Log.w("ComponentDownloads", "Bundled catalog unavailable: " + url, error);
            return null;
        }
    }

    public static String downloadDirect(String url) {
        return BuildConfig.PLAY_DISTRIBUTION ? downloadString(url) : Downloader.downloadDirect(url);
    }

    public static long copyBundled(String url, OutputStream output, long limit, Consumer<Float> progress) throws IOException {
        if (!BuildConfig.PLAY_DISTRIBUTION) throw new IOException("Bundled delivery requires a Play distribution");
        try {
            return bundled().copy(url, output, limit, null, null, progress);
        } catch (java.io.UncheckedIOException error) {
            throw error.getCause();
        } catch (Exception error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Invalid bundled component index", error);
        }
    }

    public static boolean downloadFile(String url, File destination, boolean resume, Consumer<Float> progress) {
        return downloadFile(url, destination, resume, null, null, progress);
    }

    public static boolean downloadFile(String url, File destination, boolean resume, String expectedSha256, Consumer<Float> progress) {
        return downloadFile(url, destination, resume, expectedSha256, null, progress);
    }

    public static boolean downloadFile(String url, File destination, boolean resume, String expectedSha256,
                                       BooleanSupplier cancelled, Consumer<Float> progress) {
        if (!BuildConfig.PLAY_DISTRIBUTION) {
            return Downloader.downloadFile(url, destination, resume, expectedSha256, cancelled, progress);
        }
        try {
            bundled().install(url, destination, expectedSha256, cancelled, progress);
            return true;
        } catch (Exception error) {
            Log.w("ComponentDownloads", "Bundled component unavailable: " + url, error);
            return false;
        }
    }
}
