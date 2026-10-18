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

public final class LinuxRuntimeInstaller {
    private static final String TAG = "LinuxRuntimeInstaller";

    public static final String CATALOG_URL =
            "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/linuxfs.json";

    private static final String VERSION_FILE = ".version";

    public enum Step { DOWNLOADING, VERIFYING, OTHER }

    public interface ProgressListener {
        void onProgress(String stage, int percent);

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

    public static String installedVersion(Context context) {
        File marker = new File(LinuxRuntime.rootDir(context), VERSION_FILE);
        if (!marker.isFile() || !LinuxRuntime.isInstalled(context)) return null;
        String v = FileUtils.readString(marker);
        return v == null ? null : v.trim();
    }

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

    private static final String USER_DATA = "root";

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

    /**
     * The download is named after the release, so a partial file is only ever resumed for the same
     * version and checksum, never for a different runtime that happens to share a fixed name.
     */
    static String archiveName(Release release) {
        String version = release.version.replaceAll("[^A-Za-z0-9._-]", "_");
        String sum = release.sha256.replaceAll("[^A-Za-z0-9]", "");
        if (sum.length() > 12) sum = sum.substring(0, 12);
        return "linuxfs-" + version + "-" + sum + ".tar.zst";
    }

    /** Removes archives left by other releases and by builds that used the fixed name. */
    private static void deleteOtherArchives(File dir, String keep) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("linuxfs") && name.endsWith(".tar.zst") && !name.equals(keep));
        if (files == null) return;
        for (File file : files) file.delete();
    }

    private static boolean installOnce(Context context, Release release, ProgressListener listener) {
        File archive = new File(context.getCacheDir(), archiveName(release));
        // A failed download keeps what it fetched so the next attempt resumes it; anything that
        // reached verification and failed is not trusted and is removed.
        boolean keepPartial = false;
        try {
            deleteOtherArchives(context.getCacheDir(), archive.getName());
            File pendingRemoval = removalDirectory(LinuxRuntime.rootDir(context));
            if (pendingRemoval.exists() && listener != null) listener.onProgress(context.getString(R.string.rtinst_removing_leftovers), -1);
            if (!discard(pendingRemoval, null)) throw new IOException("Cannot clear the previous runtime: " + pendingRemoval);
            emptyTrash(LinuxRuntime.rootDir(context).getParentFile());
            String downloading = context.getString(R.string.rtinst_downloading);
            if (listener != null) listener.onProgress(Step.DOWNLOADING, downloading, 0);
            boolean ok = Downloader.downloadFile(release.url, archive, true, release.sha256, (fraction) -> {
                if (listener != null) {
                    listener.onProgress(Step.DOWNLOADING, downloading,
                            fraction < 0 ? -1 : Math.round(fraction * 100f));
                }
            });
            if (!ok) {
                Log.w(TAG, "download failed");
                keepPartial = true;
                return false;
            }

            if (listener != null) listener.onProgress(Step.VERIFYING, context.getString(R.string.rtinst_verifying), -1);
            String actual = Hashes.sha256(archive);
            if (!release.sha256.equalsIgnoreCase(actual)) {
                Log.w(TAG, "checksum mismatch: wanted " + release.sha256 + ", got " + actual);
                return false;
            }

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

            try {
                RuntimeFileTree.carryHome(old, staging);
            } catch (IOException e) {
                Log.w(TAG, "could not carry root across the update; rolling back", e);
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
            if (!keepPartial) archive.delete();
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

    static final String TRASH = ".runtime-trash";

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

    static void emptyTrash(File parent) {
        File trash = new File(parent, TRASH);
        if (trash.exists() && !RuntimeFileTree.deleteWhatCan(trash, null))
            Log.i(TAG, "some runtime leftovers still cannot be deleted: " + trash);
    }

    private static boolean isEmptyDir(File dir) {
        String[] names = dir.list();
        return dir.isDirectory() && names != null && names.length == 0;
    }

    public static Removal beginUninstall(Context context) {
        if (sessionInUse()) return null;
        return beginUninstall(context.getApplicationContext(), LinuxRuntime.rootDir(context));
    }

    private static boolean sessionInUse() {
        com.droiddeck.launcher.session.SessionState session = com.droiddeck.launcher.session.SessionState.INSTANCE;
        com.droiddeck.launcher.session.SessionPhase phase = session.getPhase();
        return session.getRunning() ||
                (phase != com.droiddeck.launcher.session.SessionPhase.IDLE && phase != com.droiddeck.launcher.session.SessionPhase.FAILED);
    }

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
                requireStopped();
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
                if (root.exists()) {
                    if (!discard(pending, progress) || !root.renameTo(pending))
                        throw new IOException("Cannot prepare the runtime for removal");
                } else if (!pending.isDirectory() && !pending.mkdirs()) {
                    throw new IOException("Cannot prepare the runtime for removal");
                }
                for (File leftover : new File[]{
                        new File(root.getParentFile(), root.getName() + ".new"),
                        new File(root.getParentFile(), root.getName() + ".old"),
                        pending}) {
                    if (!discard(leftover, progress)) throw new IOException("Cannot remove " + leftover);
                }
                emptyTrash(root.getParentFile());
                requireStopped();
                if (app != null) RuntimeFileTree.delete(new File(app.getNoBackupFilesDir(), "guest-runtime"), null);
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

        private void requireStopped() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Runtime removal was interrupted");
            if (app != null && sessionInUse()) throw new IOException("A session is still using the runtime");
        }
        private void report(String stage, int percent) {
            job.stage = stage;
            job.percent = percent;
            for (ProgressListener listener : job.listeners) listener.onProgress(stage, percent);
        }
    }

    public static boolean extract(Context context, File archive, File destination, ProgressListener listener) {
        long entries = 0;
        String extracting = context.getString(R.string.rtinst_extracting);
        try (InputStream in = new ZstdCompressorInputStream(
                new BufferedInputStream(new FileInputStream(archive), 1 << 16));
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
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
                    continue;
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
