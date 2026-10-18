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

public final class RuntimeFileTree {
    private RuntimeFileTree() {}

    static volatile Predicate<Path> refuseForTest;

    static void delete(File root, LongConsumer progress) throws IOException {
        delete(root.toPath(), progress, new long[]{0});
    }

    private static void delete(Path path, LongConsumer progress, long[] removed) throws IOException {
        BasicFileAttributes attrs;
        try { attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException e) { return; }
        if (attrs.isDirectory()) {
            makeWritable(path.toFile());
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) delete(child, progress, removed);
            }
        }
        unlink(path);
        if (progress != null) progress.accept(++removed[0]);
    }

    public static boolean deleteWhatCan(File root, LongConsumer progress) {
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

    /**
     * Paths in a runtime tree that belong to the user: the home folder, imported AppImages and script apps, and catalog
     * AppImages with their menu entries and markers. Tar-kind catalog packages (the desktop) unpack over /usr and cannot
     * be carried.
     */
    static java.util.List<String> userPaths(File tree) {
        java.util.List<String> paths = new java.util.ArrayList<>(java.util.Arrays.asList("root", "opt/appimages/user", "opt/droiddeck-apps"));
        String[] menu = new File(tree, "usr/local/share/applications").list((d, n) -> n.endsWith(".desktop")
                && (n.startsWith("droiddeck-app-") || n.startsWith("droiddeck-appimage-")));
        if (menu != null) for (String n : menu) paths.add("usr/local/share/applications/" + n);
        String[] images = new File(tree, "opt/appimages").list((d, n) -> n.endsWith(".AppImage"));
        if (images != null) for (String n : images) {
            String id = n.substring(0, n.length() - ".AppImage".length());
            java.util.Collections.addAll(paths, "opt/appimages/" + n,
                    "usr/share/applications/droiddeck-" + id + ".desktop", ".droiddeck-pkg-" + id);
        }
        return paths;
    }

    /**
     * Moves each user path that exists in {@code from} into {@code to}, replacing what {@code to} ships there. With
     * {@code onlyMissing}, a path already present in {@code to} is left alone. On failure the paths already moved go back
     * before the exception is thrown, so nothing the user owns is left in a tree that is about to be deleted.
     * Returns the moved paths.
     */
    static java.util.List<String> carryUserData(File from, File to, boolean onlyMissing) throws IOException {
        java.util.List<String> moved = new java.util.ArrayList<>();
        try {
            for (String rel : userPaths(from)) {
                File src = new File(from, rel);
                if (!Files.exists(src.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
                File dst = new File(to, rel);
                if (onlyMissing && Files.exists(dst.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
                move(from, to, rel);
                moved.add(rel);
            }
        } catch (IOException e) {
            for (int i = moved.size() - 1; i >= 0; i--) {
                try { move(to, from, moved.get(i)); }
                catch (IOException back) { e.addSuppressed(back); }
            }
            throw e;
        }
        return moved;
    }

    /** Puts the given paths back from {@code from} into {@code to}; failures are collected, not stopped on. */
    static void carryBack(File from, File to, java.util.List<String> paths) throws IOException {
        IOException failed = null;
        for (int i = paths.size() - 1; i >= 0; i--) {
            try { move(from, to, paths.get(i)); }
            catch (IOException e) { if (failed == null) failed = e; else failed.addSuppressed(e); }
        }
        if (failed != null) throw failed;
    }

    private static void move(File from, File to, String rel) throws IOException {
        File src = new File(from, rel);
        File dst = new File(to, rel);
        File parent = src.getParentFile();
        if (parent != null) makeWritable(parent);
        java.util.List<File> made = new java.util.ArrayList<>();
        for (File d = dst.getParentFile(); d != null; d = d.getParentFile()) {
            made.add(d);
            if (d.equals(to)) break;
        }
        for (int i = made.size() - 1; i >= 0; i--) {
            File d = made.get(i);
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("Cannot create " + d);
            makeWritable(d);
        }
        delete(dst, null);
        if (!src.renameTo(dst)) throw new IOException("Cannot carry " + rel + " across the update");
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
