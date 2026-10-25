package com.example.dsedge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Universal live gamepad diagram for any SDL3 controller (DualSense / Edge, Xbox, Switch Pro,
 * Steam Controller 2015 &amp; 2026, generic pads). It draws only the controls the controller actually
 * reports (via NativeBridge caps) and labels the face / menu buttons for the controller kind.
 *
 * Orange = held now, green = pressed at least once (works), grey = present but untested.
 */
public class ControllerView extends View {
    private static final int ORANGE = 0xFFFF9800, GREEN = 0xFF2E7D32, GRAY = 0xFF37474F, LINE = 0xFF90A4AE;

    // frame-mask bit numbers
    private static final int B_SOUTH = 0, B_EAST = 1, B_WEST = 2, B_NORTH = 3, B_L1 = 4, B_R1 = 5,
            B_L2 = 6, B_R2 = 7, B_BACK = 8, B_START = 9, B_L3 = 10, B_R3 = 11, B_GUIDE = 12,
            B_TOUCH = 13, B_MISC = 14, B_UP = 15, B_DOWN = 16, B_LEFT = 17, B_RIGHT = 18,
            B_P1 = 19, B_P2 = 20, B_P3 = 21, B_P4 = 22;

    // tested-flag bits (not button bits)
    private static final int T_LS = 1, T_RS = 2, T_L2 = 4, T_R2 = 8, T_GYRO = 16, T_ACCEL = 32, T_TOUCH = 64;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private volatile float[] frame = new float[NativeBridge.FRAME_LEN];
    private volatile int mask;
    private volatile int testedButtons;
    private volatile int testedFlags;
    private volatile boolean connected;
    private long lastInvalidate;

    // capabilities (default: assume everything until the controller reports otherwise)
    private volatile int present = (1 << 23) - 1;
    private volatile int axes = 0xF;
    private volatile int touchpads = 1;
    private volatile String kind = "generic";

    public ControllerView(Context c) {
        super(c);
        setBackgroundColor(0xFF111111);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(0.5f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFECEFF1);
        frame[1] = frame[2] = frame[3] = frame[4] = 128f;
    }

    /** Capabilities of the displayed controller, from the native layer. */
    public void setCaps(int present, int axes, int touchpads, String kind) {
        this.present = present;
        this.axes = axes;
        this.touchpads = touchpads;
        this.kind = kind == null ? "generic" : kind;
        postInvalidate();
    }

    /** Called from any thread with each native frame. */
    public void update(float[] f) {
        if (f == null || f.length < NativeBridge.FRAME_LEN) return;
        float[] c = f.clone();
        frame = c;
        connected = true;
        int m = (int) c[0];
        mask = m;
        testedButtons |= m;
        int t = testedFlags;
        if (Math.abs(c[1] - 128f) > 60f || Math.abs(c[2] - 128f) > 60f) t |= T_LS;
        if (Math.abs(c[3] - 128f) > 60f || Math.abs(c[4] - 128f) > 60f) t |= T_RS;
        if (c[5] > 100f) t |= T_L2;
        if (c[6] > 100f) t |= T_R2;
        if (Math.abs(c[7]) + Math.abs(c[8]) + Math.abs(c[9]) > 0.6f) t |= T_GYRO;
        if (Math.abs(c[10]) + Math.abs(c[11]) + Math.abs(c[12]) > 3f) t |= T_ACCEL;
        if (c[13] > 0.5f || c[16] > 0.5f) t |= T_TOUCH;
        testedFlags = t;
        long now = System.nanoTime();
        if (now - lastInvalidate > 16_000_000L) {
            lastInvalidate = now;
            postInvalidate();
        }
    }

    public void setDisconnected() { connected = false; mask = 0; postInvalidate(); }

    public void resetTested() { testedButtons = 0; testedFlags = 0; postInvalidate(); }

