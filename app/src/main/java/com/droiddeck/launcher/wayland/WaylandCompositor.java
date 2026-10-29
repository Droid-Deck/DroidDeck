package com.droiddeck.launcher.wayland;

import android.view.Surface;

public final class WaylandCompositor {
    static {
        System.loadLibrary("droiddeckwayland");
    }

    private WaylandCompositor() {}

    private static volatile Runnable firstFrameListener;

    public static void setFirstFrameListener(Runnable r) { firstFrameListener = r; }

    public static synchronized void clearFirstFrameListener(Runnable r) {
        if (firstFrameListener == r) firstFrameListener = null;
    }

    @SuppressWarnings("unused")
    static void onFirstFramePresented() {
        Runnable r = firstFrameListener;
        if (r != null) r.run();
    }

    public interface GameListener {
        void onGameSurface(String window, String gpuName);
        void onGameFrame();
        default void onGameProgram(int pid, String program) {}
    }

    private static volatile GameListener gameListener;

    public static void setGameListener(GameListener l) { gameListener = l; }

    public static synchronized void clearGameListener(GameListener l) {
        if (gameListener == l) gameListener = null;
    }

    @SuppressWarnings("unused")
    static void onGameSurface(String window, String gpuName) {
        GameListener l = gameListener;
        if (l != null) l.onGameSurface(window, gpuName);
    }

    private static volatile long lastFrameNanos;
    private static volatile long frameIntervalNanos;
    private static volatile long gameFrames; // written by the compositor thread only

    /** Frames the game has presented since the app started; the rate is the difference over time. */
    public static long gameFrameCount() { return gameFrames; }

    public static long recentFrameIntervalMs() {
        long last = lastFrameNanos;
        if (last == 0) return -1;
        return Math.max(frameIntervalNanos, System.nanoTime() - last) / 1_000_000;
    }

    @SuppressWarnings("unused")
    static void onGameFrame() {
        long now = System.nanoTime();
        long previous = lastFrameNanos;
        lastFrameNanos = now;
        gameFrames++;
        if (previous != 0) {
            long interval = now - previous;
            long smoothed = frameIntervalNanos;
            frameIntervalNanos = smoothed == 0 ? interval : (smoothed * 3 + interval) / 4;
        }
        GameListener l = gameListener;
        if (l != null) l.onGameFrame();
    }

    @SuppressWarnings("unused")
    static void onGameProgram(int pid, String program) {
        GameListener l = gameListener;
        if (l != null) l.onGameProgram(pid, program != null ? program : "");
    }

    public interface PointerLockListener {
        void onPointerLock(boolean locked, int x, int y);
    }

    private static volatile PointerLockListener pointerLockListener;

    public static void setPointerLockListener(PointerLockListener l) { pointerLockListener = l; }

    public static void clearPointerLockListener(PointerLockListener l) {
        if (pointerLockListener == l) pointerLockListener = null;
    }

    @SuppressWarnings("unused")
    static void onPointerLock(boolean locked, int x, int y) {
        PointerLockListener l = pointerLockListener;
        if (l != null) l.onPointerLock(locked, x, y);
    }

    public interface ClipboardListener {
        void onGuestClipboardText(String text);
    }

    private static volatile ClipboardListener clipboardListener;

    public static void setClipboardListener(ClipboardListener l) { clipboardListener = l; }

    public static boolean isClipboardListener(ClipboardListener l) { return clipboardListener == l; }

    public static void clearClipboardListener(ClipboardListener l) {
        if (clipboardListener == l) clipboardListener = null;
    }

    @SuppressWarnings("unused")
    static void onClipboardText(byte[] utf8) {
        ClipboardListener l = clipboardListener;
        if (l != null && utf8 != null) l.onGuestClipboardText(new String(utf8, java.nio.charset.StandardCharsets.UTF_8));
    }

    public interface TextInputListener {
        void onTextInput(boolean enabled, String program, int x, int y, int w, int h);
    }

    private static volatile TextInputListener textInputListener;

    public static void setTextInputListener(TextInputListener l) { textInputListener = l; }

    @SuppressWarnings("unused")
    static void onTextInput(boolean enabled, String program, int x, int y, int w, int h) {
        TextInputListener l = textInputListener;
        if (l != null) l.onTextInput(enabled, program, x, y, w, h);
    }

    private static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void setClipboardText(String text) { nativeSetClipboardText(utf8(text)); }

    public static void textInputCommit(String text) { if (text != null && !text.isEmpty()) nativeTextInputCommit(utf8(text)); }

    public static void textInputPreedit(String text, int cursor) { nativeTextInputPreedit(utf8(text), cursor, cursor); }

    public static void textInputDelete(int before, int after) { nativeTextInputDelete(before, after); }

    private static native void nativeSetClipboardText(byte[] utf8);
    private static native void nativeTextInputCommit(byte[] utf8);
    private static native void nativeTextInputPreedit(byte[] utf8, int cursorBegin, int cursorEnd);
    private static native void nativeTextInputDelete(int before, int after);

    public static native void nativeStart(String xdgRuntimeDir);

    public static native void nativeStartWithSurface(Surface surface, String xdgRuntimeDir,
                                                     String driverPath, String libraryName,
                                                     String nativeLibDir);

    public static native void nativeSetSurface(Surface surface);
    public static native boolean nativeWaitSurface(int timeoutMs);

    public static native void nativeSendPointer(int action, int x, int y);

    public static native void nativeSendTouch(int action, int pointerId, int x, int y);

    public static native int nativeCursorSnapshot(int[] out);

    public static native int nativeCursorSerial();

