package com.example.dsedge;

import android.app.PendingIntent;
import android.content.Intent;
import android.hardware.usb.UsbInterface;
import android.os.Build;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import org.libsdl.app.SDLControllerManager;

/**
 * The bridge's screen: live controller test and settings. SDL3 itself runs in the background service
 * (SdlEngine), so this screen can open and close at any time without affecting the controllers. (Fallback, if
 * SDL3 can't run in the background with this build: opening this screen starts SDL3 in a hidden screen behind it.)
 */
public class BridgeActivity extends android.app.Activity implements NativeBridge.Listener {
    private interface IntSink { void set(int v); }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusView, summaryView, usbView;
    private Button updateButton;
    private ControllerView pad;
    private String updateUrl;
    private long lastSummary;
    private static final float RAD_TO_DEG = 57.29578f;
    private static final int CALIB_SAMPLES = 180;      // ~0.7s at 4ms/frame
    private volatile int calibCount = -1;              // -1 = not calibrating
    private final float[] calibSum = new float[3];
    private int calibPlayer = 0;
    private int uiPlayer = 0;
    private LinearLayout playerBox;
    private Button[] playerTabs;
    private TextView gyroCalibView;
    private boolean pendingBubble;   // user asked for the bubble; waiting for overlay permission
    private TextView audioView;


    // ---- design tokens ----
    private static final int BG = 0xFF0E1116, CARD = 0xFF171C24, CARD2 = 0xFF222A35,
            ACCENT = 0xFF4FC3F7, TEXT = 0xFFECEFF1, SUB = 0xFF8A97A7,
            OKC = 0xFF34D399, WARNC = 0xFFF59E0B, BADC = 0xFFEF4444;
    private TextView statusPill;

    private int dp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    /** True while the bridge screen is open. */
    static volatile boolean running;

    @Override protected void onCreate(Bundle state) {
        NativeBridge.appContext = getApplicationContext();
        running = true;
        super.onCreate(state);
        Settings.init(this);
        SdlEngine.start(this);
        // Android 13+: the status bar icon (the bridge's notification) needs this permission.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 1);
        }
        UpdateChecker.BuildInfo info = UpdateChecker.readBuildInfo(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(18), dp(14), dp(18));

        // ---- header bar ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView appTitle = new TextView(this);
        appTitle.setText("SDL3 Bridge");
        appTitle.setTextColor(TEXT);
        appTitle.setTextSize(21f);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        TextView appSub = new TextView(this);
        appSub.setText(info.tag + "   ·   SDL " + info.sdl);
        appSub.setTextColor(SUB);
        appSub.setTextSize(11f);
        titleCol.addView(appTitle);
        titleCol.addView(appSub);
        bar.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusPill = new TextView(this);
        statusPill.setText("Starting");
        statusPill.setTextColor(0xFF0E1116);
        statusPill.setTextSize(11f);
        statusPill.setTypeface(Typeface.DEFAULT_BOLD);
        statusPill.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusPill.setBackground(pill(SUB));
        bar.addView(statusPill);
        root.addView(bar);
        spacer(root, 14);

        // ---- controller test card ----
        LinearLayout test = card(root);
        statusView = sub(NativeBridge.lastStatus, 12f);
        usbView = sub("USB: checking...", 11f);
        pad = new ControllerView(this);
        test.addView(statusView);
        LinearLayout padWrap = new LinearLayout(this);
        padWrap.setBackground(rounded(0xFF0B0E13, dp(10)));
        padWrap.setPadding(dp(4), dp(4), dp(4), dp(4));
        padWrap.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams pw = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pw.topMargin = dp(8); pw.bottomMargin = dp(8);
        test.addView(padWrap, pw);
        summaryView = sub("Press every button and move everything to test it.", 12f);
        test.addView(summaryView);
        test.addView(usbView);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(8);
        row.addView(primaryButton("Test rumble", v -> rumbleTest()), weight());
        gap(row);
        row.addView(button("Reset", v -> { pad.resetTested(); summaryView.setText(pad.summary()); }), weight());
        gap(row);
        updateButton = button("Update", v -> { if (updateUrl != null) openUrl(updateUrl); else checkUpdate(); });
        row.addView(updateButton, weight());
        test.addView(row, rp);

