package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.StoreGameSidecar

/**
 * What an Epic game's launch line needs beyond the exe, built once at install time into the
 * sidecar's arguments: the portal and identity arguments every EOS title expects
 * (`-EpicPortal -epicusername -epicuserid -epicsandboxid -epiclocale -epicapp -epicenv=Prod`), the
 * deployment id when the app has an EOS sidecar, and the extra flags Epic ships for the title
 * (`AdditionalCommandLine`). The sign-in triple (`-AUTH_LOGIN/-AUTH_PASSWORD/-AUTH_TYPE`) is not
 * here: the exchange code lives minutes, so the launcher .bat takes it from a file the app writes
 * right before a launch (StoreLaunch.prepare).
 *
 * The EOS overlay is never provisioned: nothing points the game's EOS SDK at an overlay component.
 */
object EpicLaunchData {
    private const val TAG = "EpicLaunch"

    /** The static launch arguments for [appName] in [namespace]. Blocking: one catalog call for the extra flags. */
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

    /** The sidecar's identifiers the launcher reads back. */
    fun extras(appName: String, namespace: String, catalogItemId: String, deploymentId: String): Map<String, String> = buildMap {
        put("appName", appName); put("namespace", namespace); put("catalogItemId", catalogItemId)
        if (deploymentId.isNotEmpty()) put("deploymentId", deploymentId)
    }

    /** True when [sidecar] is an Epic install that can take a sign-in code. */
    fun wantsExchangeCode(sidecar: StoreGameSidecar): Boolean = sidecar.store == com.droiddeck.launcher.stores.Store.EPIC

    private fun sanitize(s: String): String = s.replace("\n", "").replace("\r", "").replace("\u0000", "")
}
