package com.droiddeck.launcher.session

internal object SessionAchievementGame {
    fun activeAppId(focused: Long?, windows: List<Long>, installed: Set<Long>): Long? {
        if (focused != null && focused > 0 && focused != 769L) {
            return focused.takeIf { it in windows && it in installed }
        }
        return windows.filter { it != 769L && it in installed }.distinct().singleOrNull()
    }
}
