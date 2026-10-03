package com.droiddeck.launcher.audio;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.droiddeck.launcher.core.FileUtils;
import com.droiddeck.launcher.core.SessionPart;
import com.droiddeck.launcher.core.HostProcess;
import com.droiddeck.launcher.core.TarZst;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * PulseAudio 13.0 running in the app process, with Android's AAudio as its sink, so the guest's
 * libpulse clients (Steam and everything it launches) have a server to talk to. The socket lives
 * in the app's files directory and is bound into the session at its own path, so PULSE_SERVER
 * needs no translating.
 */
public class PulseAudioComponent extends SessionPart {
    private static final String TAG = "PulseAudio";
    /** Where the guest reaches the daemon; the session exports PULSE_SERVER=unix:<this>. */
    public static final String SOCKET_NAME = "PS0";
    /** Identifies the bundled pulseaudio.tzst; a change here re-unpacks it over what a device has. */
    private static final String BUNDLE_STAMP = "2026-09-23-pa13-suspend-r5";

    private final File workingDir;
    /** Where the daemon's own output is kept for this session, or null for logcat only. */
    private File logFile;
    /**
     * A named pipe carrying microphone audio, or null for no microphone. The bundle ships
     * module-aaudio-sink but no matching source, and the daemon runs where Android permits
     * recording - so pointing module-pipe-source at a pipe the DirectAudio relay helper writes
     * turns that one stream into a source the client can see, named DirectAudioMic.
     */
    private final String micFifoPath;
    /** The DirectAudio relay's socket when the client's output should go through it, else null. */
    private String relaySocketPath;
    private volatile int pid = -1;

    public PulseAudioComponent(Context context) {
        this(context, null);
    }

    /** As above, with a microphone fed from {@code micFifoPath}; null for output only. */
    public PulseAudioComponent(Context context, String micFifoPath) {
        this.workingDir = new File(context.getFilesDir(), "pulseaudio");
        this.micFifoPath = micFifoPath;
    }

    /**
     * Route the daemon's output through the DirectAudio relay at this socket instead of an AAudio
     * stream of its own. The relay owns the stream outside proot, with its adaptive buffer; the
     * daemon only fills a shared ring. Set before {@link #start()}; the relay may start later.
     */
    public void setRelaySocket(String path) {
        this.relaySocketPath = path;
    }

    /** CRC-32 of the bundled pulseaudio.tzst, or "?" if it cannot be read. About 600 KB; cheap. */
    private String bundleChecksum() {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        byte[] buf = new byte[64 * 1024];
        try (java.io.InputStream in = app().getAssets().open("pulseaudio.tzst")) {
            int n;
            while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
        } catch (java.io.IOException e) {
            return "?";
        }
        return Long.toHexString(crc.getValue());
    }

    /** Send the daemon's output to this file as well as logcat. Set before {@link #start()}. */
    public void setLogFile(File file) {
        this.logFile = file;
    }

    public File socket() {
        return new File(workingDir, SOCKET_NAME);
    }