    /** One-line summary of what has been verified so far (only counts controls the pad has). */
    public String summary() {
        int p = present & ((1 << 23) - 1);
        int n = Integer.bitCount(p);
        int done = Integer.bitCount(testedButtons & p);
        int t = testedFlags;
        StringBuilder sb = new StringBuilder("Tested: buttons ").append(done).append('/').append(n);
        if ((axes & 1) != 0 || (axes & 2) != 0) sb.append("  sticks ").append(mark(stickOk(t)));
        if ((axes & 12) != 0) sb.append("  triggers ").append(mark(trigOk(t)));
        sb.append("  gyro ").append(mark((t & T_GYRO) != 0));
        sb.append("  accel ").append(mark((t & T_ACCEL) != 0));
        if (touchpads > 0) sb.append("  touchpad ").append(mark((t & T_TOUCH) != 0));
        return sb.toString();
    }

    private boolean stickOk(int t) {
        boolean l = (axes & 1) == 0 || (t & T_LS) != 0;
        boolean r = (axes & 2) == 0 || (t & T_RS) != 0;
        return l && r;
    }

    private boolean trigOk(int t) {
        boolean l = (axes & 4) == 0 || (t & T_L2) != 0;
        boolean r = (axes & 8) == 0 || (t & T_R2) != 0;
        return l && r;
    }

