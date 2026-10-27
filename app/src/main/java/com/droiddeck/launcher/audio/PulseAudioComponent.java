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

public class PulseAudioComponent extends SessionPart {
    private static final String TAG = "PulseAudio";
    public static final String SOCKET_NAME = "PS0";
    private static final String MIC_SOURCE = "DirectAudioMic";
    private static final String BUNDLE_STAMP = "2026-10-08-pa13-directaudio-native";



    private final File workingDir;
    private final File deviceControls;
    private File logFile;
    private final String micFifoPath;
    private volatile int pid = -1;

    public PulseAudioComponent(Context context) {
        this(context, null);
    }

    public PulseAudioComponent(Context context, String micFifoPath) {
        this.workingDir = new File(context.getFilesDir(), "pulseaudio");
        this.deviceControls = com.droiddeck.launcher.runtime.DeviceControlsComponent.directory(
                com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context));
        this.micFifoPath = micFifoPath;
    }

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
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }
        File modulesDir = new File(workingDir, "modules");
        File stamp = new File(modulesDir, ".bundle");
        String have = FileUtils.readString(stamp);
        String want = BUNDLE_STAMP + "-" + bundleChecksum();
        if (!new File(modulesDir, "arm64/module-aaudio-sink.so").isFile()
                || !new File(modulesDir, "arm64/module-directaudio-native-sink.so").isFile()
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

        socket().delete();
        if (micFifoPath != null && !micFifoPath.isEmpty()) {
            new File(micFifoPath).delete();
        }
        ArrayList<String> config = new ArrayList<>();
        config.add("load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket=\""
                + socket().getAbsolutePath() + "\"");
        config.add(".nofail");
        config.add("load-module module-directaudio-native-sink sink_name=DirectAudio performance_mode=1 adaptive=1 decay=0 limiter=1 watchdog=1 volume=1.0 control_dir=" + deviceControls);
        config.add("set-default-sink DirectAudio");
        config.add(".fail");
        if (micFifoPath != null && !micFifoPath.isEmpty()) {
            config.add("load-module module-pipe-source source_name=" + MIC_SOURCE + " file=\""
                    + micFifoPath + "\" format=s16le rate=48000 channels=1");
            config.add("set-default-source " + MIC_SOURCE);
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
                + " --log-level=info";
        final java.io.PrintWriter out = openLog();
        pid = HostProcess.start(command, env.toArray(new String[0]), workingDir, null,
                line -> {
                    Log.i(TAG, line);
                    if (out != null) synchronized (out) { out.println(line); out.flush(); }
                });
        Thread guard = new Thread(() -> ensureClientSink(out), "pulse-sink-guard");
        guard.setDaemon(true);
        guard.start();
    }

    private void ensureClientSink(java.io.PrintWriter out) {
        try {
            long deadline = System.currentTimeMillis() + 15000L;
            String sinks = null;
            while (System.currentTimeMillis() < deadline && pid > 1) {
                if (socket().exists() && (sinks = pactl("list", "short", "sinks")) != null) break;
                try { Thread.sleep(250L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            String line;
            if (sinks == null) {
                line = "== client sink: the daemon did not answer in 15 s; no fallback attempted";
            } else if (sinks.contains("\tDirectAudio\t") || sinks.startsWith("DirectAudio\t") || sinks.contains("\tDirectAudio\n") || sinks.matches("(?s).*\\bDirectAudio\\b.*")) {
                line = "== client sink: DirectAudio (module-directaudio-native-sink)";
            } else {
                boolean loaded = pactl("load-module", "module-aaudio-sink", "sink_name=AAudioSink", "performance_mode=1", "adaptive=1", "volume=1.0", "control_dir=" + deviceControls) != null
                        && pactl("set-default-sink", "AAudioSink") != null;
                line = loaded ? "== client sink: DirectAudio did not come up; FALLBACK to the plain AAudio sink (AAudioSink)"
                              : "== client sink: DirectAudio did not come up and the AAudio fallback failed too - no client sound";
                Log.w(TAG, line);
            }
            Log.i(TAG, line);
            if (out != null) synchronized (out) { out.println(line); out.flush(); }
        } catch (Throwable t) {
            Log.w(TAG, "ensureClientSink failed", t);
        }
    }

    private String pactl(String... args) {
        File pactl = new File(workingDir, "pactl");
        if (!pactl.isFile() || pid <= 1) return null;
        java.lang.Process process = null;
        try {
            File modules = new File(workingDir, "modules/arm64");
            ArrayList<String> cmd = new ArrayList<>();
            cmd.add(pactl.getAbsolutePath());
            java.util.Collections.addAll(cmd, args);
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.directory(workingDir);
            builder.redirectErrorStream(true);
            builder.environment().put("LD_LIBRARY_PATH", "/system/lib64:" + modules + ":" + workingDir);
            builder.environment().put("HOME", workingDir.getAbsolutePath());
            builder.environment().put("TMPDIR", workingDir.getAbsolutePath());
            builder.environment().put("PULSE_SERVER", "unix:" + socket().getAbsolutePath());
            process = builder.start();
            String output;
            try (InputStream in = process.getInputStream();
                 java.io.ByteArrayOutputStream outStream = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    outStream.write(buf, 0, n);
                }
                output = outStream.toString(java.nio.charset.StandardCharsets.UTF_8.name());
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? output : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    @Override
    public void stop() {
        if (pid != -1) {
            Process.killProcess(pid);
            pid = -1;
        }
    }

    public boolean setSinkSuspended(boolean suspended) {
        return suspend("sink", "@DEFAULT_SINK@", suspended);
    }

    public boolean setMicSuspended(boolean suspended) {
        return micFifoPath == null || micFifoPath.isEmpty() || suspend("source", MIC_SOURCE, suspended);
    }

    private boolean suspend(String kind, String name, boolean suspended) {
        File pactl = new File(workingDir, "pactl");
        if (!pactl.isFile() || pid <= 1) {
            Log.w(TAG, "cannot change " + kind + " state: PulseAudio control client or server unavailable");
            return false;
        }
        java.lang.Process process = null;
        try {
            File modules = new File(workingDir, "modules/arm64");
            ProcessBuilder builder = new ProcessBuilder(
                    pactl.getAbsolutePath(), "suspend-" + kind, name, Boolean.toString(suspended));
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
                Log.w(TAG, "timed out changing " + kind + " state to suspended=" + suspended);
                return false;
            }
            int status = process.exitValue();
            if (status != 0) Log.w(TAG, kind + " state change failed with exit " + status);
            return status == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "interrupted changing " + kind + " state", e);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "could not change " + kind + " state", e);
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

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
