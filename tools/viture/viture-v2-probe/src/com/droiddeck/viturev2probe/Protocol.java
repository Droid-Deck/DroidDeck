package com.droiddeck.viturev2probe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class Protocol {
    public static final int FIRMWARE = 0x3003;
    public static final int CONTROL = 0x0301;
    public static final int POSE = 0x7308;
    public static final int RAW = 0x7309;

    private Protocol() {}

    public static byte[] firmware() { return frame(FIRMWARE, new byte[0]); }
    public static byte[] pose120() { return frame(CONTROL, new byte[] {1, 2}); }
    public static byte[] raw120() { return frame(CONTROL, new byte[] {2, 2}); }
    public static byte[] off() { return frame(CONTROL, new byte[] {0, 0}); }

    static byte[] frame(int message, byte[] payload) {
        if (payload.length > 56) throw new IllegalArgumentException("payload exceeds upstream 64-byte frame");
        ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte value : payload) sum = (sum + (value & 255)) & 65535;
        buffer.putShort((short) 0x10).putShort((short) message).putShort((short) payload.length).putShort((short) sum).put(payload);
        return buffer.array();
    }

    public static Frame parse(byte[] data) {
        if (data.length < 8) throw new IllegalArgumentException("short_header");
        if (u16(data, 0) != 0x10) throw new IllegalArgumentException("preamble");
        int length = u16(data, 4);
        if (length > 56 || length + 8 > data.length) throw new IllegalArgumentException("payload_length");
        int sum = 0;
        for (int i = 8; i < 8 + length; i++) sum = (sum + (data[i] & 255)) & 65535;
        if (sum != u16(data, 6)) throw new IllegalArgumentException("checksum");
        return new Frame(u16(data, 2), Arrays.copyOfRange(data, 8, 8 + length));
    }

    public static Sample pose(Frame frame) {
        if (frame.message != POSE || frame.payload.length != 24) throw new IllegalArgumentException("pose_length_or_id");
        float[] values = floats(frame.payload, 8, 4);
        double squared = 0;
        for (float value : values) squared += (double) value * value;
        double norm = Math.sqrt(squared);
        if (Math.abs(norm - 1.0) > 0.05) throw new IllegalArgumentException("quaternion_norm");
        return new Sample(tick(frame.payload), values, norm);
    }

    public static Sample raw(Frame frame) {
        if (frame.message != RAW || frame.payload.length != 56) throw new IllegalArgumentException("raw_length_or_id");
        float[] values = floats(frame.payload, 10, 6);
        double accelNorm = Math.sqrt((double) values[3] * values[3] + (double) values[4] * values[4] + (double) values[5] * values[5]);
        return new Sample(tick(frame.payload), values, accelNorm);
    }

    private static float[] floats(byte[] data, int offset, int count) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            values[i] = buffer.getFloat(offset + i * 4);
            if (Float.isNaN(values[i]) || Float.isInfinite(values[i])) throw new IllegalArgumentException("non_finite");
        }
        return values;
    }

    private static long tick(byte[] payload) {
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt(4) & 0xffffffffL;
    }

    static int u16(byte[] data, int offset) { return (data[offset] & 255) | ((data[offset + 1] & 255) << 8); }

    public static final class Frame {
        public final int message;
        public final byte[] payload;
        Frame(int message, byte[] payload) { this.message = message; this.payload = payload; }
    }

    public static final class Sample {
        public final long tick;
        public final float[] values;
        public final double norm;
        Sample(long tick, float[] values, double norm) { this.tick = tick; this.values = values; this.norm = norm; }
    }
}
