package com.example.dsedge;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

/**
 * Fallback host for SDL3, used only when SDL3 could not run in the background service with this build
 * (SdlEngine decides, and says why). SDL's own screen class starts SDL3 the classic way; it is opened only from the
 * bridge's screen while you look at it, never by itself.
 *
 * It stays out of sight: its window is transparent and SDL's drawing surface is shrunk to one pixel (the bridge
 * draws nothing). SDL3 starts once this screen is in front, then the bridge's screen is brought back on top of it
 * in the same task; SDL3 keeps running behind it, and when you leave SDL3Bridge (Home, Back, switching apps).
 */
public class SdlHostActivity extends SDLActivity {
    static volatile boolean running;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int waits;

    @Override protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override protected String[] getArguments() {
        String map = SdlEngine.mappingFile(this);
        return map != null ? new String[] { map } : new String[0];
    }

    @Override protected void onCreate(Bundle state) {
        running = true;
        NativeBridge.appContext = getApplicationContext();
        Settings.init(this);
        super.onCreate(state);
        if (mSurface != null) {   // SDL's drawing surface: the bridge draws nothing, keep it out of sight
            ViewGroup.LayoutParams lp = mSurface.getLayoutParams();
            if (lp != null) {
                lp.width = 1;
                lp.height = 1;
                mSurface.setLayoutParams(lp);
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        keepControllerClaimed();
        waits = 0;
        ui.removeCallbacks(handBack);
        ui.post(handBack);
    }

    /** As soon as SDL3's thread runs: the bridge's screen back on top (or, if it is gone, out of the way). */
    private final Runnable handBack = new Runnable() {
        @Override public void run() {
            if (isFinishing()) return;
            if (mSDLThread == null && ++waits < 60) {   // SDL starts once its surface is ready (~100 ms)
                ui.postDelayed(this, 50);
                return;
            }
            if (BridgeActivity.running) {
                startActivity(new Intent(SdlHostActivity.this, BridgeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            } else {
                moveTaskToBack(true);
            }
        }
    };

    @Override public void onBackPressed() {
        moveTaskToBack(true);   // closing this screen would stop SDL3
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(handBack);
        keepControllerClaimed();   // SDL releases the USB controller on pause; keep it while DroidDeck is in front
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        keepControllerClaimed();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(handBack);
        running = false;
        boolean forGood = !isChangingConfigurations();
        super.onDestroy();
        if (forGood) SdlEngine.onHostGone();
    }

    private static void keepControllerClaimed() {
        if (mHIDDeviceManager != null) mHIDDeviceManager.setFrozen(!Settings.driverEnabled);
    }

    static void setHidFrozen(boolean frozen) {
        if (mHIDDeviceManager != null) mHIDDeviceManager.setFrozen(frozen);
    }
}
