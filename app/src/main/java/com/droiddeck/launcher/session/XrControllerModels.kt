package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.AddonPack
import com.droiddeck.launcher.runtime.LinuxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

internal object XrControllerModels {
    enum class Phase { DOWNLOADING, CHECKING, INSTALLING, READY, NATIVE, FALLBACK, FAILED }
    data class Status(val session: String? = null, val names: List<String> = emptyList(),
                      val phase: Phase = Phase.CHECKING, val progress: Float? = null, val complete: Boolean = false)
    internal data class Family(val profile: Int, val name: String, val packName: String, val model: String,
                               val sha: String, val size: Long) {
        val pack by lazy { createPack(null) }
        private var testPack: Pair<XrModelTestConfig, AddonPack>? = null
        @Synchronized fun forTest(test: XrModelTestConfig?): AddonPack {
            if (test == null) return pack
            return testPack?.takeIf { it.first == test }?.second
                ?: createPack(test).also { testPack = test to it }
        }
        private fun createPack(test: XrModelTestConfig?): AddonPack =
            AddonPack(packName, sha, size, listOf("LICENSE.txt", "provenance.json",
                "rendermodels/${model}_left/${model}_left.json", "rendermodels/${model}_right/${model}_right.json"),
                reuseDeviceCopy = false, labBuild = false,
                bundle = { context ->
                    check(test == null)
                    check(!BuildConfig.CI_BUILD)
                    File(context.getExternalFilesDir(null), "addon-import/$packName.tar.zst").inputStream()
                }, validateFiles = XrModelResources::pack, detailedState = true,
                downloadUrls = test?.let { listOf("${it.baseUrl}/$packName.tar.zst") },
                storageName = test?.let { "xr-model-tests/${it.request}/$packName" } ?: packName)
    }
    private val families = listOf(
        Family(2, "PICO Neo3", "pico-neo3", "droiddeck_pico_neo3", BuildConfig.PACK_PICO_NEO3_SHA256, BuildConfig.PACK_PICO_NEO3_SIZE),
        Family(3, "PICO 4", "pico-4", "droiddeck_pico_4", BuildConfig.PACK_PICO_4_SHA256, BuildConfig.PACK_PICO_4_SIZE),
        Family(4, "PICO 4 Ultra", "pico-4u", "droiddeck_pico_4u", BuildConfig.PACK_PICO_4U_SHA256, BuildConfig.PACK_PICO_4U_SIZE),
    )
    internal fun family(profile: Int): Family? = families.find { it.profile == profile }
    private data class Hand(val profile: Int, val native: String)
    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val installLock = Any()
    private var job: Job? = null
    private var requestedSession: String? = null
    private var session: String? = null
    private var owner = 0L
    private var hands: List<Hand>? = null
    private var revision = 0L
    private var testConfig: XrModelTestConfig? = null

    fun request(context: Context) {
        if (!OpenXrRuntime.isAvailable(context)) return
        synchronized(lock) {
            requestedSession = SessionState.sessionId ?: return
            schedule(context.applicationContext)
        }
    }

    fun begin(context: Context, attempt: Long) = synchronized(lock) {
        owner = attempt
        session = SessionState.sessionId
        hands = null
        testConfig = XrModelTestConfig.read(context)
        testConfig?.let { Log.i("DroidDeckXR", "Model download test request=${it.request} source=${it.baseUrl}") }
        revision++
        job?.cancel()
        mutableStatus.value = Status(session)
        if (SessionState.steamVr && OpenXrRuntime.isAvailable(context)) requestedSession = session
    }

    fun controllers(context: Context, attempt: Long, left: Int, right: Int, leftNative: String, rightNative: String) {
        synchronized(lock) {
            if (owner != attempt || session != SessionState.sessionId || !OpenXrRuntime.isAvailable(context)) return
            val next = listOf(Hand(left, leftNative), Hand(right, rightNative))
            if (hands == next) return
            hands = next
            schedule(context.applicationContext)
        }
    }

    fun end(attempt: Long) = synchronized(lock) {
        if (owner != attempt) return@synchronized
        owner = 0L
        session = null
        requestedSession = null
        hands = null
        testConfig = null
        revision++
        job?.cancel()
        mutableStatus.value = Status()
    }

