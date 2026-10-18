package com.droiddeck.launcher.glasses

internal enum class HandModel(val asset: String, val sha256: String) {
    FULL("models/hand_landmarker.task", "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"),
    LITE("models/hand_landmarker_lite.task", "173c0f47ecee7e935bd4a1e93aa580756b4180c1307c07d709313e82c28672c8");

    companion object {
        val DEFAULT = FULL
    }
}
