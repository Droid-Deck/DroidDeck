package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.handtracking.TrackingPack

internal enum class HandModel(val file: String, val sha256: String) {
    FULL(TrackingPack.HAND, "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"),
    LITE(TrackingPack.HAND_LITE, "173c0f47ecee7e935bd4a1e93aa580756b4180c1307c07d709313e82c28672c8");

    companion object {
        val DEFAULT = FULL
    }
}
