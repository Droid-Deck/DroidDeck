package com.droiddeck.launcher.session

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.core.TextureFiltering
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import java.io.File

object GameEnvironmentStore {
    private const val TAG = "GameEnvironmentStore"
    private const val SETTINGS = "game-environment.json"
    private const val GUEST_FILE = "root/.config/droiddeck/game-environment.json"
    private const val GAME_CORES_FILE = "root/.config/droiddeck/game-cores"
    const val DXVK_CONFIG = "dxvkConfig"
    const val DXVK_CONFIG_GAMES = "dxvkConfigGames"
    const val GAME_DEBUG_LOGS = "gameDebugLogs"

    @Synchronized
    fun read(context: Context): GameEnvironment.Config {
        val file = AtomicFile(File(context.filesDir, SETTINGS))
        val bytes = try { file.readFully() } catch (_: java.io.FileNotFoundException) { return GameEnvironment.Config() }
        return try {
            decode(JSONObject(bytes.toString(Charsets.UTF_8)))
        } catch (error: Exception) {
            if (error !is org.json.JSONException && error !is IllegalArgumentException) throw error
            val aside = File(context.filesDir, "$SETTINGS.unreadable")
            Log.w(TAG, "game environment settings are unreadable, set aside as ${aside.name}", error)
            aside.delete()
            file.baseFile.renameTo(aside)
            GameEnvironment.Config()
        }
    }

    fun decode(json: JSONObject): GameEnvironment.Config {
        require(json.getInt("version") == 1)
        fun entries(value: JSONObject?): Map<String, String?> = buildMap {
            value?.keys()?.forEach { name ->
                require(GameEnvironment.validName(name))
                val entry = value.get(name)
                require(entry == JSONObject.NULL || entry is String && GameEnvironment.validValue(entry))
                val current = GameEnvironment.currentName(name)
                if (current == name || !value.has(current)) put(current, if (entry == JSONObject.NULL) null else entry as String)
            }
        }
        val games = json.optJSONObject("games")
        return GameEnvironment.Config(entries(json.optJSONObject("shared")), buildMap {
            games?.keys()?.forEach { id ->
                require(id.isNotEmpty() && GameEnvironment.validScope(id))
                put(id, entries(games.getJSONObject(id)))
            }
        })
    }

    fun encode(config: GameEnvironment.Config): JSONObject {
        fun entries(values: Map<String, String?>) = JSONObject().apply {
            values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
        return JSONObject().put("version", 1).put("shared", entries(config.shared)).put("games", JSONObject().apply {
            config.games.forEach { (id, values) -> put(id, entries(values)) }
        })
    }

    @Synchronized
    fun save(context: Context, config: GameEnvironment.Config) {
        val normalized = decode(encode(config))
        write(File(context.filesDir, SETTINGS), encode(normalized).toString())
        publish(context, normalized)
    }

    @Synchronized
    fun setGameFexPreset(context: Context, scope: String, preset: String?): GameEnvironment.Config {
        val config = GameEnvironment.withGameFexPreset(read(context), scope, preset)
        save(context, config)
        return config
    }

    @Synchronized
    fun setGameSync(context: Context, scope: String, backend: String?): GameEnvironment.Config {
        val config = GameEnvironment.withGameSync(read(context), scope, backend)
        save(context, config)
        return config
    }

    @Synchronized
    fun setGameNoSteam(context: Context, scope: String, on: Boolean): GameEnvironment.Config {
        val config = GameEnvironment.withGameNoSteam(read(context), scope, on)
        save(context, config)
        return config
    }

    @Synchronized
    fun setGameResolution(context: Context, scope: String, choice: com.droiddeck.launcher.core.GameResolution.Choice?): GameEnvironment.Config {
        val config = GameEnvironment.withGameResolution(read(context), scope, choice)
        save(context, config)
        return config
    }

    @Synchronized
    fun setDefault(context: Context, scope: String, name: String, value: String): Boolean {
        val config = read(context)
        if (GameEnvironment.hasOverride(config, scope, name)) return false
        save(context, GameEnvironment.withScopeValue(config, scope, name, value))
        return true
    }

    @Synchronized
    fun publish(
        context: Context,
        config: GameEnvironment.Config = read(context),
        gameDebugLogsOverride: Boolean? = null,
    ) {
        publishGameCores(context)
        val resolved = config.copy(shared = GameEnvironment.defaults(SessionPrefs.fexPreset(context), SessionPrefs.forceSsbs(context)) + config.shared)
        val json = encode(resolved)
        val dxvk = TextureFiltering.dxvkOptions(
            SessionPrefs.textureAnisotropy(context), SessionPrefs.textureLodBias(context),
            TextureFiltering.autoLodBias(SessionState.upscaleRatio),
        )
        if (dxvk.isNotEmpty()) json.put(DXVK_CONFIG, dxvk)
        val autoBias = TextureFiltering.autoLodBias(SessionState.upscaleRatio)
        val generalAnisotropy = SessionPrefs.textureAnisotropy(context)
        val generalLodBias = SessionPrefs.textureLodBias(context)
        val gameDxvk = JSONObject()
        SessionPrefs.gameTextureFiltering(context).forEach { (scope, choice) ->
            gameDxvk.put(
                scope,
                TextureFiltering.dxvkOptions(
                    choice.anisotropy ?: generalAnisotropy,
                    choice.lodBias ?: generalLodBias,
                    autoBias,
                ),
            )
        }
        if (gameDxvk.length() > 0) json.put(DXVK_CONFIG_GAMES, gameDxvk)
        json.put(
            GAME_DEBUG_LOGS,
            gameDebugLogsOverride ?: SessionPrefs.gameDebugLogsEffective(context),
        )
        write(File(LinuxRuntime.rootDir(context), GUEST_FILE), json.toString())
    }

    private fun publishGameCores(context: Context) {
        val file = File(LinuxRuntime.rootDir(context), GAME_CORES_FILE)
        try {
            write(file, com.droiddeck.launcher.core.CpuCores.restrictionOrEmpty(SessionPrefs.gameCpus(context)) + "\n")
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun write(file: File, text: String) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
