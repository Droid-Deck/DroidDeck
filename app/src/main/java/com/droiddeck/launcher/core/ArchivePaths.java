package com.droiddeck.launcher.core;

import java.io.File;
import java.io.IOException;

public final class ArchivePaths {
    private ArchivePaths() {}

    public static File inside(File destination, String name) throws IOException {
        String base = destination.getCanonicalPath();
        File file = new File(destination, name);
        String path = file.getCanonicalPath();
        if (path.equals(base) || path.startsWith(base + File.separator)) return file;
        return null;
    }
}
