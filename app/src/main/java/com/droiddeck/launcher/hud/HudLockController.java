/*
 * FusionHUD — a lightweight in-game performance HUD for Android / Winlator overlays.
 * Copyright (C) The412Banner  <https://github.com/The412Banner>
 *
 * Licensed under the GNU General Public License v3.0 (see LICENSE), with an
 * ADDITIONAL attribution requirement under GPL-3.0 §7(b) (see ATTRIBUTION.md):
 * any use, fork, or distribution must preserve credit to "The412Banner" and a
 * link to https://github.com/The412Banner/FusionHUD in the project documentation
 * AND in the in-app credits/about screen.
 *
 * Ported to DroidDeck from FusionHUD (Bannerlator's HUD) and modified for DroidDeck.
 */

package com.droiddeck.launcher.hud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class HudLockController {

    public interface Callbacks {
        void onTap();
        void onMoved(float x, float y);
        default void onDragged(float rawX, float rawY) {}
        default void onFlung(float velocityX, float velocityY) {}
        void onLockChanged(boolean locked);
        default void onPinch(float factor) {}
        default void onPinchEnd() {}
    }

    private static final long FADE_IN_MS = 160;
    private static final long HOLD_TOGGLE_MS = 1500;
    private static final long HOLD_REFLASH_MS = 750;
    private static final long FADE_OUT_MS = 420;
    private static final long FRAME_MS = 32;

    private final View view;
    private final Callbacks cb;
    private final GestureDetector detector;
    private final ScaleGestureDetector scaleDetector;

    private boolean locked = false;

    private float downRawX, downRawY, startX, startY;
    private boolean dragging = false;
    private boolean pinched = false;

    private long fadeStart = 0;
    private long holdMs = HOLD_TOGGLE_MS;
    private boolean fadeShowLocked = false;

    private final Paint iconFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public HudLockController(Context context, View view, Callbacks cb) {
        this.view = view;
        this.cb = cb;
        this.detector = new GestureDetector(context, new Listener());
        this.detector.setIsLongpressEnabled(true);
        this.scaleDetector = new ScaleGestureDetector(context, new PinchListener());
        this.scaleDetector.setQuickScaleEnabled(false);
        iconFill.setStyle(Paint.Style.FILL);
        iconStroke.setStyle(Paint.Style.STROKE);
        iconStroke.setStrokeCap(Paint.Cap.ROUND);
        iconStroke.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setLocked(boolean locked) { this.locked = locked; }

    public boolean isLocked() { return locked; }

    public boolean isDragging() { return dragging; }

    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        detector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (dragging && !locked) cb.onMoved(view.getX(), view.getY());
            dragging = false;
        }
        return true;
    }

    private final class Listener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            downRawX = e.getRawX();
            downRawY = e.getRawY();
            startX = view.getX();
            startY = view.getY();
            dragging = false;
            pinched = false;
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            locked = !locked;
            cb.onLockChanged(locked);
            flash(locked, HOLD_TOGGLE_MS);
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            if (pinched) return true;
            if (locked) {
                flash(true, HOLD_REFLASH_MS);
            } else {
                cb.onTap();
            }
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (dragging && !locked && !pinched) cb.onFlung(velocityX, velocityY);
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (pinched) return true;
            if (locked) {
                if (!dragging) flash(true, HOLD_REFLASH_MS);
                dragging = true;
                return true;
            }
            view.setX(startX + (e2.getRawX() - downRawX));
            view.setY(startY + (e2.getRawY() - downRawY));
            dragging = true;
            cb.onDragged(e2.getRawX(), e2.getRawY());
            return true;
        }
    }

    private final class PinchListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector d) {
            if (locked) return false;
            pinched = true;
            dragging = false;
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector d) {
            cb.onPinch(d.getScaleFactor());
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector d) {
            cb.onPinchEnd();
        }
    }

    private void flash(boolean showLocked, long hold) {
        fadeShowLocked = showLocked;
        holdMs = hold;
        fadeStart = SystemClock.elapsedRealtime();
        view.removeCallbacks(fadeTick);
        view.post(fadeTick);
    }

    private final Runnable fadeTick = new Runnable() {
        @Override
        public void run() {
            view.invalidate();
            if (currentAlpha() > 0f) view.postDelayed(this, FRAME_MS);
        }
    };

    private float currentAlpha() {
        if (fadeStart == 0) return 0f;
        long e = SystemClock.elapsedRealtime() - fadeStart;
        if (e < 0) return 0f;
        if (e < FADE_IN_MS) return e / (float) FADE_IN_MS;
        long holdEnd = FADE_IN_MS + holdMs;
        if (e < holdEnd) return 1f;
        long outEnd = holdEnd + FADE_OUT_MS;
        if (e < outEnd) return 1f - (e - holdEnd) / (float) FADE_OUT_MS;
        return 0f;
    }

    private static int withAlpha(int color, float a) {
        return Color.argb(Math.round(255 * a), Color.red(color), Color.green(color), Color.blue(color));
    }

    public void drawBadge(Canvas canvas) {
        float a = currentAlpha();
        if (a <= 0f) return;
        float d = view.getResources().getDisplayMetrics().density;
        float size = 15f * d;
        float margin = 4f * d;
        float right = view.getWidth() - margin;
        float left = right - size;
        float top = margin;

        float cx = (left + right) / 2f;
        float bottom = top + size;
        float bodyTop = top + size * 0.46f;
        float bodyLeft = left + size * 0.14f;
        float bodyRight = right - size * 0.14f;
        float r = size * 0.2f;
        float shackleTop = top + size * 0.08f;

        drawLock(canvas, cx, bodyTop, bodyLeft, bodyRight, bottom, r, shackleTop, d,
            Color.argb(Math.round(150 * a), 0, 0, 0), 3.0f * d, 0.9f * d, false);
        drawLock(canvas, cx, bodyTop, bodyLeft, bodyRight, bottom, r, shackleTop, d,
            withAlpha(0xFFFFFFFF, a), 1.7f * d, 0f, true);
    }

    private void drawLock(Canvas canvas, float cx, float bodyTop, float bodyLeft, float bodyRight,
                          float bottom, float r, float shackleTop, float d,
                          int color, float strokeW, float expand, boolean punchKeyhole) {
        iconFill.setColor(color);
        iconStroke.setColor(color);
        iconStroke.setStrokeWidth(strokeW);

        canvas.drawRoundRect(bodyLeft - expand, bodyTop - expand, bodyRight + expand, bottom + expand,
            2.4f * d, 2.4f * d, iconFill);
        if (punchKeyhole) {
            int prev = iconFill.getColor();
            iconFill.setColor(Color.argb(Color.alpha(color), 0, 0, 0));
            canvas.drawCircle(cx, (bodyTop + bottom) / 2f, 1.6f * d, iconFill);
            iconFill.setColor(prev);
        }

        RectF arc = new RectF(cx - r, shackleTop, cx + r, shackleTop + 2f * r);
        if (fadeShowLocked) {
            Path p = new Path();
            p.addArc(arc, 180f, 180f);
            p.moveTo(cx - r, shackleTop + r); p.lineTo(cx - r, bodyTop);
            p.moveTo(cx + r, shackleTop + r); p.lineTo(cx + r, bodyTop);
            canvas.drawPath(p, iconStroke);
        } else {
            canvas.drawLine(cx + r, shackleTop + r, cx + r, bodyTop, iconStroke);
            Path p = new Path();
            p.addArc(arc, 180f, 180f);
            p.moveTo(cx - r, shackleTop + r); p.lineTo(cx - r, bodyTop);
            canvas.save();
            canvas.rotate(-42f, cx + r, shackleTop + r);
            canvas.drawPath(p, iconStroke);
            canvas.restore();
        }
    }
}
