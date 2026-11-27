# Android's child-process limit without developer options

Research log for removing DroidDeck's requirement to turn off "Restrict child processes" (or run
`settings put global settings_enable_monitor_phantom_procs false` over wireless ADB). The goal:
Android must not kill a Steam session for having too many Linux processes, and the user must not
need developer options.

Status: **working on the AYN Thor (Android 13).** With Android's limit on and no developer option,
a Steam session with a game running keeps 14 processes in the app's counted cgroup (63 without
this work), and Android killed nothing in 3 minutes of forced scans. DroidDeck's Setup reports the
limit as handled and no longer blocks Steam. Both counting gaps below are in identical code on
Android 12 through 16; a stock Android 14+ phone is still to be tested. The experiments are in
`tools/phantomlab` (see the end).

## How Android counts and kills

Source: `frameworks/base/services/core/java/com/android/server/am/PhantomProcessList.java`
(android12-release to android16-release and main; the parts below are unchanged across them).

1. **What is counted.** For every app process AMS knows (`mPidsSelfLocked`),
   `lookForPhantomProcessesLocked(app)` reads
   `/sys/fs/cgroup/uid_<app.info.uid>/pid_<pid>/cgroup.procs` (`/acct/...` on cgroup v1). Every
   pid listed there other than the app process itself is a "phantom process". `cgroup.procs`
   lists thread-group ids, so threads never count.
2. **What is skipped.**
   - `if (app.appZygote || app.isKilled() || app.isKilledByAm()) return;`. `appZygote` is true
     for uids in `FIRST_APP_ZYGOTE_ISOLATED_UID..LAST_APP_ZYGOTE_ISOLATED_UID` (90000–98999),
     i.e. every process forked from an app zygote (`ProcessRecord` constructor).
   - A pid that is itself an app process (`mPidsSelfLocked.get(pid) != null`).
   - A pid whose `/proc/<pid>/cmdline` cannot be opened, whose uid cannot be read, or for which
     `pidfd_open` fails. These only happen once the process is gone (an empty cmdline reads as
     `""`, not `null`, so it is still counted).
3. **When.** Only after AMS refreshes CPU stats (`AppProfiler.updateCpuStatsNow` →
   `updateProcessCpuStatesLocked`), at most every 5 s (`MONITOR_CPU_MIN_TIME`), and only when
   something asks for it: activity and process lifecycle events, mostly. Also from
   `setProcessGroupForPhantomProcessOfApp` when an app changes scheduling group. A new phantom
   schedules `trimPhantomProcessesIfNecessary`.
4. **The kill.** When the device-wide total exceeds `max_phantom_processes` (32), phantoms are
   sorted by their app's oom adj (most important first), then youngest first, and the surplus is
   killed from the end of that list (`SIGKILL` and `killProcessGroup`): the least important app's
   oldest processes go first. As the foreground app, other apps' phantoms go first, so the
   practical budget is our own 32, and of ours the **oldest** die first: the session's PipeWire,
   D-Bus and Steam itself, not the game that pushed the count over.
5. **The second killer.** `ActivityManagerService.updatePhantomProcessCpuTimeLPr`: when the app is
   at `PROCESS_STATE_HOME` or below (backgrounded, no foreground service) and a phantom used
   "excessive" CPU, `killPhantomProcessGroupLocked` kills every phantom of that app **and the app**.

Both are switched off only by `settings_enable_monitor_phantom_procs=false` (the developer option)
or by raising `max_phantom_processes` through `device_config` (shell only).