    public static final int CURSOR_BUF_INTS = 6 + 256 * 256;

    public static native void nativeSendKey(int evdev, int state);

    public static native void nativeVsync(long frameTimeNanos);

    public static native int nativeCompositorTid();

    public static native void nativeSetHideShell(boolean hide);

    public static native void nativeSetOutputRefreshRate(float hz);

    public static native void nativeSetZeroCopy(boolean on);

    public static native int nativeZeroCopyFrames();

    public static native int nativeZeroCopyLastFrameAgeMs();

    public static native void nativeSetUbwc(boolean on);

    public static native void nativeSetNoRenderNode(boolean on);

    public static native void nativeSetLayerFrameRate(float hz);

    private static volatile java.io.File sessionLogFile;

    public static void setSessionLogFile(java.io.File file) {
        sessionLogFile = file;
    }

    public static java.io.File currentLogFile() {
        return sessionLogFile;
    }

    public static native void nativeLogDisplay(String message);

    public static native void nativeLogPerf(String message);

    public static native void nativeSetOutputSize(int width, int height);

    public static native boolean nativeGamePictureRect(float[] out);

    public static native void nativeResizeOutput(int width, int height);

    public static native void nativeResetFirstFrame();


    public static final int HDR_MODE_OFF = 0, HDR_MODE_ON = 1, HDR_MODE_FORCE = 2;

    public static native void nativeSetHdrRequest(int mode, String source, boolean dxvkHdr, boolean zeroCopyForced);

    public static native void nativeSetHdrDisplay(int displayId, String name, String formats, boolean hdr10,
                                                  float maxLuminance, float maxAverageLuminance, float minLuminance,
                                                  boolean hdrSdrRatioAvailable, float hdrSdrRatio, int apiLevel);

    public static native void nativeHdrSdrRatioSample(float ratio, boolean listener);

    public static native int nativeHdrLastFrameAgeMs();

    public static native int nativeHdrGateState();

    public static native void nativeHdrSessionEnd();

    public static native boolean nativeHdrOnScreen();

    public static native void nativeLogColor(String message);

    public static native void nativeLog(String area, String message);

    public static native void nativeSetHdrSdrWhite(float nits);

    public static native void nativeSetHdrOutput(boolean on);

    public static native boolean nativeHdrOutput();

    public static native boolean nativeHdrToneMappedOnScreen();

    public static native int nativeHdrState();

    public static native void nativeHdrEnvSample(int thermalStatus, float thermalHeadroom, int brightness,
                                                 int brightnessMode);

    public static native void nativeSetHdrHighestRatio(float ratio);

    public static native float nativeHdrScreenHeadroom();

    public static native String nativeHdrScreenHeadroomWhy();

    public static native void nativeHdrNoteHeadroomRequest(float ratio);

    public static native void nativeSetScaleMode(int fullscreenMode, int screenAlignment);


    public static native void nativeSetUpscaler(int mode);

    public static native void nativeSetUpscaleSharpness(int sharpness);

    public static native void nativeSetCas(boolean enabled, int sharpness);

    public static native void nativeSetHdr(boolean enabled);

    public static native void nativeSetDeband(boolean enabled, int strength);

    public static native void nativeSetScreenEffects(float brightness, float contrast, float gamma, float saturation,
                                                     boolean fxaa, boolean toon, boolean crt, boolean ntsc);

    public static native void nativeSetLookName(String name);

    public static native void nativeSetCardboard(boolean enabled, int source, float ipdM, float screenDistM,
                                                 float screenWidthM, float k1, float k2, float fovDeg,
                                                 float lensInset, float chroma, boolean mono, float displayHz);

    public static native void nativeSetCardboardPose(float[] rotation, boolean live);
    public static native void nativeSetCardboardSpatialPose(float[] rotation, float[] position, boolean live);
    public static native boolean nativeSetSpatialFile(String path);
    public static native void nativePublishSpatial(java.nio.ByteBuffer snapshot);

    public static native void nativeSetCardboardPoseFile(String path);

    public static native void nativeSetFpsLimit(int fps);

    public static native void nativeSetRenderOnCommit(boolean on);

    public static native void nativeSetGpuTurbo(boolean on);

    public static native int nativeRaisePriority(int tid, int nice);

    public static native void nativeSendSceneInput(int type, int a, int b);

    public static final int FG_ENGINE_LSFG = 0, FG_ENGINE_DDFG = 1;

    public static native void nativeSetFrameGenEngine(int kind);

    public static native void nativeSetFrameGenArmed(boolean armed, int multiplier, int targetFps);

    public static native void nativeSetLsfgCachePath(String path);

    public static native void nativeSetFrameGenTuning(float flowScale, float refreshHz);

    public static native void nativeSetDdFgTuning(int model, int perfPreset);

    public static native int nativeFrameGenProblem();
    public static native String nativeFrameGenCapsReason();

    public static native float[] nativeFrameGenStats();

    public static native long nativeHudFrameTimes(float[] out);

    /** Stream mode: the scene is the Xbox Cloud Gaming native player's picture, handed over frame by frame. */
    public static native void nativeStreamMode(boolean on);

    public static native void nativeStreamFrame(android.hardware.HardwareBuffer buffer);

    public static native int nativeStreamFrames();

    public static void sendPointerDelta(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        nativeSendSceneInput(6, dx * 256, dy * 256);
    }

    public static void sendPointerDelta(float dx, float dy) {
        int x = Math.round(dx * 256f), y = Math.round(dy * 256f);
        if (x == 0 && y == 0) return;
        nativeSendSceneInput(6, x, y);
    }
}
