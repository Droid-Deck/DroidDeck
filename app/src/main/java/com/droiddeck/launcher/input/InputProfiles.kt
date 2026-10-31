package com.droiddeck.launcher.input

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object InputProfiles {
    const val AUTO = "auto"
    private const val VERSION = 1

    private fun prefs(context: Context) = context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE)

    fun selection(context: Context): String = prefs(context).getString("selected", AUTO)
        ?.takeIf { it in listOf(AUTO, InputProfile.GAME, InputProfile.DESKTOP) } ?: AUTO

    fun select(context: Context, id: String) {
        require(id in listOf(AUTO, InputProfile.GAME, InputProfile.DESKTOP))
        prefs(context).edit().putString("selected", id).apply()
    }

    fun active(context: Context, desktop: Boolean): InputProfile {
        val selected = selection(context)
        return read(context, if (selected != AUTO) selected else if (desktop) InputProfile.DESKTOP else InputProfile.GAME)
    }

    fun read(context: Context, id: String): InputProfile {
        migrate(context)
        var result = InputProfile.defaults(id)
        val stored = prefs(context).getString("profile.$id", null) ?: return result
        val document = runCatching { JSONObject(stored) }.getOrNull() ?: return result
        if (document.optInt("version") != VERSION) return result
        val bindings = document.optJSONArray("bindings") ?: return result
        for (i in 0 until minOf(bindings.length(), 256)) {
            val entry = bindings.optJSONObject(i) ?: continue
            val role = runCatching { InputRole.valueOf(entry.getString("role")) }.getOrNull() ?: continue
            val control = InputControl.fromId(entry.optString("control")) ?: continue
            val action = decode(entry.optJSONObject("action") ?: continue) ?: continue
            if (action.validFor(control)) result = result.withBinding(role, control, action)
        }
        val keys = document.optJSONArray("keys")
        if (keys != null) result = result.copy(keys = (0 until minOf(keys.length(), 64)).mapNotNull { i ->
            runCatching {
                val item = keys.getJSONObject(i)
                val action = decode(item.getJSONObject("action")) ?: return@mapNotNull null
                if (!action.validFor(InputControl.A) || action is InputAction.Native && action.target != null) return@mapNotNull null
                val key = PhysicalInputKey(item.getString("device"), item.getString("name"), item.getInt("keyCode"), item.getInt("scanCode"))
                if (key.device.isBlank() || key.keyCode < 0 || key.scanCode < 0) return@mapNotNull null
                PhysicalInputBinding(key, action)
            }.getOrNull()
        }.distinctBy { Triple(it.key.device, it.key.keyCode, it.key.scanCode) })
        return result
    }

    fun save(context: Context, profile: InputProfile) {
        val bindings = JSONArray()
        for ((role, controls) in profile.bindings) for ((control, action) in controls) {
            require(action.validFor(control))
            bindings.put(JSONObject().put("role", role.name).put("control", control.id).put("action", encode(action)))
        }
        val keys = JSONArray()
        for (binding in profile.keys) keys.put(JSONObject().put("device", binding.key.device).put("name", binding.key.name)
            .put("keyCode", binding.key.keyCode).put("scanCode", binding.key.scanCode).put("action", encode(binding.action)))
        prefs(context).edit().putString("profile.${profile.id}", JSONObject()
            .put("version", VERSION).put("id", profile.id).put("bindings", bindings).put("keys", keys).toString()).apply()
    }

    fun reset(context: Context, id: String) { prefs(context).edit().remove("profile.$id").apply() }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().putBoolean("migrated", true).apply()
        InputSurfaceLayout.resetAll(context)
    }

    private fun migrate(context: Context) {
        val p = prefs(context)
        if (p.getBoolean("migrated", false)) return
        var game = InputProfile.defaults(InputProfile.GAME)
        val old = ControllerPrefs.read(context)
        for ((id, target) in old.mapping) {
            val source = InputControl.fromId(id) ?: continue
            val action = if (target == ControllerPrefs.OFF) InputAction.Disabled
            else InputAction.Native(InputControl.fromId(target) ?: continue)
            if (target != id) game = game.withBinding(InputRole.PRIMARY, source, action)
        }
        if (old.selectSteam) game = game.withBinding(InputRole.PHYSICAL, InputControl.SELECT, InputAction.Native(InputControl.GUIDE))
        if (!p.contains("profile.${InputProfile.GAME}")) save(context, game)
        p.edit().putBoolean("migrated", true).apply()
    }

    private fun encode(action: InputAction): JSONObject = JSONObject().apply {
        when (action) {
            is InputAction.Native -> { put("type", "native"); action.target?.let { put("target", it.id) } }
            InputAction.Disabled -> put("type", "disabled")
            is InputAction.Keys -> { put("type", "keys"); put("codes", JSONArray(action.codes)) }
            is InputAction.MouseButton -> { put("type", "button"); put("code", action.code) }
            is InputAction.Command -> { put("type", "command"); put("command", action.command.name) }
            is InputAction.Directions -> { put("type", "directions"); put("codes", JSONArray(listOf(action.up, action.right, action.down, action.left))) }
            is InputAction.Pointer -> { put("type", "pointer"); put("speed", action.speed.toDouble()) }
            is InputAction.Scroll -> { put("type", "scroll"); put("speed", action.speed.toDouble()) }
        }
    }

    private fun decode(json: JSONObject): InputAction? = runCatching {
        when (json.getString("type")) {
            "native" -> InputAction.Native(if (json.has("target")) InputControl.fromId(json.getString("target")) ?: return null else null)
            "disabled" -> InputAction.Disabled
            "keys" -> InputAction.Keys(json.getJSONArray("codes").let { a -> (0 until a.length()).map { a.getInt(it) } })
            "button" -> InputAction.MouseButton(json.getInt("code"))
            "command" -> InputAction.Command(InputCommand.valueOf(json.getString("command")))
            "directions" -> json.getJSONArray("codes").let { InputAction.Directions(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)) }
            "pointer" -> InputAction.Pointer(json.getDouble("speed").toFloat())
            "scroll" -> InputAction.Scroll(json.getDouble("speed").toFloat())
            else -> null
        }
    }.getOrNull()
}
