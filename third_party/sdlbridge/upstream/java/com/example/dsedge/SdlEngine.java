package com.example.dsedge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.libsdl.app.HIDDeviceManager;
import org.libsdl.app.SDL;
import org.libsdl.app.SDLActivity;

/**
 * Where SDL3 runs.
 *
 * Normally headless, inside the bridge's background service -- no screen has to open for the bridge to work. It
 * does what SDL's own SDLActivity does at start-up, minus the window: load the libraries, set up SDL's Java side,
 * take USB/HID access (HIDDeviceManager), then run SDL_main (main.c) on its own thread. The bridge only uses SDL's
 * gamepad / joystick / hidapi parts, which need no window.
 *
 * SDL's Java side does expect "the app's screen" as its context (SDL.getContext()): SDL3 reads the app's files
 * and assets through it while it starts (gamepad mappings), and crashes the whole app without one. Headless there
 * is no screen, so AppContext stands in for it -- an Activity object that is never started or shown, whose calls
 * all go to the app. The mappings file is also handed to SDL3 directly, so it never needs the assets for it.
 *
 * Self-protecting: a marker is saved before every background start and cleared when SDL3's loop reports its first
 * status. If SDL3 stays silent for 10 s (the process is then restarted), or the app died during the attempt, this
 * build uses the fallback instead: SDL3 runs inside the bridge's own screen, started when you open SDL3Bridge
 * (nothing opens by itself). Every step, and the previous run's crash report if there was one, is sent to
 * DroidDeck's session log ("sdl3bridge: engine: ...").
 */
final class SdlEngine {
    private static final String TAG = "SDL3Bridge";
    private static final long CONFIRM_MS = 10_000;
    private static volatile boolean started, confirmed, fallback, failed;
    private static volatile HIDDeviceManager hid;
    private static long hostStartMs;
    /** Why SDL3 is not running (shown in the status, the notification and DroidDeck's tab), or null. */
    static volatile String error;
    /** How SDL3 runs now, for the status / DroidDeck's tab. */
    static volatile String mode = "not started";
    private static String fallbackWhy = "";

    private SdlEngine() {}

    static boolean isRunning() { return started && (fallback ? SdlHostActivity.running : true); }

    private static void log(String line) {
        Log.i(TAG, line);
        NativeBridge.onBridgeLog("engine: " + line);
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(Host.prefs(c, "engine"), Context.MODE_PRIVATE); }

