package com.example.dsedge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Receives "controller plugged in" from Android. Accepting Android's prompt gives the bridge USB access to the
 * controller; nothing needs to be shown for that. The bridge's background service (with SDL3 in it) is started
 * if it isn't running yet, and picks the controller up; no screen opens.
 */
public class UsbAttachActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The bridge runs in its background service (SDL3 included): start that, nothing comes to the front.
        try {
            startForegroundService(new Intent(this, BridgeService.class));
        } catch (RuntimeException ignored) { }
        finish();
        overridePendingTransition(0, 0);
    }
}