Today an idle Steam session is about 30 processes and a session with a game about 45 (#566, #575).

## Gap 1: an isolated process's children are never counted

Two independent reasons, either of which is enough:

- **Wrong cgroup path.** The path is built from `app.info.uid`, the *package's* uid (10148 here),
  but an isolated process runs as its own uid (99000+ for `android:isolatedProcess`, 90000+ from an
  app zygote) and its cgroup is `uid_<isolated uid>/pid_<pid>`. The lookup finds nothing
  (`FileNotFoundException`, logged only with `DEBUG_PROCESSES`) and the process is skipped.
- **App zygote skip.** Processes forked from an app zygote (`android:useAppZygote="true"`) are
  skipped outright by the `app.appZygote` check, by design ("doesn't have its own acct entry").
  This one survives even if Google fixes the path.

Measured with `max_phantom_processes=4` and monitoring on, children killed by Android:

| Where the children were forked | Children | Thor (Android 13) | Emulator (Android 15) |
|---|---|---|---|
| Main app process (control) | 10 | 6 ("Trimming phantom processes") | 6 |
| plain `isolatedProcess` service (uid 99000+) | 10 | 0 | 0 |
| `isolatedProcess` + `useAppZygote` service (uid 90000) | 40 | 0 | 0 |

`cgroup.procs` of `/sys/fs/cgroup/uid_90000/pid_<service>` held all 41 pids the whole time; the
phantom list (`dumpsys activity processes`, "All Active App Child Processes") only ever showed the
main process's children. With the limit at 4, the Thor also trimmed another app's `logcat` child,
as expected for the device-wide count.

## Gap 2: a process that execs in place is an app process, not a phantom

An `android:process` service process can `execve()` a Linux program in place. If it first clears
`FD_CLOEXEC` on its binder fd, the binder node stays alive, so AMS never sees the process die. The
pid stays in `mPidsSelfLocked` and is never counted. Its own children are counted as usual.

Same results on the Thor and the emulator:

| Worker `:wN` | Result |
|---|---|
| exec `/system/bin/sleep`, binder fd closed by exec (control) | "has died" at once; AMS restarts the service |
| exec `/system/bin/sleep`, binder fd kept | stays `fg`/`(service)` while bound; survives a density change, `am send-trim-memory`, Home/back cycles |
| exec `sh` forking 6 `sleep`s, binder fd kept | `sh` not counted; its 6 children counted (2 trimmed at limit 4) |
| guest exits (killed) | AMS logs "has died" and removes the cgroup, as for any app process |

Calls AMS makes into the process after the exec fail cleanly: the kernel logs
`binder_alloc_buf, no vma` and AMS gets `DeadObjectException` ("Failed for dead process.
ClientTransactionItem=ConfigurationChangeItem", "Remote exception" for trim). Nothing kills the
process for it. It is frozen like any cached process when the app goes to the background without a
foreground service.

## What else was tried

| Idea | Result |
|---|---|
| Move a process to another cgroup (`cgroup.procs` of root, of `uid_N`) | `EACCES`: dirs are `system:system 0775` and SELinux denies `cgroup_v2` writes to apps |
| Make a sub-cgroup under our `pid_N` (children of a sub-cgroup are not in its `cgroup.procs`) | `EACCES` |
| `clone3(CLONE_INTO_CGROUP)` | apps cannot open `/sys/fs/cgroup` (SELinux `read` denied) |
| `unshare(CLONE_NEWUSER)` / `CLONE_NEWCGROUP` | `EINVAL` (no user namespaces in GKI) / `EPERM` |
| Empty `/proc/<pid>/cmdline` | read as `""`, still counted; changing it needs `PR_SET_MM` (`CAP_SYS_RESOURCE`) |
| Run "processes" as threads (`CLONE_THREAD`) | only with a shared address space; Steam, Chromium and Wine rely on real processes |
| More `android:process` services hosting the tree | each process's children are still phantoms; only the process itself is free (Gap 2) |
| Rely on kill order | we are the foreground app, so our own newest processes die first |

## What an isolated process can do

Measured from both isolated variants (domain `isolated_app`) against the normal app
(`untrusted_app`), with fds created by the app and passed over binder:

| | app | isolated |
|---|---|---|
| `fork` | ✅ | ✅ (children uncounted) |
| `execve` of an executable in the APK's lib dir (as `libddexec.so`) | ✅ | ✅ |
| `execve` of `/system/bin/*` | ✅ | ✅ |
| create `AF_INET` socket | ✅ | ❌ `EACCES` |
| `connect`/`send`/`recv` on a TCP or UDP socket the app created | ✅ | ✅ (policy: "network sockets received over IPC") |
| read/write a passed unix stream socket / pipe | ✅ | ✅ |
| `open()` an app data file by path | ✅ | ❌ `ENOENT` (not mounted) |
| read/write/`mmap` a passed app data file | ✅ | ✅ |
| `mmap(PROT_EXEC)` of a passed app data file | ✅ | ❌ `EACCES` |
| receive a passed directory fd | ✅ | ❌ (binder refuses the transaction) |
| `mmap(PROT_EXEC)` of a memfd, anonymous RWX (JIT) | ✅ | ✅ |
| GPU device (`/dev/kgsl-3d0`, `/dev/mali0`) | ✅ | ❌ (`neverallow`, also for passed fds) |
| signal or read `/proc` of the app's processes | ✅ | ❌ (`EPERM`, `EACCES`) |

This table is the emulator (stock, enforcing policy). **The Thor's ROM runs `isolated_app`
permissive** (every isolated denial logs `permissive=1`; `untrusted_app` is enforcing): there an
isolated process could also create inet sockets and open `/dev/kgsl-3d0`. That is an AYN quirk and
must not be relied on; design for the enforcing column.

Isolated processes can talk to each other through abstract unix sockets. Everything else in the
app's world (files, sockets to the app's servers, the GPU) has to be brokered through fds.

## Routes

### A. An isolated tier for everything that does not touch the GPU

Run a session's GPU-free processes inside one app-zygote isolated service; their whole fork tree is
invisible. Needed:

- **Loader:** `libddexec.so` already starts programs without the kernel loading them; it would take
  its ELF from an inherited fd and copy segments into anonymous or memfd memory, since isolated
  processes cannot map app files executable. A broker can hand out one sealed memfd per library so
  pages stay shared.
- **File broker:** the native hook already funnels path calls through `rewrite_path`; in the
  isolated tier those calls become requests to a broker in the app, which opens the file and returns
  the fd. Directories cannot be passed, so `opendir`/`getdents` are served from listings.
  Raw syscalls (Wine's preloader, FEX internals) need a seccomp `SIGSYS` trap, which the hook
  already uses for Android's own filter.
- **Sockets:** `socket(AF_INET*)` → broker-created socket; then the process connects itself.
  Unix sockets between isolated processes become abstract sockets; connections to servers in the
  app (compositor, PipeWire, VkBridge) are connected by the broker.
- **No signals across the boundary:** a process and everything it signals or ptraces must be on the
  same side. Wine's processes all depend on `wineserver` signalling them, so Wine stays with the game.
- **GPU:** only through a remoting layer such as VkBridge (already used on Mali), never directly.

Candidates for the tier: the Steam client and its web helper's renderer/utility processes (with
the web helper's GPU work remoted or in software), the session scripts, D-Bus services, PipeWire
helpers, Python daemons. A cross-tier launch (Steam starting the game's `reaper`) runs through the
broker, with a small stand-in process left in the tier for Steam to wait on. Stand-ins are free
there.

### B. Exec-in-place workers for long-lived processes

Declare a pool of `android:process` services (`:g0`…`:g95`). Each long-lived program runs as the
main process of one worker (Gap 2): full app privileges, direct GPU, normal signals and `/proc`
(same uid and SELinux domain), and the app hears its exit through binder death. Costs and limits:

- A worker is a zygote fork (~100 ms); a pre-started pool removes the wait.
- Only the worker's main process is free; its children count. So each long-lived program must be
  started in its own worker, not forked by its parent.
- Programs the app or the session script start and do not wait on (Xwayland, D-Bus, PipeWire,
  helpers, `wineserver -p`) can move with a `droiddeck-spawn` client and no other change.
- A program another guest forks and waits on (Wine's processes, Steam's children) needs the hook to
  hand the exec to a worker and translate the pid for the parent: `wait4`, `kill`, `/proc/<pid>`,
  `SIGCHLD`. All app processes share zygote's session (sid 0, pgid of zygote), so `setpgid` and
  process-group signals across workers are possible.
- Relies on AMS never pinging a process; today it only sends oneway calls that fail harmlessly.

### Measured on the Thor: a real session

An idle Steam session (Big Picture at the library, Decky on, 0.7.11 / main `b08d8e8e1`) holds
**48 counted processes** in the app's cgroup, well past 32 before any game starts:
9 bash, 6 python3, 5 dbus-daemon (with the portals and at-spi under the session bus), 4 Decky
PluginLoader, 4 steamwebhelper, 2 Xwayland, gamescope and its reaper, PipeWire and WirePlumber,
Steam, the clipboard bridge, and the app's own logcat, PulseAudio and audio relay.

Prototype of B on this branch (`runtime/GuestWorkers.kt`, `droiddeck-spawn`, `dd_spawn` in the
session script; `DD_SPAWN_DAEMONS=0` turns it off): 24 `:gN` worker services, a spawn socket in the
app, and the session script starting the clipboard bridge, the system bus, login1 and the session
bus through it. Result:

- Each of the four runs as its own app process (`/sys/fs/cgroup/uid_<app>/pid_<worker>` with one
  process). login1's sleep handshake, NetworkManager (state 70, full connectivity) and the Steam
  library all work.
- Counted processes: **45** instead of 48. The session bus took its 9 activated children with it, so
  they are still counted, under its worker.

So starting top-level daemons in workers is not enough. What is left is the processes other
programs start. Of the 47 counted processes in that session, **40 are exec'd programs** and only 7
are forks without an exec (the session script's `( … ) &` subshells). A handoff inside the native
hook, at `execve()` in a forked child, could move all 40, leaving about 7 counted at idle. That needs
the pid translation described in B (the parent keeps the forked pid; `wait4`, `kill`, `SIGCHLD` and
`/proc/<pid>` go to the worker's process).

### Handoff in the native hook (prototype, on this branch)

`tools/nativeguest/hook/handoff.c`. At `execve()` in a forked child, a program named in
`DD_HANDOFF` is sent to the app's spawn socket instead of exec'd: the request carries argv, the
environment, the working directory and every inheritable fd with its number, and a worker execs
the program with those fds in place. The child records "my pid is now worker pid P" in a memfd
table its parent created, and exits. In the parent, `wait4`/`waitpid`/`wait`/`wait3`/`waitid` and
`kill` treat the forked pid as P until P exits; the app writes P's exit status (from
`ApplicationExitInfo`) into the table and sends the parent `SIGCHLD`. The child's side is
vfork-safe (Python's subprocess execs under `vfork`). Details that mattered:

- **Scripts:** the list also matches the `#!` interpreter (`python3` for `droiddeck-agent`).
- **Exec chains:** a forked child that first execs a wrapper (`env`, `taskset`, a shell script,
  Wine re-executing itself through `wine-preloader`) keeps its parent's table fd across the exec,
  so the wrapper's own exec can still be handed off.
- **Not handed off:** Steam and the web helper's browser process (gamescope finds a window's app by
  walking its process's parents) and its utility processes (the client refuses its transport —
  "no transport is available" — from processes that are not its children). The web helper's
  renderers hand off fine.
- One-shot programs cost a worker start (~100 ms) each, so the list names long-lived programs only
  (`xprop+-spy`: `name+prefix` matches only when an argument starts with prefix).

Default list: the portals, at-spi, `python3`, PipeWire, WirePlumber, Decky's PluginLoader, the log's
`gnudd`, `xprop -spy`, the web helper's renderers, `wine`, `wine-preloader`, `wineserver`.

Measured on the Thor, same session and game as above:

| | counted children, idle | counted, Bully (Proton) running |
|---|---|---|
| main, no handoff | 48 | 63 |
| handoff | **26** | **29** |

With the handoff on, Bully reached its menu at 30 fps, kept focus, and Steam's library, Decky, the
portals, NetworkManager/login1 and the agent all worked.

Then Android's own limit was switched back on (`settings_enable_monitor_phantom_procs=true`,
default limit 32) with the game running, and a scan was forced every 20 s for 3 minutes
(`dumpsys meminfo` refreshes the CPU stats the scan runs on; process starts and activity changes do
too, irregularly):

| | Android listed | killed | session |
|---|---|---|---|
| no handoff (`DD_SPAWN_DAEMONS=0`, same build) | 63 | **33**, oldest first: the session's bash, Python daemons, D-Bus, Xwayland, Steam, PipeWire, gamescope's reaper | FAILED |
| handoff | 27–29 (device-wide) | **0** in 9 scans | game running throughout |

### Taken further: the session itself in workers

A second round moved most of what was still counted:

- **The app's own processes:** `HostProcess.startUncounted` runs a host command in a worker with the
  same contract as `HostProcess.start` (output lines, exit code, working directory). The session
  root, the app log's logcat and the DirectAudio relay use it. A worker cannot `chdir` before its
  exec, so Android's `sh` does it from an environment of its own and exports the program's
  (`env -i` cannot be used: the APK's library path contains `==`). PulseAudio stays a normal child:
  it was SIGKILLed right after start in a worker, for a reason not yet found.
- **The script's long-lived subshells:** `dd_job FUNCTION` runs a function in a new `bash` that first
  loads the shell's variables, functions and options from a state file, so it is an exec'd program
  the hook can hand off (`bash+dd-job`). `$!`, `kill` and `wait` keep working. The zRAM scanner,
  the property watcher, Decky's supervisor and the request/timer loop use it.
- **The log pipeline** is `droiddeck-log-writer`, a script of its own instead of a `{ gnudd; cat; }`
  subshell.
- **gamescope, its reaper and the inner `droiddeck-session steam`** are handed off. Steam, its web
  helper's browser process and its utility processes still are not (see above).

Things this needed, found the hard way:

- **The binder fd is the worker's life.** It sits above every fd number the program gets; programs
  that close all inherited fds (gamescope) are kept from closing it (the hook's kept fds, also
  against `CLOSE_RANGE_CLOEXEC`); children never send it along with a handoff; and the hook closes
  copies in every other process (`DDH_WORKER_PID`), so a worker's exit is seen when its program
  exits. A worker that lost its binder fd was killed by Android within milliseconds.
- **A session of its own (`setsid`) for every worker.** All app processes are in zygote's process
  group, so a program signalling its group reached the app itself (the hook also refuses
  `kill(0)`/`kill(-pgid)` into zygote's group, and `kill(-1)`). `reap_orphans` in the session
  script stands down when its parent is zygote.
- **Nested handoffs** (a program in a worker handing off its own child): inherited `DDH_*`
  variables are dropped from each request.
- **Requests carry up to 240 fds,** in chunks of 60 (`LocalSocket` reads at most 64 per read), after
  the request itself; the spawn server catches everything per connection (an uncaught exception there
  took the whole app down once).
- **The table is made at a process's first fork,** not only when `DD_SPAWN` is in its environment at
  start: a shell that exports it has no sign of it in its own environment.
- **Exit status:** the program's hook records it with `on_exit` into the parent's table; the app's
  `ApplicationExitInfo` is only the fallback (signals, `_exit`). A program inherits its forked
  parent's ignored signals, open-files limit and umask (`DDH_SIGIGN`, `DDH_NOFILE`, `DDH_UMASK`).

Measured on the Thor, Bully under Proton, Android's limit on (default 32), a scan forced every 20 s:

| | counted, idle | counted, in game | killed in 3 min |
|---|---|---|---|
| main (no handoff) | 48 | 63 | 33, session failed |
| first round | 26 | 29 | 0 |
| **second round** | **9** | **14** | **0** |

Still counted at idle: PulseAudio, `run_gamescope`'s subshell, Steam, its web helper's browser and
two utility processes, the session bus's activation babysitter and Decky's sound-effects worker
(forks without an exec). Quitting the game, stopping the session (no worker is left) and starting
again all work. Started from the front end with the limit on, Setup shows "Child-process limit
handled by DroidDeck" and Steam starts.

**In the app:** `PhantomProcessStatus.UNCOUNTED` is reported when the limit is on but the session
runs in workers (`GuestWorkers.enabled`: the native userland, Android 11+, and the
`childProcessHandoff` session preference, on by default). It does not block Steam. Turning the
preference off (`droiddeckctl prefs set session childProcessHandoff false --type bool`) brings back
the old behavior and the old requirement.

### C. Plan

1. ~~Verify both gaps on the Thor~~ done; still to do on a stock Android 14+ phone (enforcing
   SELinux, unlike the Thor) and on a Mali device.
2. ~~B for the processes DroidDeck and the session script start themselves~~ done.
3. ~~B inside the hook, with pid translation~~ done.
4. ~~The session root, its jobs, the log pipeline, the gamescope chain~~ done: 9 idle, 14 in game.
5. ~~Setup no longer requires the developer option while the session runs in workers~~ done.
6. Open:
   - PulseAudio in a worker (SIGKILLed at start there);
   - `/proc/<pid>/stat` showing the virtual parent, so that Steam and its web helper can be handed
     off too;
   - pre-started workers, so that a handoff costs no zygote fork (about 100 ms now);
   - more games (Wine-heavy launchers, anti-cheat), the desktop mode and proot sessions (which keep
     the old behavior);
   - a settings switch for `childProcessHandoff`.
7. A (the isolated tier) only if B stops working on a future Android; it needs a file and socket
   broker and keeps the GPU outside.

## Robustness

- Gap 1's path mismatch is a bug that could be fixed; the app-zygote skip is deliberate. Use app
  zygote isolated services so either one is enough.
- Gap 2 depends on AMS not probing liveness. A future check (a ping, or watching a pidfd for the
  binder) would end it; the session would then fall back to counted processes, which is today's
  behavior.
- OEM ROMs with their own process managers (MIUI/HyperOS, ColorOS) are outside this.

## phantomlab

`tools/phantomlab` is a standalone test app (package `com.droiddeck.phantomlab`, no Gradle). It
forks sleepers from the main process, a plain isolated service, an app-zygote isolated service and
eight `:wN` workers, execs in place, and probes what a process may do.

```
tools/phantomlab/build.sh                          # build/phantomlab.apk, debug-signed
adb install -r tools/phantomlab/build/phantomlab.apk
adb shell device_config set_sync_disabled_for_tests persistent
adb shell device_config put activity_manager max_phantom_processes 4
adb shell settings put global settings_enable_monitor_phantom_procs true
adb shell am start -n com.droiddeck.phantomlab/.Main --es cmd spawn --es target iso:a --ei n 40
adb shell "am start -n com.droiddeck.phantomlab/.Main --es cmd exec --es target w0 --ei keep 1 --es argv '/system/bin/sleep|100000'"
adb shell am start -n com.droiddeck.phantomlab/.Main --es cmd probe --es target iso:a --es what self,memfd_exec,exec,fds,escape,libexec --es fds tcp,udp,unix,file:probe.txt
adb logcat -s PLAB ActivityManager | grep -i 'PLAB\|Trimming'
```

Targets: `main`, `proc` (`:proc`), `isoplain`, `iso:<name>` (one app-zygote instance per name),
`w0`…`w7`. Android scans only on CPU-stat refreshes, so switch apps a few times (5 s apart) before
reading the phantom list. Restore afterwards with
`device_config delete activity_manager max_phantom_processes` and
`device_config set_sync_disabled_for_tests none`.
