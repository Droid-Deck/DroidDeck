package com.droiddeck.shortcuts;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.SparseArray;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class ShortcutsService extends AccessibilityService {
    private final CopyOnWriteArrayList<Link> links = new CopyOnWriteArrayList<>();
    private HandlerThread windowsThread;
    private Handler windows;
    private boolean watchingWindows;
    private boolean receiving;
    private final Runnable snapshot = this::sendWindows;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { bindAll(); }
    };

    private final class Link implements ServiceConnection {
        final ComponentName component;
        volatile IBinder binder;
        volatile int flags;

        Link(ComponentName component) { this.component = component; }

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            flags = config(service);
            if ((flags & Relay.WANTS_WINDOWS) != 0) watchWindows();
        }

        @Override public void onServiceDisconnected(ComponentName name) { binder = null; }

        @Override public void onBindingDied(ComponentName name) { drop(this); bindAll(); }

        @Override public void onNullBinding(ComponentName name) { drop(this); }
    }

    @Override
    protected void onServiceConnected() {
        windowsThread = new HandlerThread("shortcuts-windows");
        windowsThread.start();
        windows = new Handler(windowsThread.getLooper());
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packages, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packages, filter);
        receiving = true;
        bindAll();
    }

    private void bindAll() {
        List<ResolveInfo> found = getPackageManager().queryIntentServices(new Intent(Relay.ACTION), 0);
        for (ResolveInfo info : found) {
            // Keys and window contents only go to a relay signed with the same key as this companion.
            if (getPackageManager().checkSignatures(getPackageName(), info.serviceInfo.packageName)
                    != PackageManager.SIGNATURE_MATCH) continue;
            ComponentName component = new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name);
            boolean linked = false;
            for (Link link : links) if (link.component.equals(component)) linked = true;
            if (linked) continue;
            Link link = new Link(component);
            try {
                if (bindService(new Intent(Relay.ACTION).setComponent(component), link, Context.BIND_AUTO_CREATE)) links.add(link);
                else unbindService(link);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void drop(Link link) {
        links.remove(link);
        try { unbindService(link); } catch (RuntimeException ignored) {}
    }

    private static int config(IBinder binder) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Relay.DESCRIPTOR);
            binder.transact(Relay.CONFIG, data, reply, 0);
            return reply.readInt();
        } catch (RemoteException | RuntimeException e) {
            return 0;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void watchWindows() {
        if (Build.VERSION.SDK_INT < 30) return;
        if (!watchingWindows) {
            watchingWindows = true;
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes |= AccessibilityEvent.TYPE_WINDOWS_CHANGED | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                setServiceInfo(info);
            }
        }
        windows.removeCallbacks(snapshot);
        windows.post(snapshot);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        boolean taken = false;
        for (Link link : links) {
            IBinder binder = link.binder;
            if (binder == null) continue;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(Relay.DESCRIPTOR);
                event.writeToParcel(data, 0);
                binder.transact(Relay.KEY, data, reply, 0);
                if (reply.readInt() != 0) taken = true;
            } catch (RemoteException | RuntimeException ignored) {
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
        return taken;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!watchingWindows || event == null || Build.VERSION.SDK_INT < 30) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            int display = event.getDisplayId();
            if (pkg == null || display == Display.DEFAULT_DISPLAY) return;
            String name = pkg.toString();
            long at = event.getEventTime();
            sendAll(Relay.ARRIVAL, data -> {
                data.writeInt(display);
                data.writeString(name);
                data.writeLong(at);
            });
        } else if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        windows.removeCallbacks(snapshot);
        windows.postDelayed(snapshot, 150);
    }

    private void sendWindows() {
        if (Build.VERSION.SDK_INT < 30) return;
        SparseArray<List<AccessibilityWindowInfo>> all;
        try { all = getWindowsOnAllDisplays(); } catch (RuntimeException e) { return; }
        Map<Integer, List<Relay.Window>> displays = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            if (all.keyAt(i) == Display.DEFAULT_DISPLAY) continue;
            List<Relay.Window> windows = new ArrayList<>();
            for (AccessibilityWindowInfo window : all.valueAt(i)) {
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                windows.add(new Relay.Window(window.getId(), window.getType(), window.getLayer(), bounds, packageOf(window)));
            }
            displays.put(all.keyAt(i), windows);
        }
        byte[] bytes = Relay.encode(displays);
        sendAll(Relay.WINDOWS, data -> data.writeByteArray(bytes));
    }

    private static String packageOf(AccessibilityWindowInfo window) {
        try {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || root.getPackageName() == null) return null;
            return root.getPackageName().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private interface Writer { void write(Parcel data); }

    private void sendAll(int code, Writer writer) {
        for (Link link : links) {
            IBinder binder = link.binder;
            if (binder == null) continue;
            if (code != Relay.INTERRUPT && (link.flags & Relay.WANTS_WINDOWS) == 0) continue;
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(Relay.DESCRIPTOR);
                writer.write(data);
                binder.transact(code, data, null, IBinder.FLAG_ONEWAY);
            } catch (RemoteException | RuntimeException ignored) {
            } finally {
                data.recycle();
            }
        }
    }

    @Override
    public void onInterrupt() {
        sendAll(Relay.INTERRUPT, data -> {});
    }

    @Override
    public boolean onUnbind(Intent intent) {
        release();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }

    private void release() {
        if (receiving) {
            receiving = false;
            try { unregisterReceiver(packages); } catch (RuntimeException ignored) {}
        }
        for (Link link : links) drop(link);
        if (windowsThread != null) {
            windows.removeCallbacksAndMessages(null);
            windowsThread.quitSafely();
            windowsThread = null;
        }
    }
}
