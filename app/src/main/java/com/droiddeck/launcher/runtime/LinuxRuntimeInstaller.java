package com.droiddeck.launcher.runtime;

import android.content.Context;
import android.util.Log;


import com.droiddeck.launcher.core.ArchivePaths;
import com.droiddeck.launcher.core.Downloader;
import com.droiddeck.launcher.core.Hashes;
import com.droiddeck.launcher.core.FileUtils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * Downloads and unpacks the Linux runtime rootfs into {@code files/linuxfs}. It is opt-in: nothing
 * fetches it until the user asks for it, and a gamescope session refuses to start without it.
 *
 * <p>The catalog row names the build, its URL and its sha256, so an interrupted or corrupted
 * download is caught before anything is written into the app's files directory.
 */
public final class LinuxRuntimeInstaller {
    private static final String TAG = "LinuxRuntimeInstaller";

    /** Catalog row, beside the other component catalogs in winlator-contents. */
    public static final String CATALOG_URL =
            "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/linuxfs.json";

    private static final String VERSION_FILE = ".version";

    public interface ProgressListener {
        /** {@code percent} is -1 while the size is unknown. */
        void onProgress(String stage, int percent);
    }

    public static final class Release {
        public final String version;
        public final String url;
        public final String sha256;
        public final long size;

        public Release(String version, String url, String sha256, long size) {
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    /**
     * One runtime operation per process. The launcher and session can both install, and a
     * launcher rebuilt mid-install (a pad plugged in, dark mode switched) forgets it had: a second
     * install would share the first one's archive and staging directory and wreck both.
     */
    private static final class Job {
        final CopyOnWriteArrayList<ProgressListener> listeners = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        volatile String stage = "Starting\u2026";
        volatile int percent = -1;
        volatile boolean ok;
        final boolean removal;
        Job(boolean removal) { this.removal = removal; }
    }

    private static final Object JOB_LOCK = new Object();
    private static Job running;
    private static volatile String removalError;

    private LinuxRuntimeInstaller() {}

    /** The build currently unpacked, or null when the runtime is not installed. */
    public static String installedVersion(Context context) {
        File marker = new File(LinuxRuntime.rootDir(context), VERSION_FILE);
        if (!marker.isFile() || !LinuxRuntime.isInstalled(context)) return null;
        String v = FileUtils.readString(marker);
        return v == null ? null : v.trim();
    }

    /** The catalog's current build, or null when it cannot be reached or read. */
    public static Release fetchRelease() {
        String body = Downloader.downloadString(CATALOG_URL);
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(body);
            String version = json.optString("version", "");
            String url = json.optString("url", "");
            String sha256 = json.optString("sha256", "");
            if (version.isEmpty() || url.isEmpty() || sha256.isEmpty()) return null;
            return new Release(version, url, sha256, json.optLong("size", 0L));
        } catch (Exception e) {
            Log.w(TAG, "catalog: " + e);
            return null;
        }
    }

    /**
     * The guest's home directory, carried across an update rather than replaced with the tarball's
     * empty one. Steam installs itself here - the client, the signed-in account and every
     * downloaded game - so replacing the rootfs wholesale used to delete all three and leave the
     * user re-downloading Steam and signing in again, with their library gone.
     */
    private static final String USER_DATA = "root";

    /**
     * Downloads {@code release} and replaces whatever is installed with it. Returns false and
     * leaves the existing runtime alone if the download or the checksum fails; the new rootfs is
     * only moved into place once it has been unpacked whole.
     *
     * <p>{@link #USER_DATA} survives the swap: the system is replaced, what the user put in it is
     * not. An update therefore keeps Steam, the login and the installed games.
     */
    public static boolean install(Context context, Release release, ProgressListener listener) {
        Job job;
        boolean owner;
        synchronized (JOB_LOCK) {
            if (running != null && running.removal) return false;
            owner = running == null;
            if (owner) { running = new Job(false); removalError = null; }
            job = running;
            if (listener != null) job.listeners.add(listener);
        }
        if (!owner) return join(job, listener);
        try {
            job.ok = installOnce(context.getApplicationContext(), release, (stage, percent) -> {
                job.stage = stage;
                job.percent = percent;
                for (ProgressListener l : job.listeners) l.onProgress(stage, percent);
            });
            return job.ok;
        } finally {
            synchronized (JOB_LOCK) {
                running = null;
            }
            job.done.countDown();
        }
    }

    /** True while an install is running in this process, whoever started it. */
    public static boolean isInstalling() {
        synchronized (JOB_LOCK) {
            return running != null && !running.removal;
        }
    }

    public static boolean isBusy() {
        synchronized (JOB_LOCK) { return running != null; }
    }

    public static boolean isRemoving() {
        synchronized (JOB_LOCK) { return running != null && running.removal; }
    }

    public static String removalError() { return removalError; }

    public static boolean hasRemovalPending(Context context) {
        return removalDirectory(LinuxRuntime.rootDir(context)).exists();
    }

    private static File removalDirectory(File root) {
        return new File(root.getParentFile(), root.getName() + ".removing");
    }

