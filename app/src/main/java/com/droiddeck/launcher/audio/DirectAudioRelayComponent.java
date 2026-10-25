package com.droiddeck.launcher.audio;

import android.os.Process;
import android.util.Log;

import com.droiddeck.launcher.core.FileUtils;
import com.droiddeck.launcher.core.SessionPart;
import com.droiddeck.launcher.core.HostProcess;

import java.io.File;
import java.util.ArrayList;

public class DirectAudioRelayComponent extends SessionPart {
    private static final String TAG = "DirectAudioRelay";
    public static final String BINARY = "libdirectaudiorelay.so";

    private final File socketPath;
    private File logFile;
    private final File micFifoPath;
    private volatile int pid = -1;
    private final Object micLock = new Object();
    private boolean micHot;
    private final File micControl;
    public java.util.function.Consumer<Boolean> onMicEnabled;

    public DirectAudioRelayComponent(File socketPath, File micFifoPath) {
        this.socketPath = socketPath;
        this.micFifoPath = micFifoPath;
        this.micControl = micFifoPath == null ? null : new File(micFifoPath.getParentFile(), "mic-enabled");
    }

    public void setLogFile(File file) {
        this.logFile = file;
    }

    @Override
    public void start() {
        stop();
        synchronized (micLock) {
            micHot = false;
        }
        File binary = new File(app().getApplicationInfo().nativeLibraryDir, BINARY);
        if (!binary.isFile()) {
            Log.w(TAG, "helper missing at " + binary + "; DirectAudio will report itself unavailable");
            return;
        }
        File parent = socketPath.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        socketPath.delete();

        if (micFifoPath != null) {
            long deadline = System.currentTimeMillis() + 3000L;
            while (!micFifoPath.exists() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            Log.i(TAG, micFifoPath.exists() ? "microphone pipe made by the daemon; starting helper"
                    : "daemon did not make the microphone pipe in 3 s; helper will make it");
        }

        StringBuilder command = new StringBuilder(binary.getAbsolutePath());
        command.append(" --socket ").append(socketPath.getAbsolutePath());
        command.append(" --log");
        final boolean withMic = micFifoPath != null && setMicEnabled(true);
        if (withMic) {
            command.append(" --mic-fifo ").append(micFifoPath.getAbsolutePath());
            command.append(" --mic-control ").append(micControl.getAbsolutePath());
        } else if (micFifoPath != null) {
            Log.w(TAG, "microphone control unavailable; starting with capture disabled");
            command.append(" --mic-control /dev/null");
        }
        ArrayList<String> env = new ArrayList<>();
        env.add("HOME=" + app().getFilesDir());
        java.io.PrintWriter out = null;
        if (logFile != null) {
            try {
                out = new java.io.PrintWriter(new java.io.FileWriter(logFile, true));
                out.println("== DirectAudio relay helper starting"
                        + (withMic ? " with the microphone" : " (no microphone)"));
                out.flush();
            } catch (Exception e) {
                Log.w(TAG, "could not open " + logFile, e);
            }
        }
        final java.io.PrintWriter log = out;
        pid = HostProcess.start(command.toString(), env.toArray(new String[0]),
                app().getFilesDir(), null,
                line -> {
                    Log.i(TAG, line);
                    if (withMic) trackMic(line);
                    if (log != null) synchronized (log) { log.println(line); log.flush(); }
                });
        Log.i(TAG, "started pid=" + pid + " socket=" + socketPath
                + (withMic ? " mic=" + micFifoPath : " (no microphone)"));
    }

    public synchronized boolean setMicEnabled(boolean enabled) {
        if (micControl == null) return false;
        File next = new File(micControl.getPath() + ".new");
        return FileUtils.writeString(next, enabled ? "1" : "0") && next.renameTo(micControl);
    }

    private void trackMic(String line) {
        if (line.startsWith("DA-Relay: mic enabled=") && onMicEnabled != null) {
            onMicEnabled.accept(line.equals("DA-Relay: mic enabled=1"));
        }
        boolean start = line.startsWith("DA-Relay: mic start ");
        if (!start && !line.startsWith("DA-Relay: mic stop ")) return;
        synchronized (micLock) {
            micHot = start;
            micLock.notifyAll();
        }
    }

    // Android keeps the input stream's wake lock while a frozen helper still has it started.
    public boolean awaitMicReleased(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (micLock) {
            while (micHot) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false;
                try {
                    micLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public void stop() {
        if (pid != -1) {
            Process.killProcess(pid);
            pid = -1;
        }
    }

    @Override
    public int suspendPid() {
        return pid;
    }
}
