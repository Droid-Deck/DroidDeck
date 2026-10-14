package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.StoreGameSidecar

object EpicLaunchData {
    private const val TAG = "EpicLaunch"

    fun arguments(context: Context, token: String?, appName: String, namespace: String, catalogItemId: String, deploymentId: String): List<String> {
        val creds = EpicCredentialStore.load(context)
        val displayName = creds?.displayName?.takeIf { it.isNotEmpty() } ?: "EpicUser"
        val accountId = creds?.accountId?.takeIf { it.isNotEmpty() } ?: "0"
        val args = ArrayList<String>()
        args += "-EpicPortal"
        args += "-epicusername=\"${sanitize(displayName).replace("\"", "")}\""
        args += "-epicuserid=${sanitize(accountId)}"
        args += "-epicsandboxid=${sanitize(namespace)}"
        args += "-epiclocale=${EpicInstallTags.localeCodeForDevice()}"
        args += "-epicapp=${sanitize(appName)}"
        args += "-epicenv=Prod"
        if (deploymentId.isNotEmpty()) args += "-epicdeploymentid=${sanitize(deploymentId)}"
        if (token != null && catalogItemId.isNotEmpty()) {
            val extra = EpicApiClient.getAdditionalCommandLine(token, namespace, catalogItemId)
            if (!extra.isNullOrBlank()) {
                val clean = extra.filter { it >= ' ' && it != '\u007f' }.trim()
                if (clean.isNotEmpty()) { args += clean; Log.i(TAG, "$appName: extra command line present") }
            }
        }
        return args
    }

    fun extras(appName: String, namespace: String, catalogItemId: String, deploymentId: String): Map<String, String> = buildMap {
        put("appName", appName); put("namespace", namespace); put("catalogItemId", catalogItemId)
        if (deploymentId.isNotEmpty()) put("deploymentId", deploymentId)
    }

    fun wantsExchangeCode(sidecar: StoreGameSidecar): Boolean = sidecar.store == com.droiddeck.launcher.stores.Store.EPIC

    private fun sanitize(s: String): String = s.replace("\n", "").replace("\r", "").replace("\u0000", "")
}
