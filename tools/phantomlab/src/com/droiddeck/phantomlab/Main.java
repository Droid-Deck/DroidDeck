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
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.widget.TextView;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drive with: am start -n com.droiddeck.phantomlab/.Main --es cmd spawn --es target iso --ei n 40
 * Results go to logcat under the PLAB tag.
 */
public class Main extends Activity {
    static final String TAG = "PLAB";

    private static final Map<String, IBinder> binders = new HashMap<>();
    private static final Map<String, List<Task>> pending = new HashMap<>();
    private static final Map<String, ServiceConnection> connections = new HashMap<>();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView text;

    private interface Op {
        void run(IBinder binder) throws Exception;
    }

    private final class Task {
        final String target;
        final String name;
        final Op op;
        final Closeable owned;

        Task(String target, String name, Op op, Closeable owned) {
            this.target = target;
            this.name = name;
            this.op = op;
            this.owned = owned;
        }

        void run(IBinder binder) {
            worker.execute(() -> {
                try {
                    op.run(binder);
                } catch (Exception e) {
                    log(target + " " + name + " failed " + e);
                } finally {
                    release();
                }
            });
        }

        void cancel() {
            log(target + " " + name + " cancelled");
            release();
        }

        private void release() {
            if (owned == null) return;
            try {
                owned.close();
            } catch (IOException e) {
                log(target + " " + name + " close failed " + e);
            }
        }
    }

    private static final class ProbeFds implements Closeable {
        final List<ParcelFileDescriptor> sent = new ArrayList<>();
        final List<FileDescriptor> owned = new ArrayList<>();

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (ParcelFileDescriptor p : sent) {
                try {
                    p.close();
                } catch (IOException e) {
                    failure = e;
                }
            }
            for (FileDescriptor fd : owned) {
                try {
                    Os.close(fd);
                } catch (ErrnoException e) {
                    failure = new IOException(e);
                }
            }
            sent.clear();
            owned.clear();
            if (failure != null) throw failure;
        }
    }

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
        runOnUiThread(() -> text.append(s + "\n"));
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

    private void withBinder(String target, String name, Op op, Closeable owned) {
        Task task = new Task(target, name, op, owned);
        IBinder binder = binders.get(target);
        if (binder != null) {
            task.run(binder);
            return;
        }
        List<Task> q = pending.get(target);
        if (q == null) {
            q = new ArrayList<>();
            pending.put(target, q);
        }
        q.add(task);
        if (!connections.containsKey(target)) bind(target);
    }

    private void bind(String target) {
        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                log("connected " + target);
                binders.put(target, service);
                List<Task> ops = pending.remove(target);
                if (ops != null) for (Task t : ops) t.run(service);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                log("DISCONNECTED " + target);
                binders.remove(target);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                log("BINDING DIED " + target);
                if (connections.get(target) != this) return;
                List<Task> ops = pending.remove(target);
                unbind(target);
                if (ops != null) {
                    pending.put(target, ops);
                    bind(target);
                }
            }
        };
        connections.put(target, conn);
        boolean ok = false;
        try {
            Intent intent = new Intent(this, serviceClass(target));
            int flags = Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT;
            if (target.contains(":")) {
                ok = getApplicationContext().bindIsolatedService(intent, flags, target.replace(':', '_'), getMainExecutor(), conn);
            } else {
                ok = getApplicationContext().bindService(intent, conn, flags);
            }
        } finally {
            log("bind " + target + " -> " + ok);
            if (!ok) unbind(target);
        }
    }

    private void unbind(String target) {
        ServiceConnection c = connections.remove(target);
        binders.remove(target);
        List<Task> q = pending.remove(target);
        if (q != null) for (Task t : q) t.cancel();
        if (c == null) return;
        try {
            getApplicationContext().unbindService(c);
        } catch (IllegalArgumentException e) {
            log("unbind " + target + " " + e);
        }
    }

    private static Parcel call(IBinder binder, int code, Parcel data) throws RemoteException {
        Parcel reply = Parcel.obtain();
        binder.transact(code, data, reply, 0);
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
                    withBinder(target, "spawn", b -> {
                        Parcel d = Parcel.obtain();
                        d.writeInt(n);
                        log(target + " spawned " + call(b, LabService.SPAWN, d).readInt());
                    }, null);
                    return;
                }
                case "who":
                    withBinder(target, "who", b -> log(target + " " + call(b, LabService.WHO, Parcel.obtain()).readString()), null);
                    return;
                case "exec": {
                    String[] argv = intent.getStringExtra("argv").split("\\|");
                    boolean keepBinder = intent.getIntExtra("keep", 1) != 0;
                    withBinder(target, "exec", b -> {
                        Parcel d = Parcel.obtain();
                        d.writeStringArray(argv);
                        d.writeInt(keepBinder ? 1 : 0);
                        log(target + " exec in pid " + call(b, LabService.EXEC, d).readInt());
                    }, null);
                    return;
                }
                case "probe": {
                    String what = intent.getStringExtra("what");
                    ProbeFds fds = probeFds(intent.getStringExtra("fds"));
                    withBinder(target, "probe", b -> {
                        Parcel d = Parcel.obtain();
                        d.writeString(what);
                        d.writeInt(fds.sent.size());
                        for (ParcelFileDescriptor p : fds.sent) d.writeFileDescriptor(p.getFileDescriptor());
                        for (String line : call(b, LabService.PROBE, d).readString().split("\n")) log(target + " probe " + line);
                    }, fds);
                    return;
                }
                case "unbind": {
                    unbind(target);
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
    private ProbeFds probeFds(String spec) throws Exception {
        ProbeFds fds = new ProbeFds();
        if (spec == null || spec.isEmpty()) return fds;
        try {
            for (String s : spec.split(",")) {
                if (s.equals("tcp") || s.equals("udp")) {
                    FileDescriptor fd = Os.socket(OsConstants.AF_INET,
                            s.equals("tcp") ? OsConstants.SOCK_STREAM : OsConstants.SOCK_DGRAM, 0);
                    fds.owned.add(fd);
                    fds.sent.add(ParcelFileDescriptor.dup(fd));
                } else if (s.equals("unix")) {
                    FileDescriptor a = new FileDescriptor();
                    FileDescriptor b = new FileDescriptor();
                    Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0, a, b);
                    fds.owned.add(a);
                    fds.owned.add(b);
                    Os.write(a, "hello from main\n".getBytes(), 0, 16);
                    fds.sent.add(ParcelFileDescriptor.dup(b));
                } else if (s.startsWith("file:")) {
                    File f = new File(getFilesDir(), s.substring(5));
                    if (!f.exists()) {
                        try (FileOutputStream o = new FileOutputStream(f)) {
                            o.write("data file contents\n".getBytes());
                        }
                    }
                    fds.sent.add(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE));
                } else if (s.startsWith("path:")) {
                    fds.sent.add(ParcelFileDescriptor.open(new File(s.substring(5)), ParcelFileDescriptor.MODE_READ_ONLY));
                } else if (s.equals("dir")) {
                    fds.sent.add(ParcelFileDescriptor.open(getFilesDir(), ParcelFileDescriptor.MODE_READ_ONLY));
                }
            }
        } catch (Exception e) {
            try {
                fds.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        return fds;
    }
}
