package com.droiddeck.launcher.input;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

public class FakeInputWriter {
    private static final String TAG = "FakeInputWriter";

    public static final short EV_SYN = 0x00;
    public static final short EV_KEY = 0x01;
    public static final short EV_ABS = 0x03;
    public static final short EV_MSC = 0x04;

    public static final short MSC_SCAN = 0x04;
    public static final short SYN_REPORT = 0x00;

    public static final short BTN_A = 0x130;
    public static final short BTN_B = 0x131;
    public static final short BTN_X = 0x133;
    public static final short BTN_Y = 0x134;
    public static final short BTN_TL = 0x136;
    public static final short BTN_TR = 0x137;
    public static final short BTN_SELECT = 0x13A;
    public static final short BTN_START = 0x13B;
    public static final short BTN_THUMBL = 0x13D;
    public static final short BTN_MODE = 0x13C;
    public static final short BTN_THUMBR = 0x13E;

    public static final short ABS_X = 0x00;
    public static final short ABS_Y = 0x01;
    public static final short ABS_RX = 0x03;
    public static final short ABS_RY = 0x04;
    public static final short ABS_GAS = 0x09;
    public static final short ABS_BRAKE = 0x0A;
    public static final short ABS_HAT0X = 0x10;
    public static final short ABS_HAT0Y = 0x11;

    private static final short[] BUTTON_MAP = {
            BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR,
            BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR,
            BTN_MODE
    };
    private static final int SNAPSHOT_IDX_MODE = 10;
    private static final int SNAPSHOT_IDX_QAM = 11;

    private static final int EVENT_SIZE = 24;
    static final int MAX_EVENTS_PER_UPDATE = 32;
    private static final int BUFFER_SIZE = EVENT_SIZE * MAX_EVENTS_PER_UPDATE;
    private static final int MAX_FAKE_INPUT_SLOTS = 4;

    private static final int RING_CAPACITY_EVENTS = 4096;
    private static final int RING_HEADER_SIZE = 64;
    private static final int RING_SIZE = RING_HEADER_SIZE + (RING_CAPACITY_EVENTS * EVENT_SIZE);
    private static final int RING_MAGIC = 0x46494252;
    private static final int RING_VERSION = 2;
    private static final int RING_MAGIC_OFFSET = 0;
    private static final int RING_VERSION_OFFSET = 4;
    private static final int RING_EVENT_SIZE_OFFSET = 8;
    private static final int RING_CAPACITY_OFFSET = 12;
    private static final int RING_WRITE_SEQ_OFFSET = 16;
    private static final int RING_GENERATION_OFFSET = 24;
    private static final int RING_SNAPSHOT_SEQ_OFFSET = 32;
    private static final int RING_SNAPSHOT_BUTTONS_OFFSET = 40;
    private static final int RING_SNAPSHOT_AXES_OFFSET = 44;
    private static final int IMU_OFFSET = RING_SIZE;
    private static final int IMU_MAGIC = 0x31554D49;
    private static final int IMU_MODE_OFFSET = IMU_OFFSET + 4;
    private static final int IMU_SEQ_OFFSET = IMU_OFFSET + 8;
    private static final int IMU_ACCEL_OFFSET = IMU_OFFSET + 16;
    private static final int IMU_GYRO_OFFSET = IMU_OFFSET + 22;
    private static final int DECK_PADS_OFFSET = IMU_OFFSET + 28;
    private static final int DECK_PRESSURE_OFFSET = IMU_OFFSET + 36;
    private static final int DECK_CONTROLS_OFFSET = IMU_OFFSET + 40;
    private static final int PAD_TYPE_OFFSET = IMU_OFFSET + 44;
    private static final int PAD_TYPE_SET = 0x80000000;
    private static final int LATENCY_OFFSET = IMU_OFFSET + 48;
    public static final int LATENCY_CONTROLLER = 1;
    public static final int LATENCY_TOUCH = 2;
    private static volatile int latency = 0;
    public static final int PAD_TYPE_DECK = 0;
    public static final int PAD_TYPE_STEAM = 1;
    public static final int PAD_TYPE_XBOX = 2;
    public static final int PAD_TYPE_DS4 = 3;
    public static final int PAD_TYPE_DUALSENSE = 4;
    private static volatile int padType = -1;
    private static volatile boolean tritonConnected = false;
    private static final int TRITON_OFFSET = RING_SIZE + 64;
    private static final int TRITON_MAGIC = 0x4e525454;
    private static final int TRITON_CONNECTED_OFFSET = TRITON_OFFSET + 4;
    private static final int TRITON_INPUT_SEQ_OFFSET = TRITON_OFFSET + 8;
    private static final int TRITON_REPORT_ID_OFFSET = TRITON_OFFSET + 16;
    private static final int TRITON_REPORT_LENGTH_OFFSET = TRITON_OFFSET + 20;
    private static final int TRITON_REPORT_OFFSET = TRITON_OFFSET + 24;
    private static final int TRITON_OUTPUT_SEQ_OFFSET = TRITON_OFFSET + 88;
    private static final int TRITON_OUTPUT_FEATURE_OFFSET = TRITON_OFFSET + 96;
    private static final int TRITON_OUTPUT_LENGTH_OFFSET = TRITON_OFFSET + 98;
    private static final int TRITON_OUTPUT_ACK_OFFSET = TRITON_OFFSET + 100;
    private static final int TRITON_OUTPUT_OFFSET = TRITON_OFFSET + 104;
    private static final int TRITON_GET_REQUEST_OFFSET = TRITON_OFFSET + 168;
    private static final int TRITON_GET_REPLY_OFFSET = TRITON_OFFSET + 176;
    private static final int TRITON_GET_REPORT_ID_OFFSET = TRITON_OFFSET + 184;
    private static final int TRITON_GET_LENGTH_OFFSET = TRITON_OFFSET + 188;
    private static final int TRITON_GET_OFFSET = TRITON_OFFSET + 192;
    private static final int TRITON_REPORT_MAX = 64;
    private static final int TRITON_BLOCK_SIZE = 256;
    private static final int RING_FILE_SIZE = TRITON_OFFSET + TRITON_BLOCK_SIZE;

