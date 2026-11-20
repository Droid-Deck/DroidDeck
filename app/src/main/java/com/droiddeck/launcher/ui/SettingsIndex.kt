package com.droiddeck.launcher.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R

internal enum class SettingsSection(val key: String, @StringRes val title: Int) {
    OVERVIEW("overview", R.string.setup_tab_overview),
    PERFORMANCE("performance", R.string.setup_tab_performance),
    CONTROLLER("controller", R.string.setup_tab_controller),
    GLASSES("glasses", R.string.setup_tab_glasses),
    LAUNCHER("launcher", R.string.setup_tab_launcher),
    DEVELOPER("developer", R.string.setup_tab_diagnostics);

    companion object {
        fun of(key: String?): SettingsSection? = entries.firstOrNull { it.key == key }
    }
}

internal object RailRequest {
    var key by androidx.compose.runtime.mutableStateOf<String?>(null)
}

internal enum class SettingsPlace(@StringRes val title: Int) {
    SETTINGS(R.string.settings_place_settings),
    TAB(R.string.settings_place_tab),
    DRAWER(R.string.settings_place_drawer),
    SHEET(R.string.settings_place_sheet),
    STEAM_QAM(R.string.settings_place_steam_qam),
}

internal class SettingsEntry(
    val place: SettingsPlace,
    @StringRes val title: Int,
    @StringRes val group: Int? = null,
    val section: SettingsSection? = null,
    val words: List<String> = emptyList(),
    val developer: Boolean = false,
    val tab: String? = null,
) {
    val within: Int? get() = section?.title ?: tab?.let { railLabelIcon(it).first } ?: group

    fun path(context: Context): List<String> =
        listOfNotNull(context.getString(place.title), within?.let(context::getString), context.getString(title))

    fun matches(context: Context, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        val text = (path(context) + words).joinToString(" ").lowercase()
        return q.split(Regex("\\s+")).all { it in text }
    }
}

internal object SettingsIndex {
    private fun s(section: SettingsSection, @StringRes title: Int, vararg words: String) =
        SettingsEntry(SettingsPlace.SETTINGS, title, section = section, words = words.toList())

    private fun d(@StringRes group: Int, @StringRes title: Int, vararg words: String) =
        SettingsEntry(SettingsPlace.DRAWER, title, group = group, words = words.toList())

    private fun sh(@StringRes group: Int, @StringRes title: Int, vararg words: String) =
        SettingsEntry(SettingsPlace.SHEET, title, group = group, words = words.toList())

