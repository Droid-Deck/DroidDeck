package com.droiddeck.shortcuts;

import android.graphics.Rect;
import android.os.Parcel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Relay {
    public static final String PACKAGE = "com.droiddeck.shortcuts";
    public static final String SERVICE = PACKAGE + ".ShortcutsService";
    public static final String ACTION = "com.droiddeck.shortcuts.RELAY";
    public static final String DESCRIPTOR = "com.droiddeck.shortcuts.Relay";
    public static final int VERSION = 3;

    public static final int CONFIG = 1;
    public static final int KEY = 2;
    public static final int INTERRUPT = 3;
    public static final int ARRIVAL = 4;
    public static final int WINDOWS = 5;

    public static final int WANTS_WINDOWS = 1;

    public static final class Window {
        public final int id;
        public final int type;
        public final int layer;
        public final Rect bounds;
        public final String pkg;

        public Window(int id, int type, int layer, Rect bounds, String pkg) {
            this.id = id;
            this.type = type;
            this.layer = layer;
            this.bounds = bounds;
            this.pkg = pkg;
        }
    }

    public static byte[] encode(Map<Integer, List<Window>> displays) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInt(displays.size());
            for (Map.Entry<Integer, List<Window>> display : displays.entrySet()) {
                parcel.writeInt(display.getKey());
                parcel.writeInt(display.getValue().size());
                for (Window window : display.getValue()) {
                    parcel.writeInt(window.id);
                    parcel.writeInt(window.type);
                    parcel.writeInt(window.layer);
                    parcel.writeInt(window.bounds.left);
                    parcel.writeInt(window.bounds.top);
                    parcel.writeInt(window.bounds.right);
                    parcel.writeInt(window.bounds.bottom);
                    parcel.writeString(window.pkg);
                }
            }
            return parcel.marshall();
        } finally {
            parcel.recycle();
        }
    }

    public static Map<Integer, List<Window>> decode(byte[] bytes) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);
            Map<Integer, List<Window>> displays = new LinkedHashMap<>();
            for (int d = parcel.readInt(); d > 0; d--) {
                int display = parcel.readInt();
                List<Window> windows = new ArrayList<>();
                for (int w = parcel.readInt(); w > 0; w--) {
                    int id = parcel.readInt();
                    int type = parcel.readInt();
                    int layer = parcel.readInt();
                    Rect bounds = new Rect(parcel.readInt(), parcel.readInt(), parcel.readInt(), parcel.readInt());
                    windows.add(new Window(id, type, layer, bounds, parcel.readString()));
                }
                displays.put(display, windows);
            }
            return displays;
        } finally {
            parcel.recycle();
        }
    }

    private Relay() {}
}