    @Override
    public void start() {
        stop();
        if (!workingDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }
        // The loadable modules (module-aaudio-sink - the app's own, from tools/aaudio-sink - the
        // native protocol, the pipe modules) ride in the apk; the daemon and its libraries come from the native library directory, the one
        // place an app may execute a file from. The bundle is unpacked once per BUNDLE_STAMP, not
        // once ever: an installed app kept the modules it unpacked on its first run, so a bundle
        // fixed in a later build never reached the device - which is how a 17.0 glibc build of
        // module-pipe-source sat beside the 13.0 daemon, failed to dlopen, and the microphone
        // never appeared. The stamp carries a checksum of the bundle itself, so any change to it -
        // a rebuilt sink module included - re-unpacks, whether or not BUNDLE_STAMP was bumped.
        File modulesDir = new File(workingDir, "modules");
        File stamp = new File(modulesDir, ".bundle");
        String have = FileUtils.readString(stamp);
        String want = BUNDLE_STAMP + "-" + bundleChecksum();
        if (!new File(modulesDir, "arm64/module-aaudio-sink.so").isFile()
                || !new File(workingDir, "pactl").isFile()
                || have == null || !want.equals(have.trim())) {
            Log.i(TAG, "unpacking pulseaudio.tzst (" + want + "; had " + have + ")");
            FileUtils.delete(modulesDir);
            if (TarZst.extractAsset(app(), "pulseaudio.tzst", workingDir)) {
                FileUtils.writeString(stamp, want);
            } else {
                Log.e(TAG, "pulseaudio.tzst did not unpack");
            }
        }
        File pactl = new File(workingDir, "pactl");
        if (pactl.isFile()) FileUtils.chmod(pactl, 0771);
        copyFromLibraryDir();

        //noinspection ResultOfMethodCallIgnored
        socket().delete();
        // module-pipe-source creates the pipe with mkfifo and fails outright if one is already
        // there - EEXIST, reported as "Unknown error 17" - and the module then does not load at
        // all, so the source never appears and the client reports no microphone. Ours lives in the
        // app's files directory and survives a session, so after the very first run the path would
        // always be occupied. Removed here, before the daemon reads this config: the daemon makes
        // it, and the relay helper starts afterwards and is content to find one already made.
        if (micFifoPath != null && !micFifoPath.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            new File(micFifoPath).delete();
        }
        ArrayList<String> config = new ArrayList<>();
        config.add("load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket=\""
                + socket().getAbsolutePath() + "\"");
        // volume=1.0 is not optional: with no volume argument module-aaudio-sink defaults
        // the sink to 0% and the session plays silence.
        // The sink's name is what the client's Audio settings show as the output device, so it
        // says which road the sound takes.
        if (relaySocketPath != null && !relaySocketPath.isEmpty()) {
            config.add("load-module module-directaudio-sink sink_name=DirectAudio socket=\"" + relaySocketPath + "\" performance_mode=1 adaptive=1 volume=1.0");
            config.add("set-default-sink DirectAudio");
        } else {
            // The classic sink: the adaptive AAudio module 0.1.5 shipped, unchanged.
            config.add("load-module module-aaudio-classic-sink sink_name=AAudioSink performance_mode=1 adaptive=1 volume=1.0");
            config.add("set-default-sink AAudioSink");
        }
        if (micFifoPath != null && !micFifoPath.isEmpty()) {
            // The format is the helper's, fixed at s16le/48000/mono: it resamples when the device
            // grants another input rate, so the daemon is never told a rate the bytes are not.
            // A pipe has no clock, so nothing here corrects drift - acceptable for voice.
            config.add("load-module module-pipe-source source_name=DirectAudioMic file=\""
                    + micFifoPath + "\" format=s16le rate=48000 channels=1");
            config.add("set-default-source DirectAudioMic");
        }
        FileUtils.writeString(new File(workingDir, "default.pa"), String.join("\n", config));

        File modules = new File(workingDir, "modules/arm64");
        ArrayList<String> env = new ArrayList<>();
        env.add("LD_LIBRARY_PATH=/system/lib64:" + modules + ":" + workingDir.getAbsolutePath());
        env.add("HOME=" + workingDir);
        env.add("TMPDIR=" + workingDir);

        String command = workingDir.getAbsolutePath() + "/libpulseaudio.so"
                + " --system=false --disable-shm=true --fail=false"
                + " -n --file=default.pa --daemonize=false --use-pid-file=false --exit-idle-time=-1"
                // Info level: the classic sink reports the stream Android granted (burst, capacity,
                // starting buffer) only there, and that is what a stutter report needs.
                + " --log-level=info";
        // A module that refuses to load, a pipe that could not be made, the daemon exiting at
        // startup: all of it used to reach logcat and nothing else, so a user's folder said nothing
        // at all about sound. Four separate faults hid behind "no input device" in one night.
        final java.io.PrintWriter out = openLog();
        pid = HostProcess.start(command, env.toArray(new String[0]), workingDir, null,
                line -> {
                    Log.i(TAG, line);
                    if (out != null) synchronized (out) { out.println(line); out.flush(); }
                });
    }

    @Override
    public void stop() {
        if (pid != -1) {
            Process.killProcess(pid);
            pid = -1;
        }
    }

    public boolean setSinkSuspended(boolean suspended) {
        File pactl = new File(workingDir, "pactl");
        if (!pactl.isFile() || pid <= 1) {
            Log.w(TAG, "cannot change sink state: PulseAudio control client or server unavailable");
            return false;
        }
        java.lang.Process process = null;
        try {
            File modules = new File(workingDir, "modules/arm64");
            ProcessBuilder builder = new ProcessBuilder(
                    pactl.getAbsolutePath(), "suspend-sink", "@DEFAULT_SINK@", Boolean.toString(suspended));
            builder.directory(workingDir);
            builder.redirectErrorStream(true);
            builder.redirectOutput(new File("/dev/null"));
            builder.environment().put("LD_LIBRARY_PATH", "/system/lib64:" + modules + ":" + workingDir);
            builder.environment().put("HOME", workingDir.getAbsolutePath());
            builder.environment().put("TMPDIR", workingDir.getAbsolutePath());
            builder.environment().put("PULSE_SERVER", "unix:" + socket().getAbsolutePath());
            process = builder.start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                Log.w(TAG, "timed out changing sink state to suspended=" + suspended);
                return false;
            }
            int status = process.exitValue();
            if (status != 0) Log.w(TAG, "sink state change failed with exit " + status);
            return status == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "interrupted changing sink state", e);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "could not change sink state", e);
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    /** The session's audio log, appended to by both the daemon and the relay helper. */
    private java.io.PrintWriter openLog() {
        if (logFile == null) return null;
        try {
            java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(logFile, true));
            w.println("== PulseAudio daemon starting" + (micFifoPath != null ? " with a microphone source" : ""));
            w.flush();
            return w;
        } catch (Exception e) {
            Log.w(TAG, "could not open " + logFile, e);
            return null;
        }
    }

    private void copyFromLibraryDir() {
        String[] libs = {"libltdl.so", "libpulseaudio.so", "libpulse.so",
                "libpulsecommon-13.0.so", "libpulsecore-13.0.so", "libsndfile.so", "libffi.so"};
        ClassLoader loader = PulseAudioComponent.class.getClassLoader();
        for (String lib : libs) {
            URL resource = loader != null ? loader.getResource("lib/arm64-v8a/" + lib) : null;
            if (resource == null) {
                Log.w(TAG, lib + " missing from the apk");
                continue;
            }
            File destination = new File(workingDir, lib);
            try (InputStream in = resource.openStream()) {
                Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                FileUtils.chmod(destination, 0771);
            } catch (Exception e) {
                Log.w(TAG, "copy " + lib, e);
            }
        }
    }
}
