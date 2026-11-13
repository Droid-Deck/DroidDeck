package com.droiddeck.launcher.stores.rockstar

import android.content.Context
import com.droiddeck.launcher.session.GameEnvironmentStore
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

object RockstarLaunch {
    internal val pipes = mapOf("gta3" to "GTA3_Pipe", "gtavc" to "GTA-VC_Pipe", "gtasa" to "GTA_SA_Pipe")

    fun supports(id: String?) = id != null && id in pipes

    fun applyDefaults(app: Context, id: String, appId: Long) {
        val scope = (appId and 0xffffffffL).toString()
        when (id) {
            "gta3" -> GameEnvironmentStore.setDefault(app, scope, "FEX_X87REDUCEDPRECISION", "0")
            "gtasa" -> GameEnvironmentStore.setDefault(app, scope, "PROTON_USE_WINED3D", "1")
        }
    }

    fun prepare(app: Context, id: String): JSONObject {
        val pipe = pipes[id] ?: return failure("unsupported-title")
        val title = RockstarTitles.byId(id) ?: return failure("unknown-title")
        val folder = StoreInstallRoot.gameFolders(app).firstOrNull { folder ->
            StoreGameSidecar.read(folder)?.let { it.store == Store.ROCKSTAR && it.id == id && it.isInstalled } == true
        } ?: return failure("not-installed")
        val installed = StoreGameSidecar.read(folder) ?: return failure("not-installed")
        val exe = installed.exeFile(folder).canonicalFile
        if (!exe.path.startsWith(folder.canonicalPath + java.io.File.separator) || !exe.isFile ||
            !java.io.File(folder, "MTLX.dll").isFile) return failure("missing-game-files")
        var ticket = RockstarAuth.ticket(app) ?: RockstarAuth.ticket(app, fresh = true) ?: return failure("signed-out")
        val account = try {
            RockstarApps.fetch(ticket.ticket) {}
        } catch (e: RockstarRos.RosException) {
            if (!(e.code.contains("Ticket", true) || e.code.contains("Sign", true) || e.code.contains("Auth", true))) throw e
            ticket = RockstarAuth.ticket(app, fresh = true) ?: return failure("signed-out")
            RockstarApps.fetch(ticket.ticket) {}
        }
        if (RockstarLibrary.owned(account).none { it.id == id } || RockstarApps.deliverer(account.apps, title) == null) return failure("not-owned")
        val tokenRoot = RockstarRos.result("app.asmx/GetTitleAccessToken2", listOf("ticket" to ticket.ticket, "titleId" to title.rosTitleId.toString(), "platformId" to "8"))
        val token = RockstarRos.text(tokenRoot, "Result")?.takeIf { it.isNotEmpty() } ?: return failure("no-title-token")
        val hash = MessageDigest.getInstance("SHA-256").digest(RockstarAuth.fingerprint(app).toString().toByteArray()).copyOf(16)
        val launch = RockstarRos.result("app.asmx/GetTitleLaunchData", listOf("ticket" to ticket.ticket, "titleAccessToken" to token,
            "machineHash" to Base64.getEncoder().encodeToString(hash)))
        val encoded = RockstarRos.text(launch, "Data") ?: return failure("no-launch-data")
        val iv = RockstarRos.text(launch, "EndpointIV") ?: return failure("no-launch-iv")
        val material = RockstarLaunchCipher.decode(encoded, iv)
        return JSONObject().put("ok", true).put("pipe", pipe).put("material", material).put("expiresAt", System.currentTimeMillis() + 90_000)
    }

    private fun failure(reason: String) = JSONObject().put("ok", false).put("reason", reason)
}
