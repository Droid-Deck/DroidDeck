package com.droiddeck.phantomlab;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drive with: am start -n com.droiddeck.phantomlab/.Main --es cmd spawn --es target iso --ei n 40
 * Results go to logcat under the PLAB tag.
 */
public class Main extends Activity {
    static final String TAG = "PLAB";

    private static final Map<String, IBinder> binders = new HashMap<>();
    private static final Map<String, List<Runnable>> pending = new HashMap<>();
    private static final Map<String, ServiceConnection> connections = new HashMap<>();
    private TextView text;
    private static final List<FileDescriptor> keep = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        text = new TextView(this);
        setContentView(text);
        log("main pid " + Process.myPid() + " uid " + Process.myUid());
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void log(String s) {
        Log.i(TAG, s);
        text.append(s + "\n");
    }

    private static Class<?> serviceClass(String target) {
        String base = target.contains(":") ? target.substring(0, target.indexOf(':')) : target;
        switch (base) {
            case "iso": return Iso.class;
            case "isoplain": return IsoPlain.class;
            case "proc": return Proc.class;
            default:
                if (base.startsWith("w")) {
                    try {
                        return Class.forName("com.droiddeck.phantomlab.W" + base.substring(1));
                    } catch (ClassNotFoundException e) {
                        throw new IllegalArgumentException(target);
                    }
                }
                throw new IllegalArgumentException(target);
        }
    }

    private void withBinder(String target, Runnable op) {
        if (binders.containsKey(target)) {
            op.run();
            return;
        }
        List<Runnable> q = pending.get(target);
        if (q != null) {
            q.add(op);
            return;
        }
        q = new ArrayList<>();
        q.add(op);
        pending.put(target, q);
        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                log("connected " + target);
                binders.put(target, service);
                List<Runnable> ops = pending.remove(target);
                if (ops != null) for (Runnable r : ops) r.run();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                log("DISCONNECTED " + target);
                binders.remove(target);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                log("BINDING DIED " + target);
            }
        };
        connections.put(target, conn);
        Intent intent = new Intent(this, serviceClass(target));
        int flags = Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT;
        boolean ok;
        if (target.contains(":")) {
            ok = getApplicationContext().bindIsolatedService(intent, flags, target.replace(':', '_'), getMainExecutor(), conn);
        } else {
            ok = getApplicationContext().bindService(intent, conn, flags);
        }
        log("bind " + target + " -> " + ok);
    }

    private Parcel call(String target, int code, Parcel data) throws Exception {
        Parcel reply = Parcel.obtain();
        binders.get(target).transact(code, data, reply, 0);
        return reply;
    }

    private void handle(Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null) return;
        String target = intent.getStringExtra("target");
        try {
            switch (cmd) {
                case "spawn": {
                    int n = intent.getIntExtra("n", 10);
                    if ("main".equals(target)) {
                        log("main spawned " + Native.spawn(n));
                        return;
                    }
                    withBinder(target, () -> {
                        try {
                            Parcel d = Parcel.obtain();
                            d.writeInt(n);
                            log(target + " spawned " + call(target, LabService.SPAWN, d).readInt());
                        } catch (Exception e) {
                            log(target + " spawn failed " + e);
                        }
                    });
                    return;
                }
                case "who":
                    withBinder(target, () -> {
                        try {
                            log(target + " " + call(target, LabService.WHO, Parcel.obtain()).readString());
                        } catch (Exception e) {
                            log(target + " who failed " + e);
                        }
                    });
                    return;
                case "exec": {
                    String[] argv = intent.getStringExtra("argv").split("\\|");
                    boolean keepBinder = intent.getIntExtra("keep", 1) != 0;
                    withBinder(target, () -> {
                        try {
                            Parcel d = Parcel.obtain();
                            d.writeStringArray(argv);
                            d.writeInt(keepBinder ? 1 : 0);
                            log(target + " exec in pid " + call(target, LabService.EXEC, d).readInt());
                        } catch (Exception e) {
                            log(target + " exec failed " + e);
                        }
                    });
                    return;
                }
                case "probe": {
                    String what = intent.getStringExtra("what");
                    List<ParcelFileDescriptor> fds = probeFds(intent.getStringExtra("fds"));
                    withBinder(target, () -> {
                        try {
                            Parcel d = Parcel.obtain();
                            d.writeString(what);
                            d.writeInt(fds.size());
                            for (ParcelFileDescriptor p : fds) d.writeFileDescriptor(p.getFileDescriptor());
                            for (String line : call(target, LabService.PROBE, d).readString().split("\n")) log(target + " probe " + line);
                        } catch (Exception e) {
                            log(target + " probe failed " + e);
                        }
                    });
                    return;
                }
                case "unbind": {
                    ServiceConnection c = connections.remove(target);
                    binders.remove(target);
                    if (c != null) getApplicationContext().unbindService(c);
                    log("unbound " + target);
                    return;
                }
                default:
                    log("unknown cmd " + cmd);
            }
        } catch (Exception e) {
            log(cmd + " failed " + e);
        }
    }

    /** fds: comma separated list of tcp, udp, file:<name>, unix, memfd. */
    private List<ParcelFileDescriptor> probeFds(String spec) throws Exception {
        List<ParcelFileDescriptor> out = new ArrayList<>();
        if (spec == null || spec.isEmpty()) return out;
        for (String s : spec.split(",")) {
            if (s.equals("tcp") || s.equals("udp")) {
                FileDescriptor fd = Os.socket(OsConstants.AF_INET,
                        s.equals("tcp") ? OsConstants.SOCK_STREAM : OsConstants.SOCK_DGRAM, 0);
                keep.add(fd);
                out.add(ParcelFileDescriptor.dup(fd));
            } else if (s.equals("unix")) {
                FileDescriptor a = new FileDescriptor();
                FileDescriptor b = new FileDescriptor();
                Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0, a, b);
                Os.write(a, "hello from main\n".getBytes(), 0, 16);
                keep.add(a);
                out.add(ParcelFileDescriptor.dup(b));
            } else if (s.startsWith("file:")) {
                File f = new File(getFilesDir(), s.substring(5));
                if (!f.exists()) {
                    try (FileOutputStream o = new FileOutputStream(f)) {
                        o.write("data file contents\n".getBytes());
                    }
                }
                out.add(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE));
            } else if (s.startsWith("path:")) {
                out.add(ParcelFileDescriptor.open(new File(s.substring(5)), ParcelFileDescriptor.MODE_READ_ONLY));
            } else if (s.equals("dir")) {
                out.add(ParcelFileDescriptor.open(getFilesDir(), ParcelFileDescriptor.MODE_READ_ONLY));
            }
        }
        return out;
    }
}
