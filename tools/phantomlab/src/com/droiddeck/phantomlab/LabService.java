package com.droiddeck.phantomlab;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

public class LabService extends Service {
    static final int SPAWN = 1;
    static final int PROBE = 2;
    static final int EXEC = 3;
    static final int WHO = 4;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            switch (code) {
                case SPAWN: {
                    int n = data.readInt();
                    reply.writeInt(Native.spawn(n));
                    return true;
                }
                case PROBE: {
                    String what = data.readString().replace("libexec",
                            "libexec=" + getApplicationInfo().nativeLibraryDir + "/libplabexe.so");
                    int[] fds = readFds(data);
                    reply.writeString(Native.probe(what, fds));
                    return true;
                }
                case EXEC: {
                    final String[] argv = data.createStringArray();
                    final boolean keep = data.readInt() != 0;
                    Log.i(Main.TAG, "pid " + Process.myPid() + " execs " + String.join(" ", argv) + " keepBinder=" + keep);
                    handler.postDelayed(() -> Native.execInPlace(argv, keep), 500);
                    reply.writeInt(Process.myPid());
                    return true;
                }
                case WHO:
                    reply.writeString("pid=" + Process.myPid() + " uid=" + Process.myUid()
                            + " isolated=" + Process.isIsolated());
                    return true;
                default:
                    return false;
            }
        }
    };

    private static int[] readFds(Parcel data) {
        int n = data.readInt();
        int[] fds = new int[n];
        for (int i = 0; i < n; i++) {
            ParcelFileDescriptor pfd = data.readFileDescriptor();
            fds[i] = pfd == null ? -1 : pfd.detachFd();
        }
        return fds;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(Main.TAG, getClass().getSimpleName() + " bound in pid " + Process.myPid() + " uid " + Process.myUid());
        return binder;
    }
}
