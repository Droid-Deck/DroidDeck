package com.droiddeck.launcher.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.droiddeck.launcher.R
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.ArrayDeque

object WirelessAdbFix {
    private const val CERT_FILE = "wireless-adb-cert.pem"
    private const val KEY_FILE = "wireless-adb-key.pem"
    private const val HOST_FILE = "wireless-adb-host.txt"
    private const val CONNECTION_FILE = "wireless-adb-connection.txt"
    const val LOOPBACK = "127.0.0.1"

    private data class SavedConnection(val host: String, val port: Int?)

    internal data class ShellResult(val exitCode: Int, val output: String, val allOutput: String = output)

    internal data class LimitReadResult(
        val global: String?,
        val override: String?,
        val maxPhantom: String?,
        val globalReadable: Boolean,
        val overrideReadable: Boolean,
        val deviceConfigReadable: Boolean,
        val diagnostics: String,
    )

    internal data class LimitChangeResult(
        val readings: LimitReadResult,
        val confirmed: Boolean,
        val diagnostics: String,
        val legacySyncRecovered: Boolean = false,
        val legacySyncFailure: Boolean = false,
    )

    private fun runShell(shell: (String) -> ShellResult, command: String): ShellResult = try {
        shell(command)
    } catch (failure: Exception) {
        ShellResult(-1, "", failure.message ?: failure.javaClass.simpleName)
    }

    private fun commandDiagnostic(command: String, result: ShellResult): String =
        "$command: ${result.allOutput.trim().ifBlank { "exit ${result.exitCode}" }}"

    internal fun readLimit(shell: (String) -> ShellResult): LimitReadResult {
        val global = runShell(shell, PhantomProcessLimit.GLOBAL_QUERY)
        val override = runShell(shell, PhantomProcessLimit.OVERRIDE_QUERY)
        val deviceConfig = runShell(shell, PhantomProcessLimit.DEVICE_CONFIG_QUERY)
        val globalReadable = global.exitCode == 0 && global.output.isNotBlank()
        val deviceConfigReadable = deviceConfig.exitCode == 0 && deviceConfig.output.isNotBlank()
        return LimitReadResult(
            global.output.takeIf { globalReadable },
            override.output.takeIf { override.exitCode == 0 },
            deviceConfig.output.takeIf { deviceConfigReadable },
            globalReadable,
            override.exitCode == 0,
            deviceConfigReadable,
            listOf(
                PhantomProcessLimit.GLOBAL_QUERY to global,
                PhantomProcessLimit.OVERRIDE_QUERY to override,
                PhantomProcessLimit.DEVICE_CONFIG_QUERY to deviceConfig,
            ).joinToString("\n") { (command, result) -> commandDiagnostic(command, result) },
        )
    }

    internal fun changeLimit(
        enabled: Boolean,
        sdk: Int,
        legacySyncPending: Boolean = false,
        recoverLegacySync: Boolean = false,
        shell: (String) -> ShellResult,
    ): LimitChangeResult {
        check(!enabled || !legacySyncPending || recoverLegacySync) { "Legacy DeviceConfig sync recovery requires confirmation" }
        val changes = PhantomProcessLimit.shellCommands(enabled, sdk).map { it to runShell(shell, it) }
        val readings = readLimit(shell)
        val diagnostics = buildList {
            changes.filter { (_, result) -> result.exitCode != 0 }.forEach { (command, result) ->
                add(commandDiagnostic(command, result))
            }
            add(readings.diagnostics)
        }.joinToString("\n")
        val normal = LimitChangeResult(
            readings,
            PhantomProcessLimit.verificationMatches(
                readings.global,
                readings.maxPhantom,
                readings.globalReadable,
                readings.deviceConfigReadable,
                enabled,
                sdk,
                readings.override,
                readings.overrideReadable,
            ),
            diagnostics,
        )
        if (!enabled || !legacySyncPending || !normal.confirmed) return normal
        val recovery = LegacyDeviceConfigSync.recover(shell)
        return normal.copy(
            confirmed = recovery.confirmed,
            diagnostics = "$diagnostics\n${recovery.diagnostics}",
            legacySyncRecovered = recovery.confirmed,
            legacySyncFailure = !recovery.confirmed,
        )
    }

    private fun rememberVerification(context: Context, readings: LimitReadResult) {
        PhantomProcessLimit.rememberVerification(
            context,
            readings.global,
            readings.maxPhantom,
            readings.globalReadable,
            readings.deviceConfigReadable,
            readings.override,
            readings.overrideReadable,
        )
    }

