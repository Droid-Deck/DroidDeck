package com.samsung.sdk.sperf;

/**
 * Entry points of libperfsdk.performance.samsung.so, the performance library in Samsung's firmware.
 * The library binds them by this class name, so the name has to stay. Used by
 * com.droiddeck.launcher.session.SamsungPerf; on other phones the library is missing.
 */
public final class Boost {
    private Boost() {
    }

    /** Holds a resource (CPU_MIN, GPU_MIN...) at a value from the service's level table. */
    public static native int requestBoost(int type, int value, int timeoutMs);

    /** Lets go of everything this app holds. */
    public static native int releaseBoost();

    /** The package name the service gives out; the library refuses requests until it is set. */
    public static native int setPkgName(String name);
}