    private static final Object RING_LOCK = new Object();
    private static final RingSlot[] RING_SLOTS = new RingSlot[MAX_FAKE_INPUT_SLOTS];

    static {
        System.loadLibrary("sdnative");
    }

    private static native void nativeStoreFence();

    public static void storeFence() {
        nativeStoreFence();
    }

    private final File eventFile;
    private final int slot;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

    private boolean isOpen = false;
    private volatile boolean destroyed = false;

    private final boolean[] prevButtonStates = new boolean[12];
    private boolean prevQam;
    private int prevThumbLX, prevThumbLY, prevThumbRX, prevThumbRY;
    private int prevTriggerL, prevTriggerR;
    private int prevHatX, prevHatY;
    private boolean hasChanges = false;
    private boolean pendingFullResend = false;
    private boolean forceResend = false;

    public FakeInputWriter(String fakeInputPath, int slot) {
        this.slot = slot;
        this.eventFile = new File(fakeInputPath, "event" + slot);
        this.buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    private static final class RingSlot {
        ByteBuffer data;
        File ringFile;
        FileChannel ringChannel;
        RandomAccessFile ringRaf;
        String exportPath;
        long generation;
        boolean active;
        boolean everActivated;
    }


    public static void prepareRingSlots(File fakeInputDir, int slotCount) {
        int boundedSlotCount = Math.max(0, Math.min(slotCount, MAX_FAKE_INPUT_SLOTS));
        synchronized (RING_LOCK) {
            for (int slot = 0; slot < boundedSlotCount; slot++) {
                ensureRingSlotLocked(slot, fakeInputDir);
            }
        }
    }

    public static String getRingEnv(File fakeInputDir) {
        synchronized (RING_LOCK) {
            prepareRingSlotsLocked(fakeInputDir, MAX_FAKE_INPUT_SLOTS);
            return buildRingEnvLocked();
        }
    }

    public static void releaseAllRingSlots() {
        synchronized (RING_LOCK) {
            for (int slot = 0; slot < RING_SLOTS.length; slot++) {
                releaseRingSlotLocked(slot);
            }
        }
    }

    private static String buildRingEnvLocked() {
        StringBuilder builder = new StringBuilder();
        for (int slot = 0; slot < RING_SLOTS.length; slot++) {
            RingSlot ringSlot = RING_SLOTS[slot];
            if (ringSlot == null || ringSlot.data == null || ringSlot.exportPath == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(slot).append('=').append(ringSlot.exportPath);
        }
        return builder.toString();
    }

    private static File getRingFile(File fakeInputDir, int slot) {
        return FakeInputRingPaths.ringFile(fakeInputDir, slot);
    }

    private static void initializeRingHeader(ByteBuffer data) {
        data.order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(RING_MAGIC_OFFSET, RING_MAGIC);
        data.putInt(RING_VERSION_OFFSET, RING_VERSION);
        data.putInt(RING_EVENT_SIZE_OFFSET, EVENT_SIZE);
        data.putInt(RING_CAPACITY_OFFSET, RING_CAPACITY_EVENTS);
        data.putLong(RING_WRITE_SEQ_OFFSET, 0L);
        data.putLong(RING_GENERATION_OFFSET, 0L);
        data.putLong(RING_SNAPSHOT_SEQ_OFFSET, 0L);
        data.putInt(RING_SNAPSHOT_BUTTONS_OFFSET, 0);
        for (int i = 0; i < 8; i++) {
            data.putShort(RING_SNAPSHOT_AXES_OFFSET + (i * 2), (short) 0);
        }
        data.putInt(IMU_OFFSET, 0);
        data.putInt(IMU_MODE_OFFSET, 0);
        data.putLong(IMU_SEQ_OFFSET, 0L);
        for (int i = 0; i < 12; i++) {
            data.putShort(IMU_ACCEL_OFFSET + (i * 2), (short) 0);
        }
        data.putInt(DECK_CONTROLS_OFFSET, 0);
        int type = padType;
        data.putInt(PAD_TYPE_OFFSET, type >= 0 ? PAD_TYPE_SET | type : 0);
        data.putInt(LATENCY_OFFSET, latency);
        data.putInt(TRITON_OFFSET, TRITON_MAGIC);
        data.putInt(TRITON_CONNECTED_OFFSET, tritonConnected ? 1 : 0);
        data.putLong(TRITON_INPUT_SEQ_OFFSET, 0L);
        data.putInt(TRITON_REPORT_ID_OFFSET, 0);
        data.putInt(TRITON_REPORT_LENGTH_OFFSET, 0);
        data.putLong(TRITON_OUTPUT_SEQ_OFFSET, 0L);
        data.putShort(TRITON_OUTPUT_FEATURE_OFFSET, (short) 0);
        data.putShort(TRITON_OUTPUT_LENGTH_OFFSET, (short) 0);
        data.putInt(TRITON_OUTPUT_ACK_OFFSET, 0);
        data.putLong(TRITON_GET_REQUEST_OFFSET, 0L);
        data.putLong(TRITON_GET_REPLY_OFFSET, 0L);
    }

    private static void releaseRingSlotLocked(int slot) {
        RingSlot ringSlot = RING_SLOTS[slot];
        if (ringSlot == null) {
            return;
        }
        synchronized (ringSlot) {
            ringSlot.data = null;
            if (ringSlot.ringChannel != null) {
                try {
                    ringSlot.ringChannel.close();
                } catch (IOException ignored) {
                }
                ringSlot.ringChannel = null;
            }
            if (ringSlot.ringRaf != null) {
                try {
                    ringSlot.ringRaf.close();
                } catch (IOException ignored) {
                }
                ringSlot.ringRaf = null;
            }
            if (ringSlot.ringFile != null && ringSlot.ringFile.exists()) {
                ringSlot.ringFile.delete();
            }
        }
        RING_SLOTS[slot] = null;
    }

    private static void prepareRingSlotsLocked(File fakeInputDir, int slotCount) {
        int boundedSlotCount = Math.max(0, Math.min(slotCount, MAX_FAKE_INPUT_SLOTS));
        for (int slot = 0; slot < boundedSlotCount; slot++) {
            ensureRingSlotLocked(slot, fakeInputDir);
        }
    }

    private static RingSlot ensureRingSlotLocked(int slot, File fakeInputDir) {
        if (slot < 0 || slot >= MAX_FAKE_INPUT_SLOTS || fakeInputDir == null) {
            return null;
        }

        File desiredRingFile = getRingFile(fakeInputDir, slot);
        if (desiredRingFile == null) {
            return null;
        }
        desiredRingFile = desiredRingFile.getAbsoluteFile();
        RingSlot existing = RING_SLOTS[slot];
        if (existing != null && existing.data != null) {
            if (existing.ringFile != null && existing.ringFile.equals(desiredRingFile)) {
                return existing;
            }
            releaseRingSlotLocked(slot);
        }

        RingSlot fileRingSlot = createFileRingSlotLocked(slot, desiredRingFile);
        if (fileRingSlot != null) {
            RING_SLOTS[slot] = fileRingSlot;
            return fileRingSlot;
        }
        return null;
    }

    private static RingSlot createFileRingSlotLocked(int slot, File ringFile) {
        File ringDir = ringFile.getParentFile();
        if (ringDir == null || (!ringDir.exists() && !ringDir.mkdirs())) {
            Log.e(TAG, "Failed to create fake input ring directory for slot " + slot);
            return null;
        }

        RandomAccessFile raf = null;
        FileChannel channel = null;
        try {
            raf = new RandomAccessFile(ringFile, "rw");
            raf.setLength(RING_FILE_SIZE);
            channel = raf.getChannel();
            ByteBuffer data = channel.map(FileChannel.MapMode.READ_WRITE, 0, RING_FILE_SIZE);
            initializeRingHeader(data);

            RingSlot ringSlot = new RingSlot();
            ringSlot.data = data;
            ringSlot.ringFile = ringFile.getAbsoluteFile();
            ringSlot.ringRaf = raf;
            ringSlot.ringChannel = channel;
            ringSlot.exportPath = FakeInputRingPaths.exportPath(ringFile);
            Log.i(TAG, "Created fake input file ring for slot " + slot + ": " + ringSlot.exportPath);
            return ringSlot;
        } catch (IOException e) {
            Log.e(TAG, "Failed to create fake input file ring for slot " + slot + ": " + e.getMessage());
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
            return null;
        }
    }

    private RingSlot ensureRingSlot() {
        synchronized (RING_LOCK) {
            return ensureRingSlotLocked(this.slot, this.eventFile.getParentFile());
        }
    }

    private boolean activateRingSlot() {
        RingSlot ringSlot = ensureRingSlot();
        if (ringSlot == null || ringSlot.data == null) {
            return false;
        }
        synchronized (ringSlot) {
            if (ringSlot.data == null) {
                return false;
            }
            if (!ringSlot.active) {
                if (ringSlot.everActivated) {
                    ringSlot.generation++;
                } else {
                    ringSlot.everActivated = true;
                }
                ringSlot.data.putLong(RING_WRITE_SEQ_OFFSET, 0L);
                clearSnapshotLocked(ringSlot.data);
                ringSlot.data.putLong(RING_GENERATION_OFFSET, ringSlot.generation);
                ringSlot.active = true;
                Log.d(TAG, "Activated fake input ring for slot " + this.slot
                        + " generation=" + ringSlot.generation);
            }
        }
        return true;
    }

    private void deactivateRingSlot() {
        RingSlot ringSlot;
        synchronized (RING_LOCK) {
            ringSlot = this.slot >= 0 && this.slot < RING_SLOTS.length ? RING_SLOTS[this.slot] : null;
        }
        if (ringSlot == null) {
            return;
        }
        synchronized (ringSlot) {
            if (ringSlot.active && ringSlot.data != null) {
                ringSlot.generation++;
                ringSlot.data.putLong(RING_WRITE_SEQ_OFFSET, 0L);
                clearSnapshotLocked(ringSlot.data);
                ringSlot.data.putLong(RING_GENERATION_OFFSET, ringSlot.generation);
                Log.i(TAG, "Deactivated fake input ring for slot " + this.slot
                        + " generation=" + ringSlot.generation);
            }
            ringSlot.active = false;
        }
    }

    private boolean flushBufferToRing() {
        RingSlot ringSlot = ensureRingSlot();
        if (ringSlot == null || ringSlot.data == null) {
            return false;
        }

        ByteBuffer source = this.buffer.duplicate();
        synchronized (ringSlot) {
            ByteBuffer ring = ringSlot.data;
            if (ring == null) {
                return false;
            }
            long writeSeq = ring.getLong(RING_WRITE_SEQ_OFFSET);
            int sourceLimit = source.limit();
            while (source.remaining() >= EVENT_SIZE) {
                int eventIndex = (int) (writeSeq % RING_CAPACITY_EVENTS);
                int targetOffset = RING_HEADER_SIZE + (eventIndex * EVENT_SIZE);
                source.limit(source.position() + EVENT_SIZE);
                ring.position(targetOffset);
                ring.put(source);
                source.limit(sourceLimit);
                writeSeq++;
            }
            writeSnapshotLocked(ring);
            nativeStoreFence();
            ring.putLong(RING_WRITE_SEQ_OFFSET, writeSeq);
        }
        return true;
    }

    private void writeSnapshotLocked(ByteBuffer ring) {
        int buttons = 0;
        for (int i = 0; i < BUTTON_MAP.length; i++) {
            if (this.prevButtonStates[i]) {
                buttons |= (1 << i);
            }
        }
        if (this.prevQam) {
            buttons |= (1 << SNAPSHOT_IDX_QAM);
        }
        long seq = ring.getLong(RING_SNAPSHOT_SEQ_OFFSET);
        ring.putLong(RING_SNAPSHOT_SEQ_OFFSET, seq + 1);
        nativeStoreFence();
        ring.putInt(RING_SNAPSHOT_BUTTONS_OFFSET, buttons);
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET, clampShort(this.prevThumbLX));
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 2, clampShort(this.prevThumbLY));
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 4, clampShort(this.prevThumbRX));
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 6, clampShort(this.prevThumbRY));
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 8, clampShort(this.prevTriggerR));
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 10, clampShort(this.prevTriggerL));
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 12, clampShort(this.prevHatX));
        ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 14, clampShort(this.prevHatY));
        nativeStoreFence();
        ring.putLong(RING_SNAPSHOT_SEQ_OFFSET, seq + 2);
    }

    public static void writeMotion(int slot, short[] accel, short[] gyro) {
        RingSlot ringSlot;
        synchronized (RING_LOCK) {
            ringSlot = slot >= 0 && slot < RING_SLOTS.length ? RING_SLOTS[slot] : null;
        }
        if (ringSlot == null) return;
        synchronized (ringSlot) {
            ByteBuffer ring = ringSlot.data;
            if (ring == null) return;
            long seq = ring.getLong(IMU_SEQ_OFFSET);
            ring.putLong(IMU_SEQ_OFFSET, seq + 1);
            nativeStoreFence();
            for (int i = 0; i < 3; i++) {
                ring.putShort(IMU_ACCEL_OFFSET + i * 2, accel[i]);
                ring.putShort(IMU_GYRO_OFFSET + i * 2, gyro[i]);
            }
            ring.putInt(IMU_OFFSET, IMU_MAGIC);
            nativeStoreFence();
            ring.putLong(IMU_SEQ_OFFSET, seq + 2);
        }
    }

    public static int readImuMode(int slot) {
        RingSlot ringSlot;
        synchronized (RING_LOCK) {
            ringSlot = slot >= 0 && slot < RING_SLOTS.length ? RING_SLOTS[slot] : null;
        }
        if (ringSlot == null) return -1;
        synchronized (ringSlot) {
            ByteBuffer ring = ringSlot.data;
            int value = ring == null ? 0 : ring.getInt(IMU_MODE_OFFSET);
            return value < 0 ? value & 0xffff : -1;
        }
    }

    public static void writeDeckControls(int slot, int controls, short[] pads, short[] pressure) {
        RingSlot ringSlot;
        synchronized (RING_LOCK) {
            ringSlot = slot >= 0 && slot < RING_SLOTS.length ? RING_SLOTS[slot] : null;
        }
        if (ringSlot == null) return;
        synchronized (ringSlot) {
            ByteBuffer ring = ringSlot.data;
            if (ring == null) return;
            long seq = ring.getLong(IMU_SEQ_OFFSET);
            ring.putLong(IMU_SEQ_OFFSET, seq + 1);
            nativeStoreFence();
            for (int i = 0; i < 4; i++) ring.putShort(DECK_PADS_OFFSET + i * 2, pads[i]);
            for (int i = 0; i < 2; i++) ring.putShort(DECK_PRESSURE_OFFSET + i * 2, pressure[i]);
            ring.putInt(DECK_CONTROLS_OFFSET, controls);
            ring.putInt(IMU_OFFSET, IMU_MAGIC);
            nativeStoreFence();
            ring.putLong(IMU_SEQ_OFFSET, seq + 2);
        }
    }

    public static boolean padHasQam() {
        int type = padType;
        return type < 0 || type == PAD_TYPE_DECK || type == PAD_TYPE_STEAM;
    }

    public static void setPadType(int type) {
        padType = type;
        RingSlot ringSlot;
        synchronized (RING_LOCK) {
            ringSlot = RING_SLOTS[0];
        }
        if (ringSlot == null) return;
        synchronized (ringSlot) {
            ByteBuffer ring = ringSlot.data;
            if (ring == null) return;
            nativeStoreFence();
            ring.putInt(PAD_TYPE_OFFSET, PAD_TYPE_SET | type);
        }
    }

    public static void setLowLatency(boolean enabled) {
        int flags = enabled ? LATENCY_CONTROLLER | LATENCY_TOUCH : 0;
        latency = flags;
        RingSlot ringSlot;
        synchronized (RING_LOCK) {
            ringSlot = RING_SLOTS[0];
        }
        if (ringSlot == null) return;
        synchronized (ringSlot) {
            ByteBuffer ring = ringSlot.data;
            if (ring == null) return;
            nativeStoreFence();
            ring.putInt(LATENCY_OFFSET, flags);
        }
    }

    public static void setTritonConnected(int slot, boolean connected) {
        tritonConnected = connected;
        RingSlot ringSlot = tritonSlot(slot);
        if (ringSlot == null) return;
        synchronized (ringSlot) {
            ByteBuffer ring = ringSlot.data;
            if (ring == null || ring.getInt(TRITON_OFFSET) != TRITON_MAGIC) return;
            nativeStoreFence();
            ring.putInt(TRITON_CONNECTED_OFFSET, connected ? 1 : 0);
        }
    }

    public static void writeTritonState(int slot, int reportId, byte[] report) {
        if (report == null || report.length == 0 || report.length > TRITON_REPORT_MAX) return;
        RingSlot ringSlot;
        synchronized (RING_LOCK) {
            ringSlot = slot >= 0 && slot < RING_SLOTS.length ? RING_SLOTS[slot] : null;
        }
        if (ringSlot == null) return;
        synchronized (ringSlot) {
            ByteBuffer ring = ringSlot.data;
            if (ring == null || ring.getInt(TRITON_OFFSET) != TRITON_MAGIC) return;
            long seq = ring.getLong(TRITON_INPUT_SEQ_OFFSET);
            if ((seq & 1) != 0) seq++;
            ring.putLong(TRITON_INPUT_SEQ_OFFSET, seq + 1);
            nativeStoreFence();
            ring.putInt(TRITON_REPORT_ID_OFFSET, reportId);
            ring.putInt(TRITON_REPORT_LENGTH_OFFSET, report.length);
            for (int i = 0; i < TRITON_REPORT_MAX; i++) {
                ring.put(TRITON_REPORT_OFFSET + i, i < report.length ? report[i] : 0);
            }
            nativeStoreFence();
            ring.putLong(TRITON_INPUT_SEQ_OFFSET, seq + 2);
        }
    }

    public static byte[] readTritonOutput(int slot, long lastSequence) {
        RingSlot ringSlot;
        synchronized (RING_LOCK) {
            ringSlot = slot >= 0 && slot < RING_SLOTS.length ? RING_SLOTS[slot] : null;
        }
        if (ringSlot == null) return null;
        synchronized (ringSlot) {
            ByteBuffer ring = ringSlot.data;
            if (ring == null || ring.getInt(TRITON_OFFSET) != TRITON_MAGIC) return null;
            nativeStoreFence();
            long seq = ring.getLong(TRITON_OUTPUT_SEQ_OFFSET);
            if ((seq & 1) != 0 || seq <= lastSequence) return null;
            int length = ring.getShort(TRITON_OUTPUT_LENGTH_OFFSET) & 0xffff;
            if (length <= 0 || length > TRITON_REPORT_MAX) return null;
            byte[] output = new byte[9 + length];
            ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).putLong(seq);
            output[8] = (byte) (ring.getShort(TRITON_OUTPUT_FEATURE_OFFSET) != 0 ? 1 : 0);
            for (int i = 0; i < length; i++) output[9 + i] = ring.get(TRITON_OUTPUT_OFFSET + i);
            nativeStoreFence();
            if (seq != ring.getLong(TRITON_OUTPUT_SEQ_OFFSET)) return null;
            // Acknowledge the exact report sequence taken so a concurrent timeout overwrite cannot lose its length.
            ring.putInt(TRITON_OUTPUT_ACK_OFFSET, (int) seq);
            nativeStoreFence();
            return output;
        }
    }

    public static long readTritonFeatureRequest(int slot) {
        RingSlot ringSlot = tritonSlot(slot);
        if (ringSlot == null) return 0L;
        synchronized (ringSlot) {
            ByteBuffer ring = ringSlot.data;
            if (ring == null || ring.getInt(TRITON_OFFSET) != TRITON_MAGIC) return 0L;
            nativeStoreFence();
            long request = ring.getLong(TRITON_GET_REQUEST_OFFSET);
            return request != ring.getLong(TRITON_GET_REPLY_OFFSET) ? request : 0L;
        }
    }

    public static void writeTritonFeatureReply(int slot, long request, byte[] report) {
        RingSlot ringSlot = tritonSlot(slot);
        if (ringSlot == null) return;
        synchronized (ringSlot) {
            ByteBuffer ring = ringSlot.data;
            if (ring == null || ring.getInt(TRITON_OFFSET) != TRITON_MAGIC) return;
            int length = report == null ? 0 : Math.min(report.length, TRITON_REPORT_MAX);
            for (int i = 0; i < TRITON_REPORT_MAX; i++) ring.put(TRITON_GET_OFFSET + i, i < length ? report[i] : 0);
            ring.putInt(TRITON_GET_LENGTH_OFFSET, length);
            nativeStoreFence();
            ring.putLong(TRITON_GET_REPLY_OFFSET, request);
        }
    }

    private static RingSlot tritonSlot(int slot) {
        synchronized (RING_LOCK) {
            return slot >= 0 && slot < RING_SLOTS.length ? RING_SLOTS[slot] : null;
        }
    }

    private static short clampShort(int value) {
        if (value > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (value < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) value;
    }

    private static void clearSnapshotLocked(ByteBuffer ring) {
        long seq = ring.getLong(RING_SNAPSHOT_SEQ_OFFSET);
        ring.putLong(RING_SNAPSHOT_SEQ_OFFSET, seq + 1);
        nativeStoreFence();
        ring.putInt(RING_SNAPSHOT_BUTTONS_OFFSET, 0);
        for (int i = 0; i < 8; i++) {
            ring.putShort(RING_SNAPSHOT_AXES_OFFSET + (i * 2), (short) 0);
        }
        nativeStoreFence();
        ring.putLong(RING_SNAPSHOT_SEQ_OFFSET, seq + 2);
    }

    private boolean flushBuffer() {
        return flushBufferToRing();
    }

    public synchronized boolean open() {
        if (destroyed)
            return false;
        if (isOpen)
            return true;

        try {
            eventFile.getParentFile().mkdirs();
            if (!eventFile.exists()) {
                eventFile.createNewFile();
            }
            if (!activateRingSlot()) {
                if (eventFile.exists()) {
                    eventFile.delete();
                }
                Log.e(TAG, "Failed to open fake input mmap ring: " + eventFile.getAbsolutePath());
                return false;
            }
            isOpen = true;
            Log.i(TAG, "Opened fake input: " + eventFile.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to open: " + e.getMessage());
            return false;
        }
    }

    public synchronized void close() {
        isOpen = false;
    }

    public synchronized void reset() {
        if (!isOpen && !open())
            return;

        buffer.clear();
        hasChanges = false;

        for (int i = 0; i < BUTTON_MAP.length; i++) {
            if (prevButtonStates[i]) {
                prevButtonStates[i] = false;
                writeEvent(EV_MSC, MSC_SCAN, BUTTON_MAP[i]);
                writeEvent(EV_KEY, BUTTON_MAP[i], 0);
            }
        }

        if (prevQam) {
            prevQam = false;
            hasChanges = true;
        }

        if (prevThumbLX != 0) {
            prevThumbLX = 0;
            writeEvent(EV_ABS, ABS_X, 0);
        }
        if (prevThumbLY != 0) {
            prevThumbLY = 0;
            writeEvent(EV_ABS, ABS_Y, 0);
        }
        if (prevThumbRX != 0) {
            prevThumbRX = 0;
            writeEvent(EV_ABS, ABS_RX, 0);
        }
        if (prevThumbRY != 0) {
            prevThumbRY = 0;
            writeEvent(EV_ABS, ABS_RY, 0);
        }
        if (prevTriggerL != 0) {
            prevTriggerL = 0;
            writeEvent(EV_ABS, ABS_BRAKE, 0);
        }
        if (prevTriggerR != 0) {
            prevTriggerR = 0;
            writeEvent(EV_ABS, ABS_GAS, 0);
        }
        if (prevHatX != 0) {
            prevHatX = 0;
            writeEvent(EV_ABS, ABS_HAT0X, 0);
        }
        if (prevHatY != 0) {
            prevHatY = 0;
            writeEvent(EV_ABS, ABS_HAT0Y, 0);
        }

        if (hasChanges) {
            writeEvent(EV_SYN, SYN_REPORT, 0);
            buffer.flip();
            if (!flushBuffer())
                Log.e(TAG, "Reset write error: fake input mmap ring unavailable");
        }
        Log.i(TAG, "Reset fake input to neutral state: " + eventFile.getAbsolutePath());
    }

    public synchronized void softRelease() {
        reset();
        close();
        Log.i(TAG, "Soft released fake input: " + eventFile.getAbsolutePath());
    }

    public synchronized void destroy() {
        destroyed = true;
        reset();
        close();
        deactivateRingSlot();
        if (eventFile != null && eventFile.exists()) {
            boolean deleted = eventFile.delete();
            Log.i(TAG, "Deleted fake input discovery node: " + eventFile.getAbsolutePath() + " (" + deleted + ")");
        }
    }

    private void writeEvent(short type, short code, int value) {
        long timeMs = System.currentTimeMillis();
        buffer.putLong(timeMs / 1000);
        buffer.putLong((timeMs % 1000) * 1000);
        buffer.putShort(type);
        buffer.putShort(code);
        buffer.putInt(value);
        hasChanges = true;
    }

    private void writeButton(int idx, boolean pressed) {
        if (idx < 0 || idx >= BUTTON_MAP.length)
            return;
        if (!forceResend && prevButtonStates[idx] == pressed)
            return;
        prevButtonStates[idx] = pressed;
        writeEvent(EV_MSC, MSC_SCAN, BUTTON_MAP[idx]);
        writeEvent(EV_KEY, BUTTON_MAP[idx], pressed ? 1 : 0);
    }

    public synchronized void writePad(PadState state) {
        if (!isOpen && !open())
            return;

        forceResend = pendingFullResend;
        pendingFullResend = false;
        if (forceResend) {
            Log.d(TAG, "Re-emitting full gamepad state after failed publish for slot " + slot);
        }

        buffer.clear();
        hasChanges = false;

        for (int i = 0; i < 10; i++) {
            writeButton(i, state.isDown(i));
        }
        writeButton(SNAPSHOT_IDX_MODE,
                state.isDown(PadState.GUIDE));
        boolean qam = state.isDown(PadState.QAM);
        if (forceResend || qam != prevQam) {
            prevQam = qam;
            hasChanges = true;
        }

        int lx = (int) (state.leftX * 32767);
        int ly = (int) (state.leftY * 32767);
        int rx = (int) (state.rightX * 32767);
        int ry = (int) (state.rightY * 32767);

        if (forceResend || lx != prevThumbLX) {
            prevThumbLX = lx;
            writeEvent(EV_ABS, ABS_X, lx);
        }
        if (forceResend || ly != prevThumbLY) {
            prevThumbLY = ly;
            writeEvent(EV_ABS, ABS_Y, ly);
        }
        if (forceResend || rx != prevThumbRX) {
            prevThumbRX = rx;
            writeEvent(EV_ABS, ABS_RX, rx);
        }
        if (forceResend || ry != prevThumbRY) {
            prevThumbRY = ry;
            writeEvent(EV_ABS, ABS_RY, ry);
        }

        int tl = (int) (state.leftTrigger * 255);
        int tr = (int) (state.rightTrigger * 255);
        if (forceResend || tl != prevTriggerL) {
            prevTriggerL = tl;
            writeEvent(EV_ABS, ABS_BRAKE, tl);
        }
        if (forceResend || tr != prevTriggerR) {
            prevTriggerR = tr;
            writeEvent(EV_ABS, ABS_GAS, tr);
        }

        int hatX = state.hatX();
        int hatY = state.hatY();
        if (forceResend || hatX != prevHatX) {
            prevHatX = hatX;
            writeEvent(EV_ABS, ABS_HAT0X, hatX);
        }
        if (forceResend || hatY != prevHatY) {
            prevHatY = hatY;
            writeEvent(EV_ABS, ABS_HAT0Y, hatY);
        }

        if (hasChanges) {
            writeEvent(EV_SYN, SYN_REPORT, 0);
            buffer.flip();
            if (!flushBuffer()) {
                Log.e(TAG, "Gamepad write error: fake input mmap ring unavailable");
                pendingFullResend = true;
            }
        }
        forceResend = false;
    }

    public boolean isOpen() {
        return isOpen;
    }
}
