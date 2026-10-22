package com.droiddeck.viturev2probe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

public final class MainActivity extends Activity {
    private static final String PERMISSION = "com.droiddeck.viturev2probe.USB_PERMISSION";
    private static final int VENDOR = 0x35ca;
    private static final int PRODUCT = 0x1301;
    private static final int INTERFACE = 0;
    private static final int BYTE_LIMIT = 4 * 1024 * 1024;
    private UsbManager manager;
    private TextView status;
    private volatile boolean stop;
    private boolean busy;
    private UsbDevice pending;
    private int durationSeconds = 15;
    private boolean rawMode;
    private boolean invalidMode;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!PERMISSION.equals(intent.getAction()) || pending == null) return;
            UsbDevice device = pending;
            pending = null;
            if (manager.hasPermission(device)) start(device);
            else { busy = false; show("USB permission denied."); }
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        manager = (UsbManager) getSystemService(USB_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);
        status = new TextView(this);
        status.setTextIsSelectable(true);
        addButton(layout, "Probe VITURE Pro 2", () -> select(PRODUCT, INTERFACE));
        addButton(layout, "Stop and disable stream", () -> stop = true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        layout.addView(scroll);
        setContentView(layout);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, new IntentFilter(PERMISSION), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, new IntentFilter(PERMISSION));
        String mode = getIntent().getStringExtra("mode");
        rawMode = "raw".equals(mode);
        invalidMode = mode != null && !rawMode && !"pose".equals(mode);
        durationSeconds = Math.max(1, Math.min(30, getIntent().getIntExtra("seconds", 15)));
        show("Pro 2 firmware query, " + (rawMode ? "raw" : "pose") + " at 120 Hz, then stream off. Capture window: " + durationSeconds + " s.");
        if (invalidMode) show("Invalid mode: use pose or raw.");
        show("Logs: " + getFilesDir() + "/captures");
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getVendorId() == VENDOR && device.getProductId() == PRODUCT)
                show(String.format("Attached Pro 2 %04x:%04x, %d interfaces", VENDOR, PRODUCT, device.getInterfaceCount()));
        }
        if (getIntent().getBooleanExtra("auto", false)) {
            select(getIntent().getIntExtra("pid", PRODUCT), getIntent().getIntExtra("interface", INTERFACE));
        }
    }

    private void addButton(LinearLayout layout, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        layout.addView(button);
    }

    @Override protected void onDestroy() {
        stop = true;
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    private void show(String message) {
        Log.i("VitureV2Probe", message);
        runOnUiThread(() -> status.append(message + "\n"));
    }

    private void select(int product, int intf) {
        if (busy) { show("A probe or permission request is already active."); return; }
        if (invalidMode) { show("Probe disabled: use mode pose or raw."); return; }
        if (product != PRODUCT || intf != INTERFACE) {
            show("This probe requires Pro 2 pid 4865 (0x1301), interface 0."); return;
        }
        List<UsbDevice> matches = new ArrayList<>();
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getVendorId() == VENDOR && device.getProductId() == product) matches.add(device);
        }
        if (matches.size() != 1) { show("Expected one matching device; found " + matches.size()); return; }
        busy = true;
        stop = false;
        UsbDevice device = matches.get(0);
        if (manager.hasPermission(device)) start(device);
        else {
            pending = device;
            PendingIntent permission = PendingIntent.getBroadcast(this, product,
                new Intent(PERMISSION).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            manager.requestPermission(device, permission);
        }
    }

    private void start(UsbDevice device) {
        new Thread(() -> {
            File directory = new File(getFilesDir(), "captures");
            directory.mkdirs();
            File file = new File(directory, "probe-" + System.currentTimeMillis() + "-pid-" + device.getProductId() + "-if-" + INTERFACE + ".jsonl");
            try (Journal journal = new Journal(file)) {
                show("Recording " + file.getName());
                journal.event("session", "vendorId", device.getVendorId(), "productId", device.getProductId(),
                    "interface", INTERFACE, "seconds", durationSeconds, "wallTimeMillis", System.currentTimeMillis(),
                    "mode", rawMode ? "raw" : "pose",
                    "modeSource", getIntent().hasExtra("mode") ? "explicit intent extra" : "default pose",
                    "requestedStream", rawMode ? "raw_120hz" : "pose_120hz",
                    "upstreamCommit", "ece8c30485f7d357fefa362ae74f3eabac9b3899", "byteLimitPerInput", BYTE_LIMIT,
                    "quaternionNormTolerance", 0.05, "timestampMeaning", "host elapsedRealtimeNanos; decoded tick is an uncalibrated device field");
                describe(device, journal);
                probe(device, journal);
                journal.event("session_end", "stoppedByUserOrActivity", stop);
                show("Saved " + file.getAbsolutePath());
            } catch (Exception error) { show("Probe error: " + error); }
            finally { runOnUiThread(() -> busy = false); }
        }, "viture-v2-probe").start();
    }

    private void describe(UsbDevice device, Journal journal) throws Exception {
        journal.event("device", "vendorId", device.getVendorId(), "productId", device.getProductId(), "configurationCount", device.getConfigurationCount());
        for (int c = 0; c < device.getConfigurationCount(); c++) {
            UsbConfiguration configuration = device.getConfiguration(c);
            journal.event("configuration", "productId", device.getProductId(), "id", configuration.getId(), "interfaceCount", configuration.getInterfaceCount());
            for (int i = 0; i < configuration.getInterfaceCount(); i++) {
                UsbInterface intf = configuration.getInterface(i);
                journal.event("interface", "productId", device.getProductId(), "configurationId", configuration.getId(),
                    "interface", intf.getId(), "alternate", intf.getAlternateSetting(), "class", intf.getInterfaceClass(),
                    "subclass", intf.getInterfaceSubclass(), "protocol", intf.getInterfaceProtocol());
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint endpoint = intf.getEndpoint(e);
                    journal.event("endpoint", "productId", device.getProductId(), "configurationId", configuration.getId(),
                        "interface", intf.getId(), "alternate", intf.getAlternateSetting(), "address", endpoint.getAddress(),
                        "type", endpoint.getType(), "direction", endpoint.getDirection(), "maxPacketSize", endpoint.getMaxPacketSize(), "interval", endpoint.getInterval());
                }
            }
        }
    }

    private void probe(UsbDevice device, Journal journal) throws Exception {
        if (device.getVendorId() != VENDOR || device.getProductId() != PRODUCT) {
            journal.event("outcome", "result", "unsupported_product"); return;
        }
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) { journal.event("outcome", "result", "open_failed"); return; }
        UsbInterface claimed = null;
        Pump pump = new Pump(connection, journal);
        UsbEndpoint output = null;
        boolean readsReady = false;
        boolean enableAttempted = false;
        String result = "unsupported_transport";
        try {
            byte[] descriptors = connection.getRawDescriptors();
            journal.event("raw_descriptors", "length", descriptors == null ? -1 : descriptors.length, "bytesBase64", encode(descriptors));
            byte[] active = new byte[1];
            int configRead = connection.controlTransfer(0x80, 8, 0, 0, active, 1, 500);
            journal.event("get_configuration", "result", configRead, "configuration", configRead == 1 ? active[0] & 255 : -1);
            if (configRead != 1) return;
            UsbConfiguration configuration = null;
            for (int c = 0; c < device.getConfigurationCount(); c++) {
                if (device.getConfiguration(c).getId() == (active[0] & 255)) configuration = device.getConfiguration(c);
            }
            if (configuration == null) return;
            List<UsbInterface> alternatives = new ArrayList<>();
            for (int i = 0; i < configuration.getInterfaceCount(); i++) {
                if (configuration.getInterface(i).getId() == INTERFACE) alternatives.add(configuration.getInterface(i));
            }
            byte[] alt = new byte[1];
            int altRead = connection.controlTransfer(0x81, 10, 0, INTERFACE, alt, 1, 500);
            int activeAlt = altRead == 1 ? alt[0] & 255 : alternatives.size() == 1 && alternatives.get(0).getAlternateSetting() == 0 ? 0 : -1;
            journal.event("get_interface", "result", altRead, "alternate", activeAlt,
                "evidence", altRead == 1 ? "GET_INTERFACE" : "only advertised alternate zero or unknown");
            UsbInterface selected = null;
            for (UsbInterface intf : alternatives) if (intf.getAlternateSetting() == activeAlt) selected = intf;
            if (selected == null) { journal.event("unsupported", "reason", "selected active interface unavailable"); return; }
            if (activeAlt != 0 || selected.getInterfaceClass() != UsbConstants.USB_CLASS_HID || selected.getEndpointCount() != 2) {
                journal.event("unsupported", "reason", "Pro 2 requires active HID interface 0 alternate 0 with two endpoints"); return;
            }
            List<UsbEndpoint> ins = new ArrayList<>();
            int outputs = 0;
            for (int e = 0; e < selected.getEndpointCount(); e++) {
                UsbEndpoint endpoint = selected.getEndpoint(e);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_INT || endpoint.getMaxPacketSize() != 64) continue;
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) ins.add(endpoint);
                else { output = endpoint; outputs++; }
            }
            if (outputs != 1 || ins.size() != 1) {
                output = null;
                journal.event("unsupported", "reason", "requires exactly one 64-byte interrupt IN and one 64-byte interrupt OUT",
                    "eligibleInputs", ins.size(), "eligibleOutputs", outputs);
                return;
            }
            boolean success = connection.claimInterface(selected, false);
            journal.event("claim_interface", "force", false, "success", success);
            if (!success) {
                success = connection.claimInterface(selected, true);
                journal.event("claim_interface", "force", true, "success", success);
            }
            if (!success) { output = null; result = "claim_failed"; return; }
            claimed = selected;
            for (UsbEndpoint endpoint : ins) {
                Input input = new Input(endpoint);
                if (!input.request.initialize(connection, endpoint)) { input.request.close(); result = "input_initialize_failed"; return; }
                input.request.setClientData(input);
                pump.inputs.add(input);
            }
            readsReady = true;
            for (Input input : pump.inputs) readsReady &= pump.queue(input);
            journal.event("inputs_started", "allQueued", readsReady, "inputCount", pump.inputs.size(), "outputEndpoint", output.getAddress());
            if (!readsReady || stop) { result = "input_queue_failed_or_stopped"; return; }
            long deadline = SystemClock.elapsedRealtime() + durationSeconds * 1000L;
            pump.send(output, "firmware_query", Protocol.firmware());
            pump.until(Math.min(deadline, SystemClock.elapsedRealtime() + 400), true, true);
            if (!stop && SystemClock.elapsedRealtime() < deadline) {
                enableAttempted = true;
                pump.send(output, rawMode ? "raw_120hz_enable" : "pose_120hz_enable", rawMode ? Protocol.raw120() : Protocol.pose120());
                pump.until(deadline, true, true);
            }
            result = pump.validPose > 0 ? "validated_v2_pose" : pump.validRaw > 0 ? "validated_v2_raw" : pump.validFrames > 0 ? "v2_frames_without_validated_imu" : "no_validated_v2_frames";
        } catch (Exception error) {
            result = "error";
            journal.event("error", "message", error.toString());
        } finally {
            if (enableAttempted && claimed != null && output != null && readsReady) {
                try {
                    for (Input input : pump.inputs) if (!input.pending && input.bytes < BYTE_LIMIT) pump.queue(input);
                    pump.send(output, "stream_off_finally", Protocol.off());
                    pump.until(SystemClock.elapsedRealtime() + 200, false, true);
                } catch (Exception error) { journal.safeEvent("stream_off_error", "message", error.toString()); }
            }
            try {
                for (Input input : pump.inputs) if (input.pending) {
                    input.cancelRequested = true;
                    try { journal.safeEvent("input_cancel", "endpoint", input.endpoint.getAddress(), "success", input.request.cancel()); }
                    catch (Exception error) { journal.safeEvent("input_cancel_error", "message", error.toString()); }
                }
                for (Command command : pump.commands) if (command.pending) {
                    command.cancelRequested = true;
                    try { journal.safeEvent("command_cancel", "name", command.name, "success", command.request.cancel()); }
                    catch (Exception error) { journal.safeEvent("command_cancel_error", "message", error.toString()); }
                }
                try { pump.until(SystemClock.elapsedRealtime() + 100, false, false); } catch (Exception ignored) {}
                for (Input input : pump.inputs) {
                    journal.safeEvent("input_end", "endpoint", input.endpoint.getAddress(), "bytes", input.bytes, "packets", input.packets,
                        "zeroLengthCompletions", input.zeros, "pendingAtClose", input.pending);
                }
                if (claimed != null) {
                    try { journal.safeEvent("release_interface", "success", connection.releaseInterface(claimed)); }
                    catch (Exception error) { journal.safeEvent("release_interface_error", "message", error.toString()); }
                }
            } finally {
                try { connection.close(); }
                finally {
                    for (Input input : pump.inputs) {
                        try { input.request.close(); } catch (Exception error) { journal.safeEvent("input_close_error", "message", error.toString()); }
                    }
                    for (Command command : pump.commands) {
                        try { command.request.close(); } catch (Exception error) { journal.safeEvent("command_close_error", "message", error.toString()); }
                    }
                }
            }
            journal.safeEvent("outcome", "result", result, "validFrames", pump.validFrames, "invalidFrames", pump.invalidFrames,
                "validPose", pump.validPose, "validRaw", pump.validRaw, "invalidSamples", pump.invalidSamples,
                "firmwareReplies", pump.firmwareReplies, "successfulFirmwareReplies", pump.successfulFirmwareReplies, "controlAcks", pump.controlAcks,
                "nonzeroControlStatuses", pump.nonzeroControlStatuses, "streamEnableAttempted", enableAttempted,
                "poseEnableAttempted", !rawMode && enableAttempted, "rawEnableAttempted", rawMode && enableAttempted,
                "streamOffWriteCompleted", pump.offCompleted);
            show("Outcome: " + result + "; valid pose " + pump.validPose + ", raw " + pump.validRaw + ", v2 frames " + pump.validFrames);
        }
    }

    private final class Pump {
        final UsbDeviceConnection connection;
        final Journal journal;
        final List<Input> inputs = new ArrayList<>();
        final List<Command> commands = new ArrayList<>();
        int validFrames, invalidFrames, validPose, validRaw, invalidSamples, firmwareReplies, successfulFirmwareReplies, controlAcks, nonzeroControlStatuses;
        boolean offCompleted;
        Pump(UsbDeviceConnection connection, Journal journal) { this.connection = connection; this.journal = journal; }

        boolean queue(Input input) throws Exception {
            if (input.bytes >= BYTE_LIMIT) return false;
            input.buffer.clear();
            input.buffer.limit(Math.min(input.buffer.capacity(), BYTE_LIMIT - input.bytes));
            input.cancelRequested = false;
            input.queued = SystemClock.elapsedRealtimeNanos();
            input.pending = input.request.queue(input.buffer);
            if (!input.pending) journal.event("input_queue_failed", "endpoint", input.endpoint.getAddress());
            return input.pending;
        }

        void send(UsbEndpoint endpoint, String name, byte[] bytes) throws Exception {
            Command command = new Command(name, bytes);
            if (!command.request.initialize(connection, endpoint)) { command.request.close(); journal.event("command_initialize_failed", "name", name); return; }
            command.request.setClientData(command);
            commands.add(command);
            command.pending = command.request.queue(command.buffer);
            journal.event("command_queued", "name", name, "endpoint", endpoint.getAddress(), "length", bytes.length,
                "bytesBase64", encode(bytes), "queued", command.pending);
            long deadline = SystemClock.elapsedRealtime() + 400;
            while (command.pending && SystemClock.elapsedRealtime() < deadline) step(50, true);
            if (command.pending) {
                command.cancelRequested = true;
                journal.event("command_timeout", "name", name, "cancelled", command.request.cancel());
            }
        }

        void until(long deadline, boolean respectStop, boolean requeueInputs) throws Exception {
            while ((!respectStop || !stop) && SystemClock.elapsedRealtime() < deadline) {
                step(Math.min(50, Math.max(1, deadline - SystemClock.elapsedRealtime())), requeueInputs);
            }
        }

        void step(long timeout, boolean requeue) throws Exception {
            try {
                UsbRequest completed = connection.requestWait(timeout);
                if (completed == null) throw new IllegalStateException("requestWait returned null");
                Object data = completed.getClientData();
                if (data instanceof Command) {
                    Command command = (Command) data;
                    command.pending = false;
                    int length = command.buffer.position();
                    if (command.name.equals("stream_off_finally") && length == command.length) offCompleted = true;
                    journal.event("command_completion", "name", command.name, "actualLength", length, "expectedLength", command.length,
                        "afterCancel", command.cancelRequested);
                    return;
                }
                Input input = (Input) data;
                input.pending = false;
                int count = input.buffer.position();
                byte[] bytes = new byte[count];
                input.buffer.flip();
                input.buffer.get(bytes);
                input.bytes += count;
                if (count > 0) input.packets++; else input.zeros++;
                journal.event("input_transfer", "endpoint", input.endpoint.getAddress(), "queueElapsedRealtimeNanos", input.queued,
                    "length", count, "bytesBase64", encode(bytes), "afterCancel", input.cancelRequested);
                if (count > 0) decode(bytes, input.endpoint.getAddress());
                if (requeue && input.bytes < BYTE_LIMIT) queue(input);
            } catch (TimeoutException ignored) {}
        }

        void decode(byte[] bytes, int endpoint) throws Exception {
            Protocol.Frame frame;
            try { frame = Protocol.parse(bytes); }
            catch (IllegalArgumentException error) { invalidFrames++; journal.event("invalid_v2_frame", "endpoint", endpoint, "reason", error.getMessage()); return; }
            validFrames++;
            journal.event("v2_frame", "endpoint", endpoint, "messageId", frame.message, "payloadLength", frame.payload.length);
            if (frame.message == Protocol.POSE || frame.message == Protocol.RAW) {
                try {
                    Protocol.Sample sample = frame.message == Protocol.POSE ? Protocol.pose(frame) : Protocol.raw(frame);
                    if (frame.message == Protocol.POSE) validPose++; else validRaw++;
                    JSONArray values = new JSONArray();
                    for (float value : sample.values) values.put((double) value);
                    journal.event(frame.message == Protocol.POSE ? "valid_pose" : "valid_raw", "endpoint", endpoint,
                        "tick", sample.tick, "values", values, "norm", sample.norm,
                        "fields", frame.message == Protocol.POSE ? "qw,qx,qy,qz" : "gyroX,gyroY,gyroZ,accelX,accelY,accelZ");
                } catch (IllegalArgumentException error) { invalidSamples++; journal.event("invalid_sample", "endpoint", endpoint, "messageId", frame.message, "reason", error.getMessage()); }
            } else if (frame.message == Protocol.FIRMWARE + 0x2000 && frame.payload.length >= 1) {
                firmwareReplies++;
                String firmware = new String(frame.payload, 1, frame.payload.length - 1, StandardCharsets.US_ASCII).replace("\u0000", "");
                if (frame.payload[0] == 0 && !firmware.isEmpty() && firmware.matches("[ -~]+")) successfulFirmwareReplies++;
                journal.event("firmware_reply", "endpoint", endpoint, "status", frame.payload[0] & 255, "firmware", firmware);
            } else if (frame.message == Protocol.CONTROL + 0x2000 && frame.payload.length == 1) {
                controlAcks++;
                if (frame.payload[0] != 0) nonzeroControlStatuses++;
                journal.event("control_ack", "endpoint", endpoint, "status", frame.payload[0] & 255);
            }
        }
    }

    private static final class Input {
        final UsbEndpoint endpoint;
        final UsbRequest request = new UsbRequest();
        final ByteBuffer buffer;
        boolean pending, cancelRequested;
        int bytes, packets, zeros;
        long queued;
        Input(UsbEndpoint endpoint) { this.endpoint = endpoint; buffer = ByteBuffer.allocateDirect(Math.max(1, endpoint.getMaxPacketSize())); }
    }

    private static final class Command {
        final String name;
        final int length;
        final UsbRequest request = new UsbRequest();
        final ByteBuffer buffer;
        boolean pending, cancelRequested;
        Command(String name, byte[] bytes) { this.name = name; length = bytes.length; buffer = ByteBuffer.allocateDirect(length); buffer.put(bytes).flip(); }
    }

    private static String encode(byte[] bytes) { return bytes == null ? "" : Base64.encodeToString(bytes, Base64.NO_WRAP); }

    private static final class Journal implements AutoCloseable {
        final BufferedWriter writer;
        Journal(File file) throws Exception { writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)); }
        void event(String event, Object... fields) throws Exception {
            JSONObject json = new JSONObject();
            json.put("event", event);
            json.put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos());
            for (int i = 0; i < fields.length; i += 2) json.put((String) fields[i], fields[i + 1]);
            writer.write(json.toString());
            writer.newLine();
            writer.flush();
        }
        void safeEvent(String event, Object... fields) {
            try { event(event, fields); } catch (Exception error) { Log.e("VitureV2Probe", "Could not log " + event, error); }
        }
        @Override public void close() throws Exception { writer.close(); }
    }
}