    val entries: List<SettingsEntry> = listOf(
        s(SettingsSection.OVERVIEW, R.string.setup_runtime, "ubuntu", "linux", "repair", "update"),
        s(SettingsSection.OVERVIEW, R.string.setup_limit, "phantom", "process", "killed", "background"),
        s(SettingsSection.OVERVIEW, R.string.setup_account, "steam", "login", "sign in"),

        s(SettingsSection.PERFORMANCE, R.string.perf_override, "steam client cores", "cpu"),
        s(SettingsSection.PERFORMANCE, R.string.perf_game_cores, "cpu", "pin", "cores", "affinity"),
        s(SettingsSection.PERFORMANCE, R.string.perf_pace_cores, "cpu"),
        s(SettingsSection.PERFORMANCE, R.string.perf_glthread, "opengl", "gl"),
        s(SettingsSection.PERFORMANCE, R.string.perf_zink),
        s(SettingsSection.PERFORMANCE, R.string.perf_noglerror),
        s(SettingsSection.PERFORMANCE, R.string.perf_gsrealtime, "gamescope"),
        s(SettingsSection.PERFORMANCE, R.string.perf_gpuclock, "gpu", "clock", "max"),
        s(SettingsSection.PERFORMANCE, R.string.setup_clear_cached, "memory", "ram", "crash", "sleep", "killed"),
        s(SettingsSection.PERFORMANCE, R.string.setup_bg_limit, "memory", "ram", "background apps"),
        s(SettingsSection.PERFORMANCE, R.string.setup_memory_hud, "memory", "ram", "overlay"),
        s(SettingsSection.PERFORMANCE, R.string.perf_sysmem),
        s(SettingsSection.PERFORMANCE, R.string.perf_xalia),
        s(SettingsSection.PERFORMANCE, R.string.perf_esync, "esync", "sync", "wine"),
        s(SettingsSection.PERFORMANCE, R.string.perf_fsync, "fsync", "sync", "wine"),
        s(SettingsSection.PERFORMANCE, R.string.perf_fastsync, "ntsync", "sync", "wine"),
        s(SettingsSection.PERFORMANCE, R.string.perf_seccomp, "proot"),
        s(SettingsSection.PERFORMANCE, R.string.perf_fastpath, "proot"),
        s(SettingsSection.PERFORMANCE, R.string.perf_native_userland, "proot"),
        s(SettingsSection.PERFORMANCE, R.string.perf_cursor_composite, "mouse", "cursor"),
        s(SettingsSection.PERFORMANCE, R.string.perf_hostname, "name"),

        s(SettingsSection.CONTROLLER, R.string.setup_tab_controller, "gamepad", "buttons", "mapping", "profile", "remap"),
        SettingsEntry(SettingsPlace.TAB, R.string.rail_storage, tab = "storage", words = listOf("sd card", "space", "uninstall", "disk")),
        SettingsEntry(SettingsPlace.TAB, R.string.rail_sync, tab = "sync", words = listOf("syncthing", "saves")),
        s(SettingsSection.GLASSES, R.string.setup_tab_glasses, "xreal", "viture", "vr", "ar"),

        s(SettingsSection.LAUNCHER, R.string.setup_theme, "color", "dark"),
        s(SettingsSection.LAUNCHER, R.string.setup_app_scale, "size", "zoom"),
        s(SettingsSection.LAUNCHER, R.string.setup_launcher_icon),
        s(SettingsSection.LAUNCHER, R.string.setup_animations),
        s(SettingsSection.LAUNCHER, R.string.setup_rail_labels, "sidebar"),
        s(SettingsSection.LAUNCHER, R.string.setup_language, "translate"),
        SettingsEntry(SettingsPlace.TAB, R.string.setup_stores_speed, tab = "downloads", words = listOf("download", "speed", "bandwidth", "slow")),
        SettingsEntry(SettingsPlace.TAB, R.string.stores_parallel, tab = "downloads", words = listOf("download", "parallel", "queue", "at once")),
        SettingsEntry(SettingsPlace.TAB, R.string.setup_group_tabs, tab = NavTabs.MORE, words = listOf("tabs", "hide", "reorder", "agent", "stores", "edit tabs")),
        s(SettingsSection.LAUNCHER, R.string.setup_home, "home screen", "launcher"),
        s(SettingsSection.LAUNCHER, R.string.setup_launcher_orientation, "rotate", "portrait"),
        s(SettingsSection.LAUNCHER, R.string.setup_session_orientation, "rotate", "portrait"),
        s(SettingsSection.LAUNCHER, R.string.setup_fullscreen),
        s(SettingsSection.LAUNCHER, R.string.setup_dual_screen_launcher, "thor", "second screen", "bottom screen"),
        s(SettingsSection.LAUNCHER, R.string.setup_low_storage_warning),
        s(SettingsSection.LAUNCHER, R.string.mode_sgdb_key, "artwork", "steamgriddb"),
        s(SettingsSection.LAUNCHER, R.string.setup_download_source, "mirror", "china"),
        s(SettingsSection.LAUNCHER, R.string.setup_download_proxy),

        s(SettingsSection.DEVELOPER, R.string.setup_latest_logs, "logs", "log", "bug", "report", "crash", "zip"),
        s(SettingsSection.DEVELOPER, R.string.setup_logs, "logs"),
        s(SettingsSection.DEVELOPER, R.string.setup_game_debug_logs, "logs", "wine", "proton"),
        SettingsEntry(SettingsPlace.SETTINGS, R.string.setup_enhanced_diagnostics, section = SettingsSection.DEVELOPER, words = listOf("logs"), developer = true),
        s(SettingsSection.DEVELOPER, R.string.setup_reset_settings, "default"),
        s(SettingsSection.DEVELOPER, R.string.dev_tools_title, "agent", "ai", "claude", "codex"),
        SettingsEntry(SettingsPlace.SETTINGS, R.string.setup_graphics_logs, section = SettingsSection.DEVELOPER, words = listOf("logs", "gpu"), developer = true),

        d(R.string.drawer_page_display, R.string.drawer_frame_limit, "fps", "cap", "limit", "non-steam", "desktop"),
        d(R.string.drawer_page_display, R.string.frame_gen_title, "lsfg", "lossless", "fps"),
        d(R.string.drawer_page_display, R.string.display_filter, "upscale", "fsr", "sharpness"),
        d(R.string.drawer_hud, R.string.drawer_hud, "fps", "counter", "overlay", "performance"),
        d(R.string.drawer_hud, R.string.perf_gpuclock, "gpu", "clock", "max"),
        d(R.string.drawer_page_controller, R.string.input_touchscreen, "touch", "mouse", "direct", "touchpad", "click"),
        d(R.string.drawer_page_controller, R.string.mode_osc, "on-screen", "controls", "touch controls", "buttons"),
        d(R.string.drawer_page_controller, R.string.osc_steam_layout, "touch layout", "edit layout", "on-screen"),
        d(R.string.drawer_page_controller, R.string.drawer_pc_keyboard, "keyboard", "typing"),
        d(R.string.mode_session, R.string.report_problem, "logs", "log", "bug", "crash", "share", "zip"),
        d(R.string.mode_session, R.string.mode_suspend, "sleep", "background", "native", "resume"),

        SettingsEntry(SettingsPlace.STEAM_QAM, R.string.drawer_frame_limit, R.string.perf_title, words = listOf("fps", "cap", "limit", "steam games")),
        SettingsEntry(SettingsPlace.STEAM_QAM, R.string.perf_title, words = listOf("performance overlay", "fps", "counter", "tdp")),

        sh(R.string.drawer_page_display, R.string.display_session, "resolution", "scaling", "hdr", "refresh"),
        sh(R.string.drawer_page_display, R.string.sheet_graphics_drivers, "turnip", "driver", "gpu", "adreno", "mali"),
        sh(R.string.mode_tab_steam, R.string.setup_offline, "offline", "steam"),
        sh(R.string.mode_audio, R.string.mode_mic, "microphone", "voice"),
        sh(R.string.mode_session, R.string.mode_suspend, "sleep", "background"),
        sh(R.string.stores_tab_library, R.string.stores_tab_library, "second library", "sd card", "steam library"),
    )

    fun search(context: Context, query: String): List<SettingsEntry> =
        entries.filter { (!it.developer || DeveloperTools.on) && it.matches(context, query) }
}
