package com.droiddeck.launcher.stores.ea

import android.content.Context
import android.util.Base64
import android.util.Log
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object EaLaunch {
    private const val TAG = "EaLaunch"
    const val ENV_FILE = ".droiddeck-ea-env"

    private fun installed(context: Context, id: String): Pair<File, StoreGameSidecar>? =
        StoreInstallRoot.gameFolders(context).firstNotNullOfOrNull { f ->
            StoreGameSidecar.read(f)?.takeIf { it.store == Store.EA && it.id == id && it.isInstalled }?.let { f to it }
        }

    internal fun environment(
        offerId: String,
        contentId: String,
        locale: String,
        generic: String,
        access: String,
        opaque: String,
        account: EaAuth.Account,
        session: String = UUID.randomUUID().toString(),
    ): LinkedHashMap<String, String> = linkedMapOf(
        "EAAuthCode" to "unavailable",
        "EAEgsProxyIpcPort" to "0",
        "EAEntitlementSource" to "EA",
        "EAExternalSource" to "EA",
        "EAFreeTrialGame" to "false",
        "EAGameLocale" to locale,
        "EAGenericAuthToken" to generic,
        "EALaunchCode" to "unavailable",
        "EALaunchOwner" to "EA",
        "EALaunchEAID" to account.eaId.ifEmpty { account.displayName },
        "EALaunchEnv" to "production",
        "EALaunchOfflineMode" to "false",
        "EALsxPort" to EaLsx.PORT.toString(),
        "EARtPLaunchCode" to EaCrypto.rtpCode().toString(),
        "EASecureLaunchTokenTemp" to account.userId,
        "EASteamProxyIpcPort" to "0",
        "OriginSessionKey" to session,
        "ContentId" to contentId,
        "EAOnErrorExitRetCode" to "1",
        "EAConnectionId" to offerId,
        "EALicenseToken" to offerId,
        "EALaunchUserAuthToken" to opaque,
        "EAAccessTokenJWS" to access,
    )

    /** Adds what installs made before the manifest's content ids and title were recorded are missing. */
    internal fun backfill(folder: File, sidecar: StoreGameSidecar): StoreGameSidecar {
        if (sidecar.extra.containsKey("contentIds") || sidecar.extra.containsKey("displayName")) return sidecar
        val manifest = EaManifest.read(folder) ?: return sidecar
        val locale = sidecar.extra["locale"].orEmpty().ifEmpty { "en_US" }
        val added = mapOf(
            "contentIds" to manifest.contentIds.joinToString(","),
            "displayName" to manifest.title(locale).orEmpty(),
        ).filterValues { it.isNotEmpty() }
        if (added.isEmpty()) return sidecar
        val updated = sidecar.copy(extra = sidecar.extra + added)
        runCatching { updated.write(folder) }.onFailure { Log.w(TAG, "sidecar: ${it.javaClass.simpleName}") }
        return updated
    }

    /** Answers the guest's request right before an EA game starts: session, licence and the LSX endpoint. */
    fun prepare(context: Context, request: JSONObject): JSONObject {
        val app = context.applicationContext
        val id = request.optString("id")
        val reply = JSONObject().put("ok", false)
        val (folder, recorded) = installed(app, id) ?: return reply.put("reason", "not-installed")
        val sidecar = backfill(folder, recorded)
        File(folder, ENV_FILE).delete()
        val account = EaAuth.load(app) ?: return reply.put("reason", "signed-out")
        val access = EaAuth.accessToken(app) ?: return reply.put("reason", "sign-in-expired")
        val contentId = sidecar.extra["contentId"].orEmpty()
        val locale = sidecar.extra["locale"].orEmpty().ifEmpty { "en_US" }
        val core = EaOoa.coreDir(folder, sidecar.exe)
        val hashVersion = core?.let(EaOoa::activationVersion) ?: 1
        val hardware = request.optJSONObject("hardware")?.let(EaOoa.Hardware::fromJson)?.takeIf { it.complete(hashVersion) }
        val legacy = EaOoa.legacyCore(core)

        val opaque = EaAuth.mint(app, EaAuth.CLIENT_ID, "token", EaAuth.OPAQUE_SCOPES, "OPAQUE", if (legacy) 14400 else 550).orEmpty()
        val lsx = EaLsx.start(app, EaLsx.Game(id, contentId, sidecar.title, sidecar.installVersion, locale, hardware, hashVersion, opaque))
        val env = environment(id, contentId, locale, if (legacy && opaque.isNotEmpty()) opaque else access, access, opaque, EaAuth.load(app) ?: account)
        val tmp = File(folder, "$ENV_FILE.tmp")
        tmp.writeText(env.entries.joinToString("\r\n", postfix = "\r\n") { "${it.key}=${it.value}" })
        tmp.renameTo(File(folder, ENV_FILE))

        val licenses = JSONArray()
        val notes = ArrayList<String>()
        if (core != null) {
            val ids = (listOf(contentId) + sidecar.extra["contentIds"].orEmpty().split(',')).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (hardware == null) notes += "no-hardware" else {
                val hash = EaOoa.machineHash(hardware, hashVersion)
                for (content in ids) {
                    notes += try {
                        for ((path, data) in EaOoa.files(core, EaOoa.request(access, content, hash))) {
                            licenses.put(JSONObject().put("path", path).put("data", Base64.encodeToString(data, Base64.NO_WRAP)))
                        }
                        "$content:ok"
                    } catch (e: Exception) {
                        Log.w(TAG, "licence $content for $id: ${e.message}")
                        "$content:refused"
                    }
                }
            }
        }
        val licenseNote = if (core == null) "none-needed" else "v$hashVersion " + notes.joinToString(",")
        Log.i(TAG, "prepared $id lsx=$lsx legacy=$legacy opaque=${opaque.isNotEmpty()} licence=$licenseNote")
        return reply.put("ok", true).put("lsx", lsx).put("license", licenseNote).put("licenses", licenses)
    }
}

object EaSizes {
    fun size(context: Context, item: com.droiddeck.launcher.stores.CatalogItem): Long {
        val token = EaAuth.accessToken(context) ?: return 0L
        val url = runCatching { EaApi.downloadUrl(token, item.id) }.getOrNull() ?: return 0L
        return EaNative.call("zip.size", JSONObject().put("url", url))?.optLong("totalBytes") ?: 0L
    }
}
