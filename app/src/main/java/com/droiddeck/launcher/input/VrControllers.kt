package com.droiddeck.launcher.input

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** What SteamVR sees for the game pad. The driver reads the file once, when SteamVR starts. */
object VrControllers {
    enum class Emulate(val key: String) { OFF("off"), INDEX("index"), TOUCH("touch"), VIVE("vive") }

    data class Settings(val emulate: Emulate = Emulate.INDEX, val sticksMoveHands: Boolean = false)

    const val FILE = "droiddeck-vr-controllers"
    private const val PREFS = "vr_controllers"

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private var loaded = false

    fun load(context: Context): Settings {
        if (!loaded) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _settings.value = Settings(
                emulate = Emulate.entries.firstOrNull { it.key == p.getString("emulate", null) } ?: Emulate.INDEX,
                sticksMoveHands = p.getBoolean("sticksMoveHands", false),
            )
            loaded = true
        }
        return _settings.value
    }

    fun setEmulate(context: Context, emulate: Emulate) = save(context, load(context).copy(emulate = emulate))
    fun setSticksMoveHands(context: Context, on: Boolean) = save(context, load(context).copy(sticksMoveHands = on))

    private fun save(context: Context, settings: Settings) {
        _settings.value = settings
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("emulate", settings.emulate.key)
            .putBoolean("sticksMoveHands", settings.sticksMoveHands)
            .apply()
        publish(context)
    }

    fun encode(settings: Settings): String =
        "emulate=${settings.emulate.key}\nsticks=${if (settings.sticksMoveHands) "hands" else "game"}\n"

    /** Writes into the session's runtime dir, which the guest sees as /run/droiddeck. */
    fun publish(context: Context) {
        runCatching {
            val dir = File(context.filesDir, ".wayland-rt").apply { mkdirs() }
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(encode(load(context)))
            tmp.renameTo(File(dir, FILE))
        }
    }
}
