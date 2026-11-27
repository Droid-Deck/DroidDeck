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

import java.io.IOException;

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
                    ParcelFileDescriptor[] pfds = readFds(data);
                    try {
                        int[] fds = new int[pfds.length];
                        for (int i = 0; i < fds.length; i++) fds[i] = pfds[i] == null ? -1 : pfds[i].getFd();
                        reply.writeString(Native.probe(what, fds));
                    } finally {
                        closeAll(pfds);
                    }
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

    private static ParcelFileDescriptor[] readFds(Parcel data) {
        ParcelFileDescriptor[] pfds = new ParcelFileDescriptor[data.readInt()];
        try {
            for (int i = 0; i < pfds.length; i++) pfds[i] = data.readFileDescriptor();
        } catch (RuntimeException e) {
            closeAll(pfds);
            throw e;
        }
        return pfds;
    }

    private static void closeAll(ParcelFileDescriptor[] pfds) {
        for (ParcelFileDescriptor pfd : pfds) {
            if (pfd == null) continue;
            try {
                pfd.close();
            } catch (IOException e) {
                Log.w(Main.TAG, "close fd failed " + e);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(Main.TAG, getClass().getSimpleName() + " bound in pid " + Process.myPid() + " uid " + Process.myUid());
        return binder;
    }
}
