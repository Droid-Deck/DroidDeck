package com.droiddeck.launcher.glasses

internal enum class HandModel(val asset: String, val sha256: String) {
    FULL("models/hand_landmarker.task", "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"),
    LITE("models/hand_landmarker_lite.task", "087f47d858105f9f25d7e88c4e318b988fb851f1aaa76069bb9a6b7ade5d0007")
}
