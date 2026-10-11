package com.droiddeck.launcher.stores.amazon;

import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Amazon's `manifest.proto`: a big-endian header length, a ManifestHeader protobuf naming the
 * compression, then an LZMA (or XZ) body holding the Manifest protobuf - packages of files, each
 * with a Windows path, a size and a SHA-256. Read with a tiny protobuf walker; the XZ library the
 * app already carries does the decompression.
 */
public final class AmazonManifest {

    public static final class ManifestFile {
        public final String path;
        public final long size;
        /** 0 = SHA-256. */
        public final int hashAlgorithm;
        public final byte[] hashBytes;

        ManifestFile(String path, long size, int hashAlgorithm, byte[] hashBytes) {
            this.path = path; this.size = size; this.hashAlgorithm = hashAlgorithm; this.hashBytes = hashBytes;
        }

        public String unixPath() { return path.replace('\\', '/'); }

        public String hashHex() {
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        }
    }

    public static final class ParsedManifest {
        public final List<ManifestFile> allFiles;
        public final long totalInstallSize;

        ParsedManifest(List<ManifestFile> files) {
            this.allFiles = files;
            long total = 0;
            for (ManifestFile f : files) total += f.size;
            this.totalInstallSize = total;
        }
    }

    private AmazonManifest() {}

    public static ParsedManifest parse(byte[] data) throws IOException {
        if (data == null || data.length < 4) throw new IOException("Manifest too short");
        int headerSize = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        if (headerSize < 0 || 4 + headerSize > data.length) throw new IOException("Invalid header size " + headerSize);
        byte[] body = Arrays.copyOfRange(data, 4 + headerSize, data.length);
        return parseManifest(decompress(body));
    }

    private static byte[] decompress(byte[] body) throws IOException {
        if (body.length < 2) return body;
        boolean isXz = (body[0] & 0xFF) == 0xFD && (body[1] & 0xFF) == 0x37;
        try (InputStream in = isXz ? new XZInputStream(new ByteArrayInputStream(body), -1) : new LZMAInputStream(new ByteArrayInputStream(body), -1)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static ParsedManifest parseManifest(byte[] bytes) {
        List<ManifestFile> files = new ArrayList<>();
        ProtoReader r = new ProtoReader(bytes);
        while (r.hasMore()) {
            int tag = (int) r.readVarint();
            if ((tag >>> 3) == 1 && (tag & 7) == 2) parsePackage(r.readBytes(), files); else r.skip(tag & 7);
        }
        return new ParsedManifest(files);
    }

    private static void parsePackage(byte[] bytes, List<ManifestFile> out) {
        ProtoReader r = new ProtoReader(bytes);
        while (r.hasMore()) {
            int tag = (int) r.readVarint();
            int field = tag >>> 3, wire = tag & 7;
            if (field == 2 && wire == 2) { ManifestFile f = parseFile(r.readBytes()); if (f != null) out.add(f); }
            else r.skip(wire);
        }
    }

    private static ManifestFile parseFile(byte[] bytes) {
        String path = "";
        long size = 0L;
        int hashAlgo = 0;
        byte[] hashValue = new byte[0];
        ProtoReader r = new ProtoReader(bytes);
        while (r.hasMore()) {
            int tag = (int) r.readVarint();
            int field = tag >>> 3, wire = tag & 7;
            if (field == 1 && wire == 2) path = r.readString();
            else if (field == 3 && wire == 0) size = r.readVarint();
            else if (field == 5 && wire == 2) {
                ProtoReader hr = new ProtoReader(r.readBytes());
                while (hr.hasMore()) {
                    int ht = (int) hr.readVarint();
                    int hf = ht >>> 3, hw = ht & 7;
                    if (hf == 1 && hw == 0) hashAlgo = (int) hr.readVarint();
                    else if (hf == 2 && hw == 2) hashValue = hr.readBytes();
                    else hr.skip(hw);
                }
            } else r.skip(wire);
        }
        if (path.isEmpty()) return null;
        return new ManifestFile(path, size, hashAlgo, hashValue);
    }

    private static final class ProtoReader {
        private final byte[] buf;
        private int pos;
        ProtoReader(byte[] buf) { this.buf = buf; }
        boolean hasMore() { return pos < buf.length; }
        long readVarint() {
            long result = 0;
            int shift = 0;
            while (pos < buf.length) {
                int b = buf[pos++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return result;
        }
        byte[] readBytes() {
            int len = (int) readVarint();
            if (len < 0 || pos + len > buf.length) return new byte[0];
            byte[] out = Arrays.copyOfRange(buf, pos, pos + len);
            pos += len;
            return out;
        }
        String readString() { return new String(readBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        void skip(int wireType) {
            switch (wireType) {
                case 0: readVarint(); break;
                case 1: pos += 8; break;
                case 2: readBytes(); break;
                case 5: pos += 4; break;
                default: pos = buf.length; break;
            }
        }
    }
}
