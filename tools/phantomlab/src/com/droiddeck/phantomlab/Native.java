package com.droiddeck.phantomlab;

final class Native {
    static {
        System.loadLibrary("lab");
    }

    /** Forks n children that sleep until killed; returns how many were started. */
    static native int spawn(int n);

    /** execve(argv) in this process; with keepBinder, the binder fds stay open across it. */
    static native void execInPlace(String[] argv, boolean keepBinder);

    /** Runs the capability probes named in what, using the given fds; returns a report. */
    static native String probe(String what, int[] fds);
}
