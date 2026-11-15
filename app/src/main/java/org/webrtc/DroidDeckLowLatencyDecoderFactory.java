package org.webrtc;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;

public class DroidDeckLowLatencyDecoderFactory implements VideoDecoderFactory {
    public interface FrameCounter { void onFrameShown(); }

    private final HardwareVideoDecoderFactory hardware;
    private final EglBase.Context sharedContext;
    private final boolean lowLatency;
    private volatile boolean zeroCopy;
    private volatile Surface zeroCopySurface;
    private volatile boolean renderNext;
    private final FrameCounter counter;
    private volatile Wrapper live;
    private ImageReader standIn;
    private HandlerThread standInThread;
    private volatile int width, height;
    private final Object decodeLock = new Object();
    private final HashMap<Long, Long> queuedAt = new HashMap<>();
    private double decodeSumMs;
    private int decodeCount;

    public DroidDeckLowLatencyDecoderFactory(EglBase.Context sharedContext, boolean lowLatency, Surface zeroCopySurface, FrameCounter counter) {
        this.hardware = new HardwareVideoDecoderFactory(sharedContext);
        this.sharedContext = sharedContext;
        this.lowLatency = lowLatency;
        this.zeroCopy = zeroCopySurface != null;
        this.zeroCopySurface = zeroCopySurface;
        this.counter = counter;
    }

    public synchronized void setZeroCopySurface(Surface surface) {
        if (!zeroCopy) return;
        moveTo(surface != null ? surface : standInSurface(), surface != null ? "the screen" : "a stand-in");
    }

    public synchronized boolean useScreen(Surface screen) {
        if (screen != null) {
            Wrapper w = live;
            if (w != null && !moveTo(screen, "the screen")) return false;
            zeroCopySurface = screen;
            zeroCopy = true;
            return true;
        }
        if (!zeroCopy) return true;
        Wrapper w = live;
        if (w != null && w.texture != null && !moveTo(w.texture, "WebRTC's texture")) return false;
        zeroCopy = false;
        renderNext = true;
        return true;
    }

    private boolean moveTo(Surface target, String what) {
        Wrapper w = live;
        if (w == null || target == null) { if (zeroCopy) zeroCopySurface = target; return true; }
        try {
            w.raw.setOutputSurface(target);
            if (zeroCopy) zeroCopySurface = target;
            Logging.d("DroidDeckDecoder", "decoder output moved to " + what);
            return true;
        } catch (RuntimeException e) {
            Logging.w("DroidDeckDecoder", "decoder output change to " + what + " failed: " + e);
            return false;
        }
    }

