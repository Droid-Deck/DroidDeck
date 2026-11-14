package com.droiddeck.launcher.input;

final class ImuModes {
    private ImuModes() {}

    // Steam and its Remote Play client each keep their own mode word, with the top bit set once written. Motion is
    // read while either process wants it, and a word that has not been set leaves the choice to the other one.
    // -1 means neither has set a mode yet.
    static int combine(int steam, int remote, boolean remoteRunning) {
        boolean remoteSet = remote < 0 && remoteRunning;
        if (steam >= 0 && !remoteSet) return -1;
        return ((steam < 0 ? steam : 0) | (remoteSet ? remote : 0)) & 0xffff;
    }
}
