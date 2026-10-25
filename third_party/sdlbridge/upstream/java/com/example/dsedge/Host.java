package com.example.dsedge;

import android.content.Context;

/**
 * Where this bridge runs: as its own app (SDL3Bridge, package com.example.dsedge), or built into SDL3DroidDeck --
 * the same code, in that app's ":sdl3" process. Built in, its settings are kept under their own names next to
 * DroidDeck's, and it updates together with SDL3DroidDeck (no update check of its own).
 */
final class Host {
    static final String OWN_PACKAGE = "com.example.dsedge";

    private Host() {}

    static boolean embedded(Context c) {
        return c != null && !OWN_PACKAGE.equals(c.getPackageName());
    }

    /** A preferences file name of the bridge: unchanged as its own app, prefixed when built into another app. */
    static String prefs(Context c, String name) {
        return embedded(c) ? "sdl3bridge_" + name : name;
    }

    /** The app's name as Android shows it ("SDL3DroidDeck" when built in). */
    static String appName(Context c) {
        try {
            CharSequence label = c.getPackageManager().getApplicationLabel(c.getApplicationInfo());
            return label != null ? label.toString() : c.getPackageName();
        } catch (RuntimeException e) {
            return c.getPackageName();
        }
    }
}