    private Surface standInSurface() {
        if (standIn == null) {
            standInThread = new HandlerThread("bl-decoder-standin");
            standInThread.start();
            standIn = ImageReader.newInstance(Math.max(width, 16), Math.max(height, 16), ImageFormat.PRIVATE, 3);
            standIn.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) image.close();
            }, new Handler(standInThread.getLooper()));
        }
        return standIn.getSurface();
    }

    public String videoSize() {
        return width > 0 ? width + "x" + height : null;
    }

    public double takeDecodeMs() {
        synchronized (decodeLock) {
            double avg = decodeCount > 0 ? decodeSumMs / decodeCount : -1;
            decodeSumMs = 0; decodeCount = 0;
            return avg;
        }
    }

    public synchronized void release() {
        if (standIn != null) { standIn.close(); standIn = null; }
        if (standInThread != null) { standInThread.quitSafely(); standInThread = null; }
    }

    @Override
    public VideoCodecInfo[] getSupportedCodecs() {
        return hardware.getSupportedCodecs();
    }

    @Override
    public VideoDecoder createDecoder(VideoCodecInfo info) {
        VideoCodecMimeType type;
        try { type = VideoCodecMimeType.valueOf(info.getName()); } catch (IllegalArgumentException e) { return hardware.createDecoder(info); }
        MediaCodecInfo codec = findHardwareDecoder(type);
        if (codec == null) return hardware.createDecoder(info);
        Integer color = MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS, codec.getCapabilitiesForType(type.mimeType()));
        if (color == null) return hardware.createDecoder(info);
        return new AndroidVideoDecoder(new WrapperFactory(), codec.getName(), type, color, sharedContext);
    }

    private static MediaCodecInfo findHardwareDecoder(VideoCodecMimeType type) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            if (MediaCodecUtils.codecSupportsType(info, type) && MediaCodecUtils.isHardwareAccelerated(info)) return info;
        }
        return null;
    }

    private class WrapperFactory implements MediaCodecWrapperFactory {
        @Override
        public MediaCodecWrapper createByCodecName(String name) throws IOException {
            return new Wrapper(MediaCodec.createByCodecName(name));
        }
    }

    private class Wrapper implements MediaCodecWrapper {
        final MediaCodec raw;
        Surface texture;
        Wrapper(MediaCodec raw) { this.raw = raw; }

        @Override
        public void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
            width = format.getInteger(MediaFormat.KEY_WIDTH);
            height = format.getInteger(MediaFormat.KEY_HEIGHT);
            texture = surface;
            live = this;
            Surface target = surface;
            if (zeroCopy) { synchronized (DroidDeckLowLatencyDecoderFactory.this) { target = zeroCopySurface != null ? zeroCopySurface : standInSurface(); } }
            if (lowLatency) {
                MediaFormat fast = MediaFormat.createVideoFormat(format.getString(MediaFormat.KEY_MIME), width, height);
                copyInt(format, fast, MediaFormat.KEY_COLOR_FORMAT);
                if (Build.VERSION.SDK_INT >= 30) fast.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                fast.setInteger(MediaFormat.KEY_PRIORITY, 0);
                fast.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
                fast.setInteger("vendor.qti-ext-dec-picture-order.enable", 1);
                try {
                    raw.configure(fast, target, crypto, flags);
                    Logging.d("DroidDeckDecoder", "low-latency decoder configured" + (zeroCopy ? " (zero-copy)" : ""));
                    return;
                } catch (RuntimeException e) {
                    Logging.w("DroidDeckDecoder", "decoder refused low-latency keys: " + e);
                }
            }
            raw.configure(format, target, crypto, flags);
        }

        private void copyInt(MediaFormat from, MediaFormat to, String key) {
            if (from.containsKey(key)) to.setInteger(key, from.getInteger(key));
        }

        @Override public void releaseOutputBuffer(int index, boolean render) {
            if (zeroCopy) { raw.releaseOutputBuffer(index, true); if (counter != null) counter.onFrameShown(); }
            else if (renderNext) { renderNext = false; raw.releaseOutputBuffer(index, true); }
            else raw.releaseOutputBuffer(index, render);
        }

        @Override public void queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags) {
            synchronized (decodeLock) {
                if (queuedAt.size() > 64) queuedAt.clear();
                queuedAt.put(presentationTimeUs, SystemClock.elapsedRealtimeNanos());
            }
            raw.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
        }

        @Override public int dequeueOutputBuffer(MediaCodec.BufferInfo info, long timeoutUs) {
            int index = raw.dequeueOutputBuffer(info, timeoutUs);
            if (index >= 0) synchronized (decodeLock) {
                Long at = queuedAt.remove(info.presentationTimeUs);
                if (at != null) { decodeSumMs += (SystemClock.elapsedRealtimeNanos() - at) / 1e6; decodeCount++; }
            }
            return index;
        }

        @Override public MediaFormat getOutputFormat() {
            MediaFormat f = raw.getOutputFormat();
            if (f.containsKey(MediaFormat.KEY_WIDTH) && f.containsKey(MediaFormat.KEY_HEIGHT)) {
                width = f.containsKey("crop-right") ? f.getInteger("crop-right") - f.getInteger("crop-left") + 1 : f.getInteger(MediaFormat.KEY_WIDTH);
                height = f.containsKey("crop-bottom") ? f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 : f.getInteger(MediaFormat.KEY_HEIGHT);
            }
            return f;
        }

        @Override public void start() { raw.start(); }
        @Override public void flush() { synchronized (decodeLock) { queuedAt.clear(); } raw.flush(); }
        @Override public void stop() { raw.stop(); }
        @Override public void release() { if (live == this) live = null; raw.release(); }
        @Override public int dequeueInputBuffer(long timeoutUs) { return raw.dequeueInputBuffer(timeoutUs); }
        @Override public MediaFormat getInputFormat() { return raw.getInputFormat(); }
        @Override public MediaFormat getOutputFormat(int index) { return raw.getOutputFormat(index); }
        @Override public ByteBuffer getInputBuffer(int index) { return raw.getInputBuffer(index); }
        @Override public ByteBuffer getOutputBuffer(int index) { return raw.getOutputBuffer(index); }
        @Override public Surface createInputSurface() { return raw.createInputSurface(); }
        @Override public void setParameters(Bundle params) { raw.setParameters(params); }
        @Override public MediaCodecInfo getCodecInfo() { return raw.getCodecInfo(); }
    }
}
