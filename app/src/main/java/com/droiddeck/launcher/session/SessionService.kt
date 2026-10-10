package com.droiddeck.launcher.session

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

/**
 * Owns the running session: the proot tree, the audio daemon, the network link and the locks that
 * keep all three alive while the app is not on screen.
 *
 * The session deliberately does **not** belong to the activity. Android demotes a process the
 * moment it loses its last visible activity, and the low-memory killer then reaps the guest - so
 * leaving Big Picture to answer a message would come back to a dead Steam. A foreground service
 * holds the process at perceptible priority, a partial wake lock keeps the CPU from dropping the
 * guest's threads, and a high-performance WiFi lock keeps the radio out of power-save so a
 * backgrounded download does not throttle to nothing. All three are Bannerlator's recipe, where
 * each was added to fix a failure seen on a device.
 *
 * The activity comes and goes on top of this; see [com.droiddeck.launcher.wayland.CompositorHost].
 */
class SessionService : Service() {
    override fun attachBaseContext(newBase: android.content.Context) =
        super.attachBaseContext(com.droiddeck.launcher.core.AppLanguage.wrap(newBase))

    private val components = java.util.concurrent.CopyOnWriteArrayList<SessionPart>()
    /** The Steam Deck controller's sysfs binds (SteamDeckPad), when this session has one. */
    private var deckBinds: List<String> = emptyList()
    private val stopLock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sessionPid = -1
    /** Auxiliary PTY-backed proot shells launched from a secondary-display terminal. */
    private val auxiliaryProcesses = HashMap<Int, Long>()
    private val auxiliaryProcessesLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var suspendController: SessionSuspendController? = null
    private val steamDownloadMonitor = SteamDownloadMonitor()
    private var downloadMonitorTask: Runnable? = null
    private var downloadActive = false
    private var suspendDownloadNow = false
    private var suspendPolicy = SessionPrefs.SUSPEND_MANUAL
    private var steamDownloadsInBackground = false
    private var activityVisible = true
    private var screenOn = true
    private var manualPauseRequested = false
    /** An explicit Steam sleep request is independent of the background policy. */
    private var steamSleepToken: String? = null
    private var nativeSleepToken: String? = null
    private var nativeSleepReady = false
    private var nativeSleepFallback = false
    private var nativeSleepTimeout: Runnable? = null
    private var suspendOperationPending = false
    private var pipTask = false
    private var suspendAttemptFailed = false
    /** One validated game request waiting for this Steam session to reach a usable state. */
    private var pendingSteamGameId: String? = null
    private var pendingSteamGameGeneration = -1
    private val flushSteamGame = object : Runnable {
        override fun run() = flushQueuedSteamGame()
    }
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> screenOn = true
                else -> return
            }
            if (screenOn) resumeSteamSleepOnReturn()
            suspendAttemptFailed = false
            updateSuspendPolicy()
        }
    }
    /** Counts sessions this service has started; a process exit from an earlier one is ignored. */
    @Volatile private var sessionGen = 0

    override fun onCreate() {
        super.onCreate()
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        screenOn = power?.isInteractive ?: true
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        screenReceiverRegistered = true
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
                suspendDownloadNow = false
                resumeSteamSleepOnReturn()
                suspendAttemptFailed = false
                updateSuspendPolicy()
                return START_NOT_STICKY
            }
            ACTION_ACTIVITY_HIDDEN -> {
                activityVisible = false
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
        screenOn = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
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
        SessionState.running = true
        if (SessionState.stopRequested) {
            stopSession(0)
            return START_NOT_STICKY
        }
        // Another Steam client on the device signs ours out seconds after every login; the one that
        // does it here runs from boot without being opened. Only the Steam session signs in.
        // The desktop too: its Steam launchers run the client right there (droiddeck-steam-launch).
        if (SessionState.mode == MODE_STEAM || SessionState.mode == MODE_DESKTOP) RivalClients.stopBeforeSession(this)
        SessionState.firstFrameSeen = false
        SessionState.guestPid = -1
        SessionEvents.transition(SessionPhase.STARTING_GUEST, "service.started", mapOf("mode" to SessionState.mode))
        // Re-arm after clearing readiness: a retained picture can present between the activity's
        // start request and this callback, and that notice must not be erased by the reset above.
        com.droiddeck.launcher.wayland.CompositorHost.newSession()
        // This session's number, claimed here and not when its process starts: the session it
        // replaces can report its own exit in the gap between the two, and that exit must not
        // be taken as this one's.
        val gen = ++sessionGen
        cancelQueuedSteamGame(clearMailbox = false)
        clearSteamGameMailbox()
        acquireLocks()
        Thread({
            // A tree the last session left behind (the app was killed or crashed, so its teardown
            // never ran) would hold the rootfs, the GPU and Steam's lock: nothing of ours should
            // be alive between sessions outside this process.
            OrphanReaper.reap("session starting")
            runSession(gen)
        }, "session-start").start()
        // The activity or the notification stops us; the system must not resurrect a session whose
        // guest processes are long gone.
        return START_NOT_STICKY
    }

    /**
     * Finish the session's folder after the session has stopped, then let go of it. The guest
     * script copies Steam's logs too, at a clean exit; this runs whatever killed the session,
     * which is when they matter. The collecting itself is [SessionArtifacts], shared with the
     * crash handler and the next-start sweep so a folder is finished whichever way it ends.
     */
    private fun collectSessionArtifacts(dir: File) {
        try {
            SessionArtifacts.collect(this, dir, "session stopped")
        } finally {
            // Last, so everything above is in the file it is about - and only this session's:
            // a session that replaced this one may already own the capture and the folder.
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

    private fun tuDebug(linuxDriverId: String): String? {
        val override = File(Environment.getExternalStorageDirectory(), TU_DEBUG_SWITCH)
            .takeIf { it.isFile }?.let { FileUtils.readString(it)?.trim() }
        if (!override.isNullOrEmpty()) return override
        if (SessionPrefs.tuSysmem(this)) return "sysmem"
        if (linuxDriverId.isEmpty()) return null
        val name = LinuxVulkanDriverManager(this).getDriverName(linuxDriverId).lowercase()
        return if (name.contains("710-720") || name.contains("710_720")) "sysmem" else null
    }

    // ── The session ─────────────────────────────────────────────────────────────────────────

    private fun runSession(gen: Int) {
        SessionTerminal.clear()
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
        SessionFiles.stage(this, root)
        com.droiddeck.launcher.agent.AgentGuest.reset(this)
        // The compat tool asks for an Epic game's sign-in code at every launch, the client's Play button included.
        com.droiddeck.launcher.stores.StoreLaunchRequests.start(this)

        val sessionDir = openSessionFolder()
        val sessionLog = File(sessionDir, "session.log")

        Log.i(TAG, GpuClockPin.start(this))

        val size = SessionState.outputSize
        val guest = ArrayList<String>()
        // The desktop's Steam launchers start the client there (droiddeck-steam-launch), through the
        // same set-up as a Steam session: it gets what the client and its games are started with.
        val steamHere = SessionState.mode == MODE_STEAM || SessionState.mode == MODE_DESKTOP
        // Some GPUs need a component Proton does not ship. Apply those before the guest, so the
        // next game launch copies them into the prefix. A download failure is logged and the
        // session still starts; the next session tries again.
        if (steamHere) {
            runCatching { ComponentsManager.ensureForcedPackages(this) }
                .onSuccess { if (it != null) Log.i(TAG, it) }
                .onFailure { Log.w(TAG, "forced components", it) }
        }
        addClientEnvironment(guest, steamHere)
        // Where the fast path's description of proot's view goes, once the binds are known.
        val fastPathAt = guest.size

        val pulse = startAudio(guest, sessionDir)

        guest.add("BL_WIDTH=" + size.first)
        guest.add("BL_HEIGHT=" + size.second)
        // Follow screen: the output is resized under gamescope when a foldable opens or closes,
        // and the session script asks gamescope to resize Steam's X screen along with it.
        if (SessionState.followScreen) guest.add("BL_FOLLOW_OUTPUT=1")
        if (SessionState.hdr) {
            // The activity opened the compositor's HDR gate: gamescope offers HDR to its clients
            // and DXVK takes the HDR10 swapchain when a game asks for one.
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
        if (controllersOn) addControllerEnvironment(guest, fakeInputDir, sessionDir)
        // The user's own games, for the runtime's shortcuts writer to put in the client's library
        // before the client starts (see frontend/AddedGames and droiddeck-steam-shortcuts).
        if (steamHere) {
            val added = com.droiddeck.launcher.frontend.AddedGames.scan(this)
            val listing = com.droiddeck.launcher.frontend.AddedGames.writeListing(this, added)
            guest.add("BL_ADDED_GAMES=" + listing.path)
            if (OfflineMode.enabled(this)) guest.add("BL_STEAM_OFFLINE=1")
            if (com.droiddeck.launcher.gpu.Lossless.owned(this)) guest.add("BL_LOSSLESS_OWNED=1")
            if (added.isNotEmpty()) Log.i(TAG, "added games: " + added.joinToString { "${it.name} (${it.exe.name})" })
        }
        // Where the guest leaves a request for another session (the desktop's Steam launchers).
        guest.add("BL_LAUNCH_DIR=" + sessionRoot.path)
        // The second library's name, for droiddeck-steam-library; the bind itself is made below.
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
        // A program under gamescope: the script's run mode takes the path (an AppImage, a script
        // or a binary inside the runtime). This is how an emulator gets the GPU - the desktop's
        // KWin composites in software and offers no dma-buf, so a Vulkan swapchain cannot exist
        // there (RPCS3 died with VK_ERROR_SURFACE_LOST); gamescope's Xwayland is the path the
        // Steam games already render through.
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

        // Android has no /dev/shm; the cache stands in for it and, unlike the real thing, keeps
        // whatever a session leaves behind. The client abandons tens of megabytes of streams a run.
        FileUtils.clear(File(cacheDir, "shm"))

        val binds = sessionBinds(controllersOn, fakeInputDir, sessionDir, guest, sessionRoot, runtimeDir)
        // The fast path is told exactly the rootfs and binds proot is given (ProotFastPath).
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
        hostEnv["PROOT_TMP_DIR"] = cacheDir.path
        // proot links against a libtalloc beside it, and Android's linker does not search an
        // executable's own directory: unnamed, the process dies before it starts and says so only
        // in `logcat -b crash`.
        // proot reads this itself, so it belongs in proot's own environment rather than the guest's.
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

        // Whether the client signs in to Valve or starts offline: read once, while it starts, and
        // rewritten by the client when it exits, so it is set again here at every session start.
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
        // One session replacing another (the desktop's Steam launchers): the old proot is killed
        // by the teardown a second after the new one has started, and its exit used to arrive
        // here as "session ended: 137" and end the NEW session. An exit belongs to the session
        // that started it.
        val pid = HostProcess.start(line, hostEnv.asArray(), root, { status ->
            if (gen != sessionGen) {
                Log.i(TAG, "an earlier session's process ended ($status); the current one carries on")
                return@start
            }
            Log.i(TAG, "session ended: $status")
            stopSession(status ?: -1)
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

    /** The session's own log folder, opened and filled with what is known before anything starts. */
    private fun openSessionFolder(): File {
        // One folder per session, claimed by whoever started first - the activity starts the
        // compositor before this service runs - so the compositor's log lands in the same place.
        val sessionDir = SessionPaths.beginOrCurrent(this)
        val sessionLog = File(sessionDir, "session.log")
        SessionState.logFile = sessionLog
        SessionState.logDirectory = sessionDir
        SessionState.sessionId = sessionDir.name
        SessionState.eventsFile = File(sessionDir, "events.jsonl")
        SessionEvents.record("session.logs_ready", mapOf("logDir" to sessionDir.path))
        // Written first, so a session that dies in its first second still says what it ran on.
        DeviceReport.write(this, File(sessionDir, "device.txt"), SessionState.mode)
        NetworkReport.write(this, File(sessionDir, "network.txt"))
        // Everything the app decides from here on - the driver it chose, the audio line, a rival
        // client stopped, the exit status - reaches logcat and nowhere a user can get at. Mirror it.
        SessionLogCapture.start(File(sessionDir, "app.log"))
        return sessionDir
    }

    /** The guest's base environment: paths, the display, the GL/Vulkan stack and the client's switches. */
    private fun addClientEnvironment(guest: MutableList<String>, steamHere: Boolean) {
        guest.add("/usr/bin/env")
        guest.add("-i")
        guest.add("HOME=/root")
        guest.add("USER=root")
        guest.add("PATH=/usr/local/bin:/usr/bin:/bin")
        guest.add("TERM=xterm-256color")
        guest.add("LANG=C.UTF-8")
        // Steam's interface language: the app's (Setup's choice, or the system's). The system's
        // region decides between Spain's and Latin American Spanish, which the app has one of.
        guest.add("BL_STEAM_LANGUAGE=" + SteamLanguage.forLocale(
            com.droiddeck.launcher.core.AppLanguage.effective(this),
            com.droiddeck.launcher.core.AppLanguage.system(this).country,
        ))
        // Without this the session is UTC: the client's clock, its logs and every timestamp in a
        // session bundle sit hours off the device's. Bannerlator carries the same line.
        guest.add("TZ=" + java.util.TimeZone.getDefault().id)
        guest.add("XDG_RUNTIME_DIR=" + LinuxRuntime.GUEST_RUNTIME_DIR)
        guest.add("XDG_SESSION_TYPE=wayland")
        guest.add("WAYLAND_DISPLAY=wayland-0")
        guest.add("BL_ANDROID_CLIPBOARD=" + File(filesDir, "session/android-clipboard").path)
        guest.add("GAMESCOPE_FORCE_GENERAL_QUEUE=1")
        // Steam's CEF needs GL and the rootfs ships no native GL driver: route it through Zink.
        guest.add("MESA_LOADER_DRIVER_OVERRIDE=zink")
        guest.add("GALLIUM_DRIVER=zink")
        guest.add("LIBGL_KOPPER_DRI2=true")
        LinuxRuntime.vulkanIcd(this)?.let { guest.add("VK_ICD_FILENAMES=" + it.path) }
        // An imported glibc Turnip, when one is set (by the user, or by Auto): the session script
        // checks the manifest and its library from inside and points the loader at it with
        // VK_DRIVER_FILES, so the runtime's own driver above stays untouched and is what a bad
        // import falls back to. One driver for every session: the driver's shader cache is keyed on
        // its build, and with one per mode every emulator compiled its shaders twice.
        val linuxDriverId = SessionPrefs.linuxDriver(this)
        LinuxVulkanDriver.resolveIcdPath(this, linuxDriverId)
            ?.let { guest.add(LinuxVulkanDriver.ENV + "=" + it) }
        // Turnip's own debug switches, for the runtime's driver and everything on it. The file in
        // Downloads holds the value verbatim ("sysmem", "sysmem,deck_emu"); with nothing there, an
        // imported driver from the A710/A720/A722 legs gets "sysmem" on its own, which is what both
        // its authors advise for those GPUs and what nothing else in the list needs.
        tuDebug(linuxDriverId)?.let { guest.add("TU_DEBUG=$it") }
        // Zink renders the client's UI (Chromium -> ANGLE -> Zink -> Turnip). Lazy descriptors is
        // the mode Zink recommends where the driver has no descriptor buffer, and what Ludashi ships
        // by default for its Zink path; a switch here because on one Fold the menus run at 14 fps.
        // compact packs Zink's descriptor sets into fewer, so a draw binds less (WinNative's default).
        if (SessionPrefs.zinkLazy(this)) {
            guest.add("ZINK_DESCRIPTORS=lazy")
            guest.add("ZINK_DEBUG=compact")
        }
        // The rest of the client-interface switches (SessionPrefs): GL marshalled off the calling
        // thread, no GL error checks, and the client run as SteamOS runs it (the script reads
        // BL_STEAMDECK; it is the one that builds the command line).
        if (SessionPrefs.glThread(this)) guest.add("mesa_glthread=true")
        if (SessionPrefs.noGlError(this)) guest.add("MESA_NO_ERROR=1")
        // Mesa's shader cache as one database instead of a file per entry. Every lookup in the
        // file cache is an open and every store an open and a rename, each a proot stop (a rename
        // two), for every pipeline DXVK, vkd3d-proton and Zink compile or find; the database is
        // opened once and read and written with pread/pwrite, which proot never sees. Mesa removes
        // the old folder itself once it has gone a week untouched.
        guest.add("MESA_DISK_CACHE_DATABASE=1")
        // glibc's per-thread rseq registration is refused by Android's app seccomp policy, so
        // every thread start paid a SIGSYS that proot answers; the malloc top pad grows the heap
        // 16 MB at a time instead of 128 KB, and every brk(2) is a proot stop too.
        guest.add("GLIBC_TUNABLES=glibc.pthread.rseq=0:glibc.malloc.top_pad=16777216")
        if (SessionState.mode == MODE_STEAM) guest.add("BL_STEAMDECK=" + (if (SessionPrefs.steamDeckMode(this)) "1" else "0"))
        if (SessionState.mode == MODE_STEAM) guest.add("BL_MANGOAPP=" + (if (SessionPrefs.mangoapp(this)) "1" else "0"))
        if (steamHere) guest.add("BL_STEAM_CHANNEL=" + SessionPrefs.steamChannel(this))
        if (SessionState.mode == MODE_STEAM) {
            guest.add("BL_GAMESCOPE_STRETCH_16X9=" + (if (SessionPrefs.stretch16x9(this)) "1" else "0"))
        }
        // Proton's own gate for its xalia helper (its `proton` script reads this, and sets
        // XALIA_SUPPORTED_ONLY itself otherwise). Skipped by default: under FEX it costs every game
        // a slice of a core for gamepad navigation the session already has.
        if (SessionPrefs.noXalia(this)) guest.add("PROTON_USE_XALIA=0")
        guest.add("BL_SYNC=" + (if (SessionPrefs.fastSync(this)) "1" else "0"))
        guest.add("BL_SYNC_FALLBACK=" + (if (SessionPrefs.syncFallback(this)) "1" else "0"))
        guest.add("BL_FSYNC_FIRST=" + (if (SessionPrefs.fsyncFirst(this)) "1" else "0"))
        // gamescope's realtime Vulkan queues (the session script turns this into
        // GAMESCOPE_FORCE_VULKAN_REALTIME); off unless the user turns it on.
        guest.add("BL_GAMESCOPE_REALTIME=" + (if (SessionPrefs.gamescopeRealtime(this)) "1" else "0"))
        guest.add("BANNER_AUDIO_DIRECT_DECAY=0")
        // Anything else, for a device that cannot be reached with a debugger: droiddeck-env in the
        // app's own external files (envSwitchFile) holds KEY=VALUE lines that go into the session's
        // environment as written, after ours, so a line here wins. Zink and Turnip tunables
        // (ZINK_DESCRIPTORS=lazy, MESA_*), gamescope's, the client's - whatever the experiment
        // needs, without a build per attempt.
        extraEnv().forEach { guest.add(it) }
        // The agent bridge's experiment lines (AgentEnv), after the user's so an agent's win.
        com.droiddeck.launcher.agent.AgentEnv.beginSession(this).forEach { guest.add(it) }
        // Core masks, Bannerlator's two (cfca3912). The client's is sent whenever the override is
        // on, even naming every core: it exists to undo the pin Steam applies to its own interface
        // renderer, and the scheduler's default is exactly what that pin takes away. A game's is
        // sent only when it is a real restriction - a game has no pin of its own to undo.
        if (steamHere && SessionPrefs.clientCpusOverride(this)) {
            guest.add("BL_CLIENT_CPUS=" + CpuCores.listOrAll(SessionPrefs.clientCpus(this)))
        }
        // The game cores also pin the desktop and a program from the rail; without a choice the
        // session script picks every core but the slowest cluster for those (program_cores).
        CpuCores.restrictionOrEmpty(SessionPrefs.gameCpus(this))
            .takeIf { it.isNotEmpty() }?.let { guest.add("BL_GAME_CPUS=$it") }
    }

    /** PulseAudio, and the DirectAudio relay when a path needs it; returns the daemon for suspend and resume. */
    private fun startAudio(guest: MutableList<String>, sessionDir: File): PulseAudioComponent {
        // PulseAudio always: the client is a native Linux program and has no other way to make a
        // sound - its menus, its music and its voice chat all go through here. DirectAudio is not
        // an alternative to it on this path: it replaces the audio driver INSIDE Wine, so it
        // changes what games do and leaves the client alone. The microphone is its own opt-in on
        // top, and the helper only opens an input stream when asked - so a user who wants game
        // sound but no recording gets exactly that, and Android's recording indicator stays off.
        // The desktop starts Windows programs through the same Proton wrappers (droiddeck-proton-run).
        val wantsDirectAudio = (SessionState.mode == MODE_STEAM || SessionState.mode == MODE_DESKTOP) &&
            SessionPrefs.directAudio(this)
        val wantsMic = SessionPrefs.micEnabled(this) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // Both paths sit under the app's files directory, which the session binds at its own path,
        // so the same string is valid on both sides and nothing has to be translated.
        val audioDir = File(filesDir, "directaudio").apply { mkdirs() }
        val relaySocket = File(audioDir, "relay.sock")
        val micFifo = if (wantsMic) File(audioDir, "mic.fifo") else null

        val audioLog = File(sessionDir, "audio.log")
        val pulse = PulseAudioComponent(this, micFifo?.absolutePath)
        // The client's own sound: DirectAudio's engine inside the daemon, one step from Android (the
        // daemon runs on the Android side). The relay below is for games and the microphone only.
        pulse.setLogFile(audioLog)
        pulse.attach(this)
        guest.add("PULSE_SERVER=unix:" + pulse.socket().absolutePath)
        components.add(pulse)
        if (wantsDirectAudio || wantsMic) {
            // After the daemon in the list, so it can wait for the pipe the daemon makes.
            val relay = DirectAudioRelayComponent(relaySocket, micFifo)
            relay.setLogFile(audioLog)
            relay.attach(this)
            components.add(relay)
        }
        if (wantsDirectAudio) {
            // Read by the Proton wrappers, which point Wine at the driver and name it in the
            // prefix. Absent, they take an early return and the game uses Proton's own audio - so
            // this variable is the whole of the selection.
            guest.add("BL_DIRECTAUDIO=/" + SessionFiles.DIRECTAUDIO_DIR)
            guest.add("BANNER_AUDIO_DIRECT_RELAY=" + relaySocket.absolutePath)
        }
        Log.i(TAG, "audio: client DirectAudio (in-process sink)" + (if (wantsDirectAudio) " + DirectAudio for games" else "")
            + (if (wantsMic) " + microphone" else "") +
            (if (SessionPrefs.micEnabled(this) && !wantsMic) " (microphone wanted but RECORD_AUDIO not granted)" else ""))
        return pulse
    }

    /** The fake evdev pads: the ring files the app writes and the identity SDL and Steam see. */
    private fun addControllerEnvironment(guest: MutableList<String>, fakeInputDir: File, sessionDir: File) {
        FakeInputWriter.prepareRingSlots(fakeInputDir, 4)
        // Virtual pads a client made last session (event16 and up, and their hidden rings) are
        // not there any more; a client that crashed never took its own down.
        fakeInputDir.listFiles()?.forEach { file ->
            val node = Regex("event(\\d+)").matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
            if (file.name.startsWith(".uinput-") || (node != null && node >= FIRST_VIRTUAL_PAD)) file.delete()
        }
        guest.add("FAKE_EVDEV_DIR=" + fakeInputDir.path)
        val rings = FakeInputWriter.getRingEnv(fakeInputDir)
        if (!rings.isNullOrEmpty()) guest.add("FAKE_EVDEV_MEMFD_PATHS=$rings")
        // SDL and Steam key their mapping database on bus+vendor+product: only a known
        // identity gets the standard layout without the user configuring the pad by hand.
        guest.add("FAKE_EVDEV_IDENTITY=xbox360")
        guest.add("FAKE_EVDEV_VIBRATION=1")
        val uinput = !File(Environment.getExternalStorageDirectory(), NO_UINPUT_SWITCH).exists()
        // In Steam, the pad is what a Deck's is: a Steam Deck controller the client reads over
        // hidraw (SteamDeckPad), which only works with Steam Input's virtual pad for games to read.
        // Decided only once its sysfs is in place: without it the client would find no Deck, and
        // with the Deck asked for the pad's own nodes are withdrawn - no controller at all.
        val wantsDeck = uinput && SessionState.mode == MODE_STEAM &&
            SessionState.steamUi != "desktop" &&
            SessionPrefs.steamController(this) == SessionPrefs.CONTROLLER_DECK &&
            !File(Environment.getExternalStorageDirectory(), NO_DECK_PAD_SWITCH).exists()
        // Steam's touch controller rides on the Deck's sysfs and is offered only beside it.
        val wantsTouch = wantsDeck && !File(Environment.getExternalStorageDirectory(), NO_STEAM_TOUCH_SWITCH).exists()
        deckBinds = if (wantsDeck) SteamDeckPad.prepare(this, fakeInputDir.parentFile!!.parentFile!!, wantsTouch) else emptyList()
        SessionState.deckPad = deckBinds.isNotEmpty()
        if (wantsDeck && !SessionState.deckPad) Log.w(TAG, "deck pad: not available this session; the pad stays an Xbox 360 controller")
        logControllersAtStart()
        if (uinput) {
            // /dev/uinput, stood in for by libfakeinput: the virtual pad Steam Input makes for a
            // game becomes a node the game reads, carrying the player's layout, as on a Deck.
            guest.add("FAKE_EVDEV_UINPUT=1")
            if (SessionState.deckPad) {
                guest.add("FAKE_EVDEV_DECK=1")
                // Steam's touch controller (SteamTouchDevice): the file the app and libfakeinput share.
                if (wantsTouch) com.droiddeck.launcher.input.SteamTouchDevice.prepare(fakeInputDir)?.let {
                    guest.add("FAKE_TOUCHCTL_RING=" + it.path)
                }
                guest.add("FAKE_DECK_SYSFS_LISTING=" + SteamDeckPad.listingDir(fakeInputDir.parentFile!!.parentFile!!).path)
            }
        } else if (SessionState.mode == MODE_STEAM) {
            // Without it, games see the pad itself wearing Steam Input's virtual-gamepad identity -
            // for games the client starts only. Everywhere else (the desktop, a program from the
            // rail) that identity hides the pad: SDL ignores a Steam virtual gamepad unless it
            // runs under Steam, so every SDL emulator came up with no controller.
            guest.add("FAKE_EVDEV_STEAM_VIRTUAL=1")
        }
        guest.add("SDL_JOYSTICK_DISABLE_UDEV=1")
        guest.add("SDL_HIDAPI_JOYSTICK_DISABLE_UDEV=1")
        // The client reads a Deck controller through SDL's HIDAPI; a hint in the environment
        // outranks the client's own. Nothing else is shown the Deck (libfakeinput).
        if (!SessionState.deckPad) guest.add("SDL_JOYSTICK_HIDAPI=0")
        // The guest side of the pads (libfakeinput: which pads were opened, the Deck's hidraw and why
        // it was refused) in pad.log beside the session's other logs, so it is in every shared zip.
        // Setup-time lines only, nothing per input event.
        guest.add("FAKE_EVDEV_LOG=1")
        guest.add("FAKE_EVDEV_LOG_FILE=" + File(sessionDir, "pad.log").path)
        SessionState.fakeInputDir = fakeInputDir
    }

    /** What proot binds into the guest: the pads, the battery, storage, the game library, added games and ROMs. */
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
        // The client's battery readout (the Quick Access Menu, the top bar) reads
        // /sys/class/power_supply/BAT<n>/..., a laptop's or a Deck's naming; Android's supply is
        // called "battery" and its files differ, so the client sees no battery at all. A directory
        // of our own, written from Android's battery API every few seconds, is bound over it.
        // Steam's time estimates come from /run/vpower instead, written straight into the rootfs.
        val battery = BatteryComponent(
            File(filesDir, "session/sys/power_supply"),
            File(LinuxRuntime.rootDir(this), "run/vpower"),
        )
        battery.attach(this)
        components.add(battery)
        binds.add(battery.dir.path + ":/sys/class/power_supply")
        // The overlay's CPU and GPU temperatures and the fan, as hwmon sensors it knows by name.
        val hwmon = HwmonComponent(File(filesDir, "session/sys/hwmon"), LinuxRuntime.rootDir(this))
        if (hwmon.prepare()) {
            hwmon.attach(this)
            components.add(hwmon)
            binds.add(hwmon.dir.path + ":/sys/class/hwmon")
        }
        // CPU load for everything in the session that reads /proc/stat, the overlay among them.
        val cpuStat = CpuStatComponent(File(filesDir, "session/proc-stat"))
        if (cpuStat.prepare()) {
            cpuStat.attach(this)
            components.add(cpuStat)
            binds.add(cpuStat.file.path + ":/proc/stat")
        }
        // The GPU memory in use for the overlay's VRAM lines, which it would read from tracefs.
        val gpuMem = GpuMemComponent(File(LinuxRuntime.rootDir(this), "run/droiddeck-hud/gpu-mem"), LinuxRuntime.rootDir(this))
        if (gpuMem.prepare()) {
            gpuMem.attach(this)
            components.add(gpuMem)
        }
        // The GPU's load and temperature for the performance overlay, where KGSL's sysfs is refused.
        val gpuStats = GpuStatsComponent(File(filesDir, "session/sys/kgsl-3d0"))
        if (gpuStats.prepare()) {
            gpuStats.attach(this)
            components.add(gpuStats)
            binds.addAll(gpuStats.binds())
        }
        // Rumble for the on-screen pad: the fake evdev layer sends force-feedback effects to this
        // listener, which drives the phone's vibrator (see RumbleComponent).
        if (controllersOn) components.add(RumbleComponent().also { it.attach(this) })
        // Where the device's files appear inside the session. Internal storage is bound at its own
        // path already, and every program's file dialog opens at home and lists "Computer" from
        // /proc/mounts, where a proot bind never shows - so a user saw only the runtime's own
        // tree and could not find the phone at all. The same storage is placed under home as
        // well, and the ROMs folder chosen on the main screen beside it; a bind rather than a
        // link, so a folder on an SD card works the same.
        val home = File(LinuxRuntime.rootDir(this), "root")
        File(home, "Storage").mkdirs()
        binds.add(Environment.getExternalStorageDirectory().path + ":/root/Storage")
        // A second Steam library: the storage chosen in the Steam cog, at the path the runtime's
        // droiddeck-steam-library registers with the client. Nothing bound = the script removes
        // the entry, so the client never offers a place that is not there.
        val library = GameStorage.effective(this)
        var storageDiagnosticLibrary: File? = null
        if (library != null) {
            val problem = GameStorage.prepare(this, library.path)
            if (problem == null) {
                File(LinuxRuntime.rootDir(this), "mnt/droiddeck-sd").mkdirs()
                // Links, prefixes and Steam entries made before the rename still name the old path.
                File(LinuxRuntime.rootDir(this), "mnt/bannerlator-sd").mkdirs()
                try {
                    binds.addAll(SecondaryLibrary.binds(filesDir, File(library.path)))
                    storageDiagnosticLibrary = File(library.path)
                    Log.i(TAG, "game storage: ${library.path} -> /mnt/droiddeck-sd (\"${library.label}\"); prefixes and native tools private")
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
                removable = storageDiagnosticLibrary?.let {
                    runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false)
                } ?: false,
            )
            if (device != null) {
                // guest already has the session command at this point. Put these with the `env -i`
                // assignments so they reach the Steam process instead of becoming script args.
                val envAt = guest.indexOf(LinuxRuntime.SESSION_SCRIPT).takeIf { it >= 0 } ?: guest.size
                guest.add(envAt, "BL_STORAGE_DIAGNOSTICS=1")
                guest.add(envAt + 1, "BL_STORAGE_DEVICE=$device")
                guest.add(envAt + 2, "BL_STORAGE_LOG=${File(sessionDir, "storage.log").absolutePath}")
            } else {
                Log.w(TAG, "storage diagnostics: selected library could not be identified; metadata snapshot only")
            }
        }
        // The user's own games folder (the Steam cog's "Added games"), bound at a fixed place so
        // the shortcuts the app writes point somewhere whatever storage the folder is on.
        for (root in com.droiddeck.launcher.frontend.AddedGames.roots(this)) {
            if (root.host.isDirectory && root.host.canRead()) {
                File(LinuxRuntime.rootDir(this), root.guest.removePrefix("/")).mkdirs()
                binds.add(root.host.path + ":" + root.guest)
                Log.i(TAG, "added games: ${root.host} -> ${root.guest}")
            } else {
                Log.w(TAG, "added games: ${root.host} is not a readable folder this session")
            }
        }
        // Store games installed on a card: each card's Games root at its own fixed place, so the
        // shortcuts point somewhere whether or not the card is also the Steam library.
        for ((host, guest) in com.droiddeck.launcher.stores.StoreInstallRoot.externalRoots(this)) {
            if (host.isDirectory && host.canRead()) {
                File(LinuxRuntime.rootDir(this), guest.removePrefix("/")).mkdirs()
                binds.add(host.path + ":" + guest)
                Log.i(TAG, "store games: $host -> $guest")
            }
        }
        // Folders of added scripts outside internal storage, where their links point.
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
        android.os.Process.sendSignal(prootPid, 15) // SIGTERM
        if (!waitForExit(prootPid, GRACE_MS)) {
            if (!stillSameProcess()) return
            Log.w(TAG, "proot $prootPid did not exit on SIGTERM; killing it")
            android.os.Process.killProcess(prootPid)
        }
        var killed = 0
        for ((pid, started) in tree) {
            val stat = readStat(pid) ?: continue       // already gone
            if (stat.second != started) continue        // same number, different process
            android.os.Process.killProcess(pid)
            killed++
        }
        if (killed > 0) Log.i(TAG, "swept $killed process(es) proot left behind")
    }

    /** Every process under [root], as (pid, start time), from a single walk of /proc. */
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

    /** (parent pid, start time) from /proc/pid/stat, or null when the process is gone. */
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
        Log.i(TAG, "queued Steam game $gameId for the running client")
    }

    private fun resumeSession() {
        activityVisible = true
        screenOn = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: screenOn
        manualPauseRequested = false
        suspendDownloadNow = false
        if (steamSleepToken == null && (nativeSleepToken == null || nativeSleepFallback)) requestSteamWake()
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

    /**
     * proot's --kill-on-exit takes its tracees down, but a session that died from the inside
     * (the client asserting, Xwayland going) leaves gamescopereaper and the session script
     * behind, still holding the Wayland socket and the audio server the next session needs. They
     * are our uid, so they are ours to kill.
     */
    /**
     * Every process of ours still running a program out of the Linux runtime once proot is gone.
     * proot's --kill-on-exit and the tree sweep only reach what proot still traces; a tracee it
     * lost - an Xwayland that aborted from one thread and then looped on a syscall proot's own
     * seccomp filter, with no tracer left, answers ENOSYS - survives both, reparented to init,
     * and wrote 9 GB of one line into the session log before anything killed it.
     */
    private fun killGuestLeftovers() {
        val me = android.os.Process.myPid()
        val rootfs = try { File(filesDir, "linuxfs").canonicalPath } catch (e: Exception) { return } + "/"
        val procs = File("/proc").listFiles { f -> f.name.all { it.isDigit() } } ?: return
        var killed = 0
        for (proc in procs) {
            val pid = proc.name.toIntOrNull() ?: continue
            if (pid == me) continue
            // Another uid's exe is not ours to read; a process already gone has none.
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

    /**
     * The desktop's Steam launchers cannot start the client where they are (no dma-buf on the
     * desktop), so they leave `steam-launch` in the session directory instead: which UI, and a
     * steam:// URL or nothing. This ends the session and hands the activity the one to start.
     * `session=desktop` (Big Picture's "Switch to Desktop", steamos-session-select) asks for the
     * desktop instead, with Steam's desktop client started in it.
     */
    private var launchWatcher: android.os.FileObserver? = null

    private fun watchLaunchRequests(dir: File) {
        launchWatcher?.stopWatching()
        listOf("steam-sleep", "steam-sleep-state", "steam-sleep-ready", "steam-wake", "steam-wake.tmp",
            "steam-native-sleep", "steam-native-sleep.tmp", "steam-native-ready", "steam-native-ready.tmp"
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

    /**
     * What the desktop cannot run where it lies (droiddeck-open), added as Add an app adds it, menu entry
     * included: an AppImage of sharun's, whose hard links Android denies apps and only the app's own proot
     * (link2symlink) makes, or a script or program whose folder of Linux programs is on noexec shared
     * storage, copied in. The answer goes beside the request, "ok <folder>" or "error <why>".
     */
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
        // A request for what is already being added waits for that, then finds it.
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
        if (suspendPolicy == SessionPrefs.SUSPEND_MANUAL && (!activityVisible || !screenOn)) {
            manualPauseRequested = true
        }
        val controller = suspendController ?: return
        val hidden = !activityVisible || !screenOn
        val downloadsBlockingSuspend = steamDownloadsInBackground && SessionState.mode == MODE_STEAM &&
            suspendPolicy != SessionPrefs.SUSPEND_NEVER && hidden && downloadActive && !suspendDownloadNow
        if (downloadsBlockingSuspend) acquireLocks()
        if (suspendPolicy != SessionPrefs.SUSPEND_NATIVE || !hidden || downloadsBlockingSuspend) {
            completeNativeSleep()
        } else if (nativeSleepToken == null && steamSleepToken == null &&
            !SessionState.suspended && !suspendAttemptFailed) {
            prepareNativeSleep()
        }
        val shouldSuspend = steamSleepToken != null || when (suspendPolicy) {
            SessionPrefs.SUSPEND_AUTO -> hidden && !downloadsBlockingSuspend
            SessionPrefs.SUSPEND_NATIVE -> hidden && nativeSleepReady && !downloadsBlockingSuspend
            SessionPrefs.SUSPEND_MANUAL -> manualPauseRequested && !downloadsBlockingSuspend
            else -> false
        }
        if (suspendOperationPending || suspendAttemptFailed || shouldSuspend == SessionState.suspended) return
        val gen = sessionGen
        suspendOperationPending = true
        SessionEvents.record(if (shouldSuspend) "session.suspend_requested" else "session.resume_requested", mapOf(
            "policy" to suspendPolicy, "activityVisible" to activityVisible,
            "screenOn" to screenOn, "steamSleep" to (steamSleepToken != null),
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
        // Write before SIGCONT: the guest's login1 sees it immediately on thaw
        // and delivers PrepareForSleep(false), restoring Steam's main surface.
        writeSteamSleepState(token, "awake")
        steamSleepToken = null
    }

    private fun resumeSteamSleepOnReturn() {
        if (suspendPolicy in setOf(SessionPrefs.SUSPEND_AUTO, SessionPrefs.SUSPEND_NATIVE) && activityVisible && screenOn) {
            if (steamSleepToken == null && (nativeSleepToken == null || nativeSleepFallback)) requestSteamWake()
            completeNativeSleep()
            completeSteamSleep()
        }
    }

    /** Let Steam prepare before freezing; retain the wake lock until that freeze completes. */
    private fun prepareNativeSleep() {
        val token = UUID.randomUUID().toString().replace("-", "")
        nativeSleepToken = token
        nativeSleepReady = false
        nativeSleepFallback = false
        val dir = LinuxRuntime.sessionRoot(this)
        runCatching {
            val staged = File(dir, "steam-native-sleep.tmp")
            staged.writeText("$token\n")
            check(staged.renameTo(File(dir, "steam-native-sleep")))
        }.onFailure { Log.w(TAG, "could not request Native sleep; using timed pause", it) }
        SessionEvents.record("steam.native_sleep_requested")
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
        // A missing or stalled guest helper must not keep the device awake indefinitely.
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
    }

    /** Resume must also recover a Steam sleep whose request never reached Android. */
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
            // Cloud saves of a store game played this session, if the compat tool's exit request did
            // not upload them (a session closed from the drawer never reaches it).
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
            SessionState.notifyEnded(status)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * proot is one thread, and every syscall it traps anywhere in the session - a path lookup in
     * Proton's python, Wine's file opens, the Steam client's /proc scans - waits for that thread to
     * be scheduled, at nice 0 beside a game that keeps the big cores busy. It is raised to nice -6,
     * under the compositor's -8. After a moment, not at once: the first guest process is forked by
     * proot itself and would inherit the value, and with it everything the session starts.
     */
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
        completeNativeSleep()
        synchronized(stopLock) {
            if (!SessionState.running) return
            SessionState.running = false
        }
        updateDownloadMonitoring()
        val stoppedGen = sessionGen
        SessionState.stopRequested = false
        SessionEvents.record("guest.exited", mapOf("status" to status))
        GpuClockPin.stop(this)
        SessionEvents.transition(SessionPhase.STOPPING, "session.stopping", mapOf("status" to status))
        suspendOperationPending = false
        launchWatcher?.stopWatching()
        launchWatcher = null
        syncWatcher?.stopWatching()
        syncWatcher = null
        // proot's --kill-on-exit takes the guest tree down only if proot gets to run it, and
        // SIGKILL never lets it. A SIGKILLed proot left its tracees alive with no tracer: every
        // seccomp-trapped syscall then failed with ENOSYS, they spun on retries at a full core
        // for over an hour, and the reaper could not even open /proc to kill them. So: SIGTERM,
        // a grace for proot's own cleanup, SIGKILL only if it will not go, then a sweep of the
        // tree it had - snapshotted first, each pid checked against its start time so a number
        // reused by a new process is never touched.
        val prootPid = sessionPid
        sessionPid = -1
        val auxiliary = synchronized(auxiliaryProcessesLock) {
            auxiliaryProcesses.toMap().also { auxiliaryProcesses.clear() }
        }
        SessionTerminal.clear(sessionGen)
        val controller = suspendController
        suspendController = null
        // On its own thread, never here: stopSession runs on the main thread (the notification's
        // Stop action arrives there), and collecting means copying the compositor's log, scrubbing
        // every Steam log line by line - 42 files on one measured run - and waiting for logcat to
        // dump the crash buffer. That was about four and a half seconds of blocked main thread,
        // and Android ANR'd the app for it: the desktop session that would not let go.
        val ended = SessionPaths.take()
        if (ended != null) Thread({ collectSessionArtifacts(ended) }, "session-collect").start()
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
                    auxiliary.forEach { (pid, started) -> teardown(pid, started) }
                    if (prootPid > 1) teardown(prootPid)
                    killGuestLeftovers()
                    finishSessionStop(status, stoppedGen)
                }, "session-teardown").start()
            } else {
                finishSessionStop(status, stoppedGen)
            }
        }
        if (prootPid > 1) acquireLocks()
        if (controller != null) controller.closeAndResume(finishAfterTeardown) else finishAfterTeardown()
    }

    /**
     * Swiped out of recents. The activity is destroyed without any of our teardown running, so the
     * guest would survive as an orphan holding the rootfs and the GPU. Treat the swipe as "quit".
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (pipTask) {
            Log.i(TAG, "PiP task dismissed - retaining the session under its background policy")
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
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Keeping the process alive ───────────────────────────────────────────────────────────

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
            @Suppress("DEPRECATION") // deprecated from API 29, still honoured; targetSdk is 28
            if (allowed && wifiLock?.isHeld != true) {
                wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DroidDeck:session-wifi")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
            Log.i(TAG, "wifi lock held=${wifiLock?.isHeld}")
        } catch (t: Throwable) {
            // A partial wake lock keeps the process alive but does not stop WiFi power-save from
            // throttling a backgrounded download to nothing, which is what this lock is for.
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

    // ── Notification ────────────────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_LOW: it must never make a sound or push a heads-up over a game.
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
                )
                if (wasActive != downloadActive) refreshNotification()
                updateSuspendPolicy()
                mainHandler.postDelayed(this, DOWNLOAD_POLL_MS)
            }
        }
        downloadMonitorTask = poll
        mainHandler.post(poll)
    }

    /** The pads attached as the session starts; the activity logs the ones that come and go after. */
    private fun logControllersAtStart() {
        val pads = ArrayList<String>()
        for (id in android.view.InputDevice.getDeviceIds()) {
            val d = android.view.InputDevice.getDevice(id) ?: continue
            if (!com.droiddeck.launcher.input.PadBridge.isFromController(d)) continue
            pads.add(String.format(java.util.Locale.ROOT, "\"%s\" (%04x:%04x)", d.name, d.vendorId, d.productId))
        }
        Log.i(TAG, "controllers at start: " + (if (pads.isEmpty()) "none" else pads.joinToString(", ")) +
            "; presented to the guest as " + if (SessionState.deckPad) "a Steam Deck controller" else "an Xbox 360 controller")
    }

    companion object {
        private const val TAG = "SessionService"
        /** proot's tracer: above everything in the guest, under the compositor thread's -8. */
        private const val TRACER_NICE = -6
        /** Downloads file whose contents become TU_DEBUG inside the session, e.g. "sysmem". */
        private const val TU_DEBUG_SWITCH = "Download/droiddeck-tu-debug"
        /**
         * Where droiddeck-env (KEY=VALUE lines added to the session environment verbatim) used to
         * be. Ignored now: a line there reaches the Steam client's environment as written
         * (LD_PRELOAD, VK_ICD_FILENAMES, PATH...), and any app with storage access can write to
         * Download - code of its choosing inside our sandbox, next to the client's saved login.
         */
        private const val LEGACY_ENV_SWITCH = "Download/droiddeck-env"
        private const val ENV_SWITCH_NAME = "droiddeck-env"

        /**
         * The session's extra-environment file: Android/data/<package>/files/droiddeck-env. Only
         * this app, adb and the user (through the app's own file manager) can write there - from
         * Android 11 on. Before that other apps with storage access can reach Android/data too, so
         * there is no such file at all.
         */
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
        private const val ACTION_TRACK_AUXILIARY = "com.droiddeck.launcher.TRACK_AUXILIARY"
        private const val ACTION_STOP_AUXILIARY = "com.droiddeck.launcher.STOP_AUXILIARY"
        private const val ACTION_AUXILIARY_EXITED = "com.droiddeck.launcher.AUXILIARY_EXITED"
        private const val EXTRA_AUXILIARY_PID = "auxiliaryPid"
        /** Command lines that can only belong to a session of ours. */
        private val STRAGGLERS = listOf("droiddeck-session", "gamescope", "Xwayland", "steamrtarm64",
            "steamwebhelper", "linuxfs/opt/android-host/proot", "/libproot.so", "pulseaudio/libpulseaudio.so")
        /** How long proot gets to run its own cleanup before it is killed outright. */
        private const val GRACE_MS = 1200L
        private const val STEAM_PICKUP_MS = 1500L
        private const val STEAM_EXIT_MS = 10_000L
        private const val GAME_LAUNCH_RETRY_MS = 250L
        private const val DOWNLOAD_POLL_MS = 2_000L
        private const val STEAM_GAME_REQUEST = "steam-game"
        private const val NO_PAD_SWITCH = "Download/droiddeck-no-pad"
        private const val NO_UINPUT_SWITCH = "Download/droiddeck-no-uinput"
        private const val NO_DECK_PAD_SWITCH = "Download/droiddeck-no-deck-pad"
        private const val NO_STEAM_TOUCH_SWITCH = "Download/droiddeck-no-steam-touch"
        /** libfakeinput numbers the pads made through its /dev/uinput stand-in from here. */
        private const val FIRST_VIRTUAL_PAD = 16

        const val EXTRA_MODE = "mode"
        const val MODE_STEAM = "steam"
        const val MODE_DESKTOP = "lxqt"
        /** A program inside the runtime, fullscreen under gamescope (EXTRA_PROGRAM = its path). */
        const val MODE_RUN = "run"
        const val EXTRA_PROGRAM = "program"
        /** MODE_RUN: arguments after the program (a game to boot). */
        const val EXTRA_PROGRAM_ARGS = "programArgs"
        /** MODE_STEAM: "desktop" for the client's desktop UI (default Big Picture); a steam:// URL to hand it.
         *  MODE_DESKTOP: "desktop" = open Steam's desktop client in the desktop once it is up. */
        const val EXTRA_STEAM_UI = "steamUi"
        const val EXTRA_STEAM_URL = "steamUrl"
        const val EXTRA_GAME_ID = "gameId"

        fun start(
            context: Context, mode: String = MODE_STEAM, program: String? = null,
            steamUi: String? = null, steamUrl: String? = null, programArgs: Array<String>? = null,
        ) {
            val intent = Intent(context, SessionService::class.java).putExtra(EXTRA_MODE, mode)
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

        fun setActivityVisible(context: Context, visible: Boolean) {
            if (!SessionState.running) return
            val action = if (visible) ACTION_ACTIVITY_VISIBLE else ACTION_ACTIVITY_HIDDEN
            context.startService(Intent(context, SessionService::class.java).setAction(action))
        }

        fun resume(context: Context) {
            if (!SessionState.running) return
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_RESUME))
        }

        /** Route a validated decimal Steam game id through the current guest session. */
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

        /** Let the session service clean up the PTY's proot tree if the control screen closes. */
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