    private fun schedule(context: Context) {
        val selected = hands ?: return
        val currentSession = session ?: return
        if (requestedSession != currentSession || currentSession != SessionState.sessionId) return
        val currentRevision = ++revision
        val test = testConfig
        job?.cancel()
        job = scope.launch {
            fun valid() = synchronized(lock) {
                revision == currentRevision && currentSession == SessionState.sessionId &&
                    SessionState.running && !SessionState.stopRequested && owner != 0L &&
                    OpenXrRuntime.isAvailable(context)
            }
            fun publish(phase: Phase, progress: Float? = null, complete: Boolean = false) {
                synchronized(lock) {
                    if (valid()) mutableStatus.value = Status(currentSession,
                        selected.mapNotNull { family(it.profile)?.name }.distinct(), phase, progress, complete)
                }
            }
            publish(Phase.CHECKING)
            val pending = selected.mapIndexedNotNull { index, hand ->
                val native = nativeReady(context, hand.native)
                if (native && test == null) null else family(hand.profile)?.takeIf { test != null || !installed(context, it, index) }
            }.distinct().map { it to it.forTest(test) }
            pending.forEach { it.second.ensure(context) }
            while (valid() && pending.any { it.second.state.value != AddonPack.State.Ready && it.second.state.value != AddonPack.State.Failed }) {
                val states = pending.map { it.second.state.value }
                when {
                    states.any { it == AddonPack.State.Installing } -> publish(Phase.INSTALLING)
                    states.any { it == AddonPack.State.Checking } -> publish(Phase.CHECKING)
                    else -> {
                        val size = pending.sumOf { it.first.size }
                        val progress = if (size > 0 && pending.all { it.first.size > 0 }) pending.sumOf {
                            val state = it.second.state.value
                            it.first.size * (if (state == AddonPack.State.Ready) 1.0 else (state as? AddonPack.State.Downloading)?.progress?.toDouble() ?: 0.0)
                        }.div(size).toFloat() else null
                        publish(Phase.DOWNLOADING, progress)
                    }
                }
                delay(100)
            }
            if (!valid()) return@launch
            publish(Phase.INSTALLING)
            var failed = false
            for ((family, pack) in pending) {
                if (pack.state.value != AddonPack.State.Ready || !runCatching { install(context, family, pack) }.getOrDefault(false)) {
                    failed = true
                    Log.w("DroidDeckXR", "${family.packName}: ${pack.failure ?: "model installation failed"}")
                } else if (test != null) {
                    Log.i("DroidDeckXR", "Model download test installed ${family.packName} request=${test.request}")
                }
            }
            if (!valid()) return@launch
            val allNative = selected.all { nativeReady(context, it.native) }
            val usable = selected.withIndex().all { (index, hand) ->
                nativeReady(context, hand.native) || family(hand.profile)?.let { installed(context, it, index) } == true ||
                    fallback(context, index)
            }
            publish(when {
                !usable -> Phase.FAILED
                failed -> Phase.FALLBACK
                allNative -> Phase.NATIVE
                else -> Phase.READY
            }, complete = usable)
        }
    }

    fun await(context: Context, running: () -> Boolean): Boolean {
        request(context)
        while (running() && OpenXrRuntime.isAvailable(context)) {
            val current = mutableStatus.value
            if (current.session == SessionState.sessionId) {
                if (current.complete) return true
                if (current.phase == Phase.FAILED) return false
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun nativeReady(context: Context, name: String): Boolean =
        name.matches(Regex("droiddeck_xr_runtime_[0-9a-f]{32}_(left|right)")) &&
            XrModelResources.model(File(context.filesDir, ".wayland-rt/xr-models/$name"), name)

    private fun models(context: Context) = File(LinuxRuntime.rootDir(context), "usr/local/lib/droiddeck-vr/droiddeck/resources/rendermodels")
    private fun fallback(context: Context, hand: Int): Boolean {
        val name = "droiddeck_hand_${if (hand == 0) "left" else "right"}"
        return XrModelResources.model(File(models(context), name), name)
    }

    private fun installed(context: Context, family: Family, hand: Int): Boolean {
        val name = "${family.model}_${if (hand == 0) "left" else "right"}"
        val directory = File(models(context), name)
        return runCatching { File(directory, AddonPack.COMPLETE).readText().trim() == family.sha &&
            XrModelResources.model(directory, name) }.getOrDefault(false)
    }

    private fun install(context: Context, family: Family, pack: AddonPack): Boolean = synchronized(installLock) {
        val source = pack.dir(context)
        check(XrModelResources.pack(source))
        val root = models(context).apply { mkdirs() }
        for (side in listOf("left", "right")) {
            val name = "${family.model}_$side"
            val pending = File(root, "$name.partial")
            pending.deleteRecursively()
            check(File(source, "rendermodels/$name").copyRecursively(pending))
            check(XrModelResources.model(pending, name))
            File(pending, AddonPack.COMPLETE).writeText(family.sha)
            val target = File(root, name)
            val previous = File(root, "$name.previous")
            previous.deleteRecursively()
            if (target.exists()) check(target.renameTo(previous))
            if (!pending.renameTo(target)) {
                previous.renameTo(target)
                error("Could not install $name")
            }
            previous.deleteRecursively()
        }
        val licenses = File(root.parentFile, "licenses/${family.packName}").apply { mkdirs() }
        File(source, "LICENSE.txt").copyTo(File(licenses, "LICENSE.txt"), overwrite = true)
        File(source, "provenance.json").copyTo(File(licenses, "provenance.json"), overwrite = true)
        true
    }
}
