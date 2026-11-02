package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.frontend.AddedExes
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.AddedGamesPublisher
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreLaunch
import com.droiddeck.launcher.stores.SteamLiveShortcuts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object EpicClientLibrary {
    private const val TAG = "EpicClientLibrary"
    private const val V1 = "root/.local/share/Epic"
    private const val NO_GUID = "00000000000000000000000000000000"

    class Install(
        val appName: String,
        val namespace: String,
        val catalogItemId: String,
        val title: String,
        val version: String,
        val exe: String,
        val location: String,
        val folder: File,
        val deploymentId: String,
        val size: Long,
    ) {
        val signature: String get() = "$location|$version|$exe"
    }

    fun manifestsDir(context: Context): File = File(LinuxRuntime.rootDir(context), "$V1/EpicGamesLauncher/Data/Manifests")

    fun aggregateFile(context: Context): File = File(LinuxRuntime.rootDir(context), "$V1/UnrealEngineLauncher/LauncherInstalled.dat")

    fun installationsFile(context: Context): File = File(EpicClient.dataDir(context), "installations.jsonl")

    fun importMarker(context: Context): File = File(EpicClient.dataDir(context), "import-scan/imported.json")

    private fun ledgerFile(context: Context): File = File(EpicClient.stateDir(context), "registered.json")
    private fun importsFile(context: Context): File = File(EpicClient.stateDir(context), "client-imports.json")

    fun guid(appName: String): String =
        MessageDigest.getInstance("MD5").digest("droiddeck-epic:$appName".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    fun installs(context: Context): List<Install> {
        val app = context.applicationContext
        val prefs = EpicPrefs.get(app)
        return StoreInstallRoot.gameFolders(app).mapNotNull { folder ->
            val sidecar = StoreGameSidecar.read(folder) ?: return@mapNotNull null
            if (sidecar.store != Store.EPIC || !sidecar.isInstalled || sidecar.exe.isEmpty() ||
                sidecar.extra["clientManaged"] == "true") return@mapNotNull null
            val namespace = sidecar.extra["namespace"].orEmpty()
            val item = sidecar.extra["catalogItemId"].orEmpty()
            if (namespace.isEmpty() || item.isEmpty()) return@mapNotNull null
            val location = AddedGames.guestPath(app, folder) ?: return@mapNotNull null
            Install(
                sidecar.id, namespace, item, sidecar.title, sidecar.installVersion, sidecar.exe, location, folder,
                sidecar.extra["deploymentId"].orEmpty(), prefs.getLong("size_${sidecar.id}", 0L),
            )
        }.distinctBy { it.appName }
    }

    fun item(install: Install, tags: List<String>, ownership: Boolean): JSONObject {
        val guid = guid(install.appName)
        val store = "${install.location}/.egstore"
        return JSONObject()
            .put("FormatVersion", 0)
            .put("bIsIncompleteInstall", false)
            .put("LaunchCommand", "")
            .put("LaunchExecutable", install.exe)
            .put("ManifestLocation", store)
            .put("ManifestHash", "")
            .put("bIsApplication", true)
            .put("bIsExecutable", true)
            .put("bIsManaged", false)
            .put("bNeedsValidation", false)
            .put("bRequiresAuth", true)
            .put("bAllowMultipleInstances", false)
            .put("bCanRunOffline", false)
            .put("bAllowUriCmdArgs", false)
            .put("bLaunchElevated", false)
            .put("BaseURLs", JSONArray())
            .put("BuildLabel", "Live")
            .put("AppCategories", JSONArray(listOf("public", "games", "applications")))
            .put("ChunkDbs", JSONArray())
            .put("CompatibleApps", JSONArray())
            .put("DisplayName", install.title)
            .put("InstallationGuid", guid)
            .put("InstallLocation", install.location)
            .put("InstallSessionId", guid)
            .put("InstallTags", JSONArray(tags))
            .put("InstallComponents", JSONArray())
            .put("HostInstallationGuid", NO_GUID)
            .put("PrereqIds", JSONArray())
            .put("PrereqSHA1Hash", "")
            .put("LastPrereqSucceededSHA1Hash", "")
            .put("StagingLocation", "$store/bps")
            .put("TechnicalType", "games")
            .put("VaultThumbnailUrl", "")
            .put("VaultTitleText", "")
            .put("InstallSize", install.size)
            .put("MainWindowProcessName", "")
            .put("ProcessNames", JSONArray())
            .put("BackgroundProcessNames", JSONArray())
            .put("IgnoredProcessNames", JSONArray())
            .put("DlcProcessNames", JSONArray())
            .put("MandatoryAppFolderName", install.folder.name)
            .put("OwnershipToken", if (ownership) "true" else "false")
            .put("SidecarConfigRevision", 0)
            .put("SidecarDeploymentId", install.deploymentId)
            .put("CatalogNamespace", install.namespace)
            .put("CatalogItemId", install.catalogItemId)
            .put("AppName", install.appName)
            .put("AppVersionString", install.version)
            .put("MainGameCatalogNamespace", install.namespace)
            .put("MainGameCatalogItemId", install.catalogItemId)
            .put("MainGameAppName", install.appName)
            .put("AllowedUriEnvVars", JSONArray())
    }

    internal fun aggregateFromManifests(manifests: File, installs: List<Install>, previous: JSONObject? = null): JSONObject {
        val entries = linkedMapOf<String, JSONObject>()
        val old = previous?.optJSONArray("InstallationList")
        if (old != null) for (index in 0 until old.length()) {
            val entry = old.optJSONObject(index) ?: continue
            val appName = entry.optString("AppName").ifEmpty { entry.optString("ArtifactId") }
            if (appName.isNotEmpty()) entries[appName] = entry
        }
        manifests.listFiles { file -> file.isFile && file.extension == "item" }
            .orEmpty().sortedBy { it.name }.forEach { file ->
                val item = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@forEach
                if (item.optBoolean("bIsIncompleteInstall", true)) return@forEach
                val appName = item.optString("AppName")
                val location = item.optString("InstallLocation")
                val namespace = item.optString("CatalogNamespace")
                val catalogItem = item.optString("CatalogItemId")
                if (appName.isEmpty() || location.isEmpty() || namespace.isEmpty() || catalogItem.isEmpty()) return@forEach
                entries[appName] = JSONObject().put("InstallLocation", location).put("NamespaceId", namespace)
                    .put("ItemId", catalogItem).put("ArtifactId", appName).put("AppVersion", item.optString("AppVersionString"))
                    .put("AppName", appName)
            }
        installs.forEach { install ->
            entries[install.appName] = JSONObject().put("InstallLocation", install.location).put("NamespaceId", install.namespace)
                .put("ItemId", install.catalogItemId).put("ArtifactId", install.appName).put("AppVersion", install.version)
                .put("AppName", install.appName)
        }
        return JSONObject().put("InstallationList", JSONArray(entries.values.toList()))
    }

    internal fun executableRelative(installLocation: String, executable: String): String? {
        val location = installLocation.trimEnd('/')
        val path = executable.replace('\\', '/').trim()
        val raw = when {
            path.startsWith("$location/") -> path.removePrefix("$location/")
            path.startsWith('/') -> return null
            else -> path.removePrefix("./")
        }
        if (location.isEmpty() || !location.startsWith('/') || raw.isEmpty() ||
            !raw.endsWith(".exe", ignoreCase = true) || raw.split('/').any { it.isEmpty() || it == "." || it == ".." } || raw.contains(':')) return null
        return raw
    }

    private fun hostFolder(context: Context, guest: String): File? {
        if (!guest.startsWith('/') || guest.split('/').any { it == ".." }) return null
        val mounts = mutableListOf<Pair<String, String>>()
        SessionPrefs.gameFolderBinds(context).forEach { (host, path) -> mounts += path.trimEnd('/') to host }
        StoreInstallRoot.externalRoots(context).forEach { (host, path) -> mounts += path.trimEnd('/') to host.absolutePath }
        GameStorage.effective(context)?.let { mounts += "/mnt/droiddeck-sd" to it.path }
        SessionPrefs.romsDir(context).takeIf { it.isNotEmpty() }?.let { mounts += "/root/ROMs" to it }
        mounts.sortedByDescending { it.first.length }.forEach { (mount, host) ->
            if (guest == mount || guest.startsWith("$mount/")) {
                val suffix = guest.removePrefix(mount).trimStart('/')
                return File(host, suffix).takeIf { it.isDirectory && AddedGames.guestPath(context, it)?.trimEnd('/') == guest.trimEnd('/') }
            }
        }
        val root = LinuxRuntime.rootDir(context)
        if (guest == "/") return root
        return File(root, guest.removePrefix("/")).takeIf { it.isDirectory && AddedGames.guestPath(context, it)?.trimEnd('/') == guest.trimEnd('/') }
    }

    @Synchronized
    internal fun importClientGames(context: Context): Boolean {
        val app = context.applicationContext
        val manifests = manifestsDir(app)
        val before = runCatching { JSONObject(importsFile(app).readText()) }.getOrDefault(JSONObject())
        val after = JSONObject()
        val imported = ArrayList<AddedGames.Game>()
        val addedExes = AddedExes.list(app)
        var changed = false
        var scanComplete = true
        val items = LinkedHashMap<String, JSONObject>()
        manifests.listFiles { file -> file.isFile && file.extension == "item" }
            .orEmpty().sortedBy { it.name }.forEach { file ->
                val item = runCatching { JSONObject(file.readText()) }.getOrNull()
                if (item == null) scanComplete = false else items[item.optString("AppName").trim()] = item
            }
        val (records, readable) = clientInstallations(installationsFile(app))
        if (!readable) scanComplete = false
        if (records.isNotEmpty()) {
            val titles = runCatching { EpicLibrary.cached(app).associate { it.appName to it.title } }.getOrDefault(emptyMap())
            for (record in records) {
                val appName = record.optString("artifactId").trim()
                if (appName.isNotEmpty()) items[appName] = manifestOf(record, titles[appName])
            }
        }
        items.values.forEach { item ->
            val appName = item.optString("AppName").trim()
            if (item.optBoolean("bIsIncompleteInstall", true)) {
                before.optJSONObject(appName)?.let { after.put(appName, it) }
                return@forEach
            }
            val namespace = item.optString("CatalogNamespace").trim()
            val catalogItem = item.optString("CatalogItemId").trim()
            val location = item.optString("InstallLocation").trimEnd('/')
            val exe = executableRelative(location, item.optString("LaunchExecutable"))
            if (appName.isEmpty() || namespace.isEmpty() || catalogItem.isEmpty() || exe == null) {
                before.optJSONObject(appName)?.let { after.put(appName, it) }
                return@forEach
            }
            val folder = hostFolder(app, location)?.takeIf { it.isDirectory }
            if (folder == null) { before.optJSONObject(appName)?.let { after.put(appName, it) }; return@forEach }
            val executable = File(folder, exe)
            if (!executable.isFile || !runCatching { executable.canonicalPath.startsWith(folder.canonicalPath + File.separator) }.getOrDefault(false)) {
                before.optJSONObject(appName)?.let { after.put(appName, it) }
                return@forEach
            }
            val existing = StoreGameSidecar.read(folder)
            if (existing != null && (existing.store != Store.EPIC || existing.id != appName || existing.extra["clientManaged"] != "true")) {
                before.optJSONObject(appName)?.let { after.put(appName, it) }
                return@forEach
            }
            val previous = before.optJSONObject(appName)
            if (previous != null && previous.optString("folder") != folder.path) {
                val oldFolder = File(previous.optString("folder"))
                StoreGameSidecar.read(oldFolder)?.takeIf { it.store == Store.EPIC && it.extra["clientManaged"] == "true" }?.let {
                    StoreGameSidecar.file(oldFolder).delete()
                    File(oldFolder, StoreLaunch.LAUNCHER).delete()
                }
                AddedExes.drop(app, oldFolder.path)
                SessionPrefs.addedGameStoredAppId(app, oldFolder.path)?.let { SteamLiveShortcuts.remove(app, it) }
                changed = true
            }
            val next = withLaunchArgs(StoreGameSidecar(
                Store.EPIC, appName, item.optString("DisplayName").ifEmpty { appName }, exe,
                installVersion = item.optString("AppVersionString"), installedAt = existing?.installedAt?.takeIf { it > 0 } ?: folder.lastModified(),
                cover = existing?.cover, hero = existing?.hero,
                extra = mapOf("namespace" to namespace, "catalogItemId" to catalogItem,
                    "deploymentId" to item.optString("SidecarDeploymentId"), "clientManaged" to "true"),
                epic = existing?.epic ?: com.droiddeck.launcher.stores.EpicOptions(),
                cloud = existing?.cloud ?: true,
                launcher = StoreLaunch.LAUNCHER,
            ), commandArgs(item.optString("LaunchCommand")))
            val sidecarChanged = existing?.toJson()?.toString(2) != next.toJson().toString(2)
            val launcher = File(folder, StoreLaunch.LAUNCHER)
            if (sidecarChanged || runCatching { launcher.readText() }.getOrNull() != StoreLaunch.launcherText(next)) {
                StoreLaunch.writeLauncher(folder, next)
                next.write(folder)
            }
            if (addedExes.none { it.folder == folder.path && it.exe == executable.path && !it.picked }) {
                AddedExes.put(app, AddedExes.Entry(folder.path, executable.path, picked = false))
                changed = true
            }
            val game = AddedGames.single(app, folder.path)
            if (game != null) imported += game
            after.put(appName, JSONObject().put("folder", folder.path).put("exe", executable.path))
            if (sidecarChanged || before.optJSONObject(appName)?.optString("folder") != folder.path ||
                before.optJSONObject(appName)?.optString("exe") != executable.path ||
                existing?.installVersion != next.installVersion) changed = true
        }
        for (name in before.keys().asSequence().toList()) {
            if (after.has(name)) continue
            if (!scanComplete) { before.optJSONObject(name)?.let { after.put(name, it) }; continue }
            val record = before.optJSONObject(name) ?: continue
            val folder = File(record.optString("folder"))
            val sidecar = StoreGameSidecar.read(folder)
            if (sidecar?.store == Store.EPIC && sidecar.extra["clientManaged"] == "true") {
                StoreGameSidecar.file(folder).delete()
                File(folder, StoreLaunch.LAUNCHER).delete()
            }
            AddedExes.drop(app, folder.path)
            SessionPrefs.addedGameStoredAppId(app, folder.path)?.let { SteamLiveShortcuts.remove(app, it) }
            changed = true
        }
        if (changed) {
            write(importsFile(app), after.toString(2))
            AddedGamesPublisher.later(app) {
                SteamLiveShortcuts.sync(app, (imported + AddedGames.scan(app).filter { it.source == Store.EPIC.id }).distinctBy { it.guestExe }) { it.source == Store.EPIC.id }
                com.droiddeck.launcher.stores.StoresState.notifyLibraryChanged(app)
            }
        }
        return changed
    }

    internal fun clientInstallations(file: File): Pair<List<JSONObject>, Boolean> {
        if (!file.isFile) return emptyList<JSONObject>() to true
        val lines = runCatching { file.readLines() }.getOrNull() ?: return emptyList<JSONObject>() to false
        val latest = LinkedHashMap<String, JSONObject>()
        var readable = true
        for (line in lines) {
            if (line.isBlank()) continue
            val value = runCatching { JSONObject(line) }.getOrNull()
            if (value == null) { readable = false; continue }
            val key = value.optString("installationId").ifEmpty { value.optString("artifactId") }
            if (key.isEmpty()) continue
            latest.remove(key)
            if (!value.optBoolean("deleted")) latest[key] = value
        }
        return latest.values.toList() to readable
    }

    internal fun manifestOf(record: JSONObject, title: String?): JSONObject {
        val dir = record.optString("dir").trimEnd('/')
        val complete = record.optBoolean("complete", false) && record.optString("platform") == "Windows"
        return JSONObject()
            .put("AppName", record.optString("artifactId").trim())
            .put("CatalogNamespace", record.optString("namespace"))
            .put("CatalogItemId", record.optString("itemId"))
            .put("InstallLocation", dir)
            .put("LaunchExecutable", record.optString("launchExecutable"))
            .put("LaunchCommand", record.optString("launchCommand"))
            .put("AppVersionString", record.optString("version"))
            .put("SidecarDeploymentId", record.optJSONObject("sidecar")?.optString("deploymentId").orEmpty())
            .put("DisplayName", title?.takeIf(String::isNotBlank) ?: dir.substringAfterLast('/'))
            .put("bIsIncompleteInstall", !complete)
    }

    @Synchronized
    fun sync(context: Context): Boolean {
        val app = context.applicationContext
        if (!LinuxRuntime.isInstalled(app)) return false
        runCatching { importClientGames(app) }.onFailure { Log.w(TAG, "client library scan failed: ${it.javaClass.simpleName}") }
        val list = installs(app)
        val manifests = manifestsDir(app)
        val ledgerFile = ledgerFile(app)
        val before = runCatching { JSONObject(ledgerFile.readText()) }.getOrDefault(JSONObject())
        val after = JSONObject()
        val tags = runCatching { EpicInstallTags.tagsForDevice() }.getOrDefault(emptyList())
        val token by lazy { runCatching { EpicCredentialStore.getValidAccessToken(app) }.getOrNull() }
        var changed = false
        try {
            manifests.mkdirs()
            for (install in list) {
                val guid = guid(install.appName)
                val known = before.optJSONObject(install.appName)
                val ownership = known?.takeIf { it.has("ownership") }?.optBoolean("ownership")
                    ?: token?.let { ownershipToken(it, install) }
                if (known?.optString("signature") != install.signature) changed = true
                write(File(manifests, "$guid.item"), item(install, tags, ownership == true).toString(2))
                runCatching { egstore(install, guid) { token } }.onFailure { Log.w(TAG, "${install.appName}: .egstore not written (${it.javaClass.simpleName})") }
                after.put(install.appName, JSONObject().put("guid", guid).put("signature", install.signature).apply { if (ownership != null) put("ownership", ownership) })
                StoreGameSidecar.read(install.folder)?.let { sidecar ->
                    val next = runCatching { manifestArgs(install, guid) }.getOrNull()?.let { withLaunchArgs(sidecar, it) } ?: sidecar
                    if (next !== sidecar) next.write(install.folder)
                    StoreLaunch.refreshLauncher(install.folder, next)
                }
            }
            for (name in before.keys().asSequence().toList()) {
                if (after.has(name)) continue
                File(manifests, guid(name) + ".item").delete()
                changed = true
            }
            val aggregate = aggregateFile(app)
            val previous = runCatching { JSONObject(aggregate.readText()) }.getOrNull()
            write(aggregate, aggregateFromManifests(manifests, list, previous).toString(2))
            write(ledgerFile, after.toString(2))
            if (changed && importMarker(app).delete()) Log.i(TAG, "the launcher rescans installed games at its next start")
        } catch (e: Exception) {
            Log.w(TAG, "registration failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return changed
    }

    private const val LAUNCH_ARGS = "launchArgs"

    private fun manifestArgs(install: Install, guid: String): List<String>? {
        val manifest = File(install.folder, ".egstore/$guid.manifest").takeIf { it.isFile } ?: return null
        val launch = EpicNative.inspect(manifest.readBytes(), JSONObject().put("op", "launch")) ?: return null
        val build = launch.optString("buildVersion")
        if (install.version.isNotEmpty() && build != install.version) return null
        val args = launch.optJSONArray("args") ?: return null
        return List(args.length()) { args.optString(it) }.filter { it.isNotEmpty() }
    }

    private fun commandArgs(command: String): List<String> {
        if (command.isBlank()) return emptyList()
        val args = EpicNative.call("launch.split", JSONObject().put("command", command))?.optJSONArray("args") ?: return emptyList()
        return List(args.length()) { args.optString(it) }.filter { it.isNotEmpty() }
    }

    internal fun withLaunchArgs(sidecar: StoreGameSidecar, args: List<String>): StoreGameSidecar {
        val previous = sidecar.extra[LAUNCH_ARGS]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
        val kept = sidecar.args.filterNot { it in previous }
        val added = args.filterNot { it in kept }
        val next = kept + added
        val extra = LinkedHashMap(sidecar.extra).apply { if (added.isEmpty()) remove(LAUNCH_ARGS) else put(LAUNCH_ARGS, added.joinToString("\n")) }
        if (next == sidecar.args && extra == sidecar.extra) return sidecar
        return sidecar.copy(args = next, extra = extra)
    }

    private fun ownershipToken(token: String, install: Install): Boolean? =
        runCatching { EpicApiClient.getCustomAttribute(token, install.namespace, install.catalogItemId, "OwnershipToken") }
            .getOrNull()?.trim()?.lowercase()?.let { it == "true" }

    private fun egstore(install: Install, guid: String, token: () -> String?) {
        val dir = File(install.folder, ".egstore")
        dir.mkdirs()
        write(
            File(dir, "$guid.mancpn"),
            JSONObject().put("FormatVersion", 0).put("AppName", install.appName).put("CatalogItemId", install.catalogItemId)
                .put("CatalogNamespace", install.namespace).toString(2),
        )
        val manifest = File(dir, "$guid.manifest")
        if (manifest.isFile && manifest.length() > 0) return
        val access = token() ?: return
        val info = EpicApiClient.getManifestApiJson(access, install.namespace, install.catalogItemId, install.appName) ?: return
        val build = runCatching { JSONObject(info).optString("buildVersion") }.getOrDefault("")
        if (install.version.isNotEmpty() && build != install.version) return
        val bytes = EpicDownloadManager.downloadManifest(info, EpicDownloadManager.parseCdnUrls(info)) ?: return
        val tmp = File(dir, "$guid.manifest.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(manifest)) tmp.delete()
    }

    private fun write(file: File, text: String) {
        if (runCatching { file.readText() }.getOrNull() == text) return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw java.io.IOException("could not write ${file.name}") }
    }
}
