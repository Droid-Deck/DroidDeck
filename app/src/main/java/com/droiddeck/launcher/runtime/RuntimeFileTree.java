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
    static java.util.List<String> userPaths(File tree) throws IOException {
        java.util.List<String> paths = new java.util.ArrayList<>(java.util.Arrays.asList("root", "opt/appimages/user", "opt/droiddeck-apps"));
        for (String n : children(new File(tree, "usr/local/share/applications"))) {
            if (n.endsWith(".desktop") && (n.startsWith("droiddeck-app-") || n.startsWith("droiddeck-appimage-")))
                paths.add("usr/local/share/applications/" + n);
        }
        for (String n : children(new File(tree, "opt/appimages"))) {
            if (!n.endsWith(".AppImage")) continue;
            String id = n.substring(0, n.length() - ".AppImage".length());
            java.util.Collections.addAll(paths, "opt/appimages/" + n,
                    "usr/share/applications/droiddeck-" + id + ".desktop", ".droiddeck-pkg-" + id);
        }
        for (String n : children(new File(tree, "usr/share/applications"))) {
            if (!n.startsWith("droiddeck-") || !n.endsWith(".desktop")) continue;
            String id = n.substring("droiddeck-".length(), n.length() - ".desktop".length());
            if (!exists(new File(tree, "opt/appimages/" + id + ".AppImage").toPath()))
                paths.add("usr/share/applications/" + n);
        }
        for (String n : children(tree)) {
            if (!n.startsWith(".droiddeck-pkg-")) continue;
            String id = n.substring(".droiddeck-pkg-".length());
            boolean hasMenu = exists(new File(tree, "usr/share/applications/droiddeck-" + id + ".desktop").toPath());
            if (hasMenu && !exists(new File(tree, "opt/appimages/" + id + ".AppImage").toPath()))
                paths.add(n);
        }
        return paths;
    }

    static java.util.List<String> existingUserPaths(File from, File to) throws IOException {
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (String rel : userPaths(from, to)) if (exists(safePath(from, rel).toPath())) paths.add(rel);
        return paths;
    }

    private static java.util.List<String> children(File dir) throws IOException {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir.toPath())) {
            for (Path entry : entries) names.add(entry.getFileName().toString());
        } catch (NoSuchFileException e) {
            return names;
        }
        return names;
    }

    static boolean exists(Path path) throws IOException {
        try { Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return true; }
        catch (NoSuchFileException e) { return false; }
    }

    private static java.util.Set<String> userPaths(File from, File to) throws IOException {
        java.util.Set<String> paths = new java.util.LinkedHashSet<>(userPaths(from));
        paths.addAll(userPaths(to));
        return paths;
    }

    static boolean hasUserData(File from, File to) throws IOException {
        for (String rel : userPaths(from, to)) if (exists(new File(from, rel).toPath())) return true;
        return false;
    }

    /**
     * Moves each user path that exists in {@code from} into {@code to}, replacing what {@code to} ships there. With
     * {@code onlyMissing}, a path already present in {@code to} is left alone. On failure the paths already moved go back
     * before the exception is thrown. Rollback failures are suppressed on that exception; callers must retain the source
     * of any incomplete rollback.
     * Returns the moved paths.
     */
    static java.util.List<String> carryUserData(File from, File to, boolean onlyMissing) throws IOException {
        return carryUserData(from, to, onlyMissing, new java.util.ArrayList<>(userPaths(from, to)));
    }

    static java.util.List<String> carryUserData(File from, File to, boolean onlyMissing, java.util.List<String> paths) throws IOException {
        java.util.List<String> moved = new java.util.ArrayList<>();
        try {
            for (String rel : paths) {
                File src = safePath(from, rel);
                if (!exists(src.toPath())) continue;
                File dst = safePath(to, rel);
                if (onlyMissing && exists(dst.toPath())) continue;
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
        File src = safePath(from, rel);
        File dst = safePath(to, rel);
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

    static void recoverCarriedPaths(File staging, File target, java.util.List<String> paths) throws IOException {
        for (String rel : paths) {
            File src = safePath(staging, rel);
            if (!exists(src.toPath())) continue;
            File dst = safePath(target, rel);
            if (!exists(dst.toPath())) move(staging, target, rel);
        }
    }

    static boolean hasPaths(File tree, java.util.List<String> paths) throws IOException {
        for (String rel : paths) if (exists(safePath(tree, rel).toPath())) return true;
        return false;
    }

    private static File safePath(File tree, String rel) throws IOException {
        Path relative = java.nio.file.Paths.get(rel);
        if (relative.isAbsolute() || relative.getNameCount() == 0) throw new IOException("Invalid runtime path: " + rel);
        for (Path part : relative) if (part.toString().equals("..")) throw new IOException("Invalid runtime path: " + rel);
        Path current = tree.toPath();
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            current = current.resolve(relative.getName(i));
            try {
                BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isDirectory()) throw new IOException("Runtime path has a non-directory parent: " + current);
            } catch (NoSuchFileException ignored) {
                break;
            }
        }
        return tree.toPath().resolve(relative).toFile();
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
