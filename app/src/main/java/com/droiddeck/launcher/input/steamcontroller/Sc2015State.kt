package com.droiddeck.launcher.input.steamcontroller

class Sc2015State {
    var buttons: Int = 0
    var leftTrigger: Int = 0
    var rightTrigger: Int = 0
    var stickX: Short = 0
    var stickY: Short = 0
    var leftPadX: Short = 0
    var leftPadY: Short = 0
    var rightPadX: Short = 0
    var rightPadY: Short = 0
    var accelX: Short = 0
    var accelY: Short = 0
    var accelZ: Short = 0
    var gyroX: Short = 0
    var gyroY: Short = 0
    var gyroZ: Short = 0
    var quatW: Short = 0
    var quatX: Short = 0
    var quatY: Short = 0
    var quatZ: Short = 0
    var batteryVoltageMv: Int = 3000
    var batteryPercent: Int = 100
    var leftPadSeenMs: Long = 0
    var rightPadSeenMs: Long = 0
    var leftPadStale: Boolean = false
    var rightPadStale: Boolean = false

    fun reset() {
        buttons = 0
        leftTrigger = 0
        rightTrigger = 0
        stickX = 0
        stickY = 0
        leftPadX = 0
        leftPadY = 0
        rightPadX = 0
        rightPadY = 0
        accelX = 0
        accelY = 0
        accelZ = 0
        gyroX = 0
        gyroY = 0
        gyroZ = 0
        quatW = 0
        quatX = 0
        quatY = 0
        quatZ = 0
        leftPadSeenMs = 0
        rightPadSeenMs = 0
        leftPadStale = false
        rightPadStale = false
    }
}
