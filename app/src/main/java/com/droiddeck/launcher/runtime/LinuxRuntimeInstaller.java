package com.droiddeck.launcher.runtime;

import android.content.Context;
import android.util.Log;


import com.droiddeck.launcher.R;
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
            "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/linuxfs.json";

    private static final String VERSION_FILE = ".version";

    /** What a stage is doing, for code that branches on it rather than on its (translated) words. */
    public enum Step { DOWNLOADING, VERIFYING, OTHER }

    public interface ProgressListener {
        /** {@code percent} is -1 while the size is unknown. */
        void onProgress(String stage, int percent);

        /** The same with its {@link Step}; a listener that branches on the step overrides this one. */
        default void onProgress(Step step, String stage, int percent) {
            onProgress(stage, percent);
        }
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
        volatile String stage;
        volatile Step step = Step.OTHER;
        volatile int percent = -1;
        volatile boolean ok;
        final boolean removal;
        boolean maintenance;
        Job(boolean removal, String stage) { this.removal = removal; this.stage = stage; }
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
            if (running != null && (running.removal || running.maintenance)) return false;
            owner = running == null;
            if (owner) { running = new Job(false, context.getString(R.string.user_apps_starting)); removalError = null; }
            job = running;
            if (listener != null) job.listeners.add(listener);
        }
        if (!owner) return join(job, listener);
        try {
            job.ok = installOnce(context.getApplicationContext(), release, new ProgressListener() {
                @Override public void onProgress(String stage, int percent) {
                    onProgress(Step.OTHER, stage, percent);
                }

                @Override public void onProgress(Step step, String stage, int percent) {
                    job.step = step;
                    job.stage = stage;
                    job.percent = percent;
                    for (ProgressListener l : job.listeners) l.onProgress(step, stage, percent);
                }
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
            return running != null && !running.removal && !running.maintenance;
        }
    }

    public static boolean isBusy() {
        synchronized (JOB_LOCK) { return running != null; }
    }

    public static boolean isRemoving() {
        synchronized (JOB_LOCK) { return running != null && running.removal; }
    }

    public static boolean isMaintaining() {
        synchronized (JOB_LOCK) { return running != null && running.maintenance; }
    }

    /** Reserve an in-place package operation with the same exclusion/progress as runtime installs. */
    public static Maintenance beginMaintenance(Context context, String stage) {
        if (com.droiddeck.launcher.session.SessionState.INSTANCE.getRunning()) return null;
        com.droiddeck.launcher.session.SessionPhase phase = com.droiddeck.launcher.session.SessionState.INSTANCE.getPhase();
        if (phase != com.droiddeck.launcher.session.SessionPhase.IDLE && phase != com.droiddeck.launcher.session.SessionPhase.FAILED) return null;
        synchronized (JOB_LOCK) {
            if (running != null) return null;
            removalError = null;
            running = new Job(false, stage);
            running.maintenance = true;
            return new Maintenance(running);
        }
    }

    public interface MaintenanceWork { String run(ProgressListener listener); }

    public static final class Maintenance {
        private final Job job;
        private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        private Maintenance(Job job) { this.job = job; }
        public boolean run(ProgressListener listener, MaintenanceWork work) {
            if (!started.compareAndSet(false, true)) return join(job, listener);
            if (listener != null) job.listeners.add(listener);
            try {
                removalError = work.run((stage, percent) -> {
                    job.stage = stage;
                    job.percent = percent;
                    for (ProgressListener l : job.listeners) l.onProgress(stage, percent);
                });
                job.ok = removalError == null;
                return job.ok;
            } catch (Exception e) {
                removalError = e.getMessage();
                Log.e(TAG, "maintenance", e);
                return false;
            } finally {
                synchronized (JOB_LOCK) { running = null; }
                job.done.countDown();
                if (listener != null) job.listeners.remove(listener);
            }
        }
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
            if (listener != null) listener.onProgress(job.step, job.stage, job.percent);
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
            if (pendingRemoval.exists() && listener != null) listener.onProgress(context.getString(R.string.rtinst_removing_leftovers), -1);
            if (!discard(pendingRemoval, null)) throw new IOException("Cannot clear the previous runtime: " + pendingRemoval);
            emptyTrash(LinuxRuntime.rootDir(context).getParentFile());
            String downloading = context.getString(R.string.rtinst_downloading);
            if (listener != null) listener.onProgress(Step.DOWNLOADING, downloading, 0);
            // Downloader reports a 0..1 fraction, or -1 while the total size is unknown.
            boolean ok = Downloader.downloadFile(release.url, archive, true, (fraction) -> {
                if (listener != null) {
                    listener.onProgress(Step.DOWNLOADING, downloading,
                            fraction < 0 ? -1 : Math.round(fraction * 100f));
                }
            });
            if (!ok) {
                Log.w(TAG, "download failed");
                return false;
            }

            if (listener != null) listener.onProgress(Step.VERIFYING, context.getString(R.string.rtinst_verifying), -1);
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
            if (!discard(staging, null) || !staging.mkdirs()) return false;
            if (listener != null) listener.onProgress(context.getString(R.string.rtinst_extracting), -1);
            if (!extract(context, archive, staging, listener)) {
                RuntimeFileTree.delete(staging, null);
                return false;
            }
            FileUtils.writeString(new File(staging, VERSION_FILE), release.version);

            if (!discard(old, null)) {
                discard(staging, null);
                return false;
            }
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
            discard(old, null);
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

    /** Where entries the app cannot unlink are set aside, out of the way of the runtime's names. */
    static final String TRASH = ".runtime-trash";

    /**
     * Gets {@code tree} out from under its name. What can be deleted is; if something inside
     * cannot be (a file made by root or {@code su} carries another SELinux category, and the app
     * may not unlink it), the remainder is moved aside into {@link #TRASH} instead of blocking
     * every later install and removal on it. False only when even that move is refused.
     */
    static boolean discard(File tree, java.util.function.LongConsumer progress) {
        if (RuntimeFileTree.deleteWhatCan(tree, progress)) return true;
        File trash = new File(tree.getParentFile(), TRASH);
        trash.mkdirs();
        File aside = new File(trash, tree.getName() + "-" + System.currentTimeMillis());
        if (tree.renameTo(aside)) {
            Log.w(TAG, "could not delete all of " + tree + "; set the rest aside in " + aside);
            return true;
        }
        Log.w(TAG, "could not delete or set aside " + tree);
        return !tree.exists();
    }

    /** Another try at what {@link #discard} set aside. Whatever still cannot go stays there. */
    static void emptyTrash(File parent) {
        File trash = new File(parent, TRASH);
        if (trash.exists() && !RuntimeFileTree.deleteWhatCan(trash, null))
            Log.i(TAG, "some runtime leftovers still cannot be deleted: " + trash);
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
        return beginUninstall(context.getApplicationContext(), LinuxRuntime.rootDir(context));
    }

    /** {@code app} words the progress; unit tests pass null and get none. */
    static Removal beginUninstall(Context app, File root) {
        synchronized (JOB_LOCK) {
            if (running != null) return null;
            removalError = null;
            running = new Job(true, text(app, R.string.rtinst_removing));
            return new Removal(app, root, running);
        }
    }

    private static String text(Context app, int id) {
        return app == null ? "" : app.getString(id);
    }

    public static final class Removal {
        private final Context app;
        private final File root;
        private final Job job;
        private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        private Removal(Context app, File root, Job job) { this.app = app; this.root = root; this.job = job; }

        public boolean run(ProgressListener listener) {
            if (!started.compareAndSet(false, true)) return join(job, listener);
            if (listener != null) job.listeners.add(listener);
            try {
                report(text(app, R.string.rtinst_removing), -1);
                final long[] removed = {0};
                final long[] lastShown = {0};
                java.util.function.LongConsumer progress = ignored -> {
                    removed[0]++;
                    long now = System.nanoTime();
                    if (removed[0] == 1 || now - lastShown[0] >= 200_000_000L) {
                        lastShown[0] = now;
                        report(app == null ? "" : app.getResources().getQuantityString(
                                R.plurals.rtinst_removing_entries, (int) Math.min(removed[0], Integer.MAX_VALUE), removed[0]), -1);
                    }
                };
                File pending = removalDirectory(root);
                // Invalidate the installation before traversing it. A process killed halfway
                // through leaves a named removal to resume, never a launchable partial runtime.
                if (root.exists()) {
                    if (!discard(pending, progress) || !root.renameTo(pending))
                        throw new IOException("Cannot prepare the runtime for removal");
                } else if (!pending.isDirectory() && !pending.mkdirs()) {
                    throw new IOException("Cannot prepare the runtime for removal");
                }
                // Keep the quarantine until all leftovers are gone, so a failure stays retryable.
                // An entry the app may not delete is set aside rather than failing the removal:
                // a quarantine that can never clear would block reinstalling for good.
                for (File leftover : new File[]{
                        new File(root.getParentFile(), root.getName() + ".new"),
                        new File(root.getParentFile(), root.getName() + ".old"),
                        pending}) {
                    if (!discard(leftover, progress)) throw new IOException("Cannot remove " + leftover);
                }
                emptyTrash(root.getParentFile());
                job.ok = true;
                report(text(app, R.string.rtinst_removed), 100);
                return true;
            } catch (IOException e) {
                removalError = text(app, R.string.rtinst_removal_failed);
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
    public static boolean extract(Context context, File archive, File destination, ProgressListener listener) {
        long entries = 0;
        String extracting = context.getString(R.string.rtinst_extracting);
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
                    listener.onProgress(extracting, -1);
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "extract", e);
            return false;
        }
    }
}