    /**
     * Follows the runtime operation already running, including removal, after a screen rebuild.
     * Blocks until it ends and returns its result, or null when there is no current operation.
     */
    public static Boolean attach(ProgressListener listener) {
        Job job;
        synchronized (JOB_LOCK) {
            job = running;
            if (job == null) return null;
            if (listener != null) job.listeners.add(listener);
        }
        return join(job, listener);
    }

    private static boolean join(Job job, ProgressListener listener) {
        try {
            if (listener != null) listener.onProgress(job.stage, job.percent);
            job.done.await();
            return job.ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (listener != null) job.listeners.remove(listener);
        }
    }

    private static boolean installOnce(Context context, Release release, ProgressListener listener) {
        File archive = new File(context.getCacheDir(), "linuxfs.tar.zst");
        try {
            // A failed/interrupted explicit removal must finish before a fresh installation.
            // Its quarantined home is not user data to carry into an update.
            File pendingRemoval = removalDirectory(LinuxRuntime.rootDir(context));
            if (pendingRemoval.exists() && listener != null) listener.onProgress("Removing runtime leftovers", -1);
            RuntimeFileTree.delete(pendingRemoval, null);
            if (listener != null) listener.onProgress("Downloading", 0);
            // Downloader reports a 0..1 fraction, or -1 while the total size is unknown.
            boolean ok = Downloader.downloadFile(release.url, archive, true, (fraction) -> {
                if (listener != null) {
                    listener.onProgress("Downloading",
                            fraction < 0 ? -1 : Math.round(fraction * 100f));
                }
            });
            if (!ok) {
                Log.w(TAG, "download failed");
                return false;
            }

            if (listener != null) listener.onProgress("Verifying", -1);
            String actual = Hashes.sha256(archive);
            if (!release.sha256.equalsIgnoreCase(actual)) {
                Log.w(TAG, "checksum mismatch: wanted " + release.sha256 + ", got " + actual);
                return false;
            }

            // Unpack beside the live rootfs and swap, so a failure here cannot leave a half
            // runtime that isInstalled() would happily launch.
            File root = LinuxRuntime.rootDir(context);
            File staging = new File(root.getParentFile(), LinuxRuntime.DIR + ".new");
            File old = new File(root.getParentFile(), LinuxRuntime.DIR + ".old");
            recoverInterruptedSwap(root, staging, old);
            RuntimeFileTree.delete(staging, null);
            if (!staging.mkdirs()) return false;
            if (listener != null) listener.onProgress("Extracting", -1);
            if (!extract(archive, staging, listener)) {
                RuntimeFileTree.delete(staging, null);
                return false;
            }
            FileUtils.writeString(new File(staging, VERSION_FILE), release.version);

            RuntimeFileTree.delete(old, null);
            if (root.isDirectory() && !root.renameTo(old)) {
                RuntimeFileTree.delete(staging, null);
                return false;
            }

            // Carry the user's home over before the new rootfs takes the name. A rename inside the
            // same filesystem, so a 30 GB library costs nothing and cannot half-copy; the tarball's
            // own empty /root is dropped first so the rename has somewhere to land. If this fails
            // the update is abandoned and the previous runtime is put back untouched - shipping a
            // working system with the user's games gone is the worse outcome.
            try {
                RuntimeFileTree.carryHome(old, staging);
            } catch (IOException e) {
                Log.w(TAG, "could not carry root across the update; rolling back", e);
                // Restore the live name even if cleaning the failed staging tree is denied.
                old.renameTo(root);
                try { RuntimeFileTree.delete(staging, null); }
                catch (IOException cleanup) { Log.w(TAG, "could not clear the failed staging tree", cleanup); }
                return false;
            }

            if (!staging.renameTo(root)) {
                File keptTo = new File(staging, USER_DATA);
                if (keptTo.isDirectory() && old.isDirectory()) keptTo.renameTo(new File(old, USER_DATA));
                if (old.isDirectory()) old.renameTo(root);
                return false;
            }
            RuntimeFileTree.delete(old, null);
            return LinuxRuntime.isInstalled(context);
        } catch (Exception e) {
            Log.e(TAG, "install", e);
            return false;
        } finally {
            archive.delete();
        }
    }

    static void recoverInterruptedSwap(File root, File staging, File old) throws IOException {
        File target = root.isDirectory() ? root : old;
        File stagedHome = new File(staging, USER_DATA);
        File home = new File(target, USER_DATA);
        if (target.isDirectory() && stagedHome.isDirectory() && (!home.exists() || isEmptyDir(home))) {
            RuntimeFileTree.carryHome(staging, target);
            Log.w(TAG, "recovered " + USER_DATA + " from an interrupted update");
        }
        if (!root.isDirectory() && old.isDirectory() && old.renameTo(root)) {
            Log.w(TAG, "restored the previous runtime after an interrupted update");
        }
    }

    private static boolean isEmptyDir(File dir) {
        String[] names = dir.list();
        return dir.isDirectory() && names != null && names.length == 0;
    }