        // ---- per-player settings ----
        LinearLayout playerCard = card(root);
        cardTitle(playerCard, "Player settings");
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(rounded(CARD2, dp(10)));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        playerTabs = new Button[Settings.PLAYERS];
        for (int i = 0; i < Settings.PLAYERS; i++) {
            final int pi = i;
            Button t = new Button(this);
            t.setText("P" + (i + 1));
            t.setAllCaps(false);
            t.setTextSize(13f);
            t.setOnClickListener(v -> showPlayer(pi));
            playerTabs[i] = t;
            tabs.addView(t, weight());
        }
        playerCard.addView(tabs);
        playerBox = new LinearLayout(this);
        playerBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbp.topMargin = dp(10);
        playerCard.addView(playerBox, pbp);
        showPlayer(0);

        // ---- SDL3 driver ----
        LinearLayout drv = card(root);
        cardTitle(drv, "SDL3 driver");
        addSpinner(drv, "Controller source", new String[] {
            "Auto: any controller (SDL3 USB driver when plugged in, else Android/Bluetooth)",
            "Forced: only SDL3's USB driver (USB cable only)"
        }, Settings.forceUsb ? 1 : 0, v -> {
            Settings.forceUsb = v == 1;
            Settings.save();
            NativeBridge.applyForce();
        });
        drv.addView(primaryButton("Force SDL3 USB driver", v -> forceUsbDriver()));
        drv.addView(sub("Plug the controller in with USB-C, tap the button and allow USB access "
                + "(choose 'always' to skip this next time). Works for any SDL3-supported controller.", 11f));

        // ---- Controller mode ----
        LinearLayout mode = card(root);
        cardTitle(mode, "Controller mode");
        addSpinner(mode, "Present controller as", Settings.PRESENT_NAMES, Settings.presentAs, v -> {
            Settings.presentAs = v;
            if (!Settings.isHookup()) Settings.lastPresentAs = v;
            Settings.save();
            NativeBridge.applyHookup();
            BridgeService.refreshBubble();
        });
        mode.addView(sub("Steam Deck: the controller is DroidDeck's Deck pad (default, full features). "
                + "DualSense / DualShock 4: Steam sees a NATIVE Sony controller (hidraw) with gyro, touchpad and back buttons. "
                + "Steam Native Hook-up: every USB controller is handed to Steam as itself (its real name and ids), "
                + "so Steam's own driver gives it all its features, like on a PC; Bluetooth controllers stay a Deck pad. "
                + "Give Steam a few seconds, or restart the session, after changing.", 11f));
        addSpinner(mode, "Poll rate", Settings.RATE_NAMES, rateIndex(), v -> {
            Settings.pollMs = Settings.RATE_MS[v];
            Settings.save();
            NativeBridge.applyRate();
        });
        mode.addView(sub("Higher rate = lower gyro/trackpad latency, a little more CPU and battery. No accuracy is lost either way.", 11f));

