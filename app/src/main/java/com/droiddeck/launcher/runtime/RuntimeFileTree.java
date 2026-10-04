package com.droiddeck.launcher.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.LongConsumer;

/** Checked removal of an app-owned runtime tree, including read-only directories. */
final class RuntimeFileTree {
    private RuntimeFileTree() {}

    static void delete(File root, LongConsumer progress) throws IOException {
        delete(root.toPath(), progress, new long[]{0});
    }

    private static void delete(Path path, LongConsumer progress, long[] removed) throws IOException {
        BasicFileAttributes attrs;
        try { attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException e) { return; }
        if (attrs.isDirectory()) {
            // Change directory permissions before opening it, so mode 000 also works. Links
            // are inspected with lstat and unlinked directly; never chmod or visit their target.
            makeWritable(path.toFile());
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) delete(child, progress, removed);
            }
        }
        Files.delete(path);
        if (progress != null) progress.accept(++removed[0]);
    }

    private static void makeWritable(File dir) throws IOException {
        if (!dir.setReadable(true, true) || !dir.setWritable(true, true) || !dir.setExecutable(true, true))
            throw new IOException("Cannot make directory removable: " + dir);
    }

    static void carryHome(File old, File staging) throws IOException {
        File home = new File(old, "root");
        if (!home.isDirectory()) return;
        makeWritable(old);
        makeWritable(staging);
        File target = new File(staging, "root");
        delete(target, null);
        if (!home.renameTo(target)) throw new IOException("Cannot carry root across the update");
    }
}