    @Synchronized
    private fun loadOrCreateIdentity(context: Context) {
        val directory = context.noBackupFilesDir
        val cert = File(directory, CERT_FILE)
        val key = File(directory, KEY_FILE)
        if (cert.isFile && key.isFile) {
            KadbCert.set(cert.readBytes(), key.readBytes())
            return
        }

        val identity = KadbCert.get(notAfter = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650))
        KadbCert.set(identity.first, identity.second)
        writeAtomically(context, cert, identity.first)
        writeAtomically(context, key, identity.second)
    }

    private fun writeAtomically(context: Context, destination: File, contents: ByteArray) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeBytes(contents)
        check(temporary.renameTo(destination)) { context.getString(R.string.adbfix_save_failed) }
    }

    suspend fun pair(context: Context, host: String, port: Int, pairingCode: String) {
        loadOrCreateIdentity(context.applicationContext)
        Kadb.pair(host, port, pairingCode, "DroidDeck")
        writeAtomically(context, File(context.noBackupFilesDir, HOST_FILE), host.toByteArray())
        writeAtomically(context, File(context.noBackupFilesDir, CONNECTION_FILE), ByteArray(0))
    }

    private fun savedConnection(context: Context): SavedConnection? {
        val directory = context.applicationContext.noBackupFilesDir
        if (!File(directory, CERT_FILE).isFile || !File(directory, KEY_FILE).isFile) return null
        val host = File(directory, HOST_FILE).takeIf(File::isFile)?.readText()?.trim()
            ?.takeIf(String::isNotEmpty) ?: return null
        val lines = File(directory, CONNECTION_FILE).takeIf(File::isFile)?.readLines()
        val port = lines?.takeIf { it.size == 2 && it[0] == host }
            ?.get(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
        return SavedConnection(host, port)
    }

    fun savedConnectionAddress(context: Context): String? = savedConnection(context)?.let { saved ->
        val host = if (':' in saved.host) "[${saved.host}]" else saved.host
        "$host:${saved.port ?: ""}"
    }

    private fun saveConnection(context: Context, host: String, port: Int) {
        writeAtomically(
            context,
            File(context.applicationContext.noBackupFilesDir, CONNECTION_FILE),
            "$host\n$port".toByteArray(),
        )
    }

    fun isLocalAddress(address: InetAddress): Boolean = isLocalAddress(address) {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.inetAddresses.toList() }
    }

    internal fun isLocalAddress(address: InetAddress, localAddresses: () -> List<InetAddress>): Boolean {
        if (address.isLoopbackAddress) return true
        return runCatching {
            localAddresses().any { it.address.contentEquals(address.address) }
        }.getOrDefault(false)
    }

    internal fun <T> usingLocalHost(
        host: String,
        errorMessage: () -> String,
        resolve: (String) -> InetAddress = { InetAddress.getByName(it) },
        localAddress: (InetAddress) -> Boolean = ::isLocalAddress,
        clearVerification: () -> Unit = PhantomProcessLimit::clearVerification,
        action: (String) -> T,
    ): T {
        clearVerification()
        val address = runCatching { resolve(host).takeIf(localAddress) }.getOrNull()
        requireNotNull(address) { errorMessage() }
        return action(address.hostAddress)
    }

    fun localConnectPort(context: Context): Int? =
        tlsPortProperty()
            ?: findConnectPort(context, LOOPBACK)

    private fun tlsPortProperty(): Int? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "service.adb.tls.port") as? String
    }.getOrNull()?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    fun findConnectPort(context: Context, pairedHost: String, timeoutSeconds: Long = 12): Int? {
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val paired = runCatching { InetAddress.getByName(pairedHost) }.getOrNull() ?: return null
        val matches: (InetAddress) -> Boolean =
            if (paired.isLoopbackAddress) ::isLocalAddress else { address -> address.address.contentEquals(paired.address) }
        val resolvedPort = AtomicInteger(-1)
        val finished = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val queue = ArrayDeque<NsdServiceInfo>()
        val seen = mutableSetOf<String>()
        var resolving = false
        val lock = Any()

        lateinit var resolveNext: () -> Unit
        resolveNext = {
            val next = synchronized(lock) {
                if (finished.get() || resolving || queue.isEmpty()) null
                else queue.removeFirst().also { resolving = true }
            }
            if (next != null) {
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) { resolving = false }
                        resolveNext()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val address = serviceInfo.host
                        if (address != null && matches(address) && finished.compareAndSet(false, true)) {
                            resolvedPort.set(serviceInfo.port)
                            latch.countDown()
                        } else {
                            synchronized(lock) { resolving = false }
                            resolveNext()
                        }
                    }
                }
                runCatching { manager.resolveService(next, listener) }.onFailure {
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains("_adb-tls-connect._tcp") == true) {
                    synchronized(lock) {
                        if (seen.add(serviceInfo.serviceName)) queue.addLast(serviceInfo)
                    }
                    resolveNext()
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) { latch.countDown() }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
        }

        try {
            manager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            latch.await(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { manager.stopServiceDiscovery(discoveryListener) }
        }
        return resolvedPort.get().takeIf { it in 1..65535 }
    }

    @Synchronized
    fun setChildProcessLimit(context: Context, host: String, port: Int, enabled: Boolean, recoverLegacySync: Boolean = false) {
        PhantomProcessLimit.clearVerification()
        val legacySyncPending = requireLegacySyncConsent(context, enabled, recoverLegacySync)
        usingLocalHost(host, { context.getString(R.string.adbfix_local_only) }) { localHost ->
            loadOrCreateIdentity(context.applicationContext)
            val result = Kadb.create(localHost, port).use { adb ->
                val result = changeLimit(enabled, Build.VERSION.SDK_INT, legacySyncPending, recoverLegacySync) { command ->
                    val response = adb.shell(command)
                    ShellResult(response.exitCode, response.output, response.allOutput)
                }
                check(result.confirmed) {
                    context.getString(
                        if (result.legacySyncFailure) R.string.adbfix_legacy_sync_not_confirmed else R.string.adbfix_not_confirmed,
                        result.diagnostics,
                    )
                }
                result
            }
            saveConnection(context, host, port)
            if (result.legacySyncRecovered) LegacyDeviceConfigSync.clear(context)
            rememberVerification(context, result.readings)
        }
    }

    @Synchronized
    fun setUsingSavedPairing(context: Context, enabled: Boolean, recoverLegacySync: Boolean = false) {
        PhantomProcessLimit.clearVerification()
        requireLegacySyncConsent(context, enabled, recoverLegacySync)
        usingSavedConnection(context) { host, port -> setChildProcessLimit(context, host, port, enabled, recoverLegacySync) }
    }

    private fun requireLegacySyncConsent(context: Context, enabled: Boolean, recoverLegacySync: Boolean): Boolean {
        val pending = LegacyDeviceConfigSync.pending(context)
        check(!enabled || !pending || recoverLegacySync) { context.getString(R.string.adbfix_legacy_sync_confirmation_required) }
        return pending
    }

    @Synchronized
    fun refreshUsingSavedPairing(context: Context): Boolean {
        PhantomProcessLimit.clearVerification()
        return runCatching {
            val readings = usingSavedConnection(context) { host, port ->
                usingLocalHost(host, { context.getString(R.string.adbfix_local_only) }) { localHost ->
                    loadOrCreateIdentity(context.applicationContext)
                    val result = Kadb.create(localHost, port).use { adb ->
                        readLimit { command ->
                            val response = adb.shell(command)
                            ShellResult(response.exitCode, response.output, response.allOutput)
                        }
                    }
                    check(result.globalReadable || result.overrideReadable || result.deviceConfigReadable) { result.diagnostics }
                    saveConnection(context, host, port)
                    result
                }
            }
            rememberVerification(context, readings)
            val confirmed = PhantomProcessLimit.read(context) in setOf(
                PhantomProcessStatus.DISABLED,
                PhantomProcessStatus.ENABLED,
                PhantomProcessStatus.NOT_APPLICABLE,
            )
            if (!confirmed) PhantomProcessLimit.clearVerification()
            confirmed
        }.getOrElse {
            PhantomProcessLimit.clearVerification()
            false
        }
    }

    private fun <T> usingSavedConnection(context: Context, action: (String, Int) -> T): T {
        val appContext = context.applicationContext
        val saved = savedConnection(appContext)
            ?: error(appContext.getString(R.string.adbfix_no_pairing))
        return usingLocalHost(saved.host, { appContext.getString(R.string.adbfix_local_only) }) {
            connectUsingSavedAddress(appContext, saved, action)
        }
    }

    private fun <T> connectUsingSavedAddress(context: Context, saved: SavedConnection, action: (String, Int) -> T): T {
        val cachedPort = saved.port
        if (cachedPort != null) {
            val cachedResult = runCatching {
                action(saved.host, cachedPort)
            }
            if (cachedResult.isSuccess) return cachedResult.getOrThrow()
            val discoveredPort = findConnectPort(context, saved.host)
            return if (discoveredPort != null && discoveredPort != cachedPort) {
                action(saved.host, discoveredPort)
            } else {
                cachedResult.getOrThrow()
            }
        } else {
            val discoveredPort = findConnectPort(context, saved.host)
                ?: error(context.getString(R.string.adbfix_enter_address))
            return action(saved.host, discoveredPort)
        }
    }
}
