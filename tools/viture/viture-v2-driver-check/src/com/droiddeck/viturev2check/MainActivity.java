package com.droiddeck.viturev2check;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.droiddeck.launcher.glasses.VitureV2Link;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final String PERMISSION = "com.droiddeck.viturev2check.USB_PERMISSION";
    private static final int VENDOR = 0x35ca, PRODUCT = 0x1301, INTERNAL_IMU = 0x0015;
    private static final long TOTAL_LIMIT_MS = 25000, CLOSE_RESERVE_MS = 1500;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean finishing = new AtomicBoolean();
    private final AtomicLong poses = new AtomicLong(), capturePoses = new AtomicLong(), packetBytes = new AtomicLong();
    private final AtomicLong readyCallbacks = new AtomicLong(), trackingOn = new AtomicLong(), trackingOff = new AtomicLong(), errors = new AtomicLong();
    private final Object poseLock = new Object();
    private UsbManager manager;
    private UsbDevice pending;
    private TextView status;
    private VitureV2Link link;
    private Journal journal;
    private boolean busy, bootstrapHandled, firstOffAccepted, resumeAccepted, finalOffAccepted;
    private int seconds;
    private volatile String phase = "idle";
    private long startedMs, firstOffExtra = -1, finalOffExtra = -1, captureStartedNs, captureEndedNs;
    private long firstCaptureTimeNs, lastCaptureTimeNs;
    private float[] lastPose;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!PERMISSION.equals(intent.getAction()) || pending == null) return;
            UsbDevice device = pending;
            pending = null;
            if (manager.hasPermission(device)) begin(device);
            else { busy = false; show("USB permission denied."); }
        }
    };

    private final VitureV2Link.Listener listener = new VitureV2Link.Listener() {
        @Override public void onReady() {
            readyCallbacks.incrementAndGet();
            event("ready", "phase", phase);
            handler.post(() -> bootstrapOff());
        }
        @Override public void onValue(int message, int value) { event("value", "messageId", message, "value", value); }
        @Override public void onPose(byte[] raw, float[] q, long timeNs) {
            long count = poses.incrementAndGet();
            packetBytes.addAndGet(raw.length);
            String observedPhase = phase;
            synchronized (poseLock) {
                lastPose = q.clone();
                if ("capture".equals(observedPhase)) {
                    if (capturePoses.incrementAndGet() == 1) firstCaptureTimeNs = timeNs;
                    lastCaptureTimeNs = timeNs;
                }
            }
            event("pose", "phase", observedPhase, "count", count, "receiptTimeNs", timeNs,
                "sharedXyzw", floats(q), "packetLength", raw.length, "packetBase64", Base64.encodeToString(raw, Base64.NO_WRAP));
        }
        @Override public void onTracking(boolean active) {
            if (active) trackingOn.incrementAndGet(); else trackingOff.incrementAndGet();
            event("tracking", "active", active, "phase", phase);
        }
        @Override public void onLost() {
            errors.incrementAndGet();
            event("lost", "phase", phase);
            handler.post(() -> finishCheck("driver_lost"));
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        manager = (UsbManager) getSystemService(USB_SERVICE);
        seconds = Math.max(10, Math.min(15, getIntent().getIntExtra("seconds", 12)));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);
        Button start = new Button(this);
        start.setText("Check production driver on Pro 2");
        start.setOnClickListener(view -> select());
        layout.addView(start);
        Button stop = new Button(this);
        stop.setText("Stop and close driver");
        stop.setOnClickListener(view -> finishCheck("user_stop"));
        layout.addView(stop);
        status = new TextView(this);
        status.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        layout.addView(scroll);
        setContentView(layout);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, new IntentFilter(PERMISSION), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, new IntentFilter(PERMISSION));
        show("Actual compiled production VitureV2Link. Pro 2 only. Ready, pause 500 ms, capture " + seconds + " s, pause 500 ms, close.");
        show("Logs: " + getFilesDir() + "/checks");
        if (getIntent().getBooleanExtra("auto", false)) select();
    }

    @Override protected void onDestroy() {
        finishCheck("activity_destroyed");
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    private void select() {
        if (busy || finishing.get()) { show("A check is already active or complete; reopen the activity for another run."); return; }
        List<UsbDevice> matches = new ArrayList<>();
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getVendorId() == VENDOR && device.getProductId() == PRODUCT) matches.add(device);
        }
        if (matches.size() != 1) { show("Expected exactly one 35ca:1301 Pro 2; found " + matches.size()); return; }
        busy = true;
        UsbDevice device = matches.get(0);
        if (manager.hasPermission(device)) begin(device);
        else {
            pending = device;
            manager.requestPermission(device, PendingIntent.getBroadcast(this, PRODUCT,
                new Intent(PERMISSION).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
    }

    private void begin(UsbDevice device) {
        if (finishing.get()) return;
        startedMs = SystemClock.elapsedRealtime();
        try {
            File directory = new File(getFilesDir(), "checks");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Could not create log directory");
            File file = new File(directory, "driver-check-" + System.currentTimeMillis() + ".jsonl");
            journal = new Journal(file);
            event("session", "vendorId", VENDOR, "productId", PRODUCT, "captureSeconds", seconds,
                "totalLimitMillis", TOTAL_LIMIT_MS, "runtime", new JSONObject(readAsset("runtime-manifest.json")));
            show("Recording " + file.getAbsolutePath());
            phase = "bootstrap";
            handler.postDelayed(() -> finishCheck("watchdog"), TOTAL_LIMIT_MS - CLOSE_RESERVE_MS);
            link = VitureV2Link.Companion.open(manager, device, listener);
            event("open_returned", "opened", link != null);
            if (link == null) { errors.incrementAndGet(); finishCheck("open_failed"); }
        } catch (Exception error) {
            errors.incrementAndGet();
            event("error", "operation", "open", "message", error.toString());
            finishCheck("open_error");
        }
    }

    private void bootstrapOff() {
        if (finishing.get() || bootstrapHandled) return;
        if (link == null) { handler.postDelayed(() -> bootstrapOff(), 5); return; }
        bootstrapHandled = true;
        try {
            firstOffAccepted = link.send(INTERNAL_IMU, new byte[] {0});
            long baseline = poses.get();
            phase = "bootstrap_off";
            event("off_requested", "stage", "bootstrap", "accepted", firstOffAccepted, "poseBaseline", baseline);
            if (!firstOffAccepted) { errors.incrementAndGet(); finishCheck("bootstrap_off_rejected"); return; }
            handler.postDelayed(() -> {
                if (finishing.get()) return;
                firstOffExtra = poses.get() - baseline;
                event("off_observation", "stage", "bootstrap", "windowMillis", 500, "newPoses", firstOffExtra,
                    "trackingActive", link.getTrackingActive(), "ready", link.getReady());
                if (firstOffExtra != 0 || link.getTrackingActive()) errors.incrementAndGet();
                resume();
            }, 500);
        } catch (Exception error) { fail("bootstrap_off", error); }
    }

    private void resume() {
        try {
            phase = "capture";
            captureStartedNs = System.nanoTime();
            resumeAccepted = link.send(INTERNAL_IMU, new byte[] {1});
            event("on_requested", "accepted", resumeAccepted);
            if (!resumeAccepted) { errors.incrementAndGet(); finishCheck("resume_rejected"); return; }
            show("Capturing production-driver poses for " + seconds + " seconds.");
            handler.postDelayed(() -> finalOff(), seconds * 1000L);
        } catch (Exception error) { fail("resume", error); }
    }

    private void finalOff() {
        if (finishing.get()) return;
        try {
            finalOffAccepted = link.send(INTERNAL_IMU, new byte[] {0});
            captureEndedNs = System.nanoTime();
            long baseline = poses.get();
            phase = "final_off";
            event("off_requested", "stage", "final", "accepted", finalOffAccepted, "poseBaseline", baseline);
            if (!finalOffAccepted) { errors.incrementAndGet(); finishCheck("final_off_rejected"); return; }
            handler.postDelayed(() -> {
                if (finishing.get()) return;
                finalOffExtra = poses.get() - baseline;
                event("off_observation", "stage", "final", "windowMillis", 500, "newPoses", finalOffExtra,
                    "trackingActive", link.getTrackingActive(), "ready", link.getReady());
                if (finalOffExtra != 0 || link.getTrackingActive()) errors.incrementAndGet();
                finishCheck("completed");
            }, 500);
        } catch (Exception error) { fail("final_off", error); }
    }

    private void fail(String operation, Exception error) {
        errors.incrementAndGet();
        event("error", "operation", operation, "message", error.toString());
        finishCheck(operation + "_error");
    }

    private void finishCheck(String reason) {
        if (!busy || !finishing.compareAndSet(false, true)) return;
        pending = null;
        handler.removeCallbacksAndMessages(null);
        phase = "closing";
        long badFrames = 0;
        try {
            if (link != null) { badFrames = link.getBadFrames(); link.close(); }
        } catch (Exception error) {
            errors.incrementAndGet();
            event("error", "operation", "close", "message", error.toString());
        } finally {
            phase = "closed";
            long captureCount = capturePoses.get();
            long captureNs = captureEndedNs > captureStartedNs ? captureEndedNs - captureStartedNs : 0;
            double rate = 0;
            JSONArray finalQ;
            synchronized (poseLock) {
                if (captureCount > 1 && lastCaptureTimeNs > firstCaptureTimeNs)
                    rate = (captureCount - 1) * 1_000_000_000.0 / (lastCaptureTimeNs - firstCaptureTimeNs);
                finalQ = floats(lastPose);
            }
            boolean pass = "completed".equals(reason) && errors.get() == 0 && readyCallbacks.get() > 0 &&
                firstOffAccepted && resumeAccepted && finalOffAccepted && firstOffExtra == 0 && finalOffExtra == 0 && captureCount > 0 &&
                trackingOn.get() >= 2 && trackingOff.get() >= 2 && link != null && !link.getReady() && !link.getTrackingActive();
            event("summary", "reason", reason, "pass", pass, "totalMillis", SystemClock.elapsedRealtime() - startedMs,
                "poseCount", poses.get(), "capturePoseCount", captureCount, "captureMillis", captureNs / 1_000_000.0,
                "sampleRateHz", rate, "readyCallbacks", readyCallbacks.get(), "trackingOnCallbacks", trackingOn.get(),
                "trackingOffCallbacks", trackingOff.get(), "errors", errors.get(), "badFrames", badFrames,
                "bootstrapOffNewPoses", firstOffExtra, "finalOffNewPoses", finalOffExtra,
                "readyAfterClose", link != null && link.getReady(), "trackingAfterClose", link != null && link.getTrackingActive(),
                "lastSharedXyzw", finalQ, "packetBytes", packetBytes.get());
            if (journal != null) {
                try { journal.close(); } catch (Exception error) { Log.e("VitureV2Check", "Log close failed", error); }
            }
            busy = false;
            show("Finished: " + reason + ", pass=" + pass + ", capture poses=" + captureCount + ", rate=" + String.format(java.util.Locale.ROOT, "%.1f", rate) + " Hz.");
        }
    }

    private void event(String type, Object... fields) {
        Journal output = journal;
        if (output == null) return;
        try { output.event(type, fields); }
        catch (Exception error) { errors.incrementAndGet(); Log.e("VitureV2Check", "Could not log " + type, error); }
    }

    private String readAsset(String name) throws Exception {
        try (InputStream input = getAssets().open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static JSONArray floats(float[] values) {
        JSONArray result = new JSONArray();
        if (values != null) for (float value : values) {
            try { result.put((double) value); } catch (Exception ignored) { result.put(JSONObject.NULL); }
        }
        return result;
    }

    private void show(String message) {
        Log.i("VitureV2Check", message);
        runOnUiThread(() -> status.append(message + "\n"));
    }

    private static final class Journal implements AutoCloseable {
        private final BufferedWriter writer;
        Journal(File file) throws Exception { writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)); }
        synchronized void event(String type, Object... fields) throws Exception {
            JSONObject json = new JSONObject();
            json.put("event", type);
            json.put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos());
            for (int i = 0; i < fields.length; i += 2) json.put((String) fields[i], fields[i + 1]);
            writer.write(json.toString());
            writer.newLine();
            writer.flush();
        }
        @Override public synchronized void close() throws Exception { writer.close(); }
    }
}
