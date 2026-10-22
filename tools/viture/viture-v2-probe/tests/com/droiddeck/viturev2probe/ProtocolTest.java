package com.droiddeck.viturev2probe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class ProtocolTest {
    private static int checks;
    public static void main(String[] args) {
        equal(Protocol.firmware(), hex("10 00 03 30 00 00 00 00"));
        equal(Protocol.pose120(), hex("10 00 01 03 02 00 03 00 01 02"));
        equal(Protocol.raw120(), hex("10 00 01 03 02 00 04 00 02 02"));
        equal(Protocol.off(), hex("10 00 01 03 02 00 00 00 00 00"));
        byte[] payload = hex("26 01 00 00 59 d6 21 00 dd e3 2b 3e 52 5d 7c 3f dd 45 9a 3b e8 27 52 3a");
        byte[] poseFrame = Protocol.frame(Protocol.POSE, payload);
        Protocol.Sample sample = Protocol.pose(Protocol.parse(poseFrame));
        check(sample.tick == 0x0021d659L && Math.abs(sample.norm - 1) < 0.001);
        check(Protocol.pose(Protocol.parse(Arrays.copyOf(poseFrame, 64))).tick == sample.tick);
        reject(() -> Protocol.parse(Arrays.copyOf(poseFrame, 31)));
        byte[] corrupt = poseFrame.clone();
        corrupt[8] ^= 1;
        reject(() -> Protocol.parse(corrupt));
        reject(() -> Protocol.parse(hex("ff fe 00 00 00 00 00 00")));
        reject(() -> Protocol.pose(Protocol.parse(Protocol.frame(Protocol.POSE, new byte[24]))));
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putFloat(8, Float.NaN);
        reject(() -> Protocol.pose(Protocol.parse(Protocol.frame(Protocol.POSE, payload))));
        byte[] raw = new byte[56];
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(4, -1).putFloat(10, 0.1f).putFloat(14, 0.2f).putFloat(18, 0.3f).putFloat(30, 1f);
        Protocol.Sample rawSample = Protocol.raw(Protocol.parse(Protocol.frame(Protocol.RAW, raw)));
        check(rawSample.tick == 0xffffffffL && rawSample.values[0] == 0.1f && rawSample.norm == 1);
        buffer.putFloat(30, Float.POSITIVE_INFINITY);
        reject(() -> Protocol.raw(Protocol.parse(Protocol.frame(Protocol.RAW, raw))));
        System.out.println("Protocol tests passed: " + checks);
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); checks++; }
    private static void equal(byte[] actual, byte[] expected) { check(Arrays.equals(actual, expected)); }
    private static void reject(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected rejection");
    }
    private static byte[] hex(String text) {
        String[] parts = text.split(" ");
        byte[] result = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) result[i] = (byte) Integer.parseInt(parts[i], 16);
        return result;
    }
}
