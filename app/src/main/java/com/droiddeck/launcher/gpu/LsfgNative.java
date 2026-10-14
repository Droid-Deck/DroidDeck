package com.droiddeck.launcher.gpu;

final class LsfgNative {
    static { System.loadLibrary("droiddeckwayland"); }

    private LsfgNative() {}

    static final int STATUS_OK = 0;
    static final int STATUS_NOT_INSTALLED = 1;
    static final int STATUS_UNREADABLE_FILE = 2;
    static final int STATUS_NOT_PORTABLE_EXECUTABLE = 3;
    static final int STATUS_MISSING_SHADERS = 4;
    static final int STATUS_TRANSLATION_FAILED = 5;
    static final int STATUS_CACHE_UNUSABLE = 6;

    static final int VARIANT_NONE = 0;

    static native int nativeValidateDll(String dllPath);
    static native int nativeBuildCache(String dllPath, String cachePath, boolean preferFp16);
    static native int nativeCacheVariant(String cachePath);
    static native String nativeStatusName(int status);
    static native String nativeVariantName(int variant);
}
