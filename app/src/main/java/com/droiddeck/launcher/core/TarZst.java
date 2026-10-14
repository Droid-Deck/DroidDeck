package com.droiddeck.launcher.core;

import android.content.Context;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class TarZst {
    private static final String TAG = "TarZst";

    private TarZst() {}

    public static boolean extractAsset(Context context, String assetPath, File destination) {
        try (InputStream in = context.getAssets().open(assetPath)) {
            return extract(in, destination);
        } catch (Exception e) {
            Log.e(TAG, "extract " + assetPath, e);
            return false;
        }
    }

    public static boolean extract(InputStream source, File destination) {
        destination.mkdirs();
        try (InputStream in = new ZstdCompressorInputStream(new BufferedInputStream(source, 1 << 16));
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                File file = ArchivePaths.inside(destination, entry.getName());
                if (file == null) continue;
                if (entry.isDirectory()) {
                    file.mkdirs();
                    continue;
                }
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory())
                    parent.mkdirs();
                if (entry.isSymbolicLink()) {
                    file.delete();
                    FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                    continue;
                }
                if (!entry.isFile()) continue;
                try (OutputStream out = new FileOutputStream(file)) {
                    FileUtils.copy(tar, out);
                }
                if ((entry.getMode() & 0111) != 0)
                    file.setExecutable(true, false);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "extract into " + destination, e);
            return false;
        }
    }
}
