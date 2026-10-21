package com.droiddeck.launcher.session

internal object ThreadSignals {
    init {
        System.loadLibrary("sdnative")
    }

    @JvmStatic external fun tgkill(tgid: Int, tid: Int, signal: Int): Int
}
