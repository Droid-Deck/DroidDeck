package com.example.dsedge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Streams controller frames (from the SDL3 loop) to bound clients such as DroidDeck, and passes their
 * rumble / lightbar requests back. Same wire protocol as the first bridge, plus decoupled touchpad data.
 */
public class BridgeService extends Service implements NativeBridge.Listener {
    static final int MSG_REGISTER = 1;
    static final int MSG_UNREGISTER = 2;
    static final int MSG_OUTPUT = 3;
    static final int MSG_FRAME = 10;
    static final int MSG_STATUS = 11;
    static final int MSG_PAD_GONE = 12;
    // Steam Controller (2026) raw passthrough. Client -> bridge:
    static final int MSG_TRITON_OUTPUT = 4;    // "data": report (id first), "feature": feature write?
    static final int MSG_TRITON_GET = 5;       // "request": long, "id": feature report id
    static final int MSG_TRITON_RUMBLE = 6;    // "l", "r": 0..65535
    // Bridge -> client:
    static final int MSG_TRITON_STATE = 13;    // "id": report id, "data": payload
    static final int MSG_TRITON_CONN = 14;     // "on": boolean, "what": description
    static final int MSG_TRITON_FEATURE = 15;  // "request": long, "data": reply (absent = no answer)
    // Steam Native Hook-up. Client -> bridge:
    static final int MSG_HOOKUP_OUT = 7;       // "slot", "kind" (0 output report, 1 feature write), "data"
    static final int MSG_HOOKUP_GET = 8;       // "slot", "request", "id", "input", "length"
    // Bridge -> client:
    static final int MSG_HOOKUP_ADD = 20;      // HookupIdentity bundle
    static final int MSG_HOOKUP_INPUT = 21;    // "slot", "data"
    static final int MSG_HOOKUP_REMOVE = 22;   // "slot"
    static final int MSG_HOOKUP_REPLY = 23;    // "slot", "request", "data" (absent = no answer)
    static final int MSG_BRIDGE_LOG = 30;      // "text": one or more log lines (newline separated)
    static final int MSG_HOOKUP_SYNC = 9;      // client watchdog: resend what is handed over (re-registers if needed)
    static final int MSG_HOOKUP_STATE = 24;    // "slots": int[] of live hook-up slots; "fast_ino": mapped shared file
    static final int MSG_HOOKUP_SHM = 25;      // <- DroidDeck: "fd": its shared hook-up file (fast path)
    // DroidDeck's SDL3Bridge settings tab (no REGISTER needed: it gets no frames, only replies)
    static final int MSG_SETTINGS_GET = 40;    // <- replyTo: send MSG_SETTINGS
    static final int MSG_SETTINGS_SET = 41;    // <- "key" [+ "p" player, "i" index] + "int" / "bool"; replyTo: send MSG_SETTINGS
    static final int MSG_SETTINGS = 42;        // -> the whole settings state + controller status (see settingsSnapshot)

    private static final float RAD_TO_DEG = 57.29578f;
    private static final float G = 9.80665f;
    private static final float PAD_W = 1920f, PAD_H = 1080f;

