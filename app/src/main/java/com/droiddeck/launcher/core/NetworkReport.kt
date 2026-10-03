package com.droiddeck.launcher.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * `network.txt`: what the session was given to work with, for the reports that say "the client
 * thinks WiFi is off" or "Firefox cannot resolve anything".
 *
 * The runtime's NetworkManager bridge reports Android's active link. Include whether the phone
 * had a validated link and which DNS servers the app wrote into the runtime's `resolv.conf`.
 *
 * **No network names.** The SSID is identifying and is deliberately not collected; the transport
 * type, the addresses' families and the DNS servers are what a diagnosis needs.
 */
object NetworkReport {
    private const val TAG = "NetworkReport"

    fun write(context: Context, target: File) {
        try {
            // Addresses as their kind ("<public IPv6>"), never the numbers: a public address is the
            // user's, and this file goes into shared session logs. The session's made-up hardware
            // address is stable per device (seeded from ANDROID_ID), so it is masked too.
            val text = LogRedactor.describeAddresses(build(context))
                .replace(Regex("(?m)^(\\s*mac )\\S+"), "$1<masked>")
            target.writeText(text)
        } catch (e: Exception) {
            Log.w(TAG, "could not write $target", e)
        }
    }

    private fun build(context: Context): String = buildString {
        append("Network as the session starts\n")
        append("=============================\n")
        append("The SSID is deliberately not recorded, and addresses appear as their kind, not their numbers.\n\n")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        if (cm == null || network == null) {
            append("Active network        none - the phone reports no connection\n")
        } else {
            val caps = cm.getNetworkCapabilities(network)
            val transport = when {
                caps == null -> "unknown"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "other"
            }
            append("Transport             ").append(transport).append('\n')
            append("Validated             ")
                .append(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: "unknown")
                .append("   (false with a working link usually means a captive portal)\n")
            append("Metered               ")
                .append(caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } ?: "unknown")
                .append('\n')
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps != null) {
                append("Link speed            down ").append(caps.linkDownstreamBandwidthKbps)
                    .append(" kbps / up ").append(caps.linkUpstreamBandwidthKbps).append(" kbps\n")
            }
            val link = cm.getLinkProperties(network)
            append("Interface             ").append(link?.interfaceName ?: "unknown").append('\n')
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append("MTU                   ").append(link?.mtu ?: 0).append('\n')
            }
            val v4 = link?.linkAddresses.orEmpty().count { it.address is Inet4Address }
            val v6 = link?.linkAddresses.orEmpty().count { it.address is Inet6Address }
            append("Addresses             ").append(v4).append(" IPv4, ").append(v6).append(" IPv6")
                .append(if (v4 == 0) "   (no IPv4 - Steam's content servers need it)" else "").append('\n')
            append("DNS from the system   ")
                .append(link?.dnsServers.orEmpty().joinToString(", ") { it.hostAddress ?: "?" }.ifEmpty { "none" })
                .append('\n')
            append("Search domains        ").append(link?.domains ?: "none").append('\n')
        }

        append("\nWhat the runtime was given\n")
        append("--------------------------\n")
        // The app writes this file from the live link at every change; it is what everything inside
        // the session resolves with, so a mismatch with the system's list above is the bug itself.
        val resolv = File(LinuxRuntime.rootDir(context), "etc/resolv.conf")
        if (resolv.isFile) {
            append("etc/resolv.conf:\n")
            FileUtils.readString(resolv)?.lines()?.forEach { append("    ").append(it).append('\n') }
        } else {
            append("etc/resolv.conf does not exist yet - written when the session's link is published.\n")
        }
        val netdev = File(LinuxRuntime.rootDir(context), "etc/droiddeck-net")
        if (netdev.isFile) {
            append("\netc/droiddeck-net (the link as the session sees it):\n")
            FileUtils.readString(netdev)?.lines()?.forEach { append("    ").append(it).append('\n') }
        }
        append("\nproot makes no network namespace, so the session uses the phone's connection\n")
        append("directly: IPv4 and IPv6 both pass through, and there is nothing to forward.\n")
        append("Steam reads Android's transport, connectivity and metering through the runtime's\n")
        append("NetworkManager D-Bus bridge. Android settings manage Wi-Fi and cellular connections.\n")
    }
}
