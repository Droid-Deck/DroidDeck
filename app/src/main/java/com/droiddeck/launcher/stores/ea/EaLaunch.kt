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
        request.optJSONObject("steam")?.let { return prepareSteam(app, request, it) }
        val id = request.optString("id")
        val (folder, recorded) = installed(app, id) ?: return JSONObject().put("ok", false).put("reason", "not-installed")
        val sidecar = backfill(folder, recorded)
        runCatching { com.droiddeck.launcher.stores.StoreLaunch.refreshLauncher(folder, sidecar) }
        File(folder, ENV_FILE).delete()
        val known = (sidecar.extra["contentIds"].orEmpty().split(',') + sidecar.extra["multiplayerId"].orEmpty()).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val game = Started(folder, id, sidecar.extra["contentId"].orEmpty(), known, sidecar.extra["contentIds"].orEmpty().split(','),
            sidecar.title, sidecar.installVersion, sidecar.extra["locale"].orEmpty().ifEmpty { "en_US" }, sidecar.exe)
        val (reply, env) = start(app, game, request)
        if (env != null) {
            val tmp = File(folder, "$ENV_FILE.tmp")
            tmp.writeText(env.entries.joinToString("\r\n", postfix = "\r\n") { "${it.key}=${it.value}" })
            tmp.renameTo(File(folder, ENV_FILE))
        }
        return reply
    }

    private val OFFER = Regex("Origin\\.OFR\\.[0-9]+\\.[0-9]+")
    private val CONTENT = Regex("[A-Za-z0-9._-]{1,64}")

    /** Steam's copy of an EA game, which Steam would hand to the EA app; the env comes back in the reply. */
    private fun prepareSteam(app: Context, request: JSONObject, steam: JSONObject): JSONObject {
        val failed = JSONObject().put("ok", false)
        val root = com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(app).canonicalFile
        val folder = File(root, steam.optString("folder").removePrefix("/")).canonicalFile
        if (!folder.path.startsWith(root.path + File.separator) || !folder.isDirectory) return failed.put("reason", "no-folder")
        fun strings(name: String, pattern: Regex) = steam.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) } }
            .orEmpty().filter { pattern.matches(it) }.distinct()
        val offers = strings("offers", OFFER)
        val contents = strings("contentIds", CONTENT)
        if (offers.isEmpty() || contents.isEmpty()) return failed.put("reason", "no-offer")
        val game = Started(folder, offers.first(), contents.first(), contents.toSet(), contents, steam.optString("title").ifEmpty { folder.name },
            steam.optString("version"), steam.optString("locale").ifEmpty { "en_US" }, steam.optString("exe"))
        val (reply, env) = start(app, game, request)
        if (env != null) reply.put("id", game.offerId).put("env", JSONObject(env.toMap()))
        return reply
    }

    private class Started(
        val folder: File, val offerId: String, val contentId: String, val contentIds: Set<String>, val licenceIds: List<String>,
        val title: String, val version: String, val locale: String, val exe: String,
    )

    private fun start(app: Context, game: Started, request: JSONObject): Pair<JSONObject, LinkedHashMap<String, String>?> {
        val reply = JSONObject().put("ok", false)
        val id = game.offerId
        val account = EaAuth.load(app) ?: return reply.put("reason", "signed-out") to null
        val access = EaAuth.accessToken(app) ?: return reply.put("reason", "sign-in-expired") to null
        val contentId = game.contentId
        val locale = game.locale
        val core = EaOoa.coreDir(game.folder, game.exe)
        val hashVersion = core?.let(EaOoa::activationVersion) ?: 1
        val hardware = request.optJSONObject("hardware")?.let(EaOoa.Hardware::fromJson)?.takeIf { it.complete(hashVersion) }
        val legacy = EaOoa.legacyCore(core)

        val lifetime = if (legacy) 14400 else 550
        val opaque = EaAuth.mint(app, EaAuth.CLIENT_ID, "token", EaAuth.OPAQUE_SCOPES, "OPAQUE", lifetime).orEmpty()
        val lsx = EaLsx.start(app, EaLsx.Game(id, contentId, game.title, game.version, locale, hardware, hashVersion, opaque,
            System.currentTimeMillis() + (lifetime - 30) * 1000L, game.contentIds))
        val env = environment(id, contentId, locale, if (legacy && opaque.isNotEmpty()) opaque else access, access, opaque, EaAuth.load(app) ?: account)

        val licenses = JSONArray()
        val notes = ArrayList<String>()
        if (core != null) {
            val ids = (listOf(contentId) + game.licenceIds).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
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
                        com.droiddeck.launcher.stores.StoresState.logLine("ea licence $content for $id v$hashVersion legacy=$legacy: ${e.message}")
                        "$content:refused"
                    }
                }
            }
        }
        if (core != null && notes.isNotEmpty() && notes.none { it.endsWith(":ok") }) runCatching {
            val rows = EaApi.legacyEntitlements(access).filter { it.optString("productId") == id }
            val types = rows.joinToString(",") { it.optString("entitlementType") + "/" + it.optString("entitlementTag") + "/" + it.optString("status") }
            com.droiddeck.launcher.stores.StoresState.logLine("ea $id has no licence; entitlements: ${types.ifEmpty { "none" }}")
        }
        val licenseNote = if (core == null) "none-needed" else "v$hashVersion " + notes.joinToString(",")
        Log.i(TAG, "prepared $id lsx=$lsx legacy=$legacy opaque=${opaque.isNotEmpty()} licence=$licenseNote")
        return reply.put("ok", true).put("lsx", lsx).put("license", licenseNote).put("licenses", licenses) to env
    }
}

object EaSizes {
    fun size(context: Context, item: com.droiddeck.launcher.stores.CatalogItem): Long {
        val token = EaAuth.accessToken(context) ?: return 0L
        val url = runCatching { EaApi.downloadUrl(token, item.id) }.getOrNull() ?: return 0L
        return EaNative.call("zip.size", JSONObject().put("url", url))?.optLong("totalBytes") ?: 0L
    }
}
