package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.AddonPack
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.audio.DirectAudioRelayComponent
import com.droiddeck.launcher.audio.PulseAudioComponent
import com.droiddeck.launcher.core.CpuCores
import com.droiddeck.launcher.core.DeviceReport
import com.droiddeck.launcher.core.HostEnvironment
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.SessionLogCapture
import com.droiddeck.launcher.core.NetworkReport
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.frontend.GameLaunchLink
import com.droiddeck.launcher.input.FakeInputWriter
import com.droiddeck.launcher.input.SteamControllerBle
import com.droiddeck.launcher.runtime.BwrapSpawner
import com.droiddeck.launcher.runtime.LinuxNetworkLinkComponent
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.ProotFastPath
import com.droiddeck.launcher.runtime.UserApps
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SessionService : Service() {
    override fun attachBaseContext(newBase: android.content.Context) =
        super.attachBaseContext(com.droiddeck.launcher.core.AppLanguage.wrap(newBase))

    private val components = java.util.concurrent.CopyOnWriteArrayList<SessionPart>()
    private var deckBinds: List<String> = emptyList()
    private var inputSysfs: InputSysfsComponent? = null
    private val stopLock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sessionPid = -1
    private val auxiliaryProcesses = HashMap<Int, Long>()
    private val auxiliaryProcessesLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var suspendController: SessionSuspendController? = null
    private val steamDownloadMonitor = SteamDownloadMonitor()
    private var downloadMonitorTask: Runnable? = null
    private var downloadActive = false
    @Volatile private var steamLibraryMounts = emptyMap<String, File>()
    private var suspendDownloadNow = false
    private var suspendPolicy = SessionPrefs.SUSPEND_MANUAL
    private var steamDownloadsInBackground = false
    private var activityVisible = true
    private var xrActivityVisible = false
    private var signInHold = false
    private val hasVisibleSurface get() = sessionHasVisibleSurface(activityVisible || signInHold, screenOn, xrActivityVisible)
    private var screenOn = true
    private var sessionDisplayId = Display.DEFAULT_DISPLAY
    private var manualPauseRequested = false
    private var steamSleepToken: String? = null
    private var nativeSleepToken: String? = null
    private var nativeSleepReady = false
    private var nativeSleepFallback = false
    private var nativeSleepIdle = false
    private var nativeSleepTimeout: Runnable? = null
    private var suspendOperationPending = false
    private var pipTask = false
    private var suspendAttemptFailed = false
    private var pendingSteamGameId: String? = null
    private var pendingSteamGameGeneration = -1
    // Bumped to cancel a running game-exit watch (a new launch, or the session ending).
    @Volatile private var gameExitWatchToken = 0
    private val flushSteamGame = object : Runnable {
        override fun run() = flushQueuedSteamGame()
    }
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = readScreenOn(false)
                Intent.ACTION_SCREEN_ON -> screenOn = readScreenOn(true)
                else -> return
            }
            if (screenOn) resumeSteamSleepOnReturn()
            suspendAttemptFailed = false
            updateSuspendPolicy()
        }
    }
    private var displayListenerRegistered = false
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId != sessionDisplayId) return
            sessionDisplayId = Display.DEFAULT_DISPLAY
            refreshScreenOn()
        }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == sessionDisplayId && displayId != Display.DEFAULT_DISPLAY) refreshScreenOn()
        }
    }

    private fun readScreenOn(interactive: Boolean? = null): Boolean {
        val id = sessionDisplayId
        val state = if (id == Display.DEFAULT_DISPLAY) null
            else (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(id)?.state
        return sessionScreenOn(id == Display.DEFAULT_DISPLAY,
            interactive ?: (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true, state)
    }

    private fun refreshScreenOn() {
        val on = readScreenOn()
        if (on == screenOn) return
        screenOn = on
        if (on) resumeSteamSleepOnReturn()
        suspendAttemptFailed = false
        updateSuspendPolicy()
    }
    @Volatile private var sessionGen = 0

    override fun onCreate() {
        super.onCreate()
        screenOn = readScreenOn()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        screenReceiverRegistered = true
        (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.let {
            it.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            displayListenerRegistered = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LAUNCH_GAME -> {
                queueSteamGame(intent.getStringExtra(EXTRA_GAME_ID))
                return START_NOT_STICKY
            }
            ACTION_TRACK_AUXILIARY -> {
                val pid = intent.getIntExtra(EXTRA_AUXILIARY_PID, -1)
                val started = if (pid > 1) readStat(pid)?.second else null
                if (started != null) {
                    val tracked = synchronized(auxiliaryProcessesLock) {
                        if (SessionState.running) {
                            auxiliaryProcesses[pid] = started
                            true
                        } else false
                    }
                    if (!tracked) Thread({ teardown(pid, started) }, "auxiliary-stop-$pid").start()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP_AUXILIARY -> {
                val pid = intent.getIntExtra(EXTRA_AUXILIARY_PID, -1)
                if (pid > 1) {
                    val started = synchronized(auxiliaryProcessesLock) { auxiliaryProcesses.remove(pid) } ?: readStat(pid)?.second
                    if (started != null) Thread({ teardown(pid, started) }, "auxiliary-stop-$pid").start()
                }
                return START_NOT_STICKY
            }
            ACTION_AUXILIARY_EXITED -> {
                synchronized(auxiliaryProcessesLock) {
                    auxiliaryProcesses.remove(intent.getIntExtra(EXTRA_AUXILIARY_PID, -1))
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                Log.i(TAG, "stop requested from the notification")
                stopSession(0)
                return START_NOT_STICKY
            }
            ACTION_PIP_BEGIN -> {
                pipTask = true
                return START_NOT_STICKY
            }
            ACTION_ACTIVITY_VISIBLE -> {
                if (!SessionState.pipActive) pipTask = false
                activityVisible = true
                sessionDisplayId = intent.getIntExtra(EXTRA_DISPLAY_ID, sessionDisplayId)
                screenOn = readScreenOn()
                suspendDownloadNow = false
                resumeSteamSleepOnReturn()
                suspendAttemptFailed = false
                updateSuspendPolicy()
                return START_NOT_STICKY
            }
            ACTION_ACTIVITY_DISPLAY -> {
                sessionDisplayId = intent.getIntExtra(EXTRA_DISPLAY_ID, sessionDisplayId)
                refreshScreenOn()
                return START_NOT_STICKY
            }
            ACTION_ACTIVITY_HIDDEN -> {
                activityVisible = false
                suspendAttemptFailed = false
                updateSuspendPolicy()
                return START_NOT_STICKY
            }
            ACTION_XR_ACTIVITY_VISIBLE -> {
                xrActivityVisible = true
                resumeSession(panelVisible = false)
                return START_NOT_STICKY
            }
            ACTION_XR_ACTIVITY_HIDDEN -> {
                xrActivityVisible = false
                suspendAttemptFailed = false
                updateSuspendPolicy()
                return START_NOT_STICKY
            }
            ACTION_SIGN_IN_HOLD -> {
                val hold = intent?.getBooleanExtra(EXTRA_HOLD, false) == true
                if (hold == signInHold) return START_NOT_STICKY
                signInHold = hold
                if (hold) manualPauseRequested = false
                suspendAttemptFailed = false
                updateSuspendPolicy()
                return START_NOT_STICKY
            }
            ACTION_SUSPEND_POLICY_CHANGED -> {
                if (!SessionState.running) return START_NOT_STICKY
                suspendPolicy = SessionPrefs.suspendPolicy(this, SessionState.mode)
                steamDownloadsInBackground = SessionPrefs.steamDownloadsInBackground(this)
                updateDownloadMonitoring()
                suspendAttemptFailed = false
                updateSuspendPolicy()
                return START_NOT_STICKY
            }
            ACTION_SUSPEND_NOW -> {
                if (!SessionState.running) return START_NOT_STICKY
                suspendDownloadNow = true
                updateSuspendPolicy()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                resumeSession()
                return START_NOT_STICKY
            }
        }
        if (com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.isMaintaining()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (SessionState.running) return START_NOT_STICKY
        if (SessionState.stopRequested) {
            SessionState.running = true
            stopSession(0)
            return START_NOT_STICKY
        }
        SessionState.mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_STEAM
        suspendPolicy = SessionPrefs.suspendPolicy(this, SessionState.mode)
        steamDownloadsInBackground = SessionPrefs.steamDownloadsInBackground(this)
        activityVisible = true
        xrActivityVisible = false
        signInHold = false
        sessionDisplayId = intent?.getIntExtra(EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY) ?: Display.DEFAULT_DISPLAY
        screenOn = readScreenOn()
        manualPauseRequested = false
        suspendDownloadNow = false
        steamDownloadMonitor.reset()
        downloadActive = false
        steamSleepToken = null
        completeNativeSleep()
        suspendOperationPending = false
        suspendAttemptFailed = false
        suspendController = null
        SessionState.suspended = false
        SessionState.pipActive = false
        pipTask = false
        SessionState.program = intent?.getStringExtra(EXTRA_PROGRAM)
        SessionState.programArgs = intent?.getStringArrayExtra(EXTRA_PROGRAM_ARGS)?.toList().orEmpty()
        SessionState.steamUi = intent?.getStringExtra(EXTRA_STEAM_UI)
        SessionState.steamUrl = intent?.getStringExtra(EXTRA_STEAM_URL)
        synchronized(stopLock) {
            SessionState.steamClientArch = null
            SessionState.running = true
        }
        if (SessionState.stopRequested) {
            stopSession(0)
            return START_NOT_STICKY
        }
        if (SessionState.mode == MODE_STEAM || SessionState.mode == MODE_DESKTOP) RivalClients.stopBeforeSession(this)
        SessionState.firstFrameSeen = false
        SessionState.guestPid = -1
        SessionEvents.transition(SessionPhase.STARTING_GUEST, "service.started", mapOf("mode" to SessionState.mode))
        com.droiddeck.launcher.wayland.CompositorHost.newSession()
        val gen = ++sessionGen
        cancelQueuedSteamGame(clearMailbox = false)
        clearSteamGameMailbox()
        if (SessionState.mode == MODE_STEAM) SessionState.steamUrl
            ?.takeIf { it.startsWith(GAME_URL_PREFIX) }?.let { watchGameExit(it.removePrefix(GAME_URL_PREFIX), gen) }
        acquireLocks()
        Thread({
            OrphanReaper.reap("session starting")
            runSession(gen)
        }, "session-start").start()
        return START_NOT_STICKY
    }

    private fun collectSessionArtifacts(dir: File, steamArch: String?) {
        try {
            SessionArtifacts.collect(this, dir, "session stopped", steamArch)
        } finally {
            SessionLogCapture.stopFor(dir)
            SessionPaths.release(this, dir)
        }
    }

    private fun extraEnv(): List<String> {
        if (File(Environment.getExternalStorageDirectory(), LEGACY_ENV_SWITCH).isFile) {
            Log.w(TAG, "ignoring $LEGACY_ENV_SWITCH: any app with storage access can write there; " +
                "the file now lives at ${envSwitchFile(this)?.path ?: "(not available before Android 11)"}")
        }
        val file = envSwitchFile(this)?.takeIf { it.isFile } ?: return emptyList()
        val lines = FileUtils.readString(file)?.lines().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') && !it.startsWith("=") }
        if (lines.isNotEmpty()) Log.i(TAG, "extra environment keys from ${file.path}: ${lines.map { it.substringBefore('=') }}")
        return lines
    }

    private fun tuDebugOverride(): String? = File(Environment.getExternalStorageDirectory(), TU_DEBUG_SWITCH)
        .takeIf { it.isFile }?.let { FileUtils.readString(it)?.trim() }?.takeIf { it.isNotEmpty() }

    private fun sysmemDriver(linuxDriverId: String): Boolean {
        if (linuxDriverId.isEmpty()) return false
        val name = LinuxVulkanDriverManager(this).getDriverName(linuxDriverId).lowercase()
        return name.contains("710-720") || name.contains("710_720")
    }


    private fun runSession(gen: Int) {
        SessionTerminal.clear()
        com.droiddeck.launcher.gpu.TpUbwcHint.forThisDevice()
        try {
            LinuxRuntime.writeAccounts(this)
        } catch (e: Exception) {
            Log.e(TAG, "could not write the guest's passwd/group", e)
            stopSession(-1)
            return
        }

        val root = LinuxRuntime.rootDir(this)
        val sessionRoot = LinuxRuntime.sessionRoot(this).apply { mkdirs() }
        val runtimeDir = File(filesDir, ".wayland-rt").apply { mkdirs() }
        killStragglers()
        for (pack in listOf(AddonPack.PROOT, AddonPack.DIRECTAUDIO))
            if (!pack.await(this, { gen == sessionGen && SessionState.running })) Log.w(TAG, "${pack.name} pack unavailable")
        SessionFiles.stage(this, root, startsSession = true)
        // Earlier sessions' reports still read the Steam logs and VAC marker this session replaces.
        SessionArtifacts.awaitCollections()
        SessionArtifacts.finishAbandoned(this)
        VacDiagnostics.arm(this)
        com.droiddeck.launcher.agent.AgentGuest.reset(this)
        com.droiddeck.launcher.stores.gog.GogComet.begin()
        com.droiddeck.launcher.stores.StoreLaunchRequests.start(this)

        val sessionDir = openSessionFolder()
        val sessionLog = File(sessionDir, "session.log")

        Log.i(TAG, GpuClockPin.start(this))
        Log.i(TAG, GameThreadHints.start(this))

        val size = SessionState.outputSize
        val guest = ArrayList<String>()
        val steamHere = SessionState.mode == MODE_STEAM || SessionState.mode == MODE_DESKTOP
        if (steamHere) {
            runCatching { ComponentsManager.ensureForcedPackages(this) }
                .onSuccess { if (it != null) Log.i(TAG, it) }
                .onFailure { Log.w(TAG, "forced components", it) }
        }
        addClientEnvironment(guest, steamHere)
        if (steamHere) guest.add("BL_EPIC_APP_ID=" + com.droiddeck.launcher.stores.epic.EpicClient.shortcutAppId)
        val fastPathAt = guest.size

        val pulse = startAudio(guest, sessionDir)
        if (steamHere) {
            val encoder = VideoEncodeBridge(File(filesDir, "venc/venc.sock"))
            encoder.attach(this)
            components.add(encoder)
            guest.add("BL_VENC_SOCKET=" + encoder.socket.absolutePath)
        }

        guest.add("BL_WIDTH=" + size.first)
        guest.add("BL_HEIGHT=" + size.second)
        if (SessionState.followScreen) guest.add("BL_FOLLOW_OUTPUT=1")
        if (SessionState.hdr) {
            guest.add("BL_HDR=1")
            guest.add("DXVK_HDR=1")
            Log.i(TAG, "hdr: gamescope --hdr-enabled, DXVK_HDR=1")
        }
        guest.add("BL_FPS=" + SessionState.fpsLimit)
        guest.add("BL_REFRESH=" + Math.round(SessionState.refreshHz))
        guest.add("BL_LOG=" + sessionLog.path)
        guest.add("BL_DEBUG_DIR=" + sessionDir.path)

        val fakeInputDir = File(sessionRoot, "dev/input").apply { mkdirs() }
        val controllersOn = !File(Environment.getExternalStorageDirectory(), NO_PAD_SWITCH).exists()
        SessionState.deckPad = false
        deckBinds = emptyList()
        inputSysfs = null
        if (controllersOn) {
            addControllerEnvironment(guest, fakeInputDir, sessionDir)
            SessionState.padBridge?.syncConnectedControllers()
        }
        val noSteamGame = noSteamGame()
        if (noSteamGame) guest.add("BL_PIN_CORES=0")
        if ((steamHere || SessionState.program == NoSteamLaunch.EPIC) && com.droiddeck.launcher.stores.epic.EpicClient.read(this).installed) {
            runCatching { com.droiddeck.launcher.stores.epic.EpicClientLibrary.sync(this) }
                .onFailure { Log.w(TAG, "Epic library sync failed", it) }
            runCatching { com.droiddeck.launcher.stores.epic.EpicClient.prepareInstallDir(this) }
                .onFailure { Log.w(TAG, "Epic install directory setup failed", it) }
        }
        if (steamHere || noSteamGame) {
            val added = com.droiddeck.launcher.frontend.AddedGames.scan(this)
            val listing = com.droiddeck.launcher.frontend.AddedGames.writeListing(this, added)
            guest.add("BL_ADDED_GAMES=" + listing.path)
            if (OfflineMode.enabled(this)) guest.add("BL_STEAM_OFFLINE=1")
            if (com.droiddeck.launcher.gpu.Lossless.owned(this)) guest.add("BL_LOSSLESS_OWNED=1")
            if (added.isNotEmpty()) Log.i(TAG, "added games: " + added.joinToString { "${it.name} (${it.exe.name})" })
        }
        guest.add("BL_LAUNCH_DIR=" + sessionRoot.path)
        GameStorage.effective(this)?.let { guest.add("BL_LIBRARY_LABEL=" + it.label.replace('"', ' ')) }
        if (SessionState.mode == MODE_STEAM && SessionState.steamUi == "desktop") guest.add("BL_STEAM_UI=desktop")
        val shellGuest = ArrayList(guest).apply {
            add("SHELL=/bin/bash")
            add("TERM=xterm-256color")
            add("/bin/bash")
            add("-c")
            add("cd \"\$HOME\" && exec /bin/bash -i")
        }
        guest.add(LinuxRuntime.SESSION_SCRIPT)
        guest.add(SessionState.mode)
        if (SessionState.mode == MODE_STEAM) SessionState.steamUrl?.takeIf { it.startsWith("steam://") }?.let {
            guest.add(it)
            Log.i(TAG, "steam: handing the client $it")
        }
        if (SessionState.mode == MODE_STEAM) com.droiddeck.launcher.frontend.SessionInstalls.watch(this)
        if (SessionState.mode == MODE_RUN) {
            val program = SessionState.program
            if (program.isNullOrEmpty()) {
                Log.e(TAG, "run mode without a program")
                stopSession(65)
                return
            }
            guest.add(program)
            guest.addAll(SessionState.programArgs)
            Log.i(TAG, "run: $program (${SessionState.programArgs.size} arguments) under gamescope")
        }

        FileUtils.clear(File(LinuxRuntime.liveDir(this), "shm"))

        val binds = sessionBinds(controllersOn, fakeInputDir, sessionDir, guest, sessionRoot, runtimeDir)
        val fastPathKey = if (ProotFastPath.enabled(this)) {
            val prootBinds = LinuxRuntime.binds(
                this, sessionRoot, runtimeDir, Environment.getExternalStorageDirectory(), binds,
            )
            val root = LinuxRuntime.rootDir(this)
            ProotFastPath.key(root, prootBinds)?.also { key ->
                val env = ProotFastPath.guestEnv(root, prootBinds, key)
                guest.addAll(fastPathAt, env)
                shellGuest.addAll(fastPathAt, env)
                Log.i(TAG, "proot: fast path on (${prootBinds.size} binds)")
            } ?: run {
                Log.w(TAG, "proot: fast path off - ${prootBinds.size} binds (it holds ${ProotFastPath.MAX_BINDS}) or a path it cannot be told")
                null
            }
        } else null

        val command = LinuxRuntime.command(
            this, sessionRoot, runtimeDir, Environment.getExternalStorageDirectory(), binds, guest,
        )

        val hostEnv = HostEnvironment()
        hostEnv["PROOT_LOADER"] = LinuxRuntime.prootLoader(this).path
        hostEnv["PROOT_TMP_DIR"] = LinuxRuntime.prootTempDir(this).path
        if (SessionPrefs.prootNoSeccomp(this)) {
            hostEnv["PROOT_NO_SECCOMP"] = "1"
            Log.i(TAG, "proot: seccomp acceleration off by request")
        }
        fastPathKey?.let { ProotFastPath.hostEnv(it).let { (k, v) -> hostEnv[k] = v } }
        val prootLibs = LinuxRuntime.prootLibraryPath(this)
        if (prootLibs.isNotEmpty()) hostEnv["LD_LIBRARY_PATH"] = prootLibs

        val terminalCommand = LinuxRuntime.command(
            this, sessionRoot, runtimeDir, Environment.getExternalStorageDirectory(), binds, shellGuest,
        )
        val terminalHostEnvironment = LinkedHashMap(System.getenv())
        hostEnv.asArray().forEach { entry ->
            val separator = entry.indexOf('=')
            if (separator > 0) terminalHostEnvironment[entry.substring(0, separator)] = entry.substring(separator + 1)
        }
        if (gen == sessionGen && SessionState.running) {
            SessionTerminal.prepare(terminalCommand, terminalHostEnvironment.map { (key, value) -> "$key=$value" }.toTypedArray(), root, gen)
        }

        if (SessionState.mode == MODE_STEAM || SessionState.mode == MODE_DESKTOP) OfflineMode.apply(this, root)

        val networkLink = LinuxNetworkLinkComponent(this, root)
        networkLink.attach(this)
        networkLink.publish()
        components.add(networkLink)
        if (gen != sessionGen || !SessionState.running) {
            Log.i(TAG, "session stopped while it was starting; not launching it")
            if (gen == sessionGen) components.clear()
            return
        }
        components.forEach { it.start() }

        val line = command.joinToString(" ") {
            it.replace("\\", "\\\\").replace(" ", "\\ ")
        }
        watchLaunchRequests(sessionRoot)
        watchSyncWanted(root)
        EsyncPacks.fetchInBackground(this, root)
        val pid = HostProcess.start(line, hostEnv.asArray(), root, { status ->
            if (gen != sessionGen) {
                Log.i(TAG, "an earlier session's process ended ($status); the current one carries on")
                return@start
            }
            val program = programStatus(sessionRoot)
            Log.i(TAG, "session ended: $status" + if (program != null) " (program $program)" else "")
            stopSession(program ?: status ?: -1)
        }, null)
        Log.i(TAG, "session pid $pid, log ${sessionLog.path}")
        if (gen != sessionGen || !SessionState.running) {
            Log.i(TAG, "session stopped while its guest was starting; taking it down")
            if (gen == sessionGen) {
                launchWatcher?.stopWatching()
                launchWatcher = null
                syncWatcher?.stopWatching()
                syncWatcher = null
                components.reversed().forEach { runCatching { it.stop() } }
                components.clear()
            }
            if (pid > 1) Thread({ teardown(pid) }, "session-teardown").start()
            return
        }
        sessionPid = pid
        if (pid > 1) {
            raiseTracer(pid, gen)
            SessionEvents.guestStarted(pid)
        } else {
            SessionEvents.record("guest.start_failed", mapOf("pid" to pid))
            SessionEvents.fail("GUEST_START_FAILED", "The guest process could not be started", pid)
            stopSession(-1)
            return
        }
        if (gen == sessionGen && SessionState.running) {
            suspendController = SessionSuspendController(
                sessionRoot = { sessionPid },
                helperRoots = { components.mapNotNull { it.suspendPid().takeIf { pid -> pid > 1 } } },
                suspendAudio = {
                    if (!pulse.setSinkSuspended(true)) Log.w(TAG, "could not suspend audio sink")
                },
                resumeAudio = {
                    if (!pulse.setSinkSuspended(false)) Log.w(TAG, "could not resume audio sink")
                },
            )
            mainHandler.post { updateSuspendPolicy() }
            updateDownloadMonitoring()
        }
    }

    private fun openSessionFolder(): File {
        val sessionDir = SessionPaths.beginOrCurrent(this)
        val sessionLog = File(sessionDir, "session.log")
        SessionState.logFile = sessionLog
        SessionState.logDirectory = sessionDir
        SessionState.sessionId = sessionDir.name
        SessionState.eventsFile = File(sessionDir, "events.jsonl")
        SessionEvents.record("session.logs_ready", mapOf("logDir" to sessionDir.path))
        DeviceReport.write(this, File(sessionDir, "device.txt"), SessionState.mode)
        NetworkReport.write(this, File(sessionDir, "network.txt"))
        SessionLogCapture.start(File(sessionDir, "app.log"))
        return sessionDir
    }

    private fun addClientEnvironment(guest: MutableList<String>, steamHere: Boolean) {
        guest.add("/usr/bin/env")
        guest.add("-i")
        guest.add("DROIDDECK_BWRAP_SOCKET=" + com.droiddeck.launcher.runtime.BwrapSpawner.socket(this))
        guest.add("HOME=/root")
        guest.add("USER=root")
        guest.add("PATH=/usr/local/bin:/usr/bin:/bin")
        guest.add("TERM=xterm-256color")
        guest.add("LANG=C.UTF-8")
        guest.add("BL_STEAM_LANGUAGE=" + SteamLanguage.forLocale(
            com.droiddeck.launcher.core.AppLanguage.effective(this),
            com.droiddeck.launcher.core.AppLanguage.system(this).country,
        ))
        guest.add("TZ=" + java.util.TimeZone.getDefault().id)
        guest.add("XDG_RUNTIME_DIR=" + LinuxRuntime.GUEST_RUNTIME_DIR)
        guest.add("XDG_SESSION_TYPE=wayland")
        guest.add("WAYLAND_DISPLAY=wayland-0")
        guest.add("BL_ANDROID_CLIPBOARD=" + File(filesDir, "session/android-clipboard").path)
        guest.add("GAMESCOPE_FORCE_GENERAL_QUEUE=1")
        guest.add("MESA_LOADER_DRIVER_OVERRIDE=zink")
        guest.add("GALLIUM_DRIVER=zink")
        guest.add("LIBGL_KOPPER_DRI2=true")
        LinuxRuntime.vulkanIcd(this)?.let { guest.add("VK_ICD_FILENAMES=" + it.path) }
        val linuxDriverId = SessionPrefs.linuxDriver(this)
        SessionState.runtimeDriver = linuxDriverId
        com.droiddeck.launcher.gpu.AutoDriverTrial.sessionStarting(
            this,
            com.droiddeck.launcher.wayland.CompositorHost.displayChoice.takeIf { com.droiddeck.launcher.wayland.CompositorHost.isStarted }
                ?: SessionPrefs.androidDriver(this),
            linuxDriverId,
        )
        LinuxVulkanDriver.resolveIcdPath(this, linuxDriverId)
            ?.let { guest.add(LinuxVulkanDriver.ENV + "=" + it) }
        val tuOverride = tuDebugOverride()
        val tuDriver = sysmemDriver(linuxDriverId)
        (tuOverride ?: "sysmem".takeIf { tuDriver || SessionPrefs.tuSysmem(this) })?.let { guest.add("TU_DEBUG=$it") }
        com.droiddeck.launcher.gpu.TpUbwcHint.forThisDevice()?.let { guest.add("${com.droiddeck.launcher.gpu.TpUbwcHint.ENV}=$it") }
        if (SessionPrefs.zinkLazy(this)) {
            guest.add("ZINK_DESCRIPTORS=lazy")
            guest.add("ZINK_DEBUG=compact")
        }
        if (SessionPrefs.glThread(this)) guest.add("mesa_glthread=true")
        if (SessionPrefs.noGlError(this)) guest.add("MESA_NO_ERROR=1")
        guest.add("MESA_DISK_CACHE_DATABASE=1")
        guest.add("GLIBC_TUNABLES=glibc.pthread.rseq=0:glibc.malloc.top_pad=16777216")
        if (SessionState.mode == MODE_STEAM) guest.add("BL_MANGOAPP=" + (if (SessionPrefs.mangoapp(this)) "1" else "0"))
        SessionState.steamClientArch = if (steamHere) SessionPrefs.steamClientArch(this) else null
        SessionState.steamClientArch?.let { arch ->
            guest.add("BL_STEAM_CLIENT_ARCH=$arch")
            SessionPaths.current()?.let { SessionArtifacts.recordClient(it, arch) }
            if (arch == "x86_64") FexCatalog.cached(this)?.let { guest.addAll(FexCatalog.environment(it)) }
        }
        if (SessionState.mode == MODE_STEAM) {
            guest.add("BL_GAMESCOPE_STRETCH_16X9=" + (if (SessionPrefs.stretch16x9(this)) "1" else "0"))
        }
        if (SessionPrefs.noXalia(this)) guest.add("PROTON_USE_XALIA=0")
        guest.add("BL_SYNC=" + (if (SessionPrefs.fastSync(this)) "1" else "0"))
        guest.add("BL_SYNC_FALLBACK=" + (if (SessionPrefs.syncFallback(this)) "1" else "0"))
        guest.add("BL_FSYNC_FIRST=" + (if (SessionPrefs.fsyncFirst(this)) "1" else "0"))
        guest.add("BL_GAMESCOPE_REALTIME=" + (if (SessionPrefs.gamescopeRealtime(this)) "1" else "0"))
        if (SessionState.mode == MODE_STEAM && SessionPrefs.zramCompression(this) &&
            com.droiddeck.launcher.core.ZramSupport.supported()) guest.add("BL_ZRAM=1")
        guest.add("BANNER_AUDIO_DIRECT_DECAY=0")
        val extra = extraEnv() + com.droiddeck.launcher.agent.AgentEnv.beginSession(this)
        if (extra.none { it.startsWith("TU_DEBUG=") }) guest.add("$GAME_TU_DEBUG=" + (tuOverride ?: if (tuDriver) "sysmem" else ""))
        extra.forEach { guest.add(it) }
        val clientPinned = steamHere && SessionPrefs.clientCpusOverride(this)
        if (clientPinned) {
            guest.add("BL_CLIENT_CPUS=" + CpuCores.listOrAll(SessionPrefs.clientCpus(this)))
        }
        CpuCores.gameLaunchList(SessionPrefs.gameCpus(this), clientPinned)
            .takeIf { it.isNotEmpty() }?.let { guest.add("BL_GAME_CPUS=$it") }
    }

    private fun noSteamGame(): Boolean =
        SessionState.mode == MODE_RUN && SessionState.program in setOf(NoSteamLaunch.PROTON_RUN, NoSteamLaunch.EPIC)

    private fun startAudio(guest: MutableList<String>, sessionDir: File): PulseAudioComponent {
        val wantsDirectAudio = (SessionState.mode == MODE_STEAM || SessionState.mode == MODE_DESKTOP || noSteamGame()) &&
            SessionPrefs.directAudio(this)
        val wantsMic = SessionPrefs.micEnabled(this) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        guest.add("BL_MIC_ENABLED=" + if (wantsMic) "1" else "0")
        guest.add("BANNER_AUDIO_DIRECT_MIC=" + if (wantsMic) "1" else "0")
        val audioDir = File(filesDir, "directaudio").apply { mkdirs() }
        val relaySocket = File(audioDir, "relay.sock")
        val micFifo = if (wantsMic) File(audioDir, "mic.fifo") else null

        val audioLog = File(sessionDir, "audio.log")
        val pulse = PulseAudioComponent(this, micFifo?.absolutePath)
        pulse.setLogFile(audioLog)
        pulse.attach(this)
        guest.add("PULSE_SERVER=unix:" + pulse.socket().absolutePath)
        components.add(pulse)
        if (wantsDirectAudio || wantsMic) {
            val relay = DirectAudioRelayComponent(relaySocket, micFifo)
            relay.setLogFile(audioLog)
            relay.attach(this)
            components.add(relay)
        }
        if (wantsDirectAudio) {
            guest.add("BL_DIRECTAUDIO=/" + SessionFiles.DIRECTAUDIO_DIR)
            guest.add("BANNER_AUDIO_DIRECT_RELAY=" + relaySocket.absolutePath)
        }
        Log.i(TAG, "audio: client DirectAudio (in-process sink)" + (if (wantsDirectAudio) " + DirectAudio for games" else "")
            + (if (wantsMic) " + microphone" else "") +
            (if (SessionPrefs.micEnabled(this) && !wantsMic) " (microphone wanted but RECORD_AUDIO not granted)" else ""))
        return pulse
    }

    private fun addControllerEnvironment(guest: MutableList<String>, fakeInputDir: File, sessionDir: File) {
        FakeInputWriter.prepareRingSlots(fakeInputDir, 4)
        val builtinPad = SessionPrefs.builtinPad(this)
        val hiddenPad = SessionState.mode == MODE_STEAM && !builtinPad
        fakeInputDir.listFiles()?.forEach { file ->
            val node = Regex("event(\\d+)").matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
            if (file.name.startsWith(".uinput-") || (node != null && node >= FIRST_VIRTUAL_PAD) ||
                (hiddenPad && node == 0)) file.delete()
        }
        guest.add("FAKE_EVDEV_DIR=" + fakeInputDir.path)
        val rings = FakeInputWriter.getRingEnv(fakeInputDir)
        val presented = if (hiddenPad) rings?.split(';')?.filterNot { it.startsWith("0=") }?.joinToString(";") else rings
        if (!presented.isNullOrEmpty()) guest.add("FAKE_EVDEV_MEMFD_PATHS=$presented")
        guest.add("FAKE_EVDEV_IDENTITY=xbox360")
        guest.add("FAKE_EVDEV_VIBRATION=1")
        val uinput = !File(Environment.getExternalStorageDirectory(), NO_UINPUT_SWITCH).exists()
        val wantsDeck = uinput && builtinPad && SessionState.mode == MODE_STEAM &&
            SessionState.steamUi != "desktop" &&
            !File(Environment.getExternalStorageDirectory(), NO_DECK_PAD_SWITCH).exists()
        val wantsTouch = wantsDeck && !File(Environment.getExternalStorageDirectory(), NO_STEAM_TOUCH_SWITCH).exists()
        val wantsTriton = wantsDeck && SteamControllerBle.hasPairedController(this)
        deckBinds = if (wantsDeck) SteamDeckPad.prepare(this, fakeInputDir.parentFile!!.parentFile!!, wantsTouch, wantsTriton,
            SessionPrefs.padType(SessionPrefs.steamController(this))) else emptyList()
        SessionState.deckPad = deckBinds.isNotEmpty()
        if (wantsDeck && !SessionState.deckPad) Log.w(TAG, "deck pad: not available this session; the pad stays an Xbox 360 controller")
        logControllersAtStart()
        com.droiddeck.launcher.input.FakeInputWriter.setLowLatency(SessionPrefs.lowLatencyController(this), SessionPrefs.lowLatencyTouch(this))
        if (uinput) {
            guest.add("FAKE_EVDEV_UINPUT=1")
            if (SessionState.deckPad) {
                guest.add("FAKE_EVDEV_DECK=1")
                if (wantsTriton) guest.add("FAKE_EVDEV_TRITON=1")
                val padType = SessionPrefs.steamController(this)
                guest.add("FAKE_EVDEV_PAD_TYPE=$padType")
                com.droiddeck.launcher.input.FakeInputWriter.setPadType(SessionPrefs.padType(padType))
                InputSysfsComponent.padTypeChanged(SessionPrefs.padType(padType))
                if (wantsTouch) com.droiddeck.launcher.input.SteamTouchDevice.prepare(fakeInputDir)?.let {
                    guest.add("FAKE_TOUCHCTL_RING=" + it.path)
                }
                guest.add("FAKE_DECK_SYSFS_LISTING=" + SteamDeckPad.listingDir(fakeInputDir.parentFile!!.parentFile!!).path)
            }
        } else if (SessionState.mode == MODE_STEAM) {
            guest.add("FAKE_EVDEV_STEAM_VIRTUAL=1")
        }
        guest.add("SDL_JOYSTICK_DISABLE_UDEV=1")
        guest.add("SDL_HIDAPI_JOYSTICK_DISABLE_UDEV=1")
        if (!SessionState.deckPad) guest.add("SDL_JOYSTICK_HIDAPI=0")
        guest.add("FAKE_EVDEV_LOG=1")
        guest.add("FAKE_EVDEV_LOG_FILE=" + File(sessionDir, "pad.log").path)
        inputSysfs = InputSysfsComponent(fakeInputDir, File(fakeInputDir.parentFile!!.parentFile!!, "sys/input"),
            File(LinuxRuntime.rootDir(this), "run/udev/data"), SessionState.deckPad, !uinput && SessionState.mode == MODE_STEAM)
            .takeIf { it.prepare() }
        SessionState.fakeInputDir = fakeInputDir
    }

    private fun sessionBinds(
        controllersOn: Boolean,
        fakeInputDir: File,
        sessionDir: File,
        guest: MutableList<String>,
        sessionRoot: File,
        runtimeDir: File,
    ): ArrayList<String> {
        val binds = ArrayList<String>()
        if (controllersOn) binds.add(fakeInputDir.path + ":/dev/input")
        if (controllersOn) binds.addAll(deckBinds)
        if (controllersOn) inputSysfs?.let {
            it.attach(this)
            components.add(it)
            binds.addAll(it.binds())
        }
        val battery = BatteryComponent(
            File(filesDir, "session/sys/power_supply"),
            File(LinuxRuntime.rootDir(this), "run/vpower"),
        )
        battery.attach(this)
        components.add(battery)
        binds.add(battery.dir.path + ":/sys/class/power_supply")
        val hwmon = HwmonComponent(File(filesDir, "session/sys/hwmon"), LinuxRuntime.rootDir(this))
        if (hwmon.prepare()) {
            hwmon.attach(this)
            components.add(hwmon)
            binds.add(hwmon.dir.path + ":/sys/class/hwmon")
        }
        val cpuStat = CpuStatComponent(File(filesDir, "session/proc-stat"))
        if (cpuStat.prepare()) {
            cpuStat.attach(this)
            components.add(cpuStat)
            binds.add(cpuStat.file.path + ":/proc/stat")
        }
        val gpuMem = GpuMemComponent(File(LinuxRuntime.rootDir(this), "run/droiddeck-hud/gpu-mem"), LinuxRuntime.rootDir(this))
        if (gpuMem.prepare()) {
            gpuMem.attach(this)
            components.add(gpuMem)
        }
        val gpuStats = GpuStatsComponent(File(filesDir, "session/sys/kgsl-3d0"))
        if (gpuStats.prepare()) {
            gpuStats.attach(this)
            components.add(gpuStats)
            binds.addAll(gpuStats.binds())
        }
        if (controllersOn) components.add(RumbleComponent().also { it.attach(this) })
        val home = File(LinuxRuntime.rootDir(this), "root")
        File(home, "Storage").mkdirs()
        binds.add(Environment.getExternalStorageDirectory().path + ":/root/Storage")
        val library = GameStorage.effective(this)
        steamLibraryMounts = emptyMap()
        var storageDiagnosticLibrary: File? = null
        if (library != null) {
            val problem = GameStorage.prepare(this, library.path)
            if (problem == null) {
                File(LinuxRuntime.rootDir(this), "mnt/droiddeck-sd").mkdirs()
                File(LinuxRuntime.rootDir(this), "mnt/bannerlator-sd").mkdirs()
                try {
                    val configured = File(library.path)
                    val content = SecondaryLibrary.contentPath(configured)
                    binds.addAll(SecondaryLibrary.binds(filesDir, configured, content = content))
                    storageDiagnosticLibrary = content
                    steamLibraryMounts = SecondaryLibrary.CARD_GUESTS.associateWith { content }
                    val route = if (content === configured) "configured" else "verified-native"
                    Log.i(TAG, "game storage: ${content.path} ($route) -> /mnt/droiddeck-sd (\"${library.label}\"); prefixes and native tools private")
                } catch (e: Exception) {
                    Log.w(TAG, "game storage: private directories could not be prepared; internal only this session", e)
                }
            } else {
                Log.w(TAG, "game storage: $problem; internal only this session")
            }
        } else {
            Log.i(TAG, "game storage: internal only")
        }
        if (SessionState.mode == MODE_STEAM && SessionPrefs.storageDiagnosticsEnabled(this)) {
            val target = storageDiagnosticLibrary ?: File(LinuxRuntime.rootDir(this), "root/.local/share/Steam")
            val device = StorageDiagnostics.writeSnapshot(
                sessionDir, target,
                selectedLibrary = if (storageDiagnosticLibrary != null) "secondary" else "internal",
                removable = storageDiagnosticLibrary != null && library?.let {
                    runCatching { Environment.isExternalStorageRemovable(File(it.path)) }.getOrDefault(false)
                } == true,
            )
            if (device != null) {
                val envAt = guest.indexOf(LinuxRuntime.SESSION_SCRIPT).takeIf { it >= 0 } ?: guest.size
                guest.add(envAt, "BL_STORAGE_DIAGNOSTICS=1")
                guest.add(envAt + 1, "BL_STORAGE_DEVICE=$device")
                guest.add(envAt + 2, "BL_STORAGE_LOG=${File(sessionDir, "storage.log").absolutePath}")
            } else {
                Log.w(TAG, "storage diagnostics: selected library could not be identified; metadata snapshot only")
            }
        }
        for (root in com.droiddeck.launcher.frontend.AddedGames.roots(this)) {
            if (root.host.isDirectory && root.host.canRead()) {
                File(LinuxRuntime.rootDir(this), root.guest.removePrefix("/")).mkdirs()
                binds.add(root.host.path + ":" + root.guest)
                Log.i(TAG, "added games: ${root.host} -> ${root.guest}")
            } else {
                Log.w(TAG, "added games: ${root.host} is not a readable folder this session")
            }
        }
        for ((host, guest) in com.droiddeck.launcher.stores.StoreInstallRoot.externalRoots(this)) {
            if (host.isDirectory && host.canRead()) {
                File(LinuxRuntime.rootDir(this), guest.removePrefix("/")).mkdirs()
                binds.add(host.path + ":" + guest)
                Log.i(TAG, "store games: $host -> $guest")
            }
        }
        binds.addAll(com.droiddeck.launcher.runtime.UserApps.binds(this))
        val roms = SessionPrefs.romsDir(this).takeIf { it.isNotEmpty() }?.let { File(it) }
        if (roms != null && roms.isDirectory && roms.canRead()) {
            File(home, "ROMs").mkdirs()
            binds.add(roms.path + ":/root/ROMs")
            Log.i(TAG, "roms: $roms -> /root/ROMs")
        } else if (roms != null) {
            Log.w(TAG, "roms: $roms is not a readable folder; /root/ROMs not offered this session")
        }
        val libraries = ArrayList<Pair<String, String>>()
        if (storageDiagnosticLibrary != null && library != null) libraries.add(SecondaryLibrary.CARD_GUESTS[0] to library.label)
        val gamesLibraries = GameStorage.gamesFolderLibraries(this)
        if (gamesLibraries.isNotEmpty()) {
            var room = if (ProotFastPath.enabled(this)) {
                ProotFastPath.MAX_BINDS - LinuxRuntime.binds(this, sessionRoot, runtimeDir, Environment.getExternalStorageDirectory(), binds).size
            } else Int.MAX_VALUE
            for (games in gamesLibraries) {
                if (games.host.path + ":" + games.guest !in binds) continue
                val problem = GameStorage.prepare(this, games.host.path)
                if (problem != null) {
                    Log.w(TAG, "steam library: $problem")
                    continue
                }
                try {
                    val extra = SecondaryLibrary.binds(filesDir, games.host, listOf(games.guest)).drop(1)
                    if (extra.size > room) {
                        Log.w(TAG, "steam library: ${games.host} needs ${extra.size} binds, $room left for the fast path; not a library this session")
                        continue
                    }
                    binds.addAll(extra)
                    room -= extra.size
                    libraries.add(games.guest to games.label)
                    Log.i(TAG, "steam library: ${games.host} -> ${games.guest} (\"${games.label}\")")
                } catch (e: Exception) {
                    Log.w(TAG, "steam library: ${games.host} private directories could not be prepared", e)
                }
            }
        }
        val x64Libraries = org.json.JSONArray()
        // Desktop mode offers the x86-64 client either way; droiddeck-steam-x64 drops library folders it is not given.
        val x64Here = SessionPrefs.steamClientArch(this) == "x86_64" ||
            (SessionState.mode == MODE_DESKTOP &&
                com.droiddeck.launcher.storage.SteamStorage.x64ClientPresent(SessionPrefs.steamRoot(this, "x86_64")))
        if (storageDiagnosticLibrary != null && library != null && x64Here) {
            val x64 = File(library.path, GameStorage.X64_CARD_DIR)
            val problem = GameStorage.prepare(this, x64.path)
            if (problem != null) {
                Log.w(TAG, "x86-64 steam library: $problem")
            } else try {
                val room = if (ProotFastPath.enabled(this)) {
                    ProotFastPath.MAX_BINDS - LinuxRuntime.binds(this, sessionRoot, runtimeDir, Environment.getExternalStorageDirectory(), binds).size
                } else Int.MAX_VALUE
                val extra = SecondaryLibrary.binds(filesDir, x64, listOf(GameStorage.X64_CARD_GUEST)).drop(1)
                if (extra.size > room) {
                    Log.w(TAG, "x86-64 steam library: $x64 needs ${extra.size} binds, $room left for the fast path; not a library this session")
                } else {
                    binds.addAll(extra)
                    x64Libraries.put(org.json.JSONObject().put("path", GameStorage.X64_CARD_GUEST).put("label", library.label.replace('"', ' ').replace('\\', ' ')))
                    Log.i(TAG, "x86-64 steam library: $x64 -> ${GameStorage.X64_CARD_GUEST} (\"${library.label}\")")
                }
            } catch (e: Exception) {
                Log.w(TAG, "x86-64 steam library: $x64 private directories could not be prepared", e)
            }
        }
        try {
            val listing = File(filesDir, "session/steam-libraries-x64.json").apply { parentFile?.mkdirs() }
            listing.writeText(x64Libraries.toString())
            val envAt = guest.indexOf(LinuxRuntime.SESSION_SCRIPT).takeIf { it >= 0 } ?: guest.size
            guest.add(envAt, "BL_X64_STEAM_LIBRARIES=" + listing.path)
        } catch (e: Exception) {
            Log.w(TAG, "x86-64 steam library: the list could not be written; internal storage alone this session", e)
        }
        try {
            val listing = File(filesDir, "session/steam-libraries.json").apply { parentFile?.mkdirs() }
            val json = org.json.JSONArray()
            for ((path, label) in libraries) json.put(org.json.JSONObject().put("path", path).put("label", label.replace('"', ' ').replace('\\', ' ')))
            listing.writeText(json.toString())
            val envAt = guest.indexOf(LinuxRuntime.SESSION_SCRIPT).takeIf { it >= 0 } ?: guest.size
            guest.add(envAt, "BL_STEAM_LIBRARIES=" + listing.path)
        } catch (e: Exception) {
            Log.w(TAG, "steam library: the list could not be written; the card alone this session", e)
        }
        return binds
    }

    private fun teardown(prootPid: Int, expectedStartTime: Long? = null) {
        fun stillSameProcess(): Boolean = expectedStartTime == null || readStat(prootPid)?.second == expectedStartTime
        if (!stillSameProcess()) return
        val tree = descendants(prootPid)
        if (!stillSameProcess()) return
        android.os.Process.sendSignal(prootPid, 15)
        if (!waitForExit(prootPid, GRACE_MS)) {
            if (!stillSameProcess()) return
            Log.w(TAG, "proot $prootPid did not exit on SIGTERM; killing it")
            android.os.Process.killProcess(prootPid)
        }
        var killed = 0
        for ((pid, started) in tree) {
            val stat = readStat(pid) ?: continue
            if (stat.second != started) continue
            android.os.Process.killProcess(pid)
            killed++
        }
        if (killed > 0) Log.i(TAG, "swept $killed process(es) proot left behind")
    }

    private fun descendants(root: Int): List<Pair<Int, Long>> {
        val started = HashMap<Int, Long>()
        val children = HashMap<Int, MutableList<Int>>()
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val stat = readStat(pid) ?: return@forEach
            started[pid] = stat.second
            children.getOrPut(stat.first) { ArrayList() }.add(pid)
        }
        val out = ArrayList<Pair<Int, Long>>()
        val queue = ArrayDeque<Int>().apply { add(root) }
        val me = android.os.Process.myPid()
        while (queue.isNotEmpty()) {
            for (kid in children[queue.removeFirst()] ?: continue) {
                if (kid <= 1 || kid == me || kid == root) continue
                val when_ = started[kid] ?: continue
                out.add(Pair(kid, when_))
                queue.add(kid)
            }
        }
        return out
    }

    private fun readStat(pid: Int): Pair<Int, Long>? {
        val stat = try { File("/proc/$pid/stat").readText() } catch (e: Exception) { return null }
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        val fields = stat.substring(close + 2).trim().split(Regex("\\s+"))
        if (fields.size < 20) return null
        return try { Pair(fields[1].toInt(), fields[19].toLong()) } catch (e: NumberFormatException) { null }
    }

    private fun askSteamToExit(prootPid: Int) {
        if (!File("/proc/$prootPid").exists()) return
        val request = File(LinuxRuntime.sessionRoot(this), "steam-stop")
        try {
            request.writeText("")
        } catch (e: Exception) {
            Log.w(TAG, "could not ask the Steam client to exit", e)
            return
        }
        val started = System.currentTimeMillis()
        if (waitForExit(prootPid, STEAM_PICKUP_MS) || request.exists()) {
            request.delete()
            return
        }
        val exited = waitForExit(prootPid, STEAM_EXIT_MS)
        Log.i(TAG, "Steam client ${if (exited) "exited" else "did not exit"} after ${System.currentTimeMillis() - started} ms")
    }

    private fun waitForExit(pid: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!File("/proc/$pid").exists()) return true
            try { Thread.sleep(50) } catch (e: InterruptedException) { return false }
        }
        return !File("/proc/$pid").exists()
    }

    private fun queueSteamGame(gameId: String?) {
        if (!GameLaunchLink.validId(gameId.orEmpty()) || !SessionState.running ||
            SessionState.mode != MODE_STEAM || SessionState.stopRequested) {
            Log.w(TAG, "ignored game launch outside a running Steam session")
            return
        }
        val accepted = gameId!!
        if (pendingSteamGameId != null) Log.i(TAG, "replacing a queued game launch with $accepted")
        pendingSteamGameId = accepted
        pendingSteamGameGeneration = sessionGen
        if (SessionState.suspended) resumeSession()
        mainHandler.removeCallbacks(flushSteamGame)
        mainHandler.post(flushSteamGame)
    }

    private fun flushQueuedSteamGame() {
        val gameId = pendingSteamGameId ?: return
        val gen = pendingSteamGameGeneration
        if (gen != sessionGen || !SessionState.running || SessionState.stopRequested ||
            SessionState.mode != MODE_STEAM || !GameLaunchLink.validId(gameId)) {
            cancelQueuedSteamGame()
            return
        }
        if (SessionState.suspended) {
            resumeSession()
            mainHandler.postDelayed(flushSteamGame, GAME_LAUNCH_RETRY_MS)
            return
        }
        if (SessionState.phase != SessionPhase.READY) {
            mainHandler.postDelayed(flushSteamGame, GAME_LAUNCH_RETRY_MS)
            return
        }

        val directory = LinuxRuntime.sessionRoot(this)
        val staged = File(directory, STEAM_GAME_REQUEST + ".tmp")
        val request = File(directory, STEAM_GAME_REQUEST)
        val written = runCatching {
            directory.mkdirs()
            staged.writeText("$gameId\n")
            check(staged.renameTo(request)) { "could not publish Steam game request" }
        }.onFailure { Log.w(TAG, "could not queue Steam game $gameId", it) }.isSuccess
        if (!written) {
            mainHandler.postDelayed(flushSteamGame, GAME_LAUNCH_RETRY_MS)
            return
        }
        pendingSteamGameId = null
        pendingSteamGameGeneration = -1
        SessionEvents.record("steam.game_launch_requested", mapOf("gameId" to gameId, "generation" to gen))
        watchGameExit(gameId, gen)
        Log.i(TAG, "queued Steam game $gameId for the running client")
    }

    /**
     * A game launched from the front end (the Games tab, or another launcher's link): once it has
     * started and then been gone for [GAME_EXIT_GRACE_MS], ask the client to exit through the
     * session script's steam-stop request. The client quits cleanly, finishing its cloud sync, so
     * the session ends on its own and the activity returns to where the launch came from instead
     * of leaving the player in Big Picture. The grace covers launchers that restart the game.
     */
    private fun watchGameExit(gameId: String, gen: Int) {
        if (!SessionPrefs.returnAfterGame(this)) return
        val token = ++gameExitWatchToken
        Thread({
            val startBy = android.os.SystemClock.elapsedRealtime() + GAME_START_TIMEOUT_MS
            var seen = false
            var goneSince = 0L
            while (token == gameExitWatchToken && gen == sessionGen && SessionState.running && !SessionState.stopRequested) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (steamGameRunning()) {
                    if (!seen) Log.i(TAG, "game $gameId started; returning to the launcher when it closes")
                    seen = true
                    goneSince = 0L
                } else if (!seen) {
                    // Never started (an install, an update or a failed launch): leave the session be.
                    if (now > startBy) {
                        Log.i(TAG, "game $gameId did not start; not watching for its exit")
                        return@Thread
                    }
                } else if (goneSince == 0L) {
                    goneSince = now
                } else if (now - goneSince >= GAME_EXIT_GRACE_MS) {
                    mainHandler.post { returnAfterGame(gameId, gen, token) }
                    return@Thread
                }
                try { Thread.sleep(GAME_EXIT_POLL_MS) } catch (e: InterruptedException) { return@Thread }
            }
        }, "game-exit-watch").start()
    }

    private fun returnAfterGame(gameId: String, gen: Int, token: Int) {
        if (token != gameExitWatchToken || gen != sessionGen || !SessionState.running ||
            SessionState.stopRequested || SessionState.mode != MODE_STEAM || !SessionPrefs.returnAfterGame(this)) return
        val written = runCatching { File(LinuxRuntime.sessionRoot(this), "steam-stop").writeText("") }
            .onFailure { Log.w(TAG, "could not ask the client to exit after game $gameId", it) }.isSuccess
        if (!written) return
        SessionEvents.record("steam.return_after_game", mapOf("gameId" to gameId))
        Log.i(TAG, "game $gameId closed: asking the client to exit and returning to the launcher")
    }

    /** A Steam game is running: the client runs each one under its reaper, and Proton's script
     *  waits on the game with waitforexitandrun. Read from the command lines, as ComponentsManager does. */
    private fun steamGameRunning(): Boolean {
        val procs = File("/proc").listFiles() ?: return false
        for (p in procs) {
            if (!p.name.all(Char::isDigit)) continue
            val cmd = runCatching { File(p, "cmdline").readBytes() }.getOrNull() ?: continue
            if (cmd.isEmpty()) continue
            val text = String(cmd).replace('\u0000', ' ')
            if (text.contains("SteamLaunch AppId=") || text.contains("waitforexitandrun")) return true
        }
        return false
    }

    private fun resumeSession(panelVisible: Boolean = true) {
        if (panelVisible) activityVisible = true
        screenOn = readScreenOn()
        manualPauseRequested = false
        suspendDownloadNow = false
        if (steamSleepToken == null && (nativeSleepToken == null || nativeSleepFallback || nativeSleepIdle)) requestSteamWake()
        completeNativeSleep()
        completeSteamSleep()
        suspendAttemptFailed = false
        updateSuspendPolicy()
    }

    private fun cancelQueuedSteamGame(clearMailbox: Boolean = true) {
        mainHandler.removeCallbacks(flushSteamGame)
        pendingSteamGameId = null
        pendingSteamGameGeneration = -1
        if (clearMailbox) clearSteamGameMailbox()
    }

    private fun clearSteamGameMailbox() {
        val directory = LinuxRuntime.sessionRoot(this)
        File(directory, STEAM_GAME_REQUEST).delete()
        File(directory, STEAM_GAME_REQUEST + ".tmp").delete()
    }

    private fun killGuestLeftovers() {
        val me = android.os.Process.myPid()
        val rootfs = try { File(filesDir, "linuxfs").canonicalPath } catch (e: Exception) { return } + "/"
        val procs = File("/proc").listFiles { f -> f.name.all { it.isDigit() } } ?: return
        var killed = 0
        for (proc in procs) {
            val pid = proc.name.toIntOrNull() ?: continue
            if (pid == me) continue
            val exe = try { File(proc, "exe").canonicalPath } catch (e: Exception) { continue }
            if (!exe.startsWith(rootfs)) continue
            android.os.Process.killProcess(pid)
            killed++
        }
        if (killed > 0) Log.w(TAG, "killed $killed guest process(es) proot no longer tracked")
    }

    private fun killStragglers() {
        val me = android.os.Process.myPid()
        val procs = File("/proc").listFiles { f -> f.name.all { it.isDigit() } } ?: return
        var killed = 0
        for (proc in procs) {
            val pid = proc.name.toIntOrNull() ?: continue
            if (pid == me) continue
            val cmdline = try {
                File(proc, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
            } catch (e: Exception) {
                continue
            }
            if (STRAGGLERS.none { cmdline.contains(it) }) continue
            android.os.Process.killProcess(pid)
            killed++
        }
        if (killed > 0) Log.w(TAG, "killed $killed leftover process(es) of a previous session")
    }

    private var launchWatcher: android.os.FileObserver? = null

    private fun programStatus(dir: File): Int? =
        runCatching { File(dir, RUN_STATUS).readText().trim().toInt() }.getOrNull()

    private fun watchLaunchRequests(dir: File) {
        launchWatcher?.stopWatching()
        listOf("steam-sleep", "steam-sleep-state", "steam-sleep-ready", "steam-wake", "steam-wake.tmp",
            "steam-native-sleep", "steam-native-sleep.tmp", "steam-native-ready", "steam-native-ready.tmp",
            RUN_STATUS, "$RUN_STATUS.tmp", "epic-game", "epic-game.tmp", com.droiddeck.launcher.stores.epic.EpicClient.PLAYING,
            com.droiddeck.launcher.stores.epic.EpicClient.OPEN, com.droiddeck.launcher.stores.epic.EpicClient.FOCUSED_APP
        ).forEach { File(dir, it).delete() }
        val gen = sessionGen
        @Suppress("DEPRECATION")
        val watcher = object : android.os.FileObserver(dir.path, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == "steam-native-ready") {
                    val request = File(dir, path)
                    val token = runCatching { request.readText().trim() }.getOrNull() ?: return
                    request.delete()
                    mainHandler.post {
                        if (gen != sessionGen || !SessionState.running || token != nativeSleepToken) return@post
                        nativeSleepReady = true
                        nativeSleepTimeout?.let { mainHandler.removeCallbacks(it) }
                        nativeSleepTimeout = null
                        writeSteamSleepState(token, "paused")
                        SessionEvents.record("steam.native_sleep_prepared")
                        updateSuspendPolicy()
                    }
                    return
                }
                if (path == "steam-sleep") {
                    val request = File(dir, path)
                    val token = runCatching { request.readText().trim() }.getOrNull() ?: return
                    request.delete()
                    if (!token.matches(Regex("[a-f0-9]{32}"))) return
                    mainHandler.post {
                        if (gen != sessionGen || !SessionState.running) return@post
                        steamSleepToken = token
                        writeSteamSleepState(token, "paused")
                        suspendAttemptFailed = false
                        Log.i(TAG, "Steam requested session sleep")
                        updateSuspendPolicy()
                    }
                    return
                }
                if (path != null && path.startsWith("desktop-import-") && path.endsWith(".request")) {
                    val request = File(dir, path)
                    val text = runCatching { request.readText() }.getOrNull() ?: return
                    request.delete()
                    importForDesktop(dir, text)
                    return
                }
                if (path != "steam-launch") return
                val file = File(dir, path)
                val text = try { file.readText() } catch (e: Exception) { return }
                file.delete()
                var ui = "desktop"
                var url = ""
                var session = MODE_STEAM
                text.lineSequence().forEach { line ->
                    when {
                        line.startsWith("ui=") -> ui = line.removePrefix("ui=").trim()
                        line.startsWith("url=") -> url = line.removePrefix("url=").trim()
                        line.startsWith("session=") && line.removePrefix("session=").trim() == "desktop" -> session = MODE_DESKTOP
                    }
                }
                Log.i(TAG, "launch request from the session: " + (if (session == MODE_DESKTOP) "the desktop with " else "") +
                    "Steam $ui" + (if (url.isNotEmpty()) " $url" else ""))
                val next = Intent(this@SessionService, com.droiddeck.launcher.SessionActivity::class.java)
                    .putExtra(EXTRA_MODE, session)
                    .putExtra(EXTRA_STEAM_UI, ui)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (url.isNotEmpty()) next.putExtra(EXTRA_STEAM_URL, url)
                SessionState.relaunch = next
                android.os.Handler(android.os.Looper.getMainLooper()).post { stopSession(0) }
            }
        }
        watcher.startWatching()
        launchWatcher = watcher
    }

    private val desktopImports = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun importForDesktop(dir: File, text: String) {
        val fields = text.lineSequence().mapNotNull { line ->
            line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].trim() }
        }.toMap()
        val key = fields["id"]?.takeIf { it.matches(Regex("[0-9a-f]{16}")) } ?: return
        val guestPath = fields["path"]?.takeIf { it.startsWith("/") && !it.contains("/../") } ?: return
        val folder = fields["kind"] == "folder"
        val binds = LinuxRuntime.lastBinds(this).map { BwrapSpawner.Bind.parse(it) }
        val file = File(BwrapSpawner.hostPath(binds, LinuxRuntime.rootDir(this).path, guestPath))
        val context = applicationContext
        val lock = desktopImports.computeIfAbsent(key) { Any() }
        Thread({
            fun imported() = UserApps.desktopImport(context, key, guestPath)?.let { "ok $it" }
            val request = UserApps.Request(
                if (folder) UserApps.Source.Script(file) else UserApps.Source.AppImage(file),
                if (folder) file.parentFile?.name else null, null, mapOf(UserApps.DESKTOP_IMPORT to "$key\n$guestPath"),
            )
            val answer = synchronized(lock) {
                try {
                    imported() ?: UserApps.add(context, request) { _, _ -> }?.let { "error $it" }
                        ?: imported() ?: "error " + context.getString(R.string.user_apps_failed)
                } catch (e: Exception) {
                    Log.e(TAG, "Desktop import of $guestPath", e)
                    "error " + (e.message ?: context.getString(R.string.user_apps_failed))
                }
            }
            Log.i(TAG, "Desktop import of $guestPath: $answer")
            val result = File(dir, "desktop-import-$key.result")
            val partial = File(dir, result.name + ".tmp")
            runCatching { partial.writeText(answer.replace('\n', ' ') + "\n"); partial.renameTo(result) }
        }, "desktop-import").start()
    }

    @Volatile private var syncWatcher: android.os.FileObserver? = null

    private fun watchSyncWanted(root: File) {
        syncWatcher?.stopWatching()
        syncWatcher = null
        val store = EsyncPacks.store(root)
        if (!store.isDirectory) return
        @Suppress("DEPRECATION")
        val watcher = object : android.os.FileObserver(store.path, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != "wanted.tsv" && path != "tools.tsv") return
                EsyncPacks.fetchInBackground(this@SessionService, root)
            }
        }
        watcher.startWatching()
        syncWatcher = watcher
    }

    private fun updateSuspendPolicy() {
        if (!SessionState.running) return
        val hidden = !hasVisibleSurface
        val downloadsBlockingSuspend = steamDownloadsInBackground && SessionState.mode == MODE_STEAM &&
            suspendPolicy != SessionPrefs.SUSPEND_NEVER && hidden && downloadActive && !suspendDownloadNow
        if (suspendPolicy == SessionPrefs.SUSPEND_MANUAL && hidden && !downloadsBlockingSuspend) {
            manualPauseRequested = true
        }
        val controller = suspendController ?: return
        if (downloadsBlockingSuspend) acquireLocks()
        val steamPause = SessionState.mode == MODE_STEAM && !downloadsBlockingSuspend && when (suspendPolicy) {
            SessionPrefs.SUSPEND_AUTO, SessionPrefs.SUSPEND_NATIVE -> hidden
            SessionPrefs.SUSPEND_MANUAL -> manualPauseRequested
            else -> false
        }
        if (!steamPause) {
            completeNativeSleep()
        } else if (nativeSleepToken == null && steamSleepToken == null &&
            !SessionState.suspended && !suspendAttemptFailed) {
            prepareNativeSleep()
        }
        val prepared = SessionState.mode != MODE_STEAM || nativeSleepReady
        val shouldSuspend = steamSleepToken != null || when (suspendPolicy) {
            SessionPrefs.SUSPEND_AUTO -> hidden && prepared && !downloadsBlockingSuspend
            SessionPrefs.SUSPEND_NATIVE -> hidden && nativeSleepReady && !downloadsBlockingSuspend
            SessionPrefs.SUSPEND_MANUAL -> manualPauseRequested && prepared && !downloadsBlockingSuspend
            else -> false
        }
        if (suspendOperationPending || suspendAttemptFailed || shouldSuspend == SessionState.suspended) return
        val gen = sessionGen
        suspendOperationPending = true
        SessionEvents.record(if (shouldSuspend) "session.suspend_requested" else "session.resume_requested", mapOf(
            "policy" to suspendPolicy, "activityVisible" to activityVisible,
            "screenOn" to screenOn, "display" to sessionDisplayId, "steamSleep" to (steamSleepToken != null),
            "xrActivityVisible" to xrActivityVisible,
            "nativeSleep" to (nativeSleepToken != null),
        ))
        if (shouldSuspend) {
            controller.freeze { success ->
                mainHandler.post {
                    if (gen != sessionGen || !SessionState.running) return@post
                    suspendOperationPending = false
                    if (success) {
                        SessionState.suspended = true
                        SessionEvents.transition(SessionPhase.SUSPENDED, "session.suspended")
                        releaseLocks()
                        refreshNotification()
                    } else {
                        completeNativeSleep()
                        completeSteamSleep()
                        suspendAttemptFailed = true
                        Log.w(TAG, "could not confirm that the session stopped")
                    }
                    updateSuspendPolicy()
                }
            }
        } else {
            acquireLocks()
            controller.resume { success ->
                mainHandler.post {
                    if (gen != sessionGen || !SessionState.running) return@post
                    suspendOperationPending = false
                    if (success) {
                        SessionState.suspended = false
                        SessionEvents.transition(SessionEvents.resumePhase(), "session.resumed")
                        refreshNotification()
                    } else {
                        suspendAttemptFailed = true
                        Log.w(TAG, "could not confirm that the session resumed")
                    }
                    updateSuspendPolicy()
                }
            }
        }
    }

    private fun writeSteamSleepState(token: String, state: String) {
        val dir = LinuxRuntime.sessionRoot(this)
        runCatching {
            val staged = File(dir, "steam-sleep-state.tmp")
            staged.writeText("$token:$state\n")
            check(staged.renameTo(File(dir, "steam-sleep-state")))
        }.onFailure { Log.w(TAG, "could not acknowledge Steam sleep", it) }
    }

    private fun completeSteamSleep() {
        val token = steamSleepToken ?: return
        writeSteamSleepState(token, "awake")
        steamSleepToken = null
    }

    private fun resumeSteamSleepOnReturn() {
        if (suspendPolicy in setOf(SessionPrefs.SUSPEND_AUTO, SessionPrefs.SUSPEND_NATIVE) && hasVisibleSurface) {
            if (steamSleepToken == null && (nativeSleepToken == null || nativeSleepFallback || nativeSleepIdle)) requestSteamWake()
            completeNativeSleep()
            completeSteamSleep()
        }
    }

    private fun prepareNativeSleep() {
        val token = UUID.randomUUID().toString().replace("-", "")
        nativeSleepToken = token
        nativeSleepReady = false
        nativeSleepFallback = false
        nativeSleepIdle = suspendPolicy != SessionPrefs.SUSPEND_NATIVE
        val dir = LinuxRuntime.sessionRoot(this)
        runCatching {
            val staged = File(dir, "steam-native-sleep.tmp")
            staged.writeText(if (nativeSleepIdle) "$token idle\n" else "$token\n")
            check(staged.renameTo(File(dir, "steam-native-sleep")))
        }.onFailure { Log.w(TAG, "could not request Native sleep; using timed pause", it) }
        SessionEvents.record("steam.native_sleep_requested", mapOf("idle" to nativeSleepIdle))
        val gen = sessionGen
        val timeout = Runnable {
            if (gen != sessionGen || !SessionState.running || nativeSleepToken != token) return@Runnable
            nativeSleepTimeout = null
            nativeSleepReady = true
            nativeSleepFallback = true
            writeSteamSleepState(token, "paused")
            SessionEvents.record("steam.native_sleep_timeout")
            updateSuspendPolicy()
        }
        nativeSleepTimeout = timeout
        mainHandler.postDelayed(timeout, 4000L)
    }

    private fun completeNativeSleep() {
        nativeSleepTimeout?.let { mainHandler.removeCallbacks(it) }
        nativeSleepTimeout = null
        val token = nativeSleepToken ?: return
        writeSteamSleepState(token, "awake")
        val dir = LinuxRuntime.sessionRoot(this)
        File(dir, "steam-native-sleep").delete()
        File(dir, "steam-native-ready").delete()
        nativeSleepToken = null
        nativeSleepReady = false
        nativeSleepFallback = false
        nativeSleepIdle = false
    }

    private fun requestSteamWake() {
        if (!SessionState.running || SessionState.mode != MODE_STEAM) return
        val dir = LinuxRuntime.sessionRoot(this)
        runCatching {
            val staged = File(dir, "steam-wake.tmp")
            staged.writeText("wake\n")
            check(staged.renameTo(File(dir, "steam-wake")))
        }.onFailure { Log.w(TAG, "could not request Steam wake", it) }
    }

    private fun finishSessionStop(status: Int, stoppedGen: Int) {
        mainHandler.post {
            if (stoppedGen != sessionGen || SessionState.running) {
                Log.i(TAG, "session $stoppedGen finished stopping after a new one started")
                return@post
            }
            SessionState.suspended = false
            SessionState.pipActive = false
            pipTask = false
            SessionState.guestPid = -1
            com.droiddeck.launcher.agent.AgentGuest.stop()
            com.droiddeck.launcher.stores.StoreLaunchRequests.stop()
            Thread({ com.droiddeck.launcher.stores.CloudSaves.uploadAllDirty(applicationContext, "session-end", running = false) }, "cloud-session-end").start()
            runCatching { com.droiddeck.launcher.agent.AgentEnv.endSession(this) }
                .onFailure { Log.w(TAG, "clearing the agent's session environment", it) }
            releaseLocks()
            if (status == 0) {
                SessionEvents.transition(SessionPhase.IDLE, "session.stopped", mapOf("status" to status))
            } else {
                val code = SessionState.failureCode ?: "GUEST_EXIT"
                val message = SessionState.failureMessage ?: "Session guest exited with status $status"
                val failureStatus = SessionState.failureStatus ?: status
                SessionEvents.fail(code, message, failureStatus)
            }
            SessionState.steamVr = false
            com.droiddeck.launcher.glasses.Glasses.cancelSteamVrStart()
            com.droiddeck.launcher.glasses.Glasses.setVrActive(false)
            com.droiddeck.launcher.glasses.Glasses.set3dForVr(false)
            SessionState.notifyEnded(status)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun raiseTracer(pid: Int, gen: Int) {
        Thread({
            try {
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (gen != sessionGen || sessionPid != pid) return@Thread
            val nice = try {
                WaylandCompositor.nativeRaisePriority(pid, TRACER_NICE)
            } catch (t: Throwable) {
                Log.w(TAG, "tracer priority", t)
                return@Thread
            }
            Log.i(TAG, "proot tracer $pid: nice $nice (asked for $TRACER_NICE)")
        }, "tracer-priority").start()
    }

    private fun stopSession(status: Int) {
        cancelQueuedSteamGame()
        gameExitWatchToken++
        completeNativeSleep()
        val (ended, endedArch) = synchronized(stopLock) {
            if (!SessionState.running) return
            SessionState.running = false
            SessionPaths.take() to SessionState.steamClientArch
        }
        xrActivityVisible = false
        updateDownloadMonitoring()
        val stoppedGen = sessionGen
        val stoppedSession = SessionState.sessionId
        val launcherEnded = SessionState.mode == MODE_STEAM || SessionState.mode == MODE_DESKTOP || SessionState.program == NoSteamLaunch.EPIC
        SessionState.stopRequested = false
        SessionEvents.record("guest.exited", mapOf("status" to status))
        GpuClockPin.stop(this)
        GameThreadHints.stop()
        SessionEvents.transition(SessionPhase.STOPPING, "session.stopping", mapOf("status" to status))
        suspendOperationPending = false
        launchWatcher?.stopWatching()
        launchWatcher = null
        syncWatcher?.stopWatching()
        syncWatcher = null
        val prootPid = sessionPid
        sessionPid = -1
        val auxiliary = synchronized(auxiliaryProcessesLock) {
            auxiliaryProcesses.toMap().also { auxiliaryProcesses.clear() }
        }
        SessionTerminal.clear(sessionGen)
        val controller = suspendController
        suspendController = null
        if (ended != null) {
            VacDiagnostics.snapshot(this, ended)
            SessionArtifacts.collectionStarted(ended)
            Thread({ collectSessionArtifacts(ended, endedArch) }, "session-collect").start()
        }
        components.reversed().forEach {
            try {
                it.stop()
            } catch (e: Exception) {
                Log.w(TAG, "stopping ${it.javaClass.simpleName}", e)
            }
        }
        components.clear()
        FakeInputWriter.releaseAllRingSlots()
        com.droiddeck.launcher.input.SteamTouchDevice.release()
        val steamClientMayRun = SessionState.mode == MODE_STEAM || SessionState.mode == MODE_DESKTOP
        val finishAfterTeardown: () -> Unit = {
            if (prootPid > 1 || auxiliary.isNotEmpty()) {
                Thread({
                    if (prootPid > 1 && steamClientMayRun) askSteamToExit(prootPid)
                    com.droiddeck.launcher.stores.gog.GogComet.end(stoppedSession)
                    auxiliary.forEach { (pid, started) -> teardown(pid, started) }
                    if (prootPid > 1) teardown(prootPid)
                    killGuestLeftovers()
                    finishSessionStop(status, stoppedGen)
                    if (launcherEnded) importLauncherGames()
                }, "session-teardown").start()
            } else {
                Thread({
                    com.droiddeck.launcher.stores.gog.GogComet.end(stoppedSession)
                    finishSessionStop(status, stoppedGen)
                    if (launcherEnded) importLauncherGames()
                }, "gog-session-stop").start()
            }
        }
        if (prootPid > 1) acquireLocks()
        if (controller != null) controller.closeAndResume(finishAfterTeardown) else finishAfterTeardown()
    }

    private fun importLauncherGames() {
        runCatching { com.droiddeck.launcher.stores.epic.EpicClientLibrary.importClientGames(this) }
            .onFailure { Log.w(TAG, "client library scan failed: ${it.javaClass.simpleName}") }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (pipTask || xrActivityVisible) {
            Log.i(TAG, "panel task dismissed - retaining the session under its active surface policy")
            activityVisible = false
            updateSuspendPolicy()
            return
        }
        Log.i(TAG, "task removed - ending the session")
        stopSession(0)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopSession(0)
        GameThreadHints.stop() // also when the session never got as far as running
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        if (displayListenerRegistered) {
            (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null


    private fun acquireLocks() {
        try {
            if (wakeLock?.isHeld != true) {
                val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidDeck:session")?.apply {
                    setReferenceCounted(false)
                    acquire(12L * 60L * 60L * 1000L)
                }
            }
            Log.i(TAG, "wake lock held=${wakeLock?.isHeld}")
        } catch (t: Throwable) {
            Log.w(TAG, "no wake lock (${t.message}) - the session may be killed in the background")
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val allowed = DeviceSupport.lowLatencyWifiSafe()
            if (!allowed) Log.i(TAG, "wifi lock skipped: its low-latency mode resets this device")
            @Suppress("DEPRECATION")
            if (allowed && wifiLock?.isHeld != true) {
                wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DroidDeck:session-wifi")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
            Log.i(TAG, "wifi lock held=${wifiLock?.isHeld}")
        } catch (t: Throwable) {
            Log.w(TAG, "no wifi lock (${t.message}) - a backgrounded download may stall")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "releasing the wake lock", t)
        }
        wakeLock = null
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "releasing the wifi lock", t)
        }
        wifiLock = null
    }


    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.session_channel),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.session_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager?.createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, SessionActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val resume = PendingIntent.getActivity(
            this, 2,
            Intent(this, SessionActivity::class.java)
                .setAction(ACTION_RESUME)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(
                when {
                    SessionState.suspended -> R.string.session_notification_paused
                    downloadActive && steamDownloadsInBackground &&
                        suspendPolicy != SessionPrefs.SUSPEND_NEVER -> R.string.session_notification_downloads
                    else -> R.string.session_notification
                }
            ))
            .setContentIntent(open)
            .apply {
                if (SessionState.suspended) {
                    addAction(Notification.Action.Builder(null, getString(R.string.resume_session), resume).build())
                } else if (downloadActive && steamDownloadsInBackground &&
                    suspendPolicy != SessionPrefs.SUSPEND_NEVER) {
                    val suspend = PendingIntent.getService(
                        this@SessionService, 3,
                        Intent(this@SessionService, SessionService::class.java).setAction(ACTION_SUSPEND_NOW),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                    addAction(Notification.Action.Builder(null, getString(R.string.suspend_now), suspend).build())
                }
            }
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_session), stop).build())
            .setOngoing(true)
            .setShowWhen(false)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun updateDownloadMonitoring() {
        val enabled = SessionState.running && SessionState.mode == MODE_STEAM &&
            steamDownloadsInBackground && suspendPolicy != SessionPrefs.SUSPEND_NEVER
        if (!enabled) {
            val wasActive = downloadActive
            downloadMonitorTask?.let(mainHandler::removeCallbacks)
            downloadMonitorTask = null
            steamDownloadMonitor.reset()
            downloadActive = false
            if (wasActive) refreshNotification()
            return
        }
        if (downloadMonitorTask != null) return
        val poll = object : Runnable {
            override fun run() {
                if (downloadMonitorTask !== this || !SessionState.running ||
                    SessionState.mode != MODE_STEAM || !steamDownloadsInBackground ||
                    suspendPolicy == SessionPrefs.SUSPEND_NEVER) {
                    return
                }
                val wasActive = downloadActive
                downloadActive = steamDownloadMonitor.poll(
                    LinuxRuntime.rootDir(this@SessionService),
                    receivedBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid()),
                    client = SessionState.steamClientArch,
                    mounts = steamLibraryMounts,
                )
                if (wasActive != downloadActive) refreshNotification()
                updateSuspendPolicy()
                mainHandler.postDelayed(this, DOWNLOAD_POLL_MS)
            }
        }
        downloadMonitorTask = poll
        mainHandler.post(poll)
    }

    private fun logControllersAtStart() {
        val pads = ArrayList<String>()
        for (id in android.view.InputDevice.getDeviceIds()) {
            val d = android.view.InputDevice.getDevice(id) ?: continue
            if (!com.droiddeck.launcher.input.PadBridge.isFromController(d)) continue
            pads.add(String.format(java.util.Locale.ROOT, "\"%s\" (%04x:%04x)", d.name, d.vendorId, d.productId))
        }
        Log.i(TAG, "controllers at start: " + (if (pads.isEmpty()) "none" else pads.joinToString(", ")) +
            "; presented to the guest as " + if (SessionState.deckPad) "switchable, starting as " + SessionPrefs.steamController(this)
            else if (SessionState.mode == MODE_STEAM && !SessionPrefs.builtinPad(this)) "one per connected controller, no built-in pad"
            else "an Xbox 360 controller")
    }

    companion object {
        private const val TAG = "SessionService"
        private const val TRACER_NICE = -6
        private const val TU_DEBUG_SWITCH = "Download/droiddeck-tu-debug"
        private const val GAME_TU_DEBUG = "DROIDDECK_GAME_TU_DEBUG"
        private const val LEGACY_ENV_SWITCH = "Download/droiddeck-env"
        private const val ENV_SWITCH_NAME = "droiddeck-env"

        fun envSwitchFile(context: Context): File? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                context.getExternalFilesDir(null)?.let { File(it, ENV_SWITCH_NAME) }
            else null
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.droiddeck.launcher.STOP_SESSION"
        const val ACTION_RESUME = "com.droiddeck.launcher.RESUME_SESSION"
        const val ACTION_SUSPEND_NOW = "com.droiddeck.launcher.SUSPEND_NOW"
        private const val ACTION_PIP_BEGIN = "com.droiddeck.launcher.PIP_BEGIN"
        const val ACTION_LAUNCH_GAME = "com.droiddeck.launcher.LAUNCH_STEAM_GAME"
        const val ACTION_HOME_GUIDE = "com.droiddeck.launcher.HOME_GUIDE"
        const val ACTION_AGENT_START = "com.droiddeck.launcher.AGENT_START"
        private const val ACTION_SUSPEND_POLICY_CHANGED = "com.droiddeck.launcher.SUSPEND_POLICY_CHANGED"
        private const val ACTION_ACTIVITY_VISIBLE = "com.droiddeck.launcher.ACTIVITY_VISIBLE"
        private const val ACTION_ACTIVITY_HIDDEN = "com.droiddeck.launcher.ACTIVITY_HIDDEN"
        private const val ACTION_ACTIVITY_DISPLAY = "com.droiddeck.launcher.ACTIVITY_DISPLAY"
        private const val EXTRA_DISPLAY_ID = "displayId"
        private const val ACTION_XR_ACTIVITY_VISIBLE = "com.droiddeck.launcher.XR_ACTIVITY_VISIBLE"
        private const val ACTION_XR_ACTIVITY_HIDDEN = "com.droiddeck.launcher.XR_ACTIVITY_HIDDEN"
        private const val ACTION_SIGN_IN_HOLD = "com.droiddeck.launcher.SIGN_IN_HOLD"
        private const val EXTRA_HOLD = "hold"
        private const val ACTION_TRACK_AUXILIARY = "com.droiddeck.launcher.TRACK_AUXILIARY"
        private const val ACTION_STOP_AUXILIARY = "com.droiddeck.launcher.STOP_AUXILIARY"
        private const val ACTION_AUXILIARY_EXITED = "com.droiddeck.launcher.AUXILIARY_EXITED"
        private const val EXTRA_AUXILIARY_PID = "auxiliaryPid"
        private val STRAGGLERS = listOf("droiddeck-session", "gamescope", "Xwayland", "steamrtarm64",
            "steamwebhelper", "linuxfs/opt/android-host/proot", "/libproot.so", "pulseaudio/libpulseaudio.so")
        private const val GRACE_MS = 1200L
        private const val STEAM_PICKUP_MS = 1500L
        private const val STEAM_EXIT_MS = 10_000L
        private const val GAME_LAUNCH_RETRY_MS = 250L
        private const val DOWNLOAD_POLL_MS = 2_000L
        private const val STEAM_GAME_REQUEST = "steam-game"
        private const val GAME_URL_PREFIX = "steam://rungameid/"
        /** How long a front-end launch may take to start (downloads, first-run installs) before the exit watch gives up. */
        private const val GAME_START_TIMEOUT_MS = 20 * 60_000L
        /** How long the game must stay gone before the client is asked to exit: launchers restart games. */
        private const val GAME_EXIT_GRACE_MS = 10_000L
        private const val GAME_EXIT_POLL_MS = 2_000L
        private const val NO_PAD_SWITCH = "Download/droiddeck-no-pad"
        private const val NO_UINPUT_SWITCH = "Download/droiddeck-no-uinput"
        private const val NO_DECK_PAD_SWITCH = "Download/droiddeck-no-deck-pad"
        private const val NO_STEAM_TOUCH_SWITCH = "Download/droiddeck-no-steam-touch"
        private const val FIRST_VIRTUAL_PAD = 16

        const val EXTRA_MODE = "mode"
        const val MODE_STEAM = "steam"
        const val MODE_DESKTOP = "lxqt"
        const val MODE_RUN = "run"
        const val EXTRA_PROGRAM = "program"
        private const val RUN_STATUS = "run-status"
        const val EXTRA_PROGRAM_ARGS = "programArgs"
        const val EXTRA_STEAM_UI = "steamUi"
        const val EXTRA_STEAM_URL = "steamUrl"
        const val EXTRA_GAME_ID = "gameId"

        fun start(
            context: Context, mode: String = MODE_STEAM, program: String? = null,
            steamUi: String? = null, steamUrl: String? = null, programArgs: Array<String>? = null,
            displayId: Int = Display.DEFAULT_DISPLAY,
        ) {
            val intent = Intent(context, SessionService::class.java).putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_DISPLAY_ID, displayId)
            if (program != null) intent.putExtra(EXTRA_PROGRAM, program)
            if (programArgs != null) intent.putExtra(EXTRA_PROGRAM_ARGS, programArgs)
            if (steamUi != null) intent.putExtra(EXTRA_STEAM_UI, steamUi)
            if (steamUrl != null) intent.putExtra(EXTRA_STEAM_URL, steamUrl)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            if (!SessionState.running) {
                SessionState.stopRequested = true
                return
            }
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }

        fun beginPip(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_PIP_BEGIN))
        }

        fun setActivityVisible(context: Context, visible: Boolean, displayId: Int = Display.DEFAULT_DISPLAY) {
            if (!SessionState.running) return
            val action = if (visible) ACTION_ACTIVITY_VISIBLE else ACTION_ACTIVITY_HIDDEN
            context.startService(Intent(context, SessionService::class.java).setAction(action).putExtra(EXTRA_DISPLAY_ID, displayId))
        }

        fun setActivityDisplay(context: Context, displayId: Int) {
            if (!SessionState.running) return
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_ACTIVITY_DISPLAY).putExtra(EXTRA_DISPLAY_ID, displayId))
        }

        fun setXrActivityVisible(context: Context, visible: Boolean) {
            if (!SessionState.running) return
            val action = if (visible) ACTION_XR_ACTIVITY_VISIBLE else ACTION_XR_ACTIVITY_HIDDEN
            context.startService(Intent(context, SessionService::class.java).setAction(action))
        }

        fun holdForSignIn(context: Context, hold: Boolean) {
            if (!SessionState.running) return
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_SIGN_IN_HOLD).putExtra(EXTRA_HOLD, hold))
        }

        fun resume(context: Context) {
            if (!SessionState.running) return
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_RESUME))
        }

        fun launchGame(context: Context, gameId: String): Boolean {
            if (!GameLaunchLink.validId(gameId) || !SessionState.running ||
                SessionState.mode != MODE_STEAM || SessionState.stopRequested) return false
            return runCatching {
                context.startService(
                    Intent(context, SessionService::class.java)
                        .setAction(ACTION_LAUNCH_GAME)
                        .putExtra(EXTRA_GAME_ID, gameId),
                )
                true
            }.getOrDefault(false)
        }

        fun suspendPolicyChanged(context: Context) {
            if (!SessionState.running) return
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_SUSPEND_POLICY_CHANGED))
        }

        fun stopTerminalProcess(context: Context, pid: Int) {
            if (pid > 1) context.startService(
                Intent(context, SessionService::class.java)
                    .setAction(ACTION_STOP_AUXILIARY)
                    .putExtra(EXTRA_AUXILIARY_PID, pid),
            )
        }

        fun registerTerminalProcess(context: Context, pid: Int) {
            if (pid > 1) context.startService(
                Intent(context, SessionService::class.java)
                    .setAction(ACTION_TRACK_AUXILIARY)
                    .putExtra(EXTRA_AUXILIARY_PID, pid),
            )
        }

        fun terminalProcessExited(context: Context, pid: Int) {
            if (pid > 1) context.startService(
                Intent(context, SessionService::class.java)
                    .setAction(ACTION_AUXILIARY_EXITED)
                    .putExtra(EXTRA_AUXILIARY_PID, pid),
            )
        }
    }
}