    /** Which build this is: the version code, or -- built into SDL3DroidDeck, whose version code stays the same from
     *  build to build -- when it was installed, so a new build tries the background again. */
    private static long version(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            if (Host.embedded(c)) return pi.lastUpdateTime;
            return android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    /** Stands in for "the app's screen" in SDL's Java side (see the class comment). Never started or shown. */
    static final class AppContext extends Activity {
        AppContext(Context base) { attachBaseContext(base); }
    }

    private static Activity standIn(Context app) {
        try {
            Context base;
            // its own copy of the app's context, so nothing set on the stand-in reaches the app's real one
            try { base = app.createConfigurationContext(app.getResources().getConfiguration()); }
            catch (RuntimeException e) { base = app; }
            return new AppContext(base);
        } catch (Throwable e) {
            log("could not make SDL3's stand-in context (" + e + "); starting without one");
            return null;
        }
    }

    /** SDL3's gamepad mappings file (empty unless you add mappings), as an absolute path; null if unavailable. */
    static String mappingFile(Context c) {
        try {
            java.io.File f = new java.io.File(c.getFilesDir(), "gamepad_map.txt");
            if (!f.exists() && !f.createNewFile()) return null;
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /** Starts SDL once per process (safe to call again, from any thread). */
    static void start(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {   // the stand-in context must be made on the main thread
            new Handler(Looper.getMainLooper()).post(() -> start(context));
            return;
        }
        startOnMain(context);
    }

    private static synchronized void startOnMain(Context context) {
        final Context app = context.getApplicationContext();
        NativeBridge.appContext = app;
        Settings.init(app);
        if (started) {
            if (fallback && !SdlHostActivity.running && context instanceof Activity) startHost((Activity) context);
            return;
        }
        started = true;
        SharedPreferences p = prefs(app);
        long ver = version(app);
        String last = p.getString("state", "");
        boolean sameBuild = p.getLong("version", -1) == ver;
        if (sameBuild && (last.equals("starting") || last.startsWith("failed"))) {
            boolean crashed = last.equals("starting");
            fallbackWhy = crashed ? "the app stopped while SDL3 was starting in the background" : last.substring(last.indexOf(':') + 1).trim();
            if (crashed) p.edit().putString("state", "failed: " + fallbackWhy).commit();
            fallback = true;
            mode = "the SDL3Bridge screen (fallback)";
            error = "Open SDL3Bridge once to start SDL3 (it could not run in the background on this phone: " + fallbackWhy + ")";
            log("SDL3 can't run in the background service with this build (" + fallbackWhy + "). Fallback: SDL3 runs "
                + "inside the SDL3Bridge screen -- open SDL3Bridge once (nothing opens by itself). A new build tries "
                + "the background again.");
            if (crashed) forwardPreviousLog();
            if (context instanceof Activity) startHost((Activity) context);
            return;
        }
        p.edit().putString("state", "starting").putLong("version", ver).commit();   // commit: must survive a crash
        log("starting SDL3 in the background service (build " + ver + ")");
        try {
            System.loadLibrary("SDL3");
            System.loadLibrary("main");
            log("libraries loaded");
            SDL.setupJNI();
            SDL.initialize();          // (this also clears SDL's context)
            Activity ctx = standIn(app);
            if (ctx != null) SDL.setContext(ctx);
            log("SDL3's Java side ready" + (ctx != null ? " (stand-in context)" : " (no context)"));
            hid = HIDDeviceManager.acquire(app);
            hid.setFrozen(!Settings.driverEnabled);
            log("USB/HID access ready");
        } catch (Throwable e) {
            fail(app, "SDL3 could not start: " + e);
            restartForFallback("SDL3 could not start in the background service");
            return;
        }
        final String main = app.getApplicationInfo().nativeLibraryDir + "/libmain.so";
        final String map = mappingFile(app);
        final String[] args = map != null ? new String[] { map } : new String[0];
        Thread t = new Thread(() -> {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY); } catch (RuntimeException ignored) { }
            try {
                SDLActivity.nativeInitMainThread();
                int rc = SDLActivity.nativeRunMain(main, "SDL_main", args);
                SDLActivity.nativeCleanupMainThread();
                fail(app, "SDL3's loop ended (" + rc + ")");
            } catch (Throwable e) {
                fail(app, "SDL3 stopped: " + e);
            }
            restartForFallback("SDL3's loop is not running");
        }, "SDLThread");
        error = null;
        mode = "background service";
        t.start();
        log("SDL3 thread started; waiting for its first status");
        // Settings the native loop reads (driver, hook-up, rate, lights), as the bridge screen used to apply.
        NativeBridge.applyDriver();
        NativeBridge.applyRate();
        NativeBridge.applyOutput();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (confirmed || fallback || failed) return;   // working, or already failed (and restarting)
            fail(app, "SDL3 did not answer within " + (CONFIRM_MS / 1000) + " s in the background service");
            restartForFallback("SDL3 did not answer");
        }, CONFIRM_MS);
    }

    /** SDL3's loop reported a status: it runs. */
    static void onNativeStatus() {
        if (!started) return;
        if (fallback) {
            if (error != null && SdlHostActivity.running) {
                error = null;
                log("SDL3 is running in the SDL3Bridge screen (fallback); you can leave the screen, it keeps running");
            }
            return;
        }
        if (confirmed) return;
        confirmed = true;
        Context c = NativeBridge.appContext;
        if (c != null) prefs(c).edit().putString("state", "ok").apply();
        log("SDL3 is running in the background service");
    }

    /**
     * The fallback screen was closed for good (its task was swiped away): SDL3 can't be restarted cleanly in the
     * same process, so the process restarts (DroidDeck's connection brings the service back) and the bridge waits
     * to be opened again.
     */
    static void onHostGone() {
        if (!fallback) return;
        error = "Open SDL3Bridge to start SDL3 again (fallback: " + fallbackWhy + ")";
        log("the SDL3Bridge screen that ran SDL3 was closed; restarting SDL3Bridge -- open it again to start SDL3");
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> android.os.Process.killProcess(android.os.Process.myPid()), 1500);
    }

    /** The process restarts (DroidDeck's connection brings the service back) and the next start uses the fallback. */
    private static void restartForFallback(String why) {
        log(why + "; restarting SDL3Bridge in 2 s so the fallback takes over");
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> android.os.Process.killProcess(android.os.Process.myPid()), 2000);   // lets the log reach DroidDeck
    }

    private static void fail(Context app, String why) {
        failed = true;
        error = why;
        Log.e(TAG, why);
        NativeBridge.onBridgeLog("engine: " + why);
        prefs(app).edit().putString("state", "failed: " + why).commit();
    }

    /** Fallback only, and only from the bridge's own screen while you look at it: SDL3's host screen on top of it. */
    private static void startHost(Activity from) {
        long now = SystemClock.uptimeMillis();
        if (hostStartMs != 0 && now - hostStartMs < 5000) return;   // never in a loop
        hostStartMs = now;
        try {
            from.startActivity(new Intent(from, SdlHostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
            log("starting SDL3 in the SDL3Bridge screen (fallback)");
        } catch (RuntimeException e) {
            log("could not start SDL3 in the SDL3Bridge screen: " + e);
        }
    }

    /** The previous run's own log lines about SDL / the bridge / a crash, into DroidDeck's session log. */
    private static void forwardPreviousLog() {
        new Thread(() -> {
            java.util.ArrayDeque<String> keep = new java.util.ArrayDeque<>();
            try {
                java.lang.Process p = Runtime.getRuntime().exec(new String[] { "logcat", "-d", "-v", "time", "-t", "3000" });
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("AndroidRuntime") || line.contains(" F/") || line.contains("/SDL")
                                || line.contains("SDL3Bridge") || line.contains("libc") || line.contains("DEBUG")
                                || line.contains("/hidapi") || line.contains("JNI DETECTED")) {
                            keep.addLast(line);
                            while (keep.size() > 80) keep.removeFirst();
                        }
                    }
                }
            } catch (Exception e) {
                keep.addLast("(could not read the log: " + e + ")");
            }
            NativeBridge.onBridgeLog("engine: ---- the bridge's own log (last " + keep.size() + " relevant lines) ----");
            for (String l : keep) NativeBridge.onBridgeLog("engine: | " + l);
        }, "engine-log").start();
    }

    /** Driver off = release the USB interface so Android can use the controller; on = claim it again. */
    static void setHidFrozen(boolean frozen) {
        HIDDeviceManager h = hid;
        if (h != null) h.setFrozen(frozen);
        else SdlHostActivity.setHidFrozen(frozen);
    }
}