    private static String mark(boolean ok) { return ok ? "OK" : "-"; }

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, (int) (width * 0.82f));
    }

    // ---------------- labels per controller kind ----------------

    private String[] faceLabels() {
        switch (kind) {
            case "ps":       return new String[] {"✕", "●", "■", "▲"}; // ✕ ● ■ ▲
            case "nintendo": return new String[] {"B", "A", "Y", "X"};
            default:         return new String[] {"A", "B", "X", "Y"};                     // xbox / steam / generic
        }
    }

    private String backLabel()  { switch (kind) { case "ps": return "Create"; case "xbox": return "View"; case "nintendo": return "-"; case "steam": return "Back"; default: return "Back"; } }
    private String startLabel() { switch (kind) { case "ps": return "Options"; case "xbox": return "Menu"; case "nintendo": return "+"; case "steam": return "Start"; default: return "Start"; } }
    private String guideLabel() { switch (kind) { case "ps": return "PS"; case "nintendo": return "Home"; case "steam": return "Steam"; default: return "Guide"; } }
    private String miscLabel()  { switch (kind) { case "ps": return "Mute"; case "xbox": return "Share"; case "nintendo": return "Capt"; default: return "Misc"; } }

    // ---------------- drawing (100 x 72 unit canvas) ----------------

    private boolean has(int bit) { return ((present >> bit) & 1) != 0; }

    private int col(int bit) {
        if (((mask >> bit) & 1) != 0) return ORANGE;
        if (((testedButtons >> bit) & 1) != 0) return GREEN;
        return GRAY;
    }

    private void label(Canvas c, String s, float x, float y, float size) {
        text.setTextSize(size);
        c.drawText(s, x, y + size * 0.35f, text);
    }

    /** Rounded rectangle button, drawn only if present. */
    private void rbtn(Canvas c, float l, float t, float r, float b, int bit, String s) {
        if (!has(bit)) return;
        rect.set(l, t, r, b);
        fill.setColor(col(bit));
        c.drawRoundRect(rect, 1f, 1f, fill);
        stroke.setColor(LINE);
        c.drawRoundRect(rect, 1f, 1f, stroke);
        if (s != null && !s.isEmpty()) label(c, s, (l + r) / 2f, (t + b) / 2f, 2.2f);
    }

    private void cbtn(Canvas c, float cx, float cy, float rad, int bit, String s) {
        if (!has(bit)) return;
        fill.setColor(col(bit));
        c.drawCircle(cx, cy, rad, fill);
        stroke.setColor(LINE);
        c.drawCircle(cx, cy, rad, stroke);
        label(c, s, cx, cy, 2.4f);
    }

    private void meter(Canvas c, float l, float t, float r, float b, float value01, boolean tested) {
        rect.set(l, t, r, b);
        fill.setColor(0xFF263238);
        c.drawRect(rect, fill);
        rect.set(l, t, l + (r - l) * Math.max(0f, Math.min(1f, value01)), b);
        fill.setColor(value01 > 0.02f ? ORANGE : (tested ? GREEN : GRAY));
        c.drawRect(rect, fill);
        rect.set(l, t, r, b);
        stroke.setColor(LINE);
        c.drawRect(rect, stroke);
    }

    private void stick(Canvas c, float cx, float cy, float x, float y, int clickBit, boolean tested) {
        boolean clickable = has(clickBit);
        boolean down = clickable && ((mask >> clickBit) & 1) != 0;
        stroke.setColor(down ? ORANGE : (tested ? 0xFF66BB6A : LINE));
        stroke.setStrokeWidth(1f);
        c.drawCircle(cx, cy, 9f, stroke);
        stroke.setStrokeWidth(0.5f);
        fill.setColor(down ? ORANGE : (tested ? GREEN : GRAY));
        float dx = (x - 128f) / 128f * 6f, dy = (y - 128f) / 128f * 6f;
        c.drawCircle(cx + dx, cy + dy, 3.6f, fill);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float u = getWidth() / 100f;
        c.save();
        c.scale(u, u);
        float[] f = frame;
        int t = testedFlags;
        String[] fl = faceLabels();

        // shoulders + triggers
        if ((axes & 4) != 0) meter(c, 6, 1, 30, 4, f[5] / 255f, (t & T_L2) != 0); else rbtn(c, 6, 1, 30, 4, B_L2, "L2");
        if ((axes & 8) != 0) meter(c, 70, 1, 94, 4, f[6] / 255f, (t & T_R2) != 0); else rbtn(c, 70, 1, 94, 4, B_R2, "R2");
        rbtn(c, 6, 5.5f, 30, 10, B_L1, "L1");
        rbtn(c, 70, 5.5f, 94, 10, B_R1, "R1");

        drawTouchpads(c);

        rbtn(c, 27, 14, 36, 18, B_BACK, backLabel());
        rbtn(c, 64, 14, 73, 18, B_START, startLabel());
        rbtn(c, 45, 36, 55, 39.5f, B_MISC, miscLabel());
        cbtn(c, 50, 44, 2.8f, B_GUIDE, guideLabel());

        // d-pad
        if (has(B_UP) || has(B_DOWN) || has(B_LEFT) || has(B_RIGHT)) {
            rbtn(c, 18, 20, 22, 24, B_UP, "");
            rbtn(c, 18, 32, 22, 36, B_DOWN, "");
            rbtn(c, 14, 26, 18, 30, B_LEFT, "");
            rbtn(c, 22, 26, 26, 30, B_RIGHT, "");
            label(c, "D-pad", 20, 40, 2.2f);
        }

        // face buttons (diamond): N top, S bottom, W left, E right
        cbtn(c, 80, 21, 3.2f, B_NORTH, fl[3]);
        cbtn(c, 80, 35, 3.2f, B_SOUTH, fl[0]);
        cbtn(c, 73, 28, 3.2f, B_WEST, fl[2]);
        cbtn(c, 87, 28, 3.2f, B_EAST, fl[1]);

        // sticks
        if ((axes & 1) != 0) { stick(c, 34, 53, f[1], f[2], B_L3, (t & T_LS) != 0); label(c, has(B_L3) ? "L3" : "L", 34, 64.5f, 2.2f); }
        if ((axes & 2) != 0) { stick(c, 66, 53, f[3], f[4], B_R3, (t & T_RS) != 0); label(c, has(B_R3) ? "R3" : "R", 66, 64.5f, 2.2f); }

        // paddles / grips
        rbtn(c, 1.5f, 40, 6.5f, 56, B_P1, "L4");
        rbtn(c, 93.5f, 40, 98.5f, 56, B_P2, "R4");
        rbtn(c, 9, 60, 21, 64.5f, B_P3, "L5");
        rbtn(c, 79, 60, 91, 64.5f, B_P4, "R5");

        // ---- bottom readout strip (own background + divider, clear of the diagram) ----
        rect.set(0, 66.5f, 100, getHeight() / u);
        fill.setColor(0xFF0B0E13);
        c.drawRect(rect, fill);
        stroke.setColor(0xFF2A3340);
        c.drawLine(3, 66.5f, 97, 66.5f, stroke);

        String s1 = connected
                ? String.format(java.util.Locale.ROOT, "%s   ·   Gyro  %.0f, %.0f, %.0f °/s",
                        kindName(), f[7] * 57.29578f, f[8] * 57.29578f, f[9] * 57.29578f)
                : "No controller connected";
        String s2 = connected
                ? String.format(java.util.Locale.ROOT, "Accel  %.2f, %.2f, %.2f g   ·   Battery  %d%%",
                        f[10] / 9.80665f, f[11] / 9.80665f, f[12] / 9.80665f, (int) Math.max(0f, f[19]))
                : "";
        label(c, s1, 50, 71.3f, 2.6f);
        if (!s2.isEmpty()) label(c, s2, 50, 76.3f, 2.6f);

        c.restore();
    }

    private String kindName() {
        switch (kind) {
            case "ps": return "PlayStation";
            case "xbox": return "Xbox";
            case "nintendo": return "Nintendo";
            case "steam": return "Steam Controller";
            default: return "Gamepad";
        }
    }


    /** Draws 0, 1 or 2 trackpads with their finger dots. */
    private void drawTouchpads(Canvas c) {
        float[] f = frame;
        if (touchpads <= 0) {
            label(c, "no touchpad", 50, 24, 2.2f);
            return;
        }
        if (touchpads >= 2) {
            padRect(c, 30, 12, 48, 34, "L pad", B_TOUCH);
            padRect(c, 52, 12, 70, 34, "R pad", B_TOUCH);
            fingerOnRect(c, 30, 48, 12, 34, 0, 0xFFFFFFFF);  // finger slot 0 -> left pad
            fingerOnRect(c, 52, 70, 12, 34, 1, 0xFF4FC3F7);  // finger slot 1 -> right pad
            return;
        }
        // single touchpad
        padRect(c, 36, 12, 64, 34, null, B_TOUCH);
        if (Settings.touchMode[0] == Settings.TOUCH_SPLIT) {
            stroke.setColor(LINE);
            c.drawLine(50, 12, 50, 34, stroke);
            label(c, "L pad", 43, 32.5f, 2f);
            label(c, "R pad", 57, 32.5f, 2f);
        } else if (Settings.touchMode[0] == Settings.TOUCH_OFF) {
            label(c, "touchpad off", 50, 32.5f, 2f);
        } else {
            label(c, "whole pad", 50, 32.5f, 2f);
        }
        fingerOnRect(c, 36, 64, 12, 34, 0, 0xFFFFFFFF);
        fingerOnRect(c, 36, 64, 12, 34, 1, 0xFF4FC3F7);
    }

    private void padRect(Canvas c, float l, float t, float r, float b, String lbl, int bit) {
        rect.set(l, t, r, b);
        fill.setColor(((mask >> bit) & 1) != 0 ? ORANGE : (((testedButtons >> bit) & 1) != 0 ? GREEN : 0xFF263238));
        c.drawRoundRect(rect, 2f, 2f, fill);
        stroke.setColor(LINE);
        c.drawRoundRect(rect, 2f, 2f, stroke);
        if (lbl != null) label(c, lbl, (l + r) / 2f, b - 1.5f, 2f);
    }

    private void fingerOnRect(Canvas c, float l, float r, float t, float b, int fingerSlot, int color) {
        float[] f = frame;
        if (f[13 + 3 * fingerSlot] <= 0.5f) return;
        float nx = f[14 + 3 * fingerSlot] / 1919f, ny = f[15 + 3 * fingerSlot] / 1079f;
        if (Settings.touchInvX[0]) nx = 1f - nx;
        if (Settings.touchInvY[0]) ny = 1f - ny;
        fill.setColor(color);
        c.drawCircle(l + nx * (r - l), t + ny * (b - t), 1.8f, fill);
    }
}