        // ---- Floating bubble ----
        LinearLayout bub = card(root);
        cardTitle(bub, "Floating bubble");
        addCheck(bub, "Show floating bubble over other apps", Settings.bubble, on -> {
            if (on) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    pendingBubble = true;
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException ignored) { }
                    return;
                }
                Settings.bubble = true;
                startService(new Intent(this, BridgeService.class).setAction("BUBBLE").putExtra("on", true));
            } else {
                Settings.bubble = false;
                startService(new Intent(this, BridgeService.class).setAction("BUBBLE").putExtra("on", false));
            }
        });
        bub.addView(sub("A draggable chat-head: tap toggles the SDL3 driver (green on / red off), hold 3s opens this app, "
                + "drag to the bottom to remove. Needs the \"Draw over other apps\" permission.", 11f));

        // ---- USB audio ----
        LinearLayout aud = card(root);
        cardTitle(aud, "USB audio");
        audioView = sub(audioInfo(), 12f);
        aud.addView(audioView);
        aud.addView(button("Developer options  →  USB audio routing", v -> openDevSettings()));
        aud.addView(sub("When the controller is plugged in via USB-C, Android may grab it as an audio device and route sound to it. "
                + "This opens Developer options — turn ON \"Disable USB audio routing\" to keep audio on the phone. "
                + "(If Developer options aren't enabled, tap Build number 7 times in About phone first.)", 11f));

        root.addView(sub("Orange = held now, green = worked at least once. Leave this app open once, then switch to DroidDeck.", 11f));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        addContentView(scroll, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        NativeBridge.listeners.add(this);
        try {
            startForegroundService(new Intent(this, BridgeService.class));
        } catch (RuntimeException ignored) { }
        NativeBridge.applyDriver();
        NativeBridge.applyRate();
        NativeBridge.applyOutput();
        checkUpdate();
        ui.post(usbRefresh);
    }

    private void showPlayer(int p) {
        uiPlayer = p;
        for (int i = 0; i < playerTabs.length; i++) {
            boolean sel = i == p;
            playerTabs[i].setBackground(rounded(sel ? ACCENT : 0x00000000, dp(8)));
            playerTabs[i].setTextColor(sel ? 0xFF0E1116 : SUB);
            playerTabs[i].setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        playerBox.removeAllViews();
        buildPlayerSection(playerBox, p);
    }

    private void buildPlayerSection(LinearLayout box, final int p) {
        subhead(box, "Gyro");
        addSlider(box, "Gyro speed % (100 = 1:1)", 800, Settings.gyroGain[p], v -> Settings.gyroGain[p] = Math.max(1, v));
        addSpinner(box, "Invert gyro axes", Settings.GYRO_INV_NAMES, Settings.gyroInvert[p], v -> { Settings.gyroInvert[p] = v; Settings.save(); });
        for (int i = 0; i < 3; i++) {
            final int out = i;
            addSpinner(box, "DroidDeck gyro " + "XYZ".charAt(i) + " comes from", Settings.AXIS_NAMES, Settings.gyroMap[p][i], v -> { Settings.gyroMap[p][out] = v; Settings.save(); });
        }
        addSpinner(box, "Apply remap/invert to accelerometer too", new String[] { "Yes (recommended)", "No" },
                Settings.accelFollows[p] ? 0 : 1, v -> { Settings.accelFollows[p] = v == 0; Settings.save(); });
        addSlider(box, "Gyro deadzone (deg/s)", 50, Settings.gyroDeadzone[p], v -> Settings.gyroDeadzone[p] = v);
        gyroCalibView = sub(calibLabel(p), 11f);
        LinearLayout gc = new LinearLayout(this);
        gc.setOrientation(LinearLayout.HORIZONTAL);
        gc.addView(button("Calibrate (hold still)", v -> startGyroCalibration()), weight());
        gap(gc);
        gc.addView(button("Reset calibration", v -> resetGyroCalibration()), weight());
        box.addView(gc);
        box.addView(gyroCalibView);
        box.addView(sub("Calibration samples the controller shown above (player 1 slot). Deadzone ignores tiny drift.", 11f));

        subhead(box, "Touchpad");
        addCheck(box, "Invert touchpad X (left/right)", Settings.touchInvX[p], on -> Settings.touchInvX[p] = on);
        addCheck(box, "Invert touchpad Y (up/down)", Settings.touchInvY[p], on -> Settings.touchInvY[p] = on);
        addCheck(box, "Swap touchpad X and Y (before invert)", Settings.touchSwap[p], on -> Settings.touchSwap[p] = on);
        addSpinner(box, "Layout", Settings.TOUCH_NAMES, Settings.touchMode[p], v -> { Settings.touchMode[p] = v; Settings.save(); pad.postInvalidate(); });

        subhead(box, "Button remap");
        box.addView(sub("Map any controller button to a Deck grip (L4/R4/L5/R5) or a trackpad touch/click. "
                + "Useful for extra buttons like a Flydigi's C and Z.", 11f));
        for (int i = 0; i < Settings.REMAP_COUNT; i++) {
            final int target = i;
            addSpinner(box, Settings.REMAP_TARGETS[i] + " pressed by", Settings.SRC_NAMES, Settings.remap[p][i], v -> { Settings.remap[p][target] = v; Settings.save(); });
        }

        subhead(box, "Light");
        addSlider(box, "Red", 255, Settings.lightR[p], v -> { Settings.lightR[p] = v; applyLight(p); });
        addSlider(box, "Green", 255, Settings.lightG[p], v -> { Settings.lightG[p] = v; applyLight(p); });
        addSlider(box, "Blue", 255, Settings.lightB[p], v -> { Settings.lightB[p] = v; applyLight(p); });
        addSlider(box, "Brightness %", 100, Settings.brightness[p], v -> { Settings.brightness[p] = v; applyLight(p); });
        addSpinner(box, "Player LEDs", Settings.PLAYER_NAMES, Settings.playerLed[p], v -> { Settings.playerLed[p] = v; Settings.save(); applyLight(p); });
    }

    private void applyLight(int p) { NativeBridge.applyOutput(p); }

    // ---------- styled UI helpers ----------

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(20));
        return g;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, dp(16)));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        parent.addView(c, lp);
        return c;
    }

    private void cardTitle(LinearLayout card, String s) {
        TextView t = new TextView(this);
        t.setText(s.toUpperCase(java.util.Locale.ROOT));
        t.setTextColor(ACCENT);
        t.setTextSize(12f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(8));
        card.addView(t);
    }

    private void subhead(LinearLayout box, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(TEXT);
        t.setTextSize(14f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(4));
        box.addView(t);
    }

    private void spacer(LinearLayout parent, int h) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h)));
    }

    private void gap(LinearLayout row) {
        View v = new View(this);
        row.addView(v, new LinearLayout.LayoutParams(dp(8), 1));
    }

    private TextView text(String s, float size) {
        TextView t = new TextView(this);
        t.setTextColor(TEXT);
        t.setTextSize(size);
        t.setPadding(0, dp(4), 0, dp(4));
        t.setText(s);
        return t;
    }

    private TextView sub(String s, float size) {
        TextView t = text(s, size);
        t.setTextColor(SUB);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setTextColor(TEXT);
        b.setBackground(rounded(CARD2, dp(10)));
        b.setStateListAnimator(null);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setOnClickListener(l);
        return b;
    }

    private Button primaryButton(String s, View.OnClickListener l) {
        Button b = button(s, l);
        b.setBackground(rounded(ACCENT, dp(10)));
        b.setTextColor(0xFF0E1116);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        return b;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private ArrayAdapter<String> styledAdapter(String[] names) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                t.setTextColor(TEXT); t.setTextSize(13f);
                return t;
            }
            @Override public View getDropDownView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getDropDownView(pos, cv, parent);
                t.setTextColor(0xFF10151C); t.setTextSize(14f);
                t.setPadding(dp(14), dp(12), dp(14), dp(12));
                return t;
            }
        };
    }

    private void addSpinner(LinearLayout root, String label, String[] names, int selected, final IntSink sink) {
        root.addView(sub(label, 12f));
        Spinner sp = new Spinner(this);
        sp.setAdapter(styledAdapter(names));
        sp.setSelection(selected);
        sp.setBackground(rounded(CARD2, dp(8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { sink.set(position); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        root.addView(sp, lp);
    }

    private void addSlider(LinearLayout root, final String label, int max, int value, final IntSink sink) {
        final TextView t = sub(label + ":  " + value, 12f);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(value);
        sb.setProgressTintList(ColorStateList.valueOf(ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(ACCENT));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                t.setText(label + ":  " + v);
                if (fromUser) sink.set(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { Settings.save(); }
        });
        root.addView(t);
        root.addView(sb);
    }

    private interface BoolSink { void set(boolean on); }

    private void addCheck(LinearLayout root, String label, boolean value, final BoolSink sink) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(TEXT);
        c.setTextSize(13f);
        c.setButtonTintList(ColorStateList.valueOf(ACCENT));
        c.setPadding(dp(6), dp(6), 0, dp(6));
        c.setChecked(value);
        c.setOnCheckedChangeListener((v, on) -> { sink.set(on); Settings.save(); });
        root.addView(c);
    }

    // ---------- USB diagnostics ----------

    private final Runnable usbRefresh = new Runnable() {
        @Override public void run() {
            int n = BridgeService.clientCount;
            usbView.setText(usbInfo() + "\nDroidDeck link: " + (n > 0 ? (n + " app(s) bound — sending frames") : "NOT bound (open a DroidDeck game session)"));
            ui.postDelayed(this, 2000);
        }
    };

    /** Android's view of the controller on USB: is it there, and may this app talk to it? */
    private String usbInfo() {
        try {
            UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
            StringBuilder sb = new StringBuilder("USB: ");
            boolean any = false;
            for (UsbDevice d : um.getDeviceList().values()) {
                if (!looksLikeController(d)) continue;
                any = true;
                String name = d.getProductName() != null ? d.getProductName() : "controller";
                sb.append(String.format(java.util.Locale.ROOT, "%s %04x:%04x access=%s  ", name,
                        d.getVendorId(), d.getProductId(), um.hasPermission(d) ? "yes" : "NO (unplug and replug, tap OK)"));
            }
            if (!any) sb.append("no controller on USB (use a USB-C data cable)");
            return sb.toString();
        } catch (RuntimeException e) {
            return "USB: unavailable";
        }
    }

    /** Lists any USB / headset audio outputs Android currently sees (e.g. the controller's audio device). */
    private int rateIndex() {
        for (int i = 0; i < Settings.RATE_MS.length; i++) if (Settings.RATE_MS[i] == Settings.pollMs) return i;
        return 1;
    }

    private String audioInfo() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            StringBuilder sb = new StringBuilder("Audio out: ");
            boolean any = false;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_USB_ACCESSORY) {
                    any = true;
                    CharSequence nm = d.getProductName();
                    sb.append(nm != null ? nm : (t == AudioDeviceInfo.TYPE_USB_HEADSET ? "USB headset" : "USB audio")).append("  ");
                }
            }
            if (!any) sb.append("no USB audio device (plug the controller in with USB-C, or no audio interface)");
            return sb.toString();
        } catch (RuntimeException e) {
            return "Audio out: unavailable";
        }
    }

    /** Opens Developer options, where "Disable USB audio routing" keeps audio on the phone. */
    private void openDevSettings() {
        // There is no public intent to a single developer toggle, so open the Developer options screen.
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (RuntimeException ignored) { }
        // Developer options not enabled or unavailable: fall back to the main Settings app.
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    // ---------- tests ----------

    private void rumbleTest() {
        NativeBridge.setRumble(uiPlayer, 220, 220);
        ui.postDelayed(() -> NativeBridge.setRumble(uiPlayer, 0, 0), 700);
    }

    // ---------- updates ----------

    private void checkUpdate() {
        updateUrl = null;
        updateButton.setText("Checking for updates...");
        UpdateChecker.check(this, (msg, url) -> {
            updateUrl = url;
            updateButton.setText(msg);
        });
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            updateButton.setText("Could not open the download link.");
        }
    }

    // ---------- SDL USB handling ----------

    @Override protected void onDestroy() {
        running = false;
        ui.removeCallbacks(usbRefresh);
        NativeBridge.listeners.remove(this);
        super.onDestroy();
    }

    // SDL releases the USB controller when the activity pauses or loses focus. Undo that so input
    // keeps flowing while DroidDeck is in front.
    /** Asks Android for USB access to every game-controller-like device, using SDL's own permission
     *  action so SDL's receiver opens the device straight away, then restarts SDL's enumeration. */
    private void forceUsbDriver() {
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        int asked = 0, have = 0, seen = 0;
        if (um != null) {
            for (UsbDevice d : um.getDeviceList().values()) {
                if (!looksLikeController(d)) continue;
                seen++;
                if (um.hasPermission(d)) { have++; continue; }
                Intent i = new Intent("org.libsdl.app.USB_PERMISSION").setPackage(getPackageName());
                int flags = Build.VERSION.SDK_INT >= 31 ? 0x02000000 : 0; // FLAG_MUTABLE
                try {
                    um.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
                    asked++;
                } catch (RuntimeException ignored) { }
            }
        }
        usbView.setText(seen == 0
                ? "No USB controller found. Use a USB-C cable (Bluetooth is not enough) and try again."
                : "USB controllers: " + seen + ", already allowed: " + have + ", asked now: " + asked);
        keepControllerClaimed();
        ui.postDelayed(NativeBridge::rescan, asked > 0 ? 4000 : 300);
    }

    /** Any USB device with a HID or vendor-specific interface (SDL3 decides whether it really is a controller). */
    private static boolean looksLikeController(UsbDevice d) {
        for (int n = 0; n < d.getInterfaceCount(); n++) {
            UsbInterface f = d.getInterface(n);
            int c = f.getInterfaceClass();
            if (c == 255 || (c == 3 && f.getInterfaceProtocol() == 0)) return true; // vendor-specific (Xbox) or generic HID
        }
        return false;
    }

    @Override protected void onResume() {
        super.onResume();
        if (android.provider.Settings.canDrawOverlays(this)) {
            if (pendingBubble) {
                pendingBubble = false;
                Settings.bubble = true;
                Settings.save();
            }
            if (Settings.bubble) {
                startService(new Intent(this, BridgeService.class).setAction("BUBBLE").putExtra("on", true));
            }
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (isFinishing()) running = false;
        keepControllerClaimed();
    }

    @Override public void onBackPressed() {
        // Fallback only: SDL3 runs in a screen behind this one, and closing them would stop it -- step back instead.
        if (SdlHostActivity.running) moveTaskToBack(true);
        else super.onBackPressed();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        keepControllerClaimed();
    }

    private void keepControllerClaimed() {
        // When the driver is off, let go of the controller (freeze = release the USB interface) so Android can use it.
        SdlEngine.setHidFrozen(!Settings.driverEnabled);
    }

    /** Release or re-claim the USB interface (the driver was switched off / on). */
    static void setHidFrozen(boolean frozen) {
        SdlEngine.setHidFrozen(frozen);
    }

    // Controllers that reach the phone through Android's input (Bluetooth) deliver their buttons and sticks to the
    // focused window: while this screen is in front, hand them to SDL like SDL's own activity did.
    private static boolean fromGamepad(int source) {
        return (source & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
            || (source & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
            || (source & android.view.InputDevice.SOURCE_DPAD) == android.view.InputDevice.SOURCE_DPAD;
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (SdlEngine.isRunning() && fromGamepad(e.getSource())) {
            try {
                if (SDLControllerManager.isDeviceSDLJoystick(e.getDeviceId())) {
                    if (e.getAction() == android.view.KeyEvent.ACTION_DOWN
                            && SDLControllerManager.onNativePadDown(e.getDeviceId(), e.getKeyCode(), e.getScanCode())) return true;
                    if (e.getAction() == android.view.KeyEvent.ACTION_UP
                            && SDLControllerManager.onNativePadUp(e.getDeviceId(), e.getKeyCode(), e.getScanCode())) return true;
                }
            } catch (UnsatisfiedLinkError | RuntimeException ignored) { }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        if (SdlEngine.isRunning() && (e.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) {
            try {
                if (SDLControllerManager.handleJoystickMotionEvent(e)) return true;
            } catch (UnsatisfiedLinkError | RuntimeException ignored) { }
        }
        return super.dispatchGenericMotionEvent(e);
    }

    // ---------- NativeBridge.Listener ----------

    private String calibLabel(int p) {
        float[] b = Settings.gyroBias[p];
        if (b[0] == 0f && b[1] == 0f && b[2] == 0f) return "Calibration: none";
        return String.format(java.util.Locale.ROOT, "Calibration bias (deg/s): x=%.2f y=%.2f z=%.2f", b[0], b[1], b[2]);
    }

    private void startGyroCalibration() {
        calibPlayer = uiPlayer;
        calibSum[0] = calibSum[1] = calibSum[2] = 0f;
        calibCount = 0;
        if (gyroCalibView != null) gyroCalibView.setText("Hold the controller still...");
    }

    private void resetGyroCalibration() {
        calibCount = -1;
        float[] b = Settings.gyroBias[uiPlayer];
        b[0] = b[1] = b[2] = 0f;
        Settings.save();
        if (gyroCalibView != null) gyroCalibView.setText(calibLabel(uiPlayer));
    }

    @Override public void onFrame(float[] f, int slot) {
        if (slot != 0) return; // the test screen shows player 1
        if (calibCount >= 0 && f.length >= 13) {
            calibSum[0] += f[7]; calibSum[1] += f[8]; calibSum[2] += f[9];
            if (++calibCount >= CALIB_SAMPLES) {
                float[] b = Settings.gyroBias[calibPlayer];
                b[0] = calibSum[0] / CALIB_SAMPLES * RAD_TO_DEG;
                b[1] = calibSum[1] / CALIB_SAMPLES * RAD_TO_DEG;
                b[2] = calibSum[2] / CALIB_SAMPLES * RAD_TO_DEG;
                Settings.save();
                calibCount = -1;
                ui.post(() -> { if (gyroCalibView != null && uiPlayer == calibPlayer) gyroCalibView.setText(calibLabel(calibPlayer)); });
            }
        }
        pad.update(f);
        long now = System.nanoTime();
        if (now - lastSummary > 300_000_000L) {
            lastSummary = now;
            ui.post(() -> summaryView.setText(pad.summary()));
        }
    }

    @Override public void onPadCaps(int slot, int present, int axes, int touchpads, String kind) {
        if (slot != 0) return;
        pad.setCaps(present, axes, touchpads, kind);
    }

    @Override public void onStatus(final String s) {
        ui.post(() -> {
            statusView.setText(s);
            boolean off = s.contains("driver OFF");
            boolean waiting = s.startsWith("Controller disconnected") || s.startsWith("Waiting") || s.startsWith("Starting");
            if (off) {
                statusPill.setText("Driver off");
                statusPill.setBackground(pill(BADC));
            } else if (waiting) {
                statusPill.setText("Waiting");
                statusPill.setBackground(pill(WARNC));
            } else {
                statusPill.setText("Connected");
                statusPill.setBackground(pill(OKC));
            }
            if (off || waiting) pad.setDisconnected();
        });
    }
}
