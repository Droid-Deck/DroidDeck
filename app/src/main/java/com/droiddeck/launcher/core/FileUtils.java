package com.droiddeck.launcher.core;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class FileUtils {
    private static final String TAG = "FileUtils";

    private FileUtils() {}

    /** Recursive delete that does not follow symlinks - a rootfs is full of them. */
    public static void delete(File file) {
        if (file == null || !file.exists() && !isSymlink(file)) return;
        if (file.isDirectory() && !isSymlink(file)) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static boolean isSymlink(File file) {
        try {
            return Files.isSymbolicLink(file.toPath());
        } catch (Exception e) {
            return false;
        }
    }

    /** Empties a directory without removing it. */
    public static void clear(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children != null) for (File child : children) delete(child);
    }

    public static boolean writeString(File file, String content) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            Log.w(TAG, "write " + file, e);
            return false;
        }
    }

    public static String readString(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public static void symlink(String target, String linkPath) {
        try {
            Os.symlink(target, linkPath);
        } catch (ErrnoException e) {
            Log.w(TAG, "symlink " + linkPath + " -> " + target + ": " + e.getMessage());
        }
    }

    public static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (ErrnoException e) {
            Log.w(TAG, "chmod " + file + ": " + e.getMessage());
        }
    }

    /** Copies an asset out of the apk, creating the parent directory. */
    public static boolean copyAsset(Context context, String assetPath, File destination) {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory()) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(destination)) {
            copy(in, out);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "copy asset " + assetPath, e);
            return false;
        }
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1 << 16];
        for (int read = in.read(buffer); read > 0; read = in.read(buffer)) out.write(buffer, 0, read);
    }

    public static String sizeToString(long bytes) {
        if (bytes >= 1L << 30) return String.format(java.util.Locale.US, "%.1f GB", bytes / (float) (1L << 30));
        if (bytes >= 1L << 20) return String.format(java.util.Locale.US, "%.0f MB", bytes / (float) (1L << 20));
        if (bytes >= 1L << 10) return String.format(java.util.Locale.US, "%.0f KB", bytes / (float) (1L << 10));
        return bytes + " B";
    }
}
