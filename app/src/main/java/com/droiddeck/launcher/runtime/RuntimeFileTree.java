package com.droiddeck.launcher.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/** Checked removal of an app-owned runtime tree, including read-only directories. */
final class RuntimeFileTree {
    private RuntimeFileTree() {}

    /** Unit tests stand in for an entry the app cannot unlink (a file another SELinux user made). */
    static volatile Predicate<Path> refuseForTest;

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
        unlink(path);
        if (progress != null) progress.accept(++removed[0]);
    }

    /**
     * Removes what it can of {@code root} and carries on past entries it cannot, rather than
     * stopping at the first. Returns true when nothing is left.
     */
    static boolean deleteWhatCan(File root, LongConsumer progress) {
        return deleteWhatCan(root.toPath(), progress, new long[]{0});
    }

    private static boolean deleteWhatCan(Path path, LongConsumer progress, long[] removed) {
        BasicFileAttributes attrs;
        try { attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException e) { return true; }
        catch (IOException e) { return false; }
        boolean emptied = true;
        if (attrs.isDirectory()) {
            try {
                makeWritable(path.toFile());
                try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                    for (Path child : children) emptied &= deleteWhatCan(child, progress, removed);
                }
            } catch (IOException | RuntimeException e) {
                emptied = false;
            }
        }
        if (!emptied) return false;
        try { unlink(path); }
        catch (NoSuchFileException e) { return true; }
        catch (IOException e) { return false; }
        if (progress != null) progress.accept(++removed[0]);
        return true;
    }

    private static void unlink(Path path) throws IOException {
        Predicate<Path> refuse = refuseForTest;
        if (refuse != null && refuse.test(path)) throw new AccessDeniedException(path.toString());
        Files.delete(path);
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
