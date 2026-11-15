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
    internal val pipes = mapOf("gta3" to "GTA3_Pipe", "gtavc" to "GTA-VC_Pipe", "gtasa" to "GTA_SA_Pipe", "gta4" to "GTAIV_Pipe", "lanoire" to "LANOIRE_Pipe")

    fun usesClassicLauncher(id: String) = id in pipes

    fun applyDefaults(app: Context, id: String, appId: Long) {
        val scope = (appId and 0xffffffffL).toString()
        when (id) {
            "gta3" -> GameEnvironmentStore.setDefault(app, scope, "FEX_X87REDUCEDPRECISION", "0")
            "gtasa" -> GameEnvironmentStore.setDefault(app, scope, "PROTON_USE_WINED3D", "1")
        }
    }

    fun prepare(app: Context, id: String): JSONObject {
        val folder = StoreInstallRoot.gameFolders(app).firstOrNull { folder ->
            StoreGameSidecar.read(folder)?.let { it.store == Store.ROCKSTAR && it.id == id && it.isInstalled } == true
        } ?: return failure("not-installed")
        val installed = StoreGameSidecar.read(folder) ?: return failure("not-installed")
        val exe = installed.exeFile(folder).canonicalFile
        if (!exe.path.startsWith(folder.canonicalPath + java.io.File.separator) || !exe.isFile) return failure("missing-game-files")
        val metadata = RockstarMetadata.find(folder)
        val pipe = pipes[id]
        val sdk = (pipe == null && metadata != null) || id in setOf("gta4", "lanoire") || listOf(folder, exe.parentFile).any { dir ->
            dir.listFiles().orEmpty().any { it.isFile && it.name.lowercase() in setOf("socialclub.dll", "socialclub64.dll") }
        }
        if (pipe == null && !sdk) return failure("no-launcher-protocol")
        if (pipe != null && !listOf(folder, exe.parentFile).any { java.io.File(it, "MTLX.dll").isFile }) return failure("missing-game-files")
        val rosId = metadata?.rosId ?: RockstarTitles.byId(id)?.rosTitleId ?: return failure("missing-title-metadata")
        var ticket = RockstarAuth.ticket(app) ?: RockstarAuth.ticket(app, fresh = true) ?: return failure("signed-out")
        if (sdk && ticket.sdkTicket.isEmpty()) ticket = RockstarAuth.ticket(app, fresh = true) ?: return failure("signed-out")
        val account = try {
            RockstarApps.fetch(ticket.ticket) {}
        } catch (e: RockstarRos.RosException) {
            if (!(e.code.contains("Ticket", true) || e.code.contains("Sign", true) || e.code.contains("Auth", true))) throw e
            ticket = RockstarAuth.ticket(app, fresh = true) ?: return failure("signed-out")
            RockstarApps.fetch(ticket.ticket) {}
        }
        if (metadata != null && account.apps.none { it.titleId.toIntOrNull() == rosId && (it.name == metadata.id || it.name.startsWith(metadata.id + "_")) }) return failure("title-metadata-mismatch")
        val tokenRoot = RockstarRos.result("app.asmx/GetTitleAccessToken2", listOf("ticket" to ticket.ticket, "titleId" to rosId.toString(), "platformId" to "8"))
        val token = RockstarRos.text(tokenRoot, "Result")?.takeIf { it.isNotEmpty() } ?: return failure("no-title-token")
        val result = JSONObject().put("ok", true).put("expiresAt", System.currentTimeMillis() + 90_000)
        if (sdk) {
            val session = JSONObject().put("accountId", ticket.rockstarId).put("identity", ticket.email.ifEmpty { ticket.nickname }).put("ticket", ticket.sdkTicket)
            if (RockstarLibrary.owns(account, rosId, metadata?.id ?: id)) session.put("titleId", metadata?.id ?: id)
            result.put("session", session)
        }
        if (pipe == null) return result
        val hash = MessageDigest.getInstance("SHA-256").digest(RockstarAuth.fingerprint(app).toString().toByteArray()).copyOf(16)
        val launch = RockstarRos.result("app.asmx/GetTitleLaunchData", listOf("ticket" to ticket.ticket, "titleAccessToken" to token,
            "machineHash" to Base64.getEncoder().encodeToString(hash)))
        val encoded = RockstarRos.text(launch, "Data") ?: return failure("no-launch-data")
        val iv = RockstarRos.text(launch, "EndpointIV") ?: return failure("no-launch-iv")
        val material = RockstarLaunchCipher.decode(encoded, iv)
        return result.put("pipe", pipe).put("material", material)
    }

    private fun failure(reason: String) = JSONObject().put("ok", false).put("reason", reason)
}