    /** Reserve removal before returning to the UI, so Play cannot race the worker. */
    public static Removal beginUninstall(Context context) {
        if (com.droiddeck.launcher.session.SessionState.INSTANCE.getRunning()) return null;
        com.droiddeck.launcher.session.SessionPhase phase = com.droiddeck.launcher.session.SessionState.INSTANCE.getPhase();
        if (phase != com.droiddeck.launcher.session.SessionPhase.IDLE && phase != com.droiddeck.launcher.session.SessionPhase.FAILED) return null;
        return beginUninstall(LinuxRuntime.rootDir(context));
    }

    static Removal beginUninstall(File root) {
        synchronized (JOB_LOCK) {
            if (running != null) return null;
            removalError = null;
            running = new Job(true);
            running.stage = "Removing Linux runtime";
            return new Removal(root, running);
        }
    }

    public static final class Removal {
        private final File root;
        private final Job job;
        private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        private Removal(File root, Job job) { this.root = root; this.job = job; }

        public boolean run(ProgressListener listener) {
            if (!started.compareAndSet(false, true)) return join(job, listener);
            if (listener != null) job.listeners.add(listener);
            try {
                report("Removing Linux runtime", -1);
                final long[] removed = {0};
                final long[] lastShown = {0};
                java.util.function.LongConsumer progress = ignored -> {
                    removed[0]++;
                    long now = System.nanoTime();
                    if (removed[0] == 1 || now - lastShown[0] >= 200_000_000L) {
                        lastShown[0] = now;
                        report("Removing Linux runtime · " + removed[0] + " entries", -1);
                    }
                };
                File pending = removalDirectory(root);
                // Invalidate the installation before traversing it. A process killed halfway
                // through leaves a named removal to resume, never a launchable partial runtime.
                if (root.exists()) {
                    RuntimeFileTree.delete(pending, progress);
                    if (!root.renameTo(pending)) throw new IOException("Cannot prepare the runtime for removal");
                } else if (!pending.isDirectory() && !pending.mkdirs()) {
                    throw new IOException("Cannot prepare the runtime for removal");
                }
                // Keep the quarantine until all leftovers are gone, so a failure stays retryable.
                RuntimeFileTree.delete(new File(root.getParentFile(), root.getName() + ".new"), progress);
                RuntimeFileTree.delete(new File(root.getParentFile(), root.getName() + ".old"), progress);
                RuntimeFileTree.delete(pending, progress);
                job.ok = true;
                report("Linux runtime removed", 100);
                return true;
            } catch (IOException e) {
                removalError = "Could not finish removing the Linux runtime. Retry removal in Setup.";
                Log.w(TAG, "runtime removal incomplete", e);
                report(removalError, -1);
                return false;
            } finally {
                synchronized (JOB_LOCK) { running = null; }
                job.done.countDown();
            }
        }
        private void report(String stage, int percent) {
            job.stage = stage;
            job.percent = percent;
            for (ProgressListener listener : job.listeners) listener.onProgress(stage, percent);
        }
    }

    /**
     * A whole-rootfs tar, which is not the shape the shared extractor handles: a distribution
     * rootfs is full of hard links (one binary under several names), and an entry written as an
     * empty file instead of its link target is a rootfs that boots to nothing. Symlinks, hard
     * links and the executable bit are all carried over here.
     */
    public static boolean extract(File archive, File destination, ProgressListener listener) {
        long entries = 0;
        try (InputStream in = new ZstdCompressorInputStream(
                new BufferedInputStream(new FileInputStream(archive), 1 << 16));
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                // Refuse anything that would land outside the runtime directory.
                File file = ArchivePaths.inside(destination, entry.getName());
                if (file == null) {
                    Log.w(TAG, "skipping entry outside the rootfs: " + entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    file.mkdirs();
                    continue;
                }
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();

                if (entry.isSymbolicLink()) {
                    file.delete();
                    FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                } else if (entry.isLink()) {
                    // A hard link to an earlier entry. Link where the filesystem allows it and
                    // fall back to a copy, which costs space but always works.
                    // The target is checked too: a link to "../../shared_prefs/x" would pull a
                    // file from outside the rootfs into it.
                    File target = ArchivePaths.inside(destination, entry.getLinkName());
                    if (target == null) {
                        Log.w(TAG, "skipping hard link outside the rootfs: " + entry.getLinkName());
                        continue;
                    }
                    file.delete();
                    try {
                        Files.createLink(file.toPath(), target.toPath());
                    } catch (IOException | UnsupportedOperationException e) {
                        if (target.isFile()) {
                            Files.copy(target.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } else if (entry.isFile()) {
                    try (OutputStream out = new FileOutputStream(file)) {
                        byte[] buffer = new byte[1 << 16];
                        int read;
                        while ((read = tar.read(buffer)) != -1) out.write(buffer, 0, read);
                    }
                    if ((entry.getMode() & 0111) != 0) file.setExecutable(true, false);
                } else {
                    continue; // device nodes and fifos: the runtime binds the real ones
                }
                if (++entries % 2000 == 0 && listener != null) {
                    listener.onProgress("Extracting", -1);
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "extract", e);
            return false;
        }
    }
}