    private final CopyOnWriteArrayList<Messenger> clients = new CopyOnWriteArrayList<>();
    static volatile int clientCount;   // how many clients (DroidDeck) are bound + registered

    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_REGISTER:
                    if (m.replyTo != null) {
                        if (!clients.contains(m.replyTo)) clients.add(m.replyTo);   // re-register = resend state, no duplicate
                        clientCount = clients.size(); sendStatus(m.replyTo, NativeBridge.lastStatus);
                        if (NativeBridge.tritonConnected) sendTritonConn(m.replyTo, true, NativeBridge.tritonWhat);
                        String backlog;
                        synchronized (NativeBridge.bridgeLog) { backlog = String.join("\n", NativeBridge.bridgeLog); }
                        if (!backlog.isEmpty()) {
                            Bundle lb = new Bundle();
                            lb.putString("text", "(earlier) " + backlog.replace("\n", "\n(earlier) "));
                            sendOne(m.replyTo, MSG_BRIDGE_LOG, lb);
                        }
                        for (Bundle id : NativeBridge.hookupIdentity) if (id != null) sendOne(m.replyTo, MSG_HOOKUP_ADD, id);
                    }
                    break;
                case MSG_UNREGISTER:
                    if (m.replyTo != null) { clients.remove(m.replyTo); clientCount = clients.size(); }
                    if (clients.isEmpty()) NativeBridge.setSteamAway(false);   // DroidDeck's session ended
                    break;
                case MSG_OUTPUT:
                    mergeOutput(m.getData());
                    break;
                case MSG_TRITON_OUTPUT: {
                    byte[] data = m.getData().getByteArray("data");
                    if (data != null) NativeBridge.tritonOutput(data, m.getData().getBoolean("feature"));
                    break;
                }
                case MSG_TRITON_GET:
                    NativeBridge.tritonGetFeature(m.getData().getLong("request"), m.getData().getInt("id", 1));
                    break;
                case MSG_HOOKUP_OUT: {
                    byte[] data = m.getData().getByteArray("data");
                    if (data != null) NativeBridge.hookupOutput(m.getData().getInt("slot"), m.getData().getInt("kind"), data);
                    break;
                }
                case MSG_HOOKUP_SYNC:
                    // DroidDeck's desktop: is the Steam client running there? (Absent = yes: the Steam session.)
                    NativeBridge.setSteamAway(m.getData() != null && m.getData().getBoolean("steamAway", false));
                    handler.removeCallbacks(steamAwayLapse);
                    if (NativeBridge.hookupWaitsForSteam()) handler.postDelayed(steamAwayLapse, 10100);
                    if (m.replyTo != null) {
                        // A client the bridge forgot (the bridge was restarted) is registered again.
                        if (!clients.contains(m.replyTo)) {
                            clients.add(m.replyTo); clientCount = clients.size();
                            sendStatus(m.replyTo, NativeBridge.lastStatus);
                        }
                        if (NativeBridge.tritonConnected) sendTritonConn(m.replyTo, true, NativeBridge.tritonWhat);
                        int[] live = new int[NativeBridge.MAX_PADS];
                        int n = 0;
                        for (int i = 0; i < NativeBridge.MAX_PADS; i++) {
                            Bundle id = NativeBridge.hookupIdentity[i];
                            if (id == null) continue;
                            sendOne(m.replyTo, MSG_HOOKUP_ADD, id);
                            live[n++] = i;
                        }
                        NativeBridge.retryUndescribed();
                        Bundle st = new Bundle();
                        st.putIntArray("slots", java.util.Arrays.copyOf(live, n));
                        st.putLong("fast_ino", NativeBridge.hookupShmInode());
                        sendOne(m.replyTo, MSG_HOOKUP_STATE, st);
                    }
                    ensureBridgeLoop();
                    break;
                case MSG_HOOKUP_SHM: {
                    Bundle d = m.getData();
                    if (d != null) d.setClassLoader(android.os.ParcelFileDescriptor.class.getClassLoader());
                    @SuppressWarnings("deprecation")
                    android.os.ParcelFileDescriptor pfd = d == null ? null : d.getParcelable("fd");
                    NativeBridge.hookupMapShm(pfd);
                    break;
                }
                case MSG_SETTINGS_SET:
                    applyRemote(m.getData());
                    // fall through: answer with the new state
                case MSG_SETTINGS_GET:
                    if (m.replyTo != null) sendOne(m.replyTo, MSG_SETTINGS, settingsSnapshot());
                    if (m.what == MSG_SETTINGS_GET) ensureBridgeLoop();   // the tab shows live controllers
                    break;
                case MSG_HOOKUP_GET:
                    NativeBridge.hookupGet(m.getData().getInt("slot"), m.getData().getLong("request"), m.getData().getInt("id"),
                        m.getData().getBoolean("input"), m.getData().getInt("length", 64));
                    break;
                case MSG_TRITON_RUMBLE:
                    NativeBridge.tritonRumble(m.getData().getInt("l"), m.getData().getInt("r"));
                    break;
            }
        }
    };
    private final Messenger messenger = new Messenger(handler);
    /** DroidDeck stopped saying that Steam is away (it went away itself): hook-up goes on again. */
    private final Runnable steamAwayLapse = NativeBridge::recheckHookup;

    // ---- floating bubble ----
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private int bubbleSize;
    private View removeZone;
    private boolean longPressFired;
    private Runnable bubbleLongPress;
    private volatile boolean connectedState;

    static volatile BridgeService instance;

    @Override public void onCreate() {
        NativeBridge.appContext = getApplicationContext();
        NativeBridge.clients = clients::size;
        instance = this;
        super.onCreate();
        Settings.init(this);
        NativeBridge.listeners.add(this);
        // SDL3 runs here, in the background: nothing has to open when DroidDeck (or a plugged-in controller)
        // starts the bridge. The notification is the bridge's icon in the status bar.
        SdlEngine.start(this);
        // The status bar icon. Started by DroidDeck's connection it is a plain ongoing notification (that
        // connection keeps the bridge alive); started from the bridge screen or a plugged-in controller,
        // onStartCommand turns it into the foreground service's notification.
        postNotification(false);
    }

    // ---------- status bar notification ----------
    private static final int NOTE_ID = 1;
    private String noteText = "";
    private long noteMs;

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("bridge", "Controller bridge", NotificationManager.IMPORTANCE_LOW));
        android.app.PendingIntent open = android.app.PendingIntent.getActivity(this, 0,
            new Intent(this, BridgeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        String text = SdlEngine.error != null ? SdlEngine.error : NativeBridge.lastStatus;
        return new Notification.Builder(this, "bridge")
            .setContentTitle(!Settings.isHookup() ? "SDL3Bridge"
                : NativeBridge.hookupWaitsForSteam() ? "SDL3Bridge · Hook-up waits for Steam (DroidDeck desktop)"
                : "SDL3Bridge · Steam Native Hook-up")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setColor(0xFF4FC3F7)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .build();
    }

    /** foreground: as the foreground service's notification; else a plain ongoing notification. */
    private boolean postNotification(boolean foreground) {
        try {
            Notification n = buildNotification();
            if (foreground) {
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                else startForeground(NOTE_ID, n);
            } else {
                getSystemService(NotificationManager.class).notify(NOTE_ID, n);
            }
            return true;
        } catch (RuntimeException e) {
            android.util.Log.w("SDL3Bridge", "notification: " + e);
            return false;
        }
    }

    /** Status changed: refresh the notification text (at most every 2 s). */
    private void refreshNotification(String status) {
        if (status == null || status.equals(noteText)) return;
        long now = SystemClock.uptimeMillis();
        if (now - noteMs < 2000) {
            main.removeCallbacks(noteRefresh);
            main.postDelayed(noteRefresh, 2000 - (now - noteMs));
            return;
        }
        noteText = status;
        noteMs = now;
        try { getSystemService(NotificationManager.class).notify(NOTE_ID, buildNotification()); } catch (RuntimeException ignored) { }
    }
    private final Runnable noteRefresh = () -> refreshNotification(NativeBridge.lastStatus);

    @Override public IBinder onBind(Intent i) { return messenger.getBinder(); }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        SdlEngine.start(this);
        if (i != null && "BUBBLE".equals(i.getAction())) {
            if (i.getBooleanExtra("on", false)) showBubble(); else hideBubble();
        } else if (Settings.bubble) {
            showBubble();
        }
        if (!postNotification(true)) postNotification(false);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        NativeBridge.listeners.remove(this);
        hideBubble();
        super.onDestroy();
    }

    // ---------- floating bubble (chat-head) ----------
    //   tap           -> toggle the SDL3 forced driver (green = on, red = off)
    //   double tap    -> toggle Steam Native Hook-up (blue = on: Steam gets the real controllers)
    //   hold 3s       -> open the bridge app
    //   drag to the bottom remove zone -> dismiss the bubble

    @SuppressWarnings("ClickableViewAccessibility")
    private void showBubble() {
        if (bubble != null) return;
        if (!android.provider.Settings.canDrawOverlays(this)) return; // no permission yet
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        bubbleSize = dp(52);
        TextView b = new TextView(this);
        b.setText(Settings.isHookup() ? "HOOK" : "SDL3");
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(12f);
        b.setWidth(bubbleSize);
        b.setHeight(bubbleSize);
        b.setBackground(bubbleBg(Settings.driverEnabled));
        b.setElevation(dp(4));

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Settings.bubbleX;
        lp.y = Settings.bubbleY;

        final int slop = dp(10);
        b.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = bubbleLp.x; startY = bubbleLp.y; moved = false; longPressFired = false;
                        bubbleLongPress = () -> { longPressFired = true; openBridgeApp(); };
                        main.postDelayed(bubbleLongPress, 3000);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int nx = startX + (int) (e.getRawX() - downX);
                        int ny = startY + (int) (e.getRawY() - downY);
                        if (!moved && Math.abs(nx - startX) + Math.abs(ny - startY) > slop) {
                            moved = true;
                            if (bubbleLongPress != null) main.removeCallbacks(bubbleLongPress);
                            showRemoveZone();
                        }
                        bubbleLp.x = nx; bubbleLp.y = ny;
                        try { wm.updateViewLayout(bubble, bubbleLp); } catch (RuntimeException ignored) { }
                        if (moved) highlightRemoveZone(overRemoveZone());
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP:
                        if (bubbleLongPress != null) main.removeCallbacks(bubbleLongPress);
                        boolean removed = false;
                        if (moved && overRemoveZone()) { removeBubbleByUser(); removed = true; }
                        hideRemoveZone();
                        if (!removed) {
                            Settings.bubbleX = bubbleLp.x; Settings.bubbleY = bubbleLp.y; Settings.save();
                            if (!moved && !longPressFired) onBubbleTap();
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(b, lp);
            bubble = b;
            bubbleLp = lp;
        } catch (RuntimeException e) {
            bubble = null;
        }
    }

    private void openBridgeApp() {
        try {
            startActivity(new Intent(BridgeService.this, BridgeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    // A single tap waits this long for a second tap before it acts, so a double tap doesn't also flip the driver.
    private static final long DOUBLE_TAP_MS = 300;
    private boolean tapPending;

    private void onBubbleTap() {
        if (tapPending) {
            tapPending = false;   // the pending single tap sees this and does nothing
            toggleHookup();
        } else {
            tapPending = true;
            main.postDelayed(() -> { if (tapPending) { tapPending = false; toggleDriver(); } }, DOUBLE_TAP_MS);
        }
    }

    /**
     * Double-tap action: Steam Native Hook-up on/off. Hook-up needs the SDL3 driver, so switching it on also
     * switches the driver on; switching it off puts back what was there before -- including the driver being
     * off (red bubble), so the controller goes back to Android / DroidDeck's own input instead of SDL3.
     */
    private void toggleHookup() {
        boolean on = !Settings.isHookup();
        boolean freeze = false;
        if (on) {
            Settings.lastPresentAs = Settings.presentAs;
            Settings.driverBeforeHookup = Settings.driverEnabled;
            Settings.presentAs = Settings.PRESENT_HOOKUP;
            if (!Settings.driverEnabled) {
                Settings.driverEnabled = true;
                try { SdlEngine.setHidFrozen(false); } catch (Throwable ignored) { }
            }
        } else {
            Settings.presentAs = Settings.lastPresentAs;
            if (!Settings.driverBeforeHookup && Settings.driverEnabled) {
                Settings.driverEnabled = false;
                freeze = true;
            }
        }
        Settings.save();
        NativeBridge.applyDriver();   // also applies hook-up (native code releases the hooked-up controllers)
        if (freeze) {
            try { SdlEngine.setHidFrozen(true); } catch (Throwable ignored) { }
        }
        updateBubble();
        try {
            android.widget.Toast.makeText(this, on
                ? "Steam Native Hook-up ON: Steam gets the real USB controllers"
                : !Settings.driverEnabled ? "Steam Native Hook-up OFF: SDL3 driver off again"
                : "Steam Native Hook-up OFF: back to " + Settings.PRESENT_NAMES[Settings.presentAs],
                android.widget.Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) { }
    }

    /** Recolor the bubble after a settings change made elsewhere (the app screen). */
    static void refreshBubble() {
        BridgeService s = instance;
        if (s != null) s.updateBubble();
    }

    /** Tap action: flip the SDL3 forced driver and recolor the bubble. */
    private void toggleDriver() {
        Settings.driverEnabled = !Settings.driverEnabled;
        Settings.save();
        NativeBridge.applyDriver();
        try { SdlEngine.setHidFrozen(!Settings.driverEnabled); } catch (Throwable ignored) { }
        updateBubble();
    }

    private void removeBubbleByUser() {
        Settings.bubble = false;
        Settings.save();
        hideBubble();
    }

    private void hideBubble() {
        if (bubble != null && wm != null) {
            try { wm.removeView(bubble); } catch (RuntimeException ignored) { }
        }
        bubble = null;
        hideRemoveZone();
    }

    // ---- remove zone (the drop target at the bottom) ----

    private void showRemoveZone() {
        if (removeZone != null || wm == null) return;
        int zs = dp(64);
        TextView z = new TextView(this);
        z.setText("✕");
        z.setTextColor(Color.WHITE);
        z.setGravity(Gravity.CENTER);
        z.setTextSize(22f);
        z.setWidth(zs);
        z.setHeight(zs);
        z.setBackground(removeZoneBg(false));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                bubbleLp.type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(48);
        try { wm.addView(z, lp); removeZone = z; } catch (RuntimeException e) { removeZone = null; }
    }

    private void hideRemoveZone() {
        if (removeZone != null && wm != null) {
            try { wm.removeView(removeZone); } catch (RuntimeException ignored) { }
        }
        removeZone = null;
    }

    private void highlightRemoveZone(boolean over) {
        if (removeZone instanceof TextView) {
            removeZone.setBackground(removeZoneBg(over));
            ((TextView) removeZone).setScaleX(over ? 1.25f : 1f);
            ((TextView) removeZone).setScaleY(over ? 1.25f : 1f);
        }
    }

    private GradientDrawable removeZoneBg(boolean over) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(over ? 0xFFC62828 : 0xCC424242);
        g.setStroke(dp(2), 0xCCFFFFFF);
        return g;
    }

    /** True when the bubble's centre is over the bottom remove zone. */
    private boolean overRemoveZone() {
        if (removeZone == null) return false;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float bx = bubbleLp.x + bubbleSize / 2f;
        float by = bubbleLp.y + bubbleSize / 2f;
        float zx = dm.widthPixels / 2f;
        float zy = dm.heightPixels - dp(48) - dp(32);   // zone centre (bottom margin + half height)
        float dx = bx - zx, dy = by - zy;
        return Math.sqrt(dx * dx + dy * dy) < dp(80);
    }

    private GradientDrawable bubbleBg(boolean driverOn) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        // blue = Steam Native Hook-up, green = SDL3 driver on, red = off
        g.setColor(!driverOn ? 0xFFC62828 : Settings.isHookup() ? 0xFF1565C0 : 0xFF2E7D32);
        g.setStroke(dp(2), 0xCCFFFFFF);
        return g;
    }

    private void updateBubble() {
        if (bubble == null) return;
        main.post(() -> {
            if (bubble == null) return;
            bubble.setBackground(bubbleBg(Settings.driverEnabled));
            bubble.setText(Settings.isHookup() ? "HOOK" : "SDL3");
        });
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ---------- client -> controller ----------

    /** Applies the touchpad swap / invert settings to one trackpad's [down, x, y]. */
    private static void orient(float[] p, int player) {
        if (Settings.touchSwap[player]) { float t = p[1]; p[1] = p[2]; p[2] = t; }
        if (Settings.touchInvX[player]) p[1] = -p[1];
        if (Settings.touchInvY[player]) p[2] = -p[2];
    }

    private void mergeOutput(Bundle b) {
        if (b == null) return;
        if (b.containsKey("rl") || b.containsKey("rr")) {
            NativeBridge.setRumble(b.getInt("slot", 0), clamp(b.getInt("rl")), clamp(b.getInt("rr")));
        }
        // A client that sets the lightbar overrides the saved colour until the bridge screen changes it again.
        if (b.containsKey("r") || b.containsKey("g") || b.containsKey("b")) {
            int ps = Math.max(0, Math.min(Settings.PLAYERS - 1, b.getInt("slot", 0)));
            if (b.containsKey("r")) Settings.lightR[ps] = clamp(b.getInt("r"));
            if (b.containsKey("g")) Settings.lightG[ps] = clamp(b.getInt("g"));
            if (b.containsKey("b")) Settings.lightB[ps] = clamp(b.getInt("b"));
            NativeBridge.applyOutput(ps);
        }
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    // ---------- controller -> clients ----------

    // A frame goes to DroidDeck only when the controller state changed (and for FRAME_SETTLE_MS after a change, so
    // DroidDeck's stick throttle always gets the final position), plus a keep-alive every FRAME_KEEPALIVE_MS. The
    // loop runs at up to 1000 Hz but most controllers report at 250-500 Hz, and an idle controller not at all:
    // sending every pass only fills DroidDeck's queue with copies.
    private static final long FRAME_SETTLE_MS = 20, FRAME_KEEPALIVE_MS = 100;
    private final float[][] lastFrame = new float[NativeBridge.MAX_PADS][];
    private final long[] lastFrameChangeMs = new long[NativeBridge.MAX_PADS], lastFrameSentMs = new long[NativeBridge.MAX_PADS];

    private boolean frameWorthSending(float[] f, int slot) {
        if (slot < 0 || slot >= NativeBridge.MAX_PADS) return true;
        long now = SystemClock.uptimeMillis();
        float[] last = lastFrame[slot];
        if (last == null || last.length != f.length) last = lastFrame[slot] = new float[f.length];
        if (!java.util.Arrays.equals(f, last)) {
            System.arraycopy(f, 0, last, 0, f.length);   // f is native's reused array: keep a copy
            lastFrameChangeMs[slot] = now;
        } else if (now - lastFrameChangeMs[slot] > FRAME_SETTLE_MS && now - lastFrameSentMs[slot] < FRAME_KEEPALIVE_MS) {
            return false;
        }
        lastFrameSentMs[slot] = now;
        return true;
    }

    @Override public void onFrame(float[] f, int slot) {
        if (slot >= 0 && slot < NativeBridge.MAX_PADS && f.length >= NativeBridge.FRAME_LEN) {
            lastBattery[slot] = (int) f[19];
            calibrationSample(f, slot);
        }
        if (clients.isEmpty() || f.length < NativeBridge.FRAME_LEN) return;
        if (!frameWorthSending(f, slot)) return;
        Bundle b = new Bundle();
        b.putByteArray("raw", new byte[0]);
        b.putLong("t", SystemClock.elapsedRealtimeNanos());
        int rawMask = (int) f[0];
        int mask2 = f.length >= 22 ? (int) f[21] : 0;          // extra buttons (MISC2..6, e.g. Flydigi C/Z)
        int mask = Settings.applyGripRemap(rawMask, mask2, slot);  // user button -> L4/R4/L5/R5
        b.putInt("mask", mask);
        b.putInt("lx", (int) f[1]); b.putInt("ly", (int) f[2]);
        b.putInt("rx", (int) f[3]); b.putInt("ry", (int) f[4]);
        b.putInt("l2", (int) f[5]); b.putInt("r2", (int) f[6]);
        float gk = Settings.gyroGain[slot] / 100f;
        int inv = Settings.gyroInvert[slot];
        float dz = Settings.gyroDeadzone[slot];
        float[] bias = Settings.gyroBias[slot];
        int[] gmap = Settings.gyroMap[slot];
        boolean accFollows = Settings.accelFollows[slot];
        float[] gy = {f[7] * RAD_TO_DEG, f[8] * RAD_TO_DEG, f[9] * RAD_TO_DEG};   // deg/s
        float[] ac = {f[10] / G, f[11] / G, f[12] / G};                          // g
        for (int k = 0; k < 3; k++) {
            gy[k] -= bias[k];                              // remove resting drift (from Calibrate)
            if (Math.abs(gy[k]) < dz) gy[k] = 0f;          // deadzone, in real deg/s (before gain)
        }
        float[] go = new float[3], ao = new float[3];
        for (int i = 0; i < 3; i++) {
            float sign = (inv & (1 << i)) != 0 ? -1f : 1f;
            int src = gmap[i];
            go[i] = gy[src] * gk * sign;
            ao[i] = accFollows ? ac[src] * sign : ac[i];
        }
        // Sony family only (DualShock 4 = 3, DualSense = 4, DualSense Edge = 5): their report has ONE
        // full-width multitouch pad and remaps the IMU axes (X, Z, -Y) vs the Deck IMU. The Steam Deck (0)
        // and Steam Controller (1) have two trackpads and use the Deck IMU convention, so they are left as-is.
        int presentType = Settings.PRESENT_PAD_TYPE[Settings.presentAs];
        boolean sony = Settings.isSonyPadType(presentType);
        if (sony) {
            float tg = go[1]; go[1] = -go[2]; go[2] = tg;
            float ta = ao[1]; ao[1] = -ao[2]; ao[2] = ta;
        }
        b.putFloatArray("gyro", go);
        b.putFloatArray("accel", ao);


        // Raw fingers (kept for older clients) ...
        List<Integer> t = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            if (f[13 + 3 * i] > 0.5f) { t.add(i + 1); t.add((int) f[14 + 3 * i]); t.add((int) f[15 + 3 * i]); }
        }
        int[] touch = new int[t.size()];
        for (int i = 0; i < touch.length; i++) touch[i] = t.get(i);
        b.putIntArray("touch", touch);

        // ... and the decoupled version: each half is its own trackpad with local -1..1 coordinates.
        float[] padL = new float[3], padR = new float[3];
        int mode = Settings.touchMode[slot];
        // A 2015 Steam Controller has two real trackpads: always split the touchpad into left/right halves.
        if (presentType == Settings.PAD_TYPE_STEAM_2015 && mode != Settings.TOUCH_OFF) mode = Settings.TOUCH_SPLIT;
        // A native Sony pad (DualShock 4, DualSense, DualSense Edge) has ONE full-width multitouch
        // surface, so ignore the Deck split there: keep SDL's finger slots (finger 0 is the primary
        // touch the game tracks) and map both across the whole pad. padL carries finger 0, padR finger 1.
        // Only the Steam Deck pad (type 0) keeps the left/right split, since it has two real trackpads.
        if (mode != Settings.TOUCH_OFF) {
            if (sony) {
                for (int i = 0; i < 2; i++) {
                    if (f[13 + 3 * i] <= 0.5f) continue;
                    float[] p = (i == 0) ? padL : padR;
                    p[0] = 1f; p[1] = f[14 + 3 * i] / PAD_W * 2f - 1f; p[2] = f[15 + 3 * i] / PAD_H * 2f - 1f;
                }
            } else for (int i = 0; i < 2; i++) {
                if (f[13 + 3 * i] <= 0.5f) continue;
                float x = f[14 + 3 * i], y = f[15 + 3 * i];
                float ny = y / PAD_H * 2f - 1f;
                if (mode == Settings.TOUCH_SINGLE) {
                    if (padR[0] == 0f) { padR[0] = 1f; padR[1] = x / PAD_W * 2f - 1f; padR[2] = ny; }
                } else if (x < PAD_W / 2f) {
                    if (padL[0] == 0f) { padL[0] = 1f; padL[1] = x / (PAD_W / 2f) * 2f - 1f; padL[2] = ny; }
                } else {
                    if (padR[0] == 0f) { padR[0] = 1f; padR[1] = (x - PAD_W / 2f) / (PAD_W / 2f) * 2f - 1f; padR[2] = ny; }
                }
            }
        }
        int click = -1; // -1 none, 0 left trackpad, 1 right trackpad
        if (((mask >> 13) & 1) != 0 && mode != Settings.TOUCH_OFF) {
            click = (mode == Settings.TOUCH_SPLIT && padL[0] > 0f && padR[0] == 0f) ? 0 : 1;
        }
        // Button -> trackpad touch / click remaps (targets 4..7)
        int[] rm = Settings.remap[slot];
        if (Settings.srcDown(rawMask, mask2, rm[6]) && padL[0] == 0f) { padL[0] = 1f; padL[1] = 0f; padL[2] = 0f; }
        if (Settings.srcDown(rawMask, mask2, rm[7]) && padR[0] == 0f) { padR[0] = 1f; padR[1] = 0f; padR[2] = 0f; }
        if (Settings.srcDown(rawMask, mask2, rm[4])) { if (padL[0] == 0f) padL[0] = 1f; click = 0; }
        if (Settings.srcDown(rawMask, mask2, rm[5])) { if (padR[0] == 0f) padR[0] = 1f; click = 1; }
        orient(padL, slot);
        orient(padR, slot);
        b.putFloatArray("padL", padL);
        b.putFloatArray("padR", padR);
        b.putInt("padClick", click);

        int state = (int) f[20];
        b.putInt("slot", slot);
        b.putInt("ptype", Settings.PRESENT_PAD_TYPE[Settings.presentAs]);
        b.putInt("vid", NativeBridge.vids[slot]);
        b.putInt("pid", NativeBridge.pids[slot]);
        b.putInt("battery", Math.max(0, (int) f[19]));
        b.putString("charge", state == 3 ? "charging" : state == 4 ? "full" : state == 1 ? "discharging" : "unknown");
        for (Messenger c : clients) {
            try {
                Message m = Message.obtain(null, MSG_FRAME);
                m.setData(b);
                c.send(m);
            } catch (RemoteException e) { clients.remove(c); clientCount = clients.size(); }
        }
    }

    @Override public void onPadGone(int slot) {
        for (Messenger c : clients) {
            try {
                Bundle b = new Bundle();
                b.putInt("slot", slot);
                Message m = Message.obtain(null, MSG_PAD_GONE);
                m.setData(b);
                c.send(m);
            } catch (RemoteException e) { clients.remove(c); }
        }
    }

    // ---------- Steam Controller (2026) raw passthrough -> clients ----------
    @Override public void onTritonState(int reportId, byte[] payload) {
        if (clients.isEmpty() || payload == null) return;
        Bundle b = new Bundle();
        b.putInt("id", reportId);
        b.putByteArray("data", payload);
        sendAll(MSG_TRITON_STATE, b);
    }

    @Override public void onTritonConnected(boolean on, String what) {
        for (Messenger c : clients) sendTritonConn(c, on, what);
    }

    @Override public void onTritonFeature(long request, byte[] report) {
        Bundle b = new Bundle();
        b.putLong("request", request);
        if (report != null) b.putByteArray("data", report);
        sendAll(MSG_TRITON_FEATURE, b);
    }

    private void sendTritonConn(Messenger c, boolean on, String what) {
        try {
            Bundle b = new Bundle();
            b.putBoolean("on", on);
            b.putString("what", what);
            Message m = Message.obtain(null, MSG_TRITON_CONN);
            m.setData(b);
            c.send(m);
        } catch (RemoteException e) { clients.remove(c); clientCount = clients.size(); }
    }

    /** SDL3's loop must run for controllers to work: it runs in this service, no screen needed. */
    private void ensureBridgeLoop() {
        SdlEngine.start(this);
    }

    // ---------- Steam Native Hook-up -> clients ----------
    @Override public void onHookupAdded(int slot, Bundle identity) { sendAll(MSG_HOOKUP_ADD, identity); }

    @Override public void onHookupInput(int slot, byte[] report) {
        if (clients.isEmpty() || report == null) return;
        Bundle b = new Bundle();
        b.putInt("slot", slot);
        b.putByteArray("data", report);
        sendAll(MSG_HOOKUP_INPUT, b);
    }

    @Override public void onBridgeLog(String line) {
        Bundle b = new Bundle();
        b.putString("text", line);
        sendAll(MSG_BRIDGE_LOG, b);
    }

    @Override public void onHookupRemoved(int slot) {
        Bundle b = new Bundle();
        b.putInt("slot", slot);
        sendAll(MSG_HOOKUP_REMOVE, b);
    }

    @Override public void onHookupReply(int slot, long request, byte[] report) {
        Bundle b = new Bundle();
        b.putInt("slot", slot);
        b.putLong("request", request);
        if (report != null) b.putByteArray("data", report);
        sendAll(MSG_HOOKUP_REPLY, b);
    }

    // ---------- DroidDeck's SDL3Bridge settings tab ----------
    private final int[] lastBattery = { -1, -1, -1, -1 };

    // Gyro calibration started from DroidDeck: averages the resting gyro of that player's own controller.
    private static final int CALIB_SAMPLES = 180;   // ~0.7 s at 4 ms per frame
    private final Object calibLock = new Object();
    private int calibPlayer = -1, calibCount;
    private final float[] calibSum = new float[3];

    private void startCalibration(int p) {
        synchronized (calibLock) { calibPlayer = p; calibCount = 0; calibSum[0] = calibSum[1] = calibSum[2] = 0f; }
    }

    private void calibrationSample(float[] f, int slot) {
        synchronized (calibLock) {
            if (calibPlayer != slot) return;
            calibSum[0] += f[7]; calibSum[1] += f[8]; calibSum[2] += f[9];
            if (++calibCount < CALIB_SAMPLES) return;
            float[] b = Settings.gyroBias[slot];
            for (int k = 0; k < 3; k++) b[k] = calibSum[k] / CALIB_SAMPLES * RAD_TO_DEG;
            calibPlayer = -1;
        }
        Settings.save();
    }

    /** Everything the tab shows: settings, their choices (so DroidDeck needs no copy of them) and live status. */
    private Bundle settingsSnapshot() {
        Bundle b = new Bundle();
        b.putInt("v", 1);
        b.putString("status", NativeBridge.lastStatus);
        b.putBoolean("overlayAllowed", android.provider.Settings.canDrawOverlays(this));
        b.putBoolean("notifications", getSystemService(NotificationManager.class).areNotificationsEnabled());
        b.putString("engineError", SdlEngine.error == null ? "" : SdlEngine.error);
        b.putString("engineMode", SdlEngine.mode);
        b.putBoolean("fastPath", NativeBridge.hookupShmInode() != 0);
        b.putString("triton", NativeBridge.tritonConnected ? NativeBridge.tritonWhat : "");
        String[] padNames = new String[NativeBridge.MAX_PADS], padIds = new String[NativeBridge.MAX_PADS];
        String[] hookNames = new String[NativeBridge.MAX_PADS], hookIds = new String[NativeBridge.MAX_PADS];
        for (int i = 0; i < NativeBridge.MAX_PADS; i++) {
            String n = NativeBridge.names[i];
            padNames[i] = n == null ? "" : n;
            padIds[i] = n == null ? "" : String.format("%04x:%04x", NativeBridge.vids[i], NativeBridge.pids[i]);
            Bundle h = NativeBridge.hookupIdentity[i];
            hookNames[i] = h == null ? "" : h.getString("name", "");
            hookIds[i] = h == null ? "" : String.format("%04x:%04x", h.getInt("vid"), h.getInt("pid"));
        }
        b.putStringArray("padNames", padNames);
        b.putStringArray("padIds", padIds);
        b.putIntArray("padBattery", lastBattery.clone());
        b.putStringArray("hookNames", hookNames);
        b.putStringArray("hookIds", hookIds);
        synchronized (calibLock) { b.putInt("calibrating", calibPlayer); }
        // global settings
        b.putInt("presentAs", Settings.presentAs);
        b.putBoolean("driverEnabled", Settings.driverEnabled);
        b.putBoolean("forceUsb", Settings.forceUsb);
        b.putInt("pollMs", Settings.pollMs);
        b.putBoolean("bubble", Settings.bubble);
        // choices
        b.putStringArray("presentNames", Settings.PRESENT_NAMES);
        b.putStringArray("rateNames", Settings.RATE_NAMES);
        b.putIntArray("rateMs", Settings.RATE_MS);
        b.putStringArray("touchNames", Settings.TOUCH_NAMES);
        b.putStringArray("srcNames", Settings.SRC_NAMES);
        b.putStringArray("remapTargets", Settings.REMAP_TARGETS);
        b.putStringArray("axisNames", Settings.AXIS_NAMES);
        b.putStringArray("gyroInvNames", Settings.GYRO_INV_NAMES);
        b.putStringArray("playerLedNames", Settings.PLAYER_NAMES);
        // per player (index p, or p * n + i for the ones with several entries)
        int P = Settings.PLAYERS;
        int[] gyroMap = new int[P * 3], remap = new int[P * Settings.REMAP_COUNT];
        float[] bias = new float[P * 3];
        for (int p = 0; p < P; p++) {
            for (int i = 0; i < 3; i++) { gyroMap[p * 3 + i] = Settings.gyroMap[p][i]; bias[p * 3 + i] = Settings.gyroBias[p][i]; }
            for (int i = 0; i < Settings.REMAP_COUNT; i++) remap[p * Settings.REMAP_COUNT + i] = Settings.remap[p][i];
        }
        b.putIntArray("touchMode", Settings.touchMode.clone());
        b.putBooleanArray("touchInvX", Settings.touchInvX.clone());
        b.putBooleanArray("touchInvY", Settings.touchInvY.clone());
        b.putBooleanArray("touchSwap", Settings.touchSwap.clone());
        b.putIntArray("gyroGain", Settings.gyroGain.clone());
        b.putIntArray("gyroInvert", Settings.gyroInvert.clone());
        b.putIntArray("gyroDeadzone", Settings.gyroDeadzone.clone());
        b.putIntArray("gyroMap", gyroMap);
        b.putFloatArray("gyroBias", bias);
        b.putBooleanArray("accelFollows", Settings.accelFollows.clone());
        b.putIntArray("remap", remap);
        b.putIntArray("lightR", Settings.lightR.clone());
        b.putIntArray("lightG", Settings.lightG.clone());
        b.putIntArray("lightB", Settings.lightB.clone());
        b.putIntArray("brightness", Settings.brightness.clone());
        b.putIntArray("playerLed", Settings.playerLed.clone());
        return b;
    }

    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** One change from DroidDeck's tab, with the same effect as the same change on this app's own screen. */
    private void applyRemote(Bundle d) {
        if (d == null) return;
        String key = d.getString("key", "");
        int p = clampInt(d.getInt("p", 0), 0, Settings.PLAYERS - 1);
        int i = d.getInt("i", 0);
        int v = d.getInt("int", 0);
        boolean on = d.getBoolean("bool", false);
        switch (key) {
            case "presentAs":
                Settings.presentAs = clampInt(v, 0, Settings.PRESENT_NAMES.length - 1);
                if (!Settings.isHookup()) Settings.lastPresentAs = Settings.presentAs;
                Settings.save();
                NativeBridge.applyHookup();
                updateBubble();
                break;
            case "driverEnabled":
                if (Settings.driverEnabled != on) toggleDriver();
                break;
            case "forceUsb":
                Settings.forceUsb = on;
                Settings.save();
                NativeBridge.applyForce();
                break;
            case "pollMs":
                Settings.pollMs = clampInt(v, 1, 16);
                Settings.save();
                NativeBridge.applyRate();
                break;
            case "bubble":
                if (on && !android.provider.Settings.canDrawOverlays(this)) break;   // needs the permission first
                Settings.bubble = on;
                Settings.save();
                if (on) showBubble(); else hideBubble();
                break;
            case "touchMode": Settings.touchMode[p] = clampInt(v, 0, 2); Settings.save(); break;
            case "touchInvX": Settings.touchInvX[p] = on; Settings.save(); break;
            case "touchInvY": Settings.touchInvY[p] = on; Settings.save(); break;
            case "touchSwap": Settings.touchSwap[p] = on; Settings.save(); break;
            case "gyroGain": Settings.gyroGain[p] = clampInt(v, 1, 800); Settings.save(); break;
            case "gyroInvert": Settings.gyroInvert[p] = clampInt(v, 0, 7); Settings.save(); break;
            case "gyroDeadzone": Settings.gyroDeadzone[p] = clampInt(v, 0, 50); Settings.save(); break;
            case "gyroMap":
                if (i >= 0 && i < 3) { Settings.gyroMap[p][i] = clampInt(v, 0, 2); Settings.save(); }
                break;
            case "accelFollows": Settings.accelFollows[p] = on; Settings.save(); break;
            case "remap":
                if (i >= 0 && i < Settings.REMAP_COUNT) { Settings.remap[p][i] = clampInt(v, 0, Settings.SRC_NAMES.length - 1); Settings.save(); }
                break;
            case "lightR": Settings.lightR[p] = clampInt(v, 0, 255); Settings.save(); NativeBridge.applyOutput(p); break;
            case "lightG": Settings.lightG[p] = clampInt(v, 0, 255); Settings.save(); NativeBridge.applyOutput(p); break;
            case "lightB": Settings.lightB[p] = clampInt(v, 0, 255); Settings.save(); NativeBridge.applyOutput(p); break;
            case "brightness": Settings.brightness[p] = clampInt(v, 0, 100); Settings.save(); NativeBridge.applyOutput(p); break;
            case "playerLed": Settings.playerLed[p] = clampInt(v, 0, 4); Settings.save(); NativeBridge.applyOutput(p); break;
            case "calibrate": startCalibration(p); break;
            case "resetCalibration":
                synchronized (calibLock) { if (calibPlayer == p) calibPlayer = -1; }
                Settings.gyroBias[p][0] = Settings.gyroBias[p][1] = Settings.gyroBias[p][2] = 0f;
                Settings.save();
                break;
            default:
                android.util.Log.w("SDL3Bridge", "settings: unknown key from DroidDeck: " + key);
        }
    }

    private void sendOne(Messenger c, int what, Bundle b) {
        try {
            Message m = Message.obtain(null, what);
            m.setData(b);
            c.send(m);
        } catch (RemoteException e) { clients.remove(c); clientCount = clients.size(); }
    }

    private void sendAll(int what, Bundle b) {
        for (Messenger c : clients) {
            try {
                Message m = Message.obtain(null, what);
                m.setData(b);
                c.send(m);
            } catch (RemoteException e) { clients.remove(c); clientCount = clients.size(); }
        }
    }

    @Override public void onStatus(String s) {
        connectedState = s != null && s.startsWith("Connected");
        updateBubble();
        main.post(() -> refreshNotification(s));
        for (Messenger c : clients) sendStatus(c, s);
    }

    private void sendStatus(Messenger c, String s) {
        try {
            Bundle b = new Bundle();
            b.putString("text", s);
            Message m = Message.obtain(null, MSG_STATUS);
            m.setData(b);
            c.send(m);
        } catch (RemoteException e) { clients.remove(c); }
    }
}
