package com.droiddeck.launcher.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.io.File

/**
 * The agent bridge for devices whose shell has no `content` command: the same calls as
 * [AgentBridgeProvider], answered in a file under the app's external files folder.
 */
class AgentCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val method = intent.getStringExtra("method") ?: return
        val id = intent.getStringExtra("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: return
        val request = intent.getStringExtra(AgentBridgeProvider.EXTRA_REQUEST)
        val pending = goAsync()
        Thread({
            val json = try {
                val extras = Bundle().apply { putString(AgentBridgeProvider.EXTRA_REQUEST, request) }
                context.contentResolver.call("${context.packageName}.agent", method, null, extras)
                    ?.getString(AgentBridgeProvider.RESULT_JSON) ?: """{"ok":false,"error":{"code":"NO_RESPONSE","message":"no response"}}"""
            } catch (e: Exception) {
                org.json.JSONObject().put("ok", false)
                    .put("error", org.json.JSONObject().put("code", "COMMAND_FAILED").put("message", e.message ?: e.javaClass.simpleName)).toString()
            }
            try {
                val dir = File(context.getExternalFilesDir(null), "agent-calls").apply { mkdirs() }
                File(dir, "$id.json.part").writeText(json)
                File(dir, "$id.json.part").renameTo(File(dir, "$id.json"))
            } finally {
                pending.finish()
            }
        }, "agent-call-$id").start()
    }
}
